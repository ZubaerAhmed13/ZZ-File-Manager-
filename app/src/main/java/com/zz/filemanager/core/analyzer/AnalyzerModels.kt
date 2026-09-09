package com.zz.filemanager.core.analyzer

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry

enum class StorageCategory { IMAGES, VIDEO, AUDIO, DOCUMENTS, ARCHIVES, APKS, OTHER }

data class AnalyzedFile(val entry: FileEntry, val parent: BrowserLocation, val sizeBytes: Long)
data class AnalyzedFolder(val location: BrowserLocation, val sizeBytes: Long, val fileCount: Long, val folderCount: Long)

data class DuplicateMember(val entry: FileEntry, val parent: BrowserLocation, val sha256: String)
data class DuplicateGroup(val sizeBytes: Long, val sha256: String, val members: List<DuplicateMember>)

data class AnalyzerProgress(
    val filesScanned: Long = 0L,
    val directoriesScanned: Long = 0L,
    val bytesAccounted: Long = 0L,
    val currentLocation: String? = null,
    val elapsedMillis: Long = 0L,
)

data class AnalyzerSnapshot(
    val root: BrowserLocation,
    val progress: AnalyzerProgress,
    val complete: Boolean,
    val categoryBytes: Map<StorageCategory, Long>,
    val largestFiles: List<AnalyzedFile>,
    val largestFolders: List<AnalyzedFolder>,
    val extensionCounts: Map<String, Long>,
    val emptyFiles: List<AnalyzedFile>,
    val emptyDirectories: List<BrowserLocation>,
    val totalEmptyFiles: Long,
    val totalEmptyDirectories: Long,
    val accessibleCapacityBytes: Long? = null,
    val freeBytes: Long? = null,
    val warnings: List<String> = emptyList(),
)

data class DuplicateProgress(
    val phase: Int,
    val filesVisited: Long,
    val candidates: Long,
    val bytesHashed: Long,
    val currentName: String? = null,
)
