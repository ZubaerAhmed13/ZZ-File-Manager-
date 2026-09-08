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
import com.zz.filemanager.core.util.FileClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class LocalStorageProvider(private val context: Context) : StorageProvider {
    override val id: String = ID

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        val directory = validated(location.reference, location.rootReference)
        if (!directory.exists()) throw StorageAccessException.Unavailable()
        if (!directory.isDirectory) throw StorageAccessException.Io()
        if (!directory.canRead()) throw StorageAccessException.PermissionRequired()
        val children = directory.listFiles()
            ?: if (!directory.canRead()) throw StorageAccessException.PermissionRequired() else emptyArray()
        children.map { file ->
            coroutineContext.ensureActive()
            toEntry(file, location.storageId)
        }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = withContext(Dispatchers.IO) {
        val path = item.path ?: return@withContext null
        val file = File(path)
        if (!file.exists()) null else toEntry(file, "local")
    }

    override suspend fun openInputStream(item: FileReference): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(item.path ?: throw StorageAccessException.Unavailable())
    }

    override suspend fun exists(item: FileReference): Boolean = withContext(Dispatchers.IO) {
        item.path?.let(::File)?.exists() == true
    }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = withContext(Dispatchers.IO) {
        val current = validated(location.reference, location.rootReference)
        val root = File(location.rootReference).canonicalFile
        if (current == root) return@withContext null
        val parent = current.parentFile?.canonicalFile ?: return@withContext null
        if (!isInside(parent, root)) return@withContext null
        location.copy(
            id = "local:${parent.path}",
            displayName = if (parent == root) rootDisplayName(location) else parent.name.ifBlank { location.displayName },
            reference = parent.path,
            readable = parent.canRead(),
            writable = parent.canWrite(),
        )
    }

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = withContext(Dispatchers.IO) {
        val root = File(location.rootReference).canonicalFile
        val current = validated(location.reference, location.rootReference)
        val rootLabel = rootDisplayName(location)
        val result = mutableListOf(
            Breadcrumb(
                rootLabel,
                location.copy(
                    id = "local:${root.path}",
                    displayName = rootLabel,
                    reference = root.path,
                    readable = root.canRead(),
                    writable = root.canWrite(),
                ),
            ),
        )
        if (current == root) return@withContext result
        val relative = current.path.removePrefix(root.path).trimStart(File.separatorChar)
        var cursor = root
        relative.split(File.separatorChar).filter { it.isNotBlank() }.forEach { segment ->
            cursor = File(cursor, segment)
            result += Breadcrumb(
                segment,
                location.copy(
                    id = "local:${cursor.path}",
                    displayName = segment,
                    reference = cursor.path,
                    readable = cursor.canRead(),
                    writable = cursor.canWrite(),
                ),
            )
        }
        result
    }

    private fun toEntry(file: File, storageId: String): FileEntry {
        val extension = FileClassifier.extensionFor(file.name)
        val mime = if (file.isDirectory) null else extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val type = FileClassifier.classify(file.name, mime, file.isDirectory)
        val modified = file.lastModified().takeIf { it > 0L }
        return FileEntry(
            id = "local:${file.absolutePath}",
            reference = FileReference(ID, "local:${file.absolutePath}", path = file.absolutePath),
            name = file.name.ifBlank { file.absolutePath },
            extension = extension,
            mimeType = mime,
            type = type,
            sizeBytes = if (file.isFile) file.length() else null,
            modifiedAtMillis = modified,
            createdAtMillis = null,
            isHidden = file.name.startsWith('.') || file.isHidden,
            isReadable = file.canRead(),
            isWritable = file.canWrite(),
            childCount = null,
            storageId = storageId,
            thumbnailKey = if (file.isFile) "${file.absolutePath}:${modified ?: 0L}:${file.length()}" else null,
        )
    }

    private fun validated(path: String, rootPath: String): File {
        val file = runCatching { File(path).canonicalFile }.getOrElse { throw StorageAccessException.Io(it) }
        val root = runCatching { File(rootPath).canonicalFile }.getOrElse { throw StorageAccessException.Io(it) }
        if (!isInside(file, root)) throw StorageAccessException.PermissionRequired()
        return file
    }

    private fun isInside(file: File, root: File): Boolean = file == root || file.path.startsWith(root.path + File.separator)

    @Suppress("DEPRECATION")
    private fun rootDisplayName(location: BrowserLocation): String {
        if (location.reference == location.rootReference && location.displayName.isNotBlank()) {
            return location.displayName
        }
        if (location.storageId == "primary") return context.getString(R.string.internal_storage)

        val root = runCatching { File(location.rootReference).canonicalFile }.getOrNull()
        if (root != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            val volume = manager.storageVolumes.firstOrNull { storageVolume ->
                val volumeRoot = storageVolume.directory?.let { runCatching { it.canonicalFile }.getOrNull() }
                volumeRoot == root
            }
            if (volume != null) {
                return if (volume.isPrimary) context.getString(R.string.internal_storage) else volume.getDescription(context)
            }
        }
        return location.storageId.takeIf { it.isNotBlank() } ?: context.getString(R.string.internal_storage)
    }

    companion object { const val ID = "local" }
}
