package com.zz.filemanager.core.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.zz.filemanager.R
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
        val category = runCatching { MediaCategory.valueOf(location.reference) }
            .getOrElse { throw StorageAccessException.Unavailable(it) }
        val spec = querySpec(category)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        try {
            val result = ArrayList<FileEntry>()
            context.contentResolver.query(spec.collection, projection, spec.selection, spec.selectionArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val rowId = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: context.getString(R.string.unnamed_item)
                    val mime = cursor.getString(mimeIndex)
                    val size = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex)
                    val modified = if (cursor.isNull(modifiedIndex)) null else cursor.getLong(modifiedIndex) * 1000L
                    val uri = ContentUris.withAppendedId(spec.collection, rowId)
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
        } catch (error: IllegalArgumentException) {
            throw StorageAccessException.Unavailable(error)
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

    private fun querySpec(category: MediaCategory): QuerySpec = when (category) {
        MediaCategory.IMAGES -> QuerySpec(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        MediaCategory.VIDEOS -> QuerySpec(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        MediaCategory.AUDIO -> QuerySpec(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        MediaCategory.DOWNLOADS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            QuerySpec(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        } else {
            QuerySpec(
                collection = MediaStore.Files.getContentUri("external"),
                selection = "${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DATA} LIKE ?",
                selectionArgs = arrayOf("%/Download/%", "%/Downloads/%"),
            )
        }
        MediaCategory.APKS -> QuerySpec(
            collection = MediaStore.Files.getContentUri("external"),
            selection = "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            selectionArgs = arrayOf("application/vnd.android.package-archive", "%.apk"),
        )
        MediaCategory.DOCUMENTS -> documentQuerySpec()
    }

    private fun documentQuerySpec(): QuerySpec {
        val exactMimes = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation",
            "text/csv",
        )
        val extensions = listOf("pdf", "txt", "md", "csv", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp")
        val mimeClause = exactMimes.joinToString(prefix = "${MediaStore.MediaColumns.MIME_TYPE} IN (", postfix = ")") { "?" }
        val extensionClause = extensions.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
        return QuerySpec(
            collection = MediaStore.Files.getContentUri("external"),
            selection = "${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR $mimeClause OR $extensionClause",
            selectionArgs = buildList {
                add("text/%")
                addAll(exactMimes)
                addAll(extensions.map { "%.$it" })
            }.toTypedArray(),
        )
    }

    private data class QuerySpec(
        val collection: Uri,
        val selection: String? = null,
        val selectionArgs: Array<String>? = null,
    )

    companion object { const val ID = "media" }
}
