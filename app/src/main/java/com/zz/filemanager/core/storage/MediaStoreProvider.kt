package com.zz.filemanager.core.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.MediaCategory
import com.zz.filemanager.core.util.FileClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class MediaStoreProvider(private val context: Context) : StorageProvider {
    override val id: String = ID

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = withContext(Dispatchers.IO) {
        val category = runCatching { MediaCategory.valueOf(location.reference) }.getOrElse { throw StorageAccessException.Unavailable(it) }
        val collection = when (category) {
            MediaCategory.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        try {
            val result = ArrayList<FileEntry>()
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val rowId = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: "Unnamed"
                    val mime = cursor.getString(mimeIndex)
                    val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                    val modified = if (cursor.isNull(modifiedIndex)) null else cursor.getLong(modifiedIndex) * 1000L
                    val uri = ContentUris.withAppendedId(collection, rowId)
                    result += FileEntry(
                        id = "media:$uri",
                        reference = FileReference(ID, "media:$uri", uri = uri.toString()),
                        name = name,
                        extension = FileClassifier.extensionFor(name),
                        mimeType = mime,
                        type = FileClassifier.classify(name, mime),
                        sizeBytes = size,
                        modifiedAtMillis = modified,
                        createdAtMillis = null,
                        isHidden = name.startsWith('.'),
                        isReadable = true,
                        isWritable = false,
                        childCount = null,
                        storageId = "media:${category.name.lowercase()}",
                        thumbnailKey = "$uri:${modified ?: 0L}:${size ?: -1L}",
                    )
                }
            }
            result
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = null
    override suspend fun openInputStream(item: FileReference): InputStream = withContext(Dispatchers.IO) {
        val uri = item.uri?.let(Uri::parse) ?: throw StorageAccessException.Unavailable()
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    }
    override suspend fun exists(item: FileReference): Boolean = item.uri?.let(Uri::parse)?.let { uri ->
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    } ?: false
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = listOf(Breadcrumb(location.displayName, location))

    companion object { const val ID = "media" }
}
