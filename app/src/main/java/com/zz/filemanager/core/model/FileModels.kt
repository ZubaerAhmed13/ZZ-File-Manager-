package com.zz.filemanager.core.model

enum class FileEntryType {
    DIRECTORY, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOCUMENT, SPREADSHEET,
    PRESENTATION, ARCHIVE, APK, GENERIC, UNKNOWN
}

enum class StorageType { INTERNAL, EXTERNAL_VOLUME, SD_CARD, SAF_TREE, USB, NETWORK, CLOUD }
enum class ViewMode { LIST, GRID }
enum class SortField { NAME, DATE_MODIFIED, SIZE, TYPE }
enum class SortDirection { ASCENDING, DESCENDING }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class MediaCategory { IMAGES, VIDEOS, AUDIO, DOCUMENTS, DOWNLOADS, APKS }

data class SortConfiguration(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true,
)

data class FileReference(
    val providerId: String,
    val opaqueId: String,
    val uri: String? = null,
    val path: String? = null,
)

/**
 * A provider-neutral reference plus the logical root that authorized it.
 * File-operation code must carry this scope instead of converting every URI to a path.
 */
data class ScopedFileReference(
    val reference: FileReference,
    val rootReference: String,
    val storageId: String,
)

data class FileEntry(
    val id: String,
    val reference: FileReference,
    val name: String,
    val extension: String?,
    val mimeType: String?,
    val type: FileEntryType,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val createdAtMillis: Long?,
    val isHidden: Boolean,
    val isReadable: Boolean,
    val isWritable: Boolean,
    val childCount: Int?,
    val storageId: String,
    val thumbnailKey: String?,
    val isSymbolicLink: Boolean = false,
) {
    val isDirectory: Boolean get() = type == FileEntryType.DIRECTORY
}

data class BrowserLocation(
    val providerId: String,
    val id: String,
    val displayName: String,
    val reference: String,
    val rootReference: String,
    val storageId: String,
    val readable: Boolean = true,
    val writable: Boolean = false,
) {
    val identity: String get() = "$providerId::$id"
}

data class StorageLocation(
    val id: String,
    val displayName: String,
    val type: StorageType,
    val totalBytes: Long?,
    val freeBytes: Long?,
    val readable: Boolean,
    val writable: Boolean,
    val available: Boolean,
    val root: BrowserLocation,
) {
    val usedBytes: Long?
        get() = if (totalBytes != null && freeBytes != null) (totalBytes - freeBytes).coerceAtLeast(0L) else null
}

data class Breadcrumb(val label: String, val location: BrowserLocation)

data class OpenFileRequest(val uri: String, val mimeType: String?)

enum class BrowserProblem { PERMISSION_REQUIRED, UNAVAILABLE, IO_ERROR, UNKNOWN }

sealed interface BrowserUiState {
    data object Loading : BrowserUiState
    data class Content(
        val location: BrowserLocation,
        val entries: List<FileEntry>,
        val breadcrumbs: List<Breadcrumb>,
        val viewMode: ViewMode,
        val sort: SortConfiguration,
        val showHidden: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val canGoUp: Boolean,
    ) : BrowserUiState
    data class Empty(
        val location: BrowserLocation,
        val breadcrumbs: List<Breadcrumb>,
        val viewMode: ViewMode,
        val sort: SortConfiguration,
        val showHidden: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val canGoUp: Boolean,
    ) : BrowserUiState
    data class Problem(val location: BrowserLocation, val problem: BrowserProblem) : BrowserUiState
}

data class HomeUiState(
    val loading: Boolean = true,
    val storageLocations: List<StorageLocation> = emptyList(),
    val recentLocations: List<BrowserLocation> = emptyList(),
    val safLocations: List<BrowserLocation> = emptyList(),
    val broadStorageAccess: Boolean = false,
    val mediaCategories: List<MediaCategory> = listOf(
        MediaCategory.IMAGES,
        MediaCategory.VIDEOS,
        MediaCategory.AUDIO,
        MediaCategory.DOCUMENTS,
        MediaCategory.DOWNLOADS,
        MediaCategory.APKS,
    ),
)
