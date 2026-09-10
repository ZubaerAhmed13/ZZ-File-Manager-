package com.zz.filemanager.core.cloud

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.remote.AndroidKeystoreCredentialStore
import com.zz.filemanager.core.remote.SecureCredentialStore
import com.zz.filemanager.core.storage.IncrementalStorageProvider
import com.zz.filemanager.core.storage.ProviderCapabilities
import com.zz.filemanager.core.storage.StorageAccessException
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.storage.WritableStorageProvider
import com.zz.filemanager.core.util.FileClassifier
import kotlinx.coroutines.runBlocking
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * StorageProvider bridge for a direct-cloud account. It preserves provider-native ids as the
 * canonical FileReference identity and opens sessions lazily from a Keystore-backed refresh token.
 */
class DirectCloudStorageProvider(
    private val account: CloudAccountIdentity,
    private val credentials: SecureCredentialStore,
    private val adapters: DirectCloudAdapterRegistry,
    private val rootNativeFileId: String = "root",
    override val id: String = account.providerId,
) : WritableStorageProvider, IncrementalStorageProvider {

    fun rootLocation(): BrowserLocation = BrowserLocation(
        providerId = id,
        id = "$id:$rootNativeFileId",
        displayName = account.displayName,
        reference = rootNativeFileId,
        rootReference = rootNativeFileId,
        storageId = id,
        readable = true,
        writable = true,
    )

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
        val all = mutableListOf<FileEntry>()
        listChildrenIncrementally(location) { all += it }
        return all
    }

    override suspend fun listChildrenIncrementally(
        location: BrowserLocation,
        pageSize: Int,
        onPage: suspend (List<FileEntry>) -> Unit,
    ) {
        requireLocation(location)
        require(pageSize in 1..1_000)
        withSession { session ->
            var token: String? = null
            do {
                val page = session.listPage(location.reference, token, pageSize)
                if (page.items.isNotEmpty()) onPage(page.items.map(::toEntry))
                token = page.nextPageToken
            } while (token != null)
        }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = withSession { session ->
        session.stat(nativeId(item))?.let(::toEntry)
    }

    override suspend fun openInputStream(item: FileReference): InputStream {
        val session = openSession()
        return try {
            val stream = session.openRead(nativeId(item), 0L)
            object : FilterInputStream(stream) {
                override fun close() {
                    try { super.close() } finally { session.close() }
                }
            }
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw mapCloudError(error)
        }
    }

    override suspend fun exists(item: FileReference): Boolean = getMetadata(item) != null

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? {
        requireLocation(location)
        if (location.reference == rootNativeFileId) return null
        return withSession { session ->
            val current = session.stat(location.reference) ?: return@withSession null
            val parentId = current.parentNativeFileId ?: return@withSession null
            val parent = session.stat(parentId) ?: return@withSession null
            toLocation(parent)
        }
    }

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> {
        requireLocation(location)
        return withSession { session ->
            val reverse = mutableListOf<Breadcrumb>()
            var currentId: String? = location.reference
            while (currentId != null) {
                val item = session.stat(currentId) ?: break
                reverse += Breadcrumb(item.displayName, toLocation(item))
                currentId = item.parentNativeFileId
            }
            reverse.asReversed()
        }
    }

    override suspend fun mutationIdentity(item: FileReference): String? = withSession { session ->
        session.stat(nativeId(item))?.let { "${it.nativeFileId}:${it.revision.orEmpty()}" }
    }

    override suspend fun revisionIdentity(item: FileReference): String? = withSession { session ->
        session.stat(nativeId(item))?.revision
    }

    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities {
        requireLocation(location)
        return ProviderCapabilities(
            setOf(
                StorageCapability.READ,
                StorageCapability.WRITE,
                StorageCapability.CREATE_FILE,
                StorageCapability.CREATE_DIRECTORY,
                StorageCapability.DELETE,
                StorageCapability.RENAME,
                StorageCapability.MOVE_NATIVE,
                StorageCapability.STABLE_IDENTITY,
            ),
        )
    }

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry {
        requireLocation(parent)
        return withSession { session -> toEntry(session.createDirectory(parent.reference, name)) }
    }

    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry {
        requireLocation(parent)
        return withSession { session ->
            session.beginUpload(parent.reference, name, 0L).use { upload ->
                toEntry(upload.commit(0L, "zz-empty-v1"))
            }
        }
    }

    override suspend fun delete(item: ScopedFileReference): Boolean {
        requireScoped(item)
        return withSession { session ->
            val current = session.stat(nativeId(item.reference)) ?: return@withSession false
            session.delete(current.nativeFileId, current.revision)
            true
        }
    }

    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry {
        requireScoped(item)
        return withSession { session ->
            val current = session.stat(nativeId(item.reference)) ?: throw StorageAccessException.Unavailable()
            toEntry(session.rename(current.nativeFileId, newName, current.revision))
        }
    }

    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream {
        requireScoped(item)
        require(truncate) { "Direct-cloud append requires a provider resumable-upload implementation" }
        val session = openSession()
        try {
            val current = session.stat(nativeId(item.reference)) ?: throw StorageAccessException.Unavailable()
            val parentId = current.parentNativeFileId ?: throw StorageAccessException.ReadOnly()
            val upload = session.beginUpload(
                parentNativeFileId = parentId,
                name = current.displayName,
                expectedSizeBytes = null,
                targetNativeFileId = current.nativeFileId,
            )
            val delegate = upload.outputStream()
            return object : FilterOutputStream(delegate) {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    var failure: Throwable? = null
                    try { super.close() } catch (error: Throwable) { failure = error }
                    if (failure == null) {
                        try {
                            runBlocking { upload.commit(null, current.revision ?: "zz-write-v1") }
                        } catch (error: Throwable) {
                            failure = error
                            runCatching { runBlocking { upload.abort() } }
                        }
                    }
                    runCatching { upload.close() }
                    runCatching { session.close() }
                    failure?.let { throw mapCloudError(it) }
                }
            }
        } catch (error: Throwable) {
            runCatching { session.close() }
            throw mapCloudError(error)
        }
    }

    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? {
        requireLocation(parent)
        var found: FileEntry? = null
        withSession { session ->
            var token: String? = null
            do {
                val page = session.listPage(parent.reference, token, 256)
                page.items.firstOrNull { it.displayName == name }?.let { found = toEntry(it) }
                if (found != null) break
                token = page.nextPageToken
            } while (token != null)
        }
        return found
    }

    override suspend fun freeBytes(location: BrowserLocation): Long? = null
    override suspend fun totalBytes(location: BrowserLocation): Long? = null

    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean {
        requireScoped(source)
        requireLocation(destination)
        val sourceId = nativeId(source.reference)
        if (destination.reference == sourceId) return true
        return withSession { session ->
            var cursor: String? = destination.reference
            while (cursor != null) {
                if (cursor == sourceId) return@withSession true
                cursor = session.stat(cursor)?.parentNativeFileId
            }
            false
        }
    }

    override suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean =
        item.reference.providerId == id && destination.providerId == id

    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? {
        requireScoped(item)
        requireLocation(destination)
        return withSession { session ->
            val current = session.stat(nativeId(item.reference)) ?: return@withSession null
            var moved = session.move(current.nativeFileId, destination.reference, current.revision)
            if (moved.displayName != newName) moved = session.rename(moved.nativeFileId, newName, moved.revision)
            toEntry(moved)
        }
    }

    private suspend fun <T> withSession(block: suspend (DirectCloudSession) -> T): T {
        val session = openSession()
        return try { block(session) } catch (error: Throwable) { throw mapCloudError(error) } finally { runCatching { session.close() } }
    }

    private fun openSession(): DirectCloudSession {
        val reference = AndroidKeystoreCredentialStore.oauthRefreshReference(id)
        val refresh = credentials.getChars(reference) ?: throw StorageAccessException.AuthenticationRequired()
        return try {
            adapters.requireAdapter(account.providerKey).open(account, refresh)
        } catch (error: Throwable) {
            throw mapCloudError(error)
        } finally {
            refresh.fill('\u0000')
        }
    }

    private fun requireLocation(location: BrowserLocation) {
        require(location.providerId == id) { "Location belongs to another provider" }
    }

    private fun requireScoped(item: ScopedFileReference) {
        require(item.reference.providerId == id && item.storageId == id) { "Item belongs to another provider" }
    }

    private fun nativeId(reference: FileReference): String {
        require(reference.providerId == id) { "Reference belongs to another provider" }
        return reference.opaqueId
    }

    private fun toLocation(item: CloudItem) = BrowserLocation(
        providerId = id,
        id = "$id:${item.nativeFileId}",
        displayName = item.displayName,
        reference = item.nativeFileId,
        rootReference = rootNativeFileId,
        storageId = id,
        readable = true,
        writable = item.writable,
    )

    private fun toEntry(item: CloudItem): FileEntry {
        val extension = FileClassifier.extensionFor(item.displayName)
        return FileEntry(
            id = "$id:${item.nativeFileId}",
            reference = FileReference(providerId = id, opaqueId = item.nativeFileId),
            name = item.displayName,
            extension = extension,
            mimeType = item.mimeType,
            type = FileClassifier.classify(item.displayName, item.mimeType, item.directory),
            sizeBytes = if (item.directory) null else item.sizeBytes,
            modifiedAtMillis = item.modifiedAtMillis,
            createdAtMillis = null,
            isHidden = item.displayName.startsWith('.'),
            isReadable = true,
            isWritable = item.writable,
            childCount = null,
            storageId = id,
            thumbnailKey = item.revision?.let { "$id:${item.nativeFileId}:$it" },
        )
    }
}

private fun mapCloudError(error: Throwable): StorageAccessException = when (error) {
    is StorageAccessException -> error
    is CloudAuthException.ReauthenticationRequired -> StorageAccessException.AuthenticationRequired(error)
    is CloudAuthException.ExpiredToken, is CloudAuthException.RefreshFailed -> StorageAccessException.AuthenticationFailed(error)
    is SecurityException -> StorageAccessException.PermissionRequired(error)
    else -> StorageAccessException.Io(error)
}
