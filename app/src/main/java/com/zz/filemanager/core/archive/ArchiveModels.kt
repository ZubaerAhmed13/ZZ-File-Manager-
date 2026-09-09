package com.zz.filemanager.core.archive

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.operation.CollisionPolicy

enum class ArchiveFormat {
    ZIP,
    TAR,
    TAR_GZ,
    TAR_BZ2,
    TAR_XZ,
    SEVEN_Z,
    RAR,
    UNKNOWN,
}

enum class ZipCompressionLevel { FASTEST, FAST, NORMAL, MAXIMUM }

data class ArchiveEntryInfo(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val compressedSize: Long?,
    val uncompressedSize: Long?,
    val modifiedAtMillis: Long? = null,
    val encrypted: Boolean = false,
) {
    val compressionRatio: Double?
        get() = if (compressedSize != null && uncompressedSize != null && compressedSize > 0L) {
            uncompressedSize.toDouble() / compressedSize.toDouble()
        } else null
}

data class ArchiveListing(
    val format: ArchiveFormat,
    val entries: List<ArchiveEntryInfo>,
    val encrypted: Boolean,
    val stagedForRandomAccess: Boolean,
)

data class ArchiveSource(
    val entry: FileEntry,
    val parent: BrowserLocation,
)

data class ArchiveExtractRequest(
    val source: ArchiveSource,
    val destination: BrowserLocation,
    val selectedPaths: Set<String> = emptySet(),
    val password: CharArray? = null,
    val collisionPolicy: CollisionPolicy = CollisionPolicy.KEEP_BOTH,
    val allowSuspiciousExpansion: Boolean = false,
)

data class ArchiveCreateRequest(
    val sources: List<ArchiveSource>,
    val destination: BrowserLocation,
    val archiveName: String,
    val format: ArchiveFormat,
    val zipCompressionLevel: ZipCompressionLevel = ZipCompressionLevel.NORMAL,
    val password: CharArray? = null,
)

data class ArchiveProgress(
    val phase: String,
    val currentPath: String? = null,
    val processedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val processedEntries: Long = 0L,
    val totalEntries: Long? = null,
)

sealed class ArchiveFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedFormat(val format: ArchiveFormat) : ArchiveFailure("Unsupported archive format: $format")
    class BadPassword(cause: Throwable? = null) : ArchiveFailure("The archive password is missing or incorrect", cause)
    class Corrupted(cause: Throwable? = null) : ArchiveFailure("The archive is corrupted or malformed", cause)
    class UnsafePath(val path: String) : ArchiveFailure("Unsafe archive path rejected: $path")
    class DuplicatePath(val path: String) : ArchiveFailure("Duplicate normalized archive path: $path")
    class SuspiciousExpansion(val reason: String) : ArchiveFailure(reason)
    class InsufficientSpace(val required: Long?, val available: Long?) : ArchiveFailure("Insufficient destination or temporary storage")
    class ProviderLimit(val detail: String) : ArchiveFailure(detail)
}
