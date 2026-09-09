package com.zz.filemanager.core.media

data class ImageMetadata(
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val exifDate: String?,
    val cameraMake: String?,
    val cameraModel: String?,
    val orientation: Int?,
    val exposure: String?,
    val aperture: String?,
    val iso: String?,
    val focalLength: String?,
    val hasGps: Boolean,
)

data class VideoMetadata(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int?,
    val frameRate: Double?,
    val videoMime: String?,
    val audioMime: String?,
    val bitrate: Long?,
)

data class AudioMetadata(
    val durationMs: Long?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val mimeType: String?,
    val bitrate: Long?,
)
