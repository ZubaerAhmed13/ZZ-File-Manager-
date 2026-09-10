package com.zz.filemanager.core.remote

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.ProviderCapabilities
import com.zz.filemanager.core.storage.ResumableWritableStorageProvider
import com.zz.filemanager.core.storage.StorageAccessException
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.util.FileClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Connection-bound provider adapter. Remote identities remain opaque provider identities and are
 * never represented as local filesystem paths. Blocking network sessions are scoped to one call or
 * to one returned stream, and stream close closes its owning remote session.
 */
class RemoteStorageProvider(
    private val connectionId: String,
    private val connections: NetworkConnectionRepository,
    private val credentials: SecureCredentialStore,
    private val factories: RemoteFileSystemFactoryRegistry,
) : ResumableWritableStorageProvider {
    private val initial = connections.get(connectionId) ?: throw StorageAccessException.Unavailable()
    override val id: String = initial.providerId

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = io {
        validateLocation(location)
        withFileSystem { _, fs -> fs.list(scopedPath(location.reference, location.rootReference)).map { it.toEntry(location.storageId) } }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = io {
        validateReference(item)
        withFileSystem { connection, fs -> fs.stat(scopedPath(item.opaqueId, connection.rootPath))?.toEntry(connection.storageId) }
    }

    override suspend fun openInputStream(item: FileReference): InputStream = openInputStreamAt(item, 0L)

    override suspend fun openInputStreamAt(item: FileReference, offset: Long): InputStream = io {
        require(offset >= 0L)
        validateReference(item)
        val (connection, fs) = openFileSystem()
        try {
            if (offset != 0L && !fs.capabilities.seekRead) throw StorageAccessException.Io(IllegalStateException("Provider does not support resumable reads"))
            SessionInputStream(fs.openInput(scopedPath(item.opaqueId, connection.rootPath), offset), fs)
        } catch (error: Throwable) {
            runCatching { fs.close() }
            throw mapped(error)
        }
    }

    override suspend fun exists(item: FileReference): Boolean = io {
        validateReference(item)
        withFileSystem { connection, fs -> fs.stat(scopedPath(item.opaqueId, connection.rootPath)) != null }
    }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = io {
        validateLocation(location)
        val root = RemotePath.normalize(location.rootReference)
        val current = scopedPath(location.reference, root)
        if (current == root) return@io null
        val parent = RemotePath.parent(current) ?: return@io null
        if (!RemotePath.isWithin(root, parent)) return@io null
        location.copy(id = "$id:$parent", displayName = RemotePath.name(parent), reference = parent)
    }

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = io {
        validateLocation(location)
        val root = RemotePath.normalize(location.rootReference)
        val current = scopedPath(location.reference, root)
        val connection = currentConnection()
        val crumbs = mutableListOf(
            Breadcrumb(connection.displayName, location.copy(id = "$id:$root", displayName = connection.displayName, reference = root)),
        )
        if (current == root) return@io crumbs
        val rootSegments = root.trim('/').split('/').filter(String::isNotBlank)
        val currentSegments = current.trim('/').split('/').filter(String::isNotBlank)
        var cursor = root
        currentSegments.drop(rootSegments.size).forEach { segment ->
            cursor = RemotePath.resolve(cursor, segment)
            val crumb = location.copy(id = "$id:$cursor", displayName = segment, reference = cursor)
            crumbs += Breadcrumb(segment, crumb)
        }
        crumbs
    }

    override suspend fun mutationIdentity(item: FileReference): String? = io {
        validateReference(item)
        withFileSystem { connection, fs -> fs.stat(scopedPath(item.opaqueId, connection.rootPath))?.stableId }
    }

    override suspend fun revisionIdentity(item: FileReference): String? = io {
        validateReference(item)
        withFileSystem { connection, fs -> fs.stat(scopedPath(item.opaqueId, connection.rootPath))?.resumeProof() }
    }

    override suspend fun resumeIdentity(item: FileReference): String? = revisionIdentity(item)

    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities = io {
        validateLocation(location)
        withFileSystem { connection, fs ->
            val c = fs.capabilities
            val writable = !connection.readOnlyOverride && !c.readOnly && location.writable
            buildSet {
                if (c.read) add(StorageCapability.READ)
                if (writable && c.createFile) addAll(setOf(StorageCapability.WRITE, StorageCapability.CREATE_FILE))
                if (writable && c.createDirectory) add(StorageCapability.CREATE_DIRECTORY)
                if (writable && c.delete) add(StorageCapability.DELETE)
                if (writable && c.rename) add(StorageCapability.RENAME)
                if (writable && c.nativeMove) add(StorageCapability.MOVE_NATIVE)
                if (writable && c.serverSideCopy) add(StorageCapability.COPY_SERVER_SIDE)
                if (writable && c.atomicReplace) add(StorageCapability.ATOMIC_RENAME)
                if (c.seekRead) addAll(setOf(StorageCapability.RANDOM_ACCESS, StorageCapability.RESUME_READ))
                if (writable && c.seekWrite) add(StorageCapability.RESUME_WRITE)
                if (c.freeSpace) add(StorageCapability.QUERY_FREE_SPACE)
                if (c.totalSpace) add(StorageCapability.QUERY_TOTAL_SPACE)
                if (c.stableIdentity) add(StorageCapability.STABLE_IDENTITY)
            }.let(::ProviderCapabilities)
        }
    }

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry = io {
        validateLocation(parent)
        val path = scopedPath(RemotePath.resolve(parent.reference, RemotePath.leaf(name)), parent.rootReference)
        withFileSystem { _, fs ->
            requireWritable(fs)
            fs.createDirectory(path)
            fs.stat(path)?.toEntry(parent.storageId) ?: throw StorageAccessException.Io(IllegalStateException("Created directory could not be verified"))
        }
    }

    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry = io {
        validateLocation(parent)
        val safeName = RemotePath.leaf(name)
        val path = scopedPath(RemotePath.resolve(parent.reference, safeName), parent.rootReference)
        withFileSystem { _, fs ->
            requireWritable(fs)
            fs.openOutput(path, offset = 0L, truncate = true).use { it.flush() }
            (fs.stat(path) ?: RemoteNode(path, safeName, directory = false, sizeBytes = 0L, mimeType = mimeType)).toEntry(parent.storageId)
        }
    }

    override suspend fun delete(item: ScopedFileReference): Boolean = io {
        validateScoped(item)
        withFileSystem { _, fs ->
            requireWritable(fs)
            val path = scopedPath(item.reference.opaqueId, item.rootReference)
            val node = fs.stat(path) ?: return@withFileSystem false
            fs.delete(path, node.directory)
            fs.stat(path) == null
        }
    }

    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry = io {
        validateScoped(item)
        val safeName = RemotePath.leaf(newName)
        withFileSystem { _, fs ->
            requireWritable(fs)
            val source = scopedPath(item.reference.opaqueId, item.rootReference)
            val parent = RemotePath.parent(source) ?: throw StorageAccessException.PermissionRequired()
            val target = scopedPath(RemotePath.resolve(parent, safeName), item.rootReference)
            fs.rename(source, target, replace = false)
            fs.stat(target)?.toEntry(item.storageId) ?: throw StorageAccessException.Io(IllegalStateException("Renamed item could not be verified"))
        }
    }

    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream = openOutputStreamAtInternal(item, 0L, truncate)

    override suspend fun openOutputStreamAt(item: ScopedFileReference, offset: Long): OutputStream = openOutputStreamAtInternal(item, offset, truncate = offset == 0L)

    private suspend fun openOutputStreamAtInternal(item: ScopedFileReference, offset: Long, truncate: Boolean): OutputStream = io {
        require(offset >= 0L)
        validateScoped(item)
        val (connection, fs) = openFileSystem()
        try {
            requireWritable(fs)
            if (offset != 0L && !fs.capabilities.seekWrite) throw StorageAccessException.Io(IllegalStateException("Provider does not support resumable writes"))
            val path = scopedPath(item.reference.opaqueId, item.rootReference.ifBlank { connection.rootPath })
            SessionOutputStream(fs.openOutput(path, offset, truncate), fs)
        } catch (error: Throwable) {
            runCatching { fs.close() }
            throw mapped(error)
        }
    }

    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = io {
        validateLocation(parent)
        val path = scopedPath(RemotePath.resolve(parent.reference, RemotePath.leaf(name)), parent.rootReference)
        withFileSystem { _, fs -> fs.stat(path)?.toEntry(parent.storageId) }
    }

    override suspend fun freeBytes(location: BrowserLocation): Long? = io {
        validateLocation(location)
        withFileSystem { _, fs -> fs.freeBytes(scopedPath(location.reference, location.rootReference)) }
    }

    override suspend fun totalBytes(location: BrowserLocation): Long? = io {
        validateLocation(location)
        withFileSystem { _, fs -> fs.totalBytes(scopedPath(location.reference, location.rootReference)) }
    }

    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean = io {
        validateScoped(source)
        validateLocation(destination)
        if (source.reference.providerId != destination.providerId) return@io false
        val sourcePath = scopedPath(source.reference.opaqueId, source.rootReference)
        val destinationPath = scopedPath(destination.reference, destination.rootReference)
        destinationPath == sourcePath || RemotePath.isWithin(sourcePath, destinationPath)
    }

    override suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean = io {
        if (item.reference.providerId != id || destination.providerId != id) return@io false
        RemotePath.leaf(newName)
        validateScoped(item)
        validateLocation(destination)
        withFileSystem { connection, fs -> !connection.readOnlyOverride && !fs.capabilities.readOnly && fs.capabilities.nativeMove && fs.capabilities.rename }
    }

    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? {
        if (!canMoveNative(item, destination, newName)) return null
        return io {
            withFileSystem { _, fs ->
                val source = scopedPath(item.reference.opaqueId, item.rootReference)
                val target = scopedPath(RemotePath.resolve(destination.reference, RemotePath.leaf(newName)), destination.rootReference)
                fs.rename(source, target, replace = false)
                fs.stat(target)?.toEntry(destination.storageId)
            }
        }
    }

    override suspend fun copyServerSide(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = io {
        if (item.reference.providerId != id || destination.providerId != id) return@io null
        validateScoped(item)
        validateLocation(destination)
        withFileSystem { _, fs ->
            if (!fs.capabilities.serverSideCopy) return@withFileSystem null
            val source = scopedPath(item.reference.opaqueId, item.rootReference)
            val target = scopedPath(RemotePath.resolve(destination.reference, RemotePath.leaf(newName)), destination.rootReference)
            if (!fs.copyServerSide(source, target)) return@withFileSystem null
            fs.stat(target)?.toEntry(destination.storageId)
        }
    }

    override suspend fun replaceAtomically(staged: ScopedFileReference, existing: ScopedFileReference, finalName: String): FileEntry? = io {
        validateScoped(staged)
        validateScoped(existing)
        if (staged.reference.providerId != existing.reference.providerId) return@io null
        withFileSystem { _, fs ->
            if (!fs.capabilities.atomicReplace) return@withFileSystem null
            val source = scopedPath(staged.reference.opaqueId, staged.rootReference)
            val existingPath = scopedPath(existing.reference.opaqueId, existing.rootReference)
            val targetParent = RemotePath.parent(existingPath) ?: return@withFileSystem null
            val target = scopedPath(RemotePath.resolve(targetParent, RemotePath.leaf(finalName)), existing.rootReference)
            fs.rename(source, target, replace = true)
            fs.stat(target)?.toEntry(existing.storageId)
        }
    }

    fun rootLocation(): BrowserLocation {
        val connection = currentConnection()
        val root = RemotePath.normalize(connection.rootPath)
        return BrowserLocation(
            providerId = id,
            id = "$id:$root",
            displayName = connection.displayName,
            reference = root,
            rootReference = root,
            storageId = connection.storageId,
            readable = true,
            writable = !connection.readOnlyOverride,
        )
    }

    private fun currentConnection(): NetworkConnection = connections.get(connectionId) ?: throw StorageAccessException.Unavailable()

    private fun validateLocation(location: BrowserLocation) {
        if (location.providerId != id) throw StorageAccessException.PermissionRequired()
        scopedPath(location.reference, location.rootReference)
    }

    private fun validateReference(reference: FileReference) {
        if (reference.providerId != id) throw StorageAccessException.PermissionRequired()
    }

    private fun validateScoped(scoped: ScopedFileReference) {
        validateReference(scoped.reference)
        scopedPath(scoped.reference.opaqueId, scoped.rootReference)
    }

    private fun scopedPath(path: String, root: String): String {
        val normalizedRoot = RemotePath.normalize(root.ifBlank { currentConnection().rootPath })
        val normalized = RemotePath.normalize(path)
        if (!RemotePath.isWithin(normalizedRoot, normalized)) throw StorageAccessException.PermissionRequired()
        return normalized
    }

    private fun requireWritable(fs: RemoteFileSystem) {
        val connection = currentConnection()
        if (connection.readOnlyOverride || fs.capabilities.readOnly) throw StorageAccessException.ReadOnly()
    }

    private fun loadSecrets(connection: NetworkConnection): RemoteSessionSecrets = RemoteSessionSecrets(
        password = credentials.getChars(AndroidKeystoreCredentialStore.passwordReference(connection.id)),
        privateKey = credentials.getBytes(AndroidKeystoreCredentialStore.privateKeyReference(connection.id)),
        privateKeyPassphrase = credentials.getChars(AndroidKeystoreCredentialStore.privateKeyPassphraseReference(connection.id)),
        oauthRefreshToken = connection.oauthAccountId?.let { credentials.getChars(AndroidKeystoreCredentialStore.oauthRefreshReference(it)) },
    )

    private fun openFileSystem(): Pair<NetworkConnection, RemoteFileSystem> {
        val connection = currentConnection()
        val secrets = loadSecrets(connection)
        return try {
            val fs = factories.factoryFor(connection.protocol).open(connection, secrets, connections.settings())
            connections.updateState(connection.id, RemoteConnectionState.CONNECTED, endpoint = "${connection.host}:${connection.port}")
            connection to fs
        } catch (error: Throwable) {
            connections.updateState(connection.id, stateFor(error))
            throw mapped(error)
        } finally {
            secrets.clear()
        }
    }

    private inline fun <T> withFileSystem(block: (NetworkConnection, RemoteFileSystem) -> T): T {
        val (connection, fs) = openFileSystem()
        return try { block(connection, fs) } catch (error: Throwable) { throw mapped(error) } finally { runCatching { fs.close() } }
    }

    private fun stateFor(error: Throwable): RemoteConnectionState = when (error) {
        is RemoteAccessException.AuthenticationRequired, is RemoteAccessException.HostKeyTrustRequired -> RemoteConnectionState.AUTH_REQUIRED
        is RemoteAccessException.AuthenticationFailed -> RemoteConnectionState.AUTH_FAILED
        is RemoteAccessException.HostUnreachable -> RemoteConnectionState.HOST_UNREACHABLE
        is RemoteAccessException.Timeout -> RemoteConnectionState.TIMEOUT
        is RemoteAccessException.Tls -> RemoteConnectionState.TLS_ERROR
        is RemoteAccessException.Certificate -> RemoteConnectionState.CERTIFICATE_ERROR
        is RemoteAccessException.ServerIdentityChanged -> RemoteConnectionState.SERVER_CHANGED
        is RemoteAccessException.PermissionDenied -> RemoteConnectionState.PERMISSION_DENIED
        is RemoteAccessException.ReadOnly -> RemoteConnectionState.READ_ONLY
        is RemoteAccessException.Protocol -> RemoteConnectionState.PROTOCOL_ERROR
        else -> RemoteConnectionState.UNKNOWN
    }

    private fun mapped(error: Throwable): Throwable = when (error) {
        is StorageAccessException -> error
        is RemoteAccessException.AuthenticationRequired -> StorageAccessException.AuthenticationRequired(error)
        is RemoteAccessException.HostKeyTrustRequired -> StorageAccessException.AuthenticationRequired(error)
        is RemoteAccessException.AuthenticationFailed -> StorageAccessException.AuthenticationFailed(error)
        is RemoteAccessException.HostUnreachable -> StorageAccessException.Unavailable(error)
        is RemoteAccessException.Timeout -> StorageAccessException.Timeout(error)
        is RemoteAccessException.Tls -> StorageAccessException.Tls(error)
        is RemoteAccessException.Certificate -> StorageAccessException.Certificate(error)
        is RemoteAccessException.ServerIdentityChanged -> StorageAccessException.ServerIdentityChanged(error)
        is RemoteAccessException.PermissionDenied -> StorageAccessException.PermissionRequired(error)
        is RemoteAccessException.ReadOnly -> StorageAccessException.ReadOnly(error)
        is RemoteAccessException.Protocol -> StorageAccessException.Protocol(error)
        else -> StorageAccessException.Io(error)
    }

    private suspend inline fun <T> io(crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun RemoteNode.toEntry(storageId: String): FileEntry {
        val normalizedPath = RemotePath.normalize(path)
        return FileEntry(
            id = stableId ?: "$id:$normalizedPath",
            reference = FileReference(providerId = id, opaqueId = normalizedPath, uri = null, path = null),
            name = name,
            extension = FileClassifier.extensionFor(name),
            mimeType = mimeType,
            type = FileClassifier.classify(name, mimeType, directory),
            sizeBytes = if (directory) null else sizeBytes,
            modifiedAtMillis = modifiedAtMillis,
            createdAtMillis = createdAtMillis,
            isHidden = hidden,
            isReadable = readable,
            isWritable = writable,
            childCount = null,
            storageId = storageId,
            thumbnailKey = revision ?: stableId,
        )
    }

    private fun RemoteNode.resumeProof(): String? = revision ?: stableId?.let { stable -> "$stable:${sizeBytes ?: -1L}:${modifiedAtMillis ?: -1L}" }

    private class SessionInputStream(delegate: InputStream, private val session: RemoteFileSystem) : FilterInputStream(delegate) {
        override fun close() { try { super.close() } finally { runCatching { session.close() } } }
    }

    private class SessionOutputStream(delegate: OutputStream, private val session: RemoteFileSystem) : FilterOutputStream(delegate) {
        override fun close() { try { super.close() } finally { runCatching { session.close() } } }
    }
}
