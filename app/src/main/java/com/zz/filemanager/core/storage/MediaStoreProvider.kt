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
import com.zz.filemanager.core.model.CategoryMetric
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
        val parsed = parseLocation(location.reference)
        val category = runCatching { MediaCategory.valueOf(parsed.category) }
            .getOrElse { throw StorageAccessException.Unavailable(it) }
        if (parsed.relativePath == null && category in GROUPED_CATEGORIES) return@withContext listGroups(category)
        listFiles(category, parsed.relativePath)
    }

    private suspend fun listFiles(category: MediaCategory, relativePath: String?): List<FileEntry> {
        val spec = querySpec(category, relativePath)
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
            return result
        } catch (error: SecurityException) {
            throw StorageAccessException.PermissionRequired(error)
        } catch (error: IllegalArgumentException) {
            throw StorageAccessException.Unavailable(error)
        }
    }

    private suspend fun listGroups(category: MediaCategory): List<FileEntry> {
        val spec = querySpec(category)
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(pathColumn, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        data class Aggregate(var count: Long = 0, var bytes: Long = 0, var sizeKnown: Boolean = true, var modified: Long? = null)
        val groups = linkedMapOf<String, Aggregate>()
        try {
            context.contentResolver.query(spec.collection, projection, spec.selection, spec.selectionArgs, null)?.use { cursor ->
                val pathIndex = cursor.getColumnIndex(pathColumn); val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE); val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val raw = if (pathIndex >= 0 && !cursor.isNull(pathIndex)) cursor.getString(pathIndex) else null
                    val path = when { raw.isNullOrBlank() -> OTHER_GROUP; Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> raw.trim('/').ifBlank { OTHER_GROUP }; else -> raw.substringBeforeLast('/', "").ifBlank { OTHER_GROUP } }
                    val aggregate = groups.getOrPut(path) { Aggregate() }; aggregate.count++
                    if (sizeIndex < 0 || cursor.isNull(sizeIndex)) aggregate.sizeKnown = false else aggregate.bytes += cursor.getLong(sizeIndex).coerceAtLeast(0)
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) { val modified = cursor.getLong(modifiedIndex) * 1000; aggregate.modified = maxOf(aggregate.modified ?: modified, modified) }
                }
            }
            return groups.map { (path, aggregate) ->
                val reference = encodeLocation(category, path)
                FileEntry(
                    id = "media-group:$reference", reference = FileReference(ID, reference),
                    name = if (path == OTHER_GROUP) context.getString(R.string.other) else path.trimEnd('/').substringAfterLast('/'),
                    extension = null, mimeType = null, type = com.zz.filemanager.core.model.FileEntryType.DIRECTORY,
                    sizeBytes = aggregate.bytes.takeIf { aggregate.sizeKnown }, modifiedAtMillis = aggregate.modified,
                    createdAtMillis = null, isHidden = false, isReadable = true, isWritable = false,
                    childCount = aggregate.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), storageId = "media:${category.name.lowercase()}", thumbnailKey = null,
                )
            }.sortedBy { it.name.lowercase() }
        } catch (error: SecurityException) { throw StorageAccessException.PermissionRequired(error) }
        catch (error: IllegalArgumentException) { throw StorageAccessException.Unavailable(error) }
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = null

    /** Lightweight Home metric query: no FileEntry or thumbnail allocation. */
    suspend fun categoryMetric(category: MediaCategory): CategoryMetric = withContext(Dispatchers.IO) {
        val spec = querySpec(category)
        var count = 0L
        var total = 0L
        var sizeKnown = true
        try {
            context.contentResolver.query(spec.collection, arrayOf(MediaStore.MediaColumns.SIZE), spec.selection, spec.selectionArgs, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive(); count++
                    if (sizeIndex < 0 || cursor.isNull(sizeIndex)) sizeKnown = false else total += cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
            }
            CategoryMetric(count, total.takeIf { sizeKnown })
        } catch (_: SecurityException) { CategoryMetric() }
        catch (_: IllegalArgumentException) { CategoryMetric() }
    }

    override suspend fun openInputStream(item: FileReference): InputStream = withContext(Dispatchers.IO) {
        val uri = item.uri?.let(Uri::parse) ?: throw StorageAccessException.Unavailable()
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    }

    override suspend fun exists(item: FileReference): Boolean = item.uri?.let(Uri::parse)?.let { uri ->
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    } ?: false

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? {
        val parsed = parseLocation(location.reference)
        if (parsed.relativePath == null) return null
        return runCatching { MediaCategory.valueOf(parsed.category) }.getOrNull()?.let(::rootLocation)
    }
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> {
        val parsed = parseLocation(location.reference)
        if (parsed.relativePath == null) return listOf(Breadcrumb(location.displayName, location))
        val category = runCatching { MediaCategory.valueOf(parsed.category) }.getOrNull() ?: return listOf(Breadcrumb(location.displayName, location))
        return listOf(Breadcrumb(categoryLabel(category), rootLocation(category)), Breadcrumb(location.displayName, location))
    }

    private fun querySpec(category: MediaCategory, relativePath: String? = null): QuerySpec {
        val base = when (category) {
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
        if (relativePath == null) return base
        val clause: String
        val args = base.selectionArgs.orEmpty().toMutableList()
        if (relativePath == OTHER_GROUP) {
            clause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR ${MediaStore.MediaColumns.RELATIVE_PATH} = '')" else "${MediaStore.MediaColumns.DATA} IS NULL"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            clause = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"; args += relativePath.trim('/') + "/"
        } else {
            clause = "${MediaStore.MediaColumns.DATA} LIKE ?"; args += relativePath.trimEnd('/') + "/%"
        }
        return base.copy(selection = if (base.selection.isNullOrBlank()) clause else "(${base.selection}) AND ($clause)", selectionArgs = args.toTypedArray())
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

    private data class ParsedLocation(val category: String, val relativePath: String?)
    private fun parseLocation(reference: String): ParsedLocation {
        val index = reference.indexOf(LOCATION_SEPARATOR)
        return if (index < 0) ParsedLocation(reference, null) else ParsedLocation(reference.substring(0, index), Uri.decode(reference.substring(index + LOCATION_SEPARATOR.length)))
    }
    private fun encodeLocation(category: MediaCategory, path: String) = "${category.name}$LOCATION_SEPARATOR${Uri.encode(path)}"
    private fun rootLocation(category: MediaCategory) = BrowserLocation(ID, "media:${category.name}", categoryLabel(category), category.name, category.name, "media:${category.name.lowercase()}", true, false)
    private fun categoryLabel(category: MediaCategory): String = context.getString(when (category) { MediaCategory.IMAGES -> R.string.images; MediaCategory.VIDEOS -> R.string.videos; MediaCategory.AUDIO -> R.string.audio; MediaCategory.DOCUMENTS -> R.string.documents; MediaCategory.DOWNLOADS -> R.string.downloads; MediaCategory.APKS -> R.string.apks })

    companion object {
        const val ID = "media"
        private const val LOCATION_SEPARATOR = "::"
        private const val OTHER_GROUP = "__other__"
        private val GROUPED_CATEGORIES = setOf(MediaCategory.IMAGES, MediaCategory.VIDEOS, MediaCategory.AUDIO, MediaCategory.DOCUMENTS)
    }
}
