package com.zz.filemanager.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaInspector(
    context: Context,
    private val storage: StorageRepository,
    private val providers: StorageProviderRegistry,
) {
    private val appContext = context.applicationContext

    suspend fun image(entry: FileEntry): ImageMetadata = withContext(Dispatchers.IO) {
        val provider = providers.providerFor(entry.reference.providerId)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        provider.openInputStream(entry.reference).use { BitmapFactory.decodeStream(it, null, options) }
        val exif = runCatching { provider.openInputStream(entry.reference).use(::ExifInterface) }.getOrNull()
        ImageMetadata(
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 },
            mimeType = options.outMimeType ?: entry.mimeType,
            exifDate = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            cameraMake = exif?.getAttribute(ExifInterface.TAG_MAKE),
            cameraModel = exif?.getAttribute(ExifInterface.TAG_MODEL),
            orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)?.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
            exposure = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
            aperture = exif?.getAttribute(ExifInterface.TAG_F_NUMBER),
            iso = exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
            focalLength = exif?.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
            hasGps = exif?.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null && exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null,
        )
    }

    suspend fun video(entry: FileEntry): VideoMetadata = withContext(Dispatchers.IO) {
        val uri = mediaUri(entry) ?: return@withContext VideoMetadata(null, null, null, null, null, entry.mimeType, null, null)
        val retriever = MediaMetadataRetriever()
        val tracks = inspectTracks(uri)
        try {
            retriever.setDataSource(appContext, uri)
            VideoMetadata(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
                frameRate = tracks.videoFrameRate ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull(),
                videoMime = tracks.videoMime ?: entry.mimeType,
                audioMime = tracks.audioMime,
                bitrate = tracks.bitrate ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
            )
        } catch (_: Throwable) {
            VideoMetadata(null, null, null, null, tracks.videoFrameRate, tracks.videoMime ?: entry.mimeType, tracks.audioMime, tracks.bitrate)
        } finally {
            retriever.release()
        }
    }

    suspend fun audio(entry: FileEntry): AudioMetadata = withContext(Dispatchers.IO) {
        val uri = mediaUri(entry) ?: return@withContext AudioMetadata(null, null, null, null, entry.mimeType, null)
        val retriever = MediaMetadataRetriever()
        val tracks = inspectTracks(uri)
        try {
            retriever.setDataSource(appContext, uri)
            AudioMetadata(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                mimeType = tracks.audioMime ?: entry.mimeType,
                bitrate = tracks.bitrate ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
            )
        } catch (_: Throwable) {
            AudioMetadata(null, null, null, null, tracks.audioMime ?: entry.mimeType, tracks.bitrate)
        } finally {
            retriever.release()
        }
    }

    private data class Tracks(
        val videoMime: String? = null,
        val audioMime: String? = null,
        val videoFrameRate: Double? = null,
        val bitrate: Long? = null,
    )

    private fun inspectTracks(uri: Uri): Tracks {
        val extractor = MediaExtractor()
        return try {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.declaredLength >= 0L) extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                else extractor.setDataSource(afd.fileDescriptor)
            } ?: return Tracks()
            var videoMime: String? = null
            var audioMime: String? = null
            var frameRate: Double? = null
            var bitrate: Long? = null
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("video/") == true -> {
                        videoMime = videoMime ?: mime
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                    }
                    mime?.startsWith("audio/") == true -> audioMime = audioMime ?: mime
                }
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    val current = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    bitrate = (bitrate ?: 0L) + current
                }
            }
            Tracks(videoMime, audioMime, frameRate, bitrate)
        } catch (_: Throwable) {
            Tracks()
        } finally {
            extractor.release()
        }
    }

    private fun mediaUri(entry: FileEntry): Uri? = storage.openRequest(entry)?.uri?.let(Uri::parse)
}
