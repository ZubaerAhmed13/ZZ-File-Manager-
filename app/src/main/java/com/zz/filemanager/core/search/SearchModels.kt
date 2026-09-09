package com.zz.filemanager.core.search

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference

enum class SearchScope { CURRENT_FOLDER, CURRENT_FOLDER_RECURSIVE, CURRENT_STORAGE, ALL_ACCESSIBLE_LOCATIONS }
enum class SearchTypeFilter { ALL, FOLDERS, IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, APKS, OTHER }
enum class SearchMatchMode { CONTAINS, STARTS_WITH, EXACT }
enum class SearchSort { RELEVANCE, NAME, DATE, SIZE, TYPE, LOCATION }

data class FileSearchQuery(
    val text: String = "",
    val scope: SearchScope = SearchScope.CURRENT_FOLDER,
    val typeFilter: SearchTypeFilter = SearchTypeFilter.ALL,
    val extensions: Set<String> = emptySet(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val includeHidden: Boolean = false,
    val includeFiles: Boolean = true,
    val includeDirectories: Boolean = true,
    val matchMode: SearchMatchMode = SearchMatchMode.CONTAINS,
    val sort: SearchSort = SearchSort.RELEVANCE,
)

data class SearchResult(
    val id: String,
    val reference: FileReference,
    val parentLocation: BrowserLocation,
    val name: String,
    val relativePath: String,
    val type: FileEntryType,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val mimeType: String?,
    val storageId: String,
    val readable: Boolean,
    val writable: Boolean,
    val relevance: Int,
) { val isDirectory: Boolean get() = type == FileEntryType.DIRECTORY }

data class SearchFailure(val locationName: String, val message: String)

sealed interface SearchUpdate {
    data class Batch(val results: List<SearchResult>, val scanned: Long, val totalMatches: Long) : SearchUpdate
    data class Completed(val scanned: Long, val totalMatches: Long, val failures: List<SearchFailure>) : SearchUpdate
}

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data class Searching(val results: List<SearchResult>, val scanned: Long, val totalMatches: Long) : SearchUiState
    data class Results(val results: List<SearchResult>, val scanned: Long, val totalMatches: Long, val failures: List<SearchFailure>) : SearchUiState
    data class Empty(val scanned: Long, val failures: List<SearchFailure>) : SearchUiState
    data class Error(val message: String) : SearchUiState
    data object Cancelled : SearchUiState
}

object SearchResultSorter {
    fun sort(items: List<SearchResult>, sort: SearchSort): List<SearchResult> = when (sort) {
        SearchSort.RELEVANCE -> items.sortedWith(compareByDescending<SearchResult> { it.relevance }.thenBy { SearchCoordinator.normalize(it.name) })
        SearchSort.NAME -> items.sortedBy { SearchCoordinator.normalize(it.name) }
        SearchSort.DATE -> items.sortedByDescending { it.modifiedAtMillis ?: Long.MIN_VALUE }
        SearchSort.SIZE -> items.sortedByDescending { it.sizeBytes ?: Long.MIN_VALUE }
        SearchSort.TYPE -> items.sortedWith(compareBy<SearchResult> { it.type.name }.thenBy { SearchCoordinator.normalize(it.name) })
        SearchSort.LOCATION -> items.sortedWith(compareBy<SearchResult> { SearchCoordinator.normalize(it.parentLocation.displayName) }.thenBy { SearchCoordinator.normalize(it.name) })
    }
}
