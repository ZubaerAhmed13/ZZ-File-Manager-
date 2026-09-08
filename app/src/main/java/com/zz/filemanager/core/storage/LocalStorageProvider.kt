package com.zz.filemanager.core.storage

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import com.zz.filemanager.R
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.util.FileClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

class LocalStorageProvider(private val context: Context) : WritableStorageProvider {
    override val id: String = ID

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        val directory = validated(location.reference, location.rootReference)
        if (!directory.exists()) throw StorageAccessException.Unavailable()
        if (!directory.isDirectory) throw StorageAccessException.Io()
        if (!directory.canRead()) throw StorageAccessException.PermissionRequired()
        val children = directory.listFiles() ?: if (!directory.canRead()) throw StorageAccessException.PermissionRequired() else emptyArray()
        children.map { file -> coroutineContext.ensureActive(); toEntry(file, location.storageId) }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = withContext(Dispatchers.IO) {
        val path = item.path ?: return@withContext null
        val file = File(path)
        if (!file.exists()) null else toEntry(file, "local")
    }

    override suspend fun openInputStream(item: FileReference): InputStream = withContext(Dispatchers.IO) { FileInputStream(item.path ?: throw StorageAccessException.Unavailable()) }
    override suspend fun exists(item: FileReference): Boolean = withContext(Dispatchers.IO) { item.path?.let(::File)?.exists() == true }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = withContext(Dispatchers.IO) {
        val current = validated(location.reference, location.rootReference)
        val root = File(location.rootReference).canonicalFile
        if (current == root) return@withContext null
        val parent = current.parentFile?.canonicalFile ?: return@withContext null
        if (!isInside(parent, root)) return@withContext null
        location.copy(id = "local:${parent.path}", displayName = if (parent == root) rootDisplayName(location) else parent.name.ifBlank { location.displayName }, reference = parent.path, readable = parent.canRead(), writable = parent.canWrite())
    }

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = withContext(Dispatchers.IO) {
        val root = File(location.rootReference).canonicalFile
        val current = validated(location.reference, location.rootReference)
        val rootLabel = rootDisplayName(location)
        val result = mutableListOf(Breadcrumb(rootLabel, location.copy(id = "local:${root.path}", displayName = rootLabel, reference = root.path, readable = root.canRead(), writable = root.canWrite())))
        if (current == root) return@withContext result
        val relative = current.path.removePrefix(root.path).trimStart(File.separatorChar)
        var cursor = root
        relative.split(File.separatorChar).filter { it.isNotBlank() }.forEach { segment ->
            cursor = File(cursor, segment)
            result += Breadcrumb(segment, location.copy(id = "local:${cursor.path}", displayName = segment, reference = cursor.path, readable = cursor.canRead(), writable = cursor.canWrite()))
        }
        result
    }

    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities = withContext(Dispatchers.IO) {
        val directory = validated(location.reference, location.rootReference)
        val capabilities = mutableSetOf(StorageCapability.READ)
        if (directory.canWrite() && location.writable) capabilities += setOf(StorageCapability.WRITE, StorageCapability.CREATE_FILE, StorageCapability.CREATE_DIRECTORY, StorageCapability.DELETE, StorageCapability.RENAME, StorageCapability.MOVE_NATIVE, StorageCapability.RANDOM_ACCESS, StorageCapability.ATOMIC_RENAME, StorageCapability.SET_MODIFIED_TIME)
        ProviderCapabilities(capabilities)
    }

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry = withContext(Dispatchers.IO) {
        requireSafeLeafName(name); val directory = validated(parent.reference, parent.rootReference); if (!directory.canWrite()) throw StorageAccessException.ReadOnly()
        val child = File(directory, name); if (child.exists()) throw StorageAccessException.Io(IllegalStateException("destination exists"))
        runCatching { Files.createDirectory(child.toPath()) }.getOrElse { throw mapWriteFailure(it) }; toEntry(child, parent.storageId)
    }

    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry = withContext(Dispatchers.IO) {
        requireSafeLeafName(name); val directory = validated(parent.reference, parent.rootReference); if (!directory.canWrite()) throw StorageAccessException.ReadOnly()
        val child = File(directory, name); if (child.exists()) throw StorageAccessException.Io(IllegalStateException("destination exists"))
        runCatching { Files.createFile(child.toPath()) }.getOrElse { throw mapWriteFailure(it) }; toEntry(child, parent.storageId)
    }

    override suspend fun delete(item: ScopedFileReference): Boolean = withContext(Dispatchers.IO) { val file = scoped(item); runCatching { Files.deleteIfExists(file.toPath()) }.getOrElse { throw mapWriteFailure(it) } }

    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry = withContext(Dispatchers.IO) {
        requireSafeLeafName(newName); val source = scoped(item); if (!source.exists()) throw StorageAccessException.Unavailable(); if (source.name == newName) return@withContext toEntry(source, item.storageId)
        val parent = source.parentFile ?: throw StorageAccessException.Io(); val target = File(parent, newName); if (!isInside(target.canonicalFile, File(item.rootReference).canonicalFile)) throw StorageAccessException.PermissionRequired()
        val sameUnderlying = target.exists() && runCatching { target.canonicalFile == source.canonicalFile }.getOrDefault(false); if (target.exists() && !sameUnderlying) throw StorageAccessException.Io(IllegalStateException("destination exists"))
        try {
            if (sameUnderlying) { val temporary = uniqueTemporarySibling(parent, ".zzcase-${UUID.randomUUID()}"); Files.move(source.toPath(), temporary.toPath()); try { Files.move(temporary.toPath(), target.toPath()) } catch (error: Throwable) { runCatching { Files.move(temporary.toPath(), source.toPath()) }; throw error } }
            else Files.move(source.toPath(), target.toPath())
        } catch (error: Throwable) { throw mapWriteFailure(error) }
        toEntry(target, item.storageId)
    }

    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream = withContext(Dispatchers.IO) {
        val file = scoped(item); if (!file.exists()) throw StorageAccessException.Unavailable(); if (!file.isFile) throw StorageAccessException.Io()
        try { FileOutputStream(file, !truncate) } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) } catch (error: Throwable) { throw StorageAccessException.Io(error) }
    }

    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = withContext(Dispatchers.IO) { requireSafeLeafName(name); val child = File(validated(parent.reference, parent.rootReference), name); if (!child.exists()) null else toEntry(child, parent.storageId) }
    override suspend fun freeBytes(location: BrowserLocation): Long? = withContext(Dispatchers.IO) { validated(location.reference, location.rootReference).usableSpace.takeIf { it >= 0L } }

    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean = withContext(Dispatchers.IO) {
        val sourceFile = scoped(source)
        val destinationFile = validated(destination.reference, destination.rootReference)
        destinationFile == sourceFile || (sourceFile.isDirectory && isInside(destinationFile, sourceFile))
    }

    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = withContext(Dispatchers.IO) {
        requireSafeLeafName(newName); val source = scoped(item); val destinationDirectory = validated(destination.reference, destination.rootReference); if (!destinationDirectory.canWrite()) throw StorageAccessException.ReadOnly()
        val target = File(destinationDirectory, newName); if (target.exists()) return@withContext null
        val sameFileStore = runCatching { Files.getFileStore(source.toPath()) == Files.getFileStore(destinationDirectory.toPath()) }.getOrDefault(false); if (!sameFileStore) return@withContext null
        try { runCatching { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }.recoverCatching { Files.move(source.toPath(), target.toPath()) }.getOrThrow(); toEntry(target, destination.storageId) } catch (error: Throwable) { throw mapWriteFailure(error) }
    }

    private fun toEntry(file: File, storageId: String): FileEntry {
        val symbolicLink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false); val extension = FileClassifier.extensionFor(file.name)
        val mime = if (file.isDirectory && !symbolicLink) null else extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val type = if (symbolicLink && file.isDirectory) com.zz.filemanager.core.model.FileEntryType.GENERIC else FileClassifier.classify(file.name, mime, file.isDirectory)
        val modified = file.lastModified().takeIf { it > 0L }
        return FileEntry(id = "local:${file.absolutePath}", reference = FileReference(ID, "local:${file.absolutePath}", path = file.absolutePath), name = file.name.ifBlank { file.absolutePath }, extension = extension, mimeType = mime, type = type, sizeBytes = if (file.isFile || symbolicLink) file.length() else null, modifiedAtMillis = modified, createdAtMillis = null, isHidden = file.name.startsWith('.') || file.isHidden, isReadable = file.canRead(), isWritable = file.canWrite(), childCount = null, storageId = storageId, thumbnailKey = if (file.isFile && !symbolicLink) "${file.absolutePath}:${modified ?: 0L}:${file.length()}" else null, isSymbolicLink = symbolicLink)
    }

    private fun scoped(item: ScopedFileReference): File { val path = item.reference.path ?: throw StorageAccessException.Unavailable(); return validated(path, item.rootReference) }
    private fun validated(path: String, rootPath: String): File { val file = runCatching { File(path).canonicalFile }.getOrElse { throw StorageAccessException.Io(it) }; val root = runCatching { File(rootPath).canonicalFile }.getOrElse { throw StorageAccessException.Io(it) }; if (!isInside(file, root)) throw StorageAccessException.PermissionRequired(); return file }
    private fun requireSafeLeafName(name: String) { if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) throw StorageAccessException.Io(IllegalArgumentException("invalid leaf name")) }
    private fun uniqueTemporarySibling(parent: File, prefix: String): File { var index = 0; while (true) { val candidate = File(parent, if (index == 0) prefix else "$prefix-$index"); if (!candidate.exists()) return candidate; index++ } }
    private fun mapWriteFailure(error: Throwable): StorageAccessException = when (error) { is SecurityException -> StorageAccessException.PermissionRequired(error); else -> StorageAccessException.Io(error) }
    private fun isInside(file: File, root: File): Boolean = file == root || file.path.startsWith(root.path + File.separator)

    @Suppress("DEPRECATION")
    private fun rootDisplayName(location: BrowserLocation): String {
        if (location.reference == location.rootReference && location.displayName.isNotBlank()) return location.displayName
        if (location.storageId == "primary") return context.getString(R.string.internal_storage)
        val root = runCatching { File(location.rootReference).canonicalFile }.getOrNull()
        if (root != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            val volume = manager.storageVolumes.firstOrNull { storageVolume -> storageVolume.directory?.let { runCatching { it.canonicalFile }.getOrNull() } == root }
            if (volume != null) return if (volume.isPrimary) context.getString(R.string.internal_storage) else volume.getDescription(context)
        }
        return location.storageId.takeIf { it.isNotBlank() } ?: context.getString(R.string.internal_storage)
    }
    companion object { const val ID = "local" }
}
