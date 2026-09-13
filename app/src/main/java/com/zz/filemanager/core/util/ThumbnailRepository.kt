package com.zz.filemanager.core.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

class ThumbnailRepository(private val context: Context) {
    private val cache: LruCache<String, Bitmap>
    init {
        val memoryClassMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val maxKb = minOf(32 * 1024, max(4 * 1024, memoryClassMb * 1024 / 8))
        cache = object : LruCache<String, Bitmap>(maxKb) { override fun sizeOf(key: String, value: Bitmap): Int = max(1, value.byteCount / 1024) }
    }
    fun clearMemoryCache() = cache.evictAll()
    suspend fun load(entry: FileEntry, width: Int, height: Int): Bitmap? {
        if (entry.type != FileEntryType.IMAGE && entry.type != FileEntryType.VIDEO) return null
        val key = "${entry.thumbnailKey ?: entry.id}:$width:$height"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { coroutineContext.ensureActive(); val bitmap = runCatching { decode(entry, width, height) }.getOrNull(); coroutineContext.ensureActive(); if (bitmap != null) cache.put(key, bitmap); bitmap }
    }
    private fun decode(entry: FileEntry, width: Int, height: Int): Bitmap? {
        val uri = entry.reference.uri?.let(Uri::parse)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) return context.contentResolver.loadThumbnail(uri, Size(width, height), null)
        return when (entry.type) { FileEntryType.IMAGE -> decodeImage(entry, width, height); FileEntryType.VIDEO -> decodeVideo(entry, width, height); else -> null }
    }
    private fun decodeImage(entry: FileEntry, width: Int, height: Int): Bitmap? {
        val path = entry.reference.path
        val uri = entry.reference.uri?.let(Uri::parse)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (path != null) BitmapFactory.decodeFile(path, bounds) else uri?.let { context.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val decoded = if (path != null) BitmapFactory.decodeFile(path, options) else uri?.let { context.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) } } ?: return null
        val orientation = runCatching {
            val exif = if (path != null) ExifInterface(path) else uri?.let { value -> context.contentResolver.openInputStream(value)?.use { stream -> ExifInterface(stream) } }
            exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return rotateForExif(decoded, orientation)
    }
    private fun rotateForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f); ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f); ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f); ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f); ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f); else -> return bitmap }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    @Suppress("DEPRECATION") private fun decodeVideo(entry: FileEntry, width: Int, height: Int): Bitmap? {
        val path = entry.reference.path
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path != null) return ThumbnailUtils.createVideoThumbnail(File(path), Size(width, height), null)
        val retriever = MediaMetadataRetriever()
        return try { if (path != null) retriever.setDataSource(path) else entry.reference.uri?.let(Uri::parse)?.let { retriever.setDataSource(context, it) } ?: return null; val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null; val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height, 1f); if (scale >= 1f) frame else Bitmap.createScaledBitmap(frame, max(1, (frame.width * scale).toInt()), max(1, (frame.height * scale).toInt()), true).also { if (it !== frame) frame.recycle() } } finally { retriever.release() }
    }
}
