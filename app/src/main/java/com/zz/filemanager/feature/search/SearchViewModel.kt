package com.zz.filemanager.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.search.FileSearchQuery
import com.zz.filemanager.core.search.SearchRepository
import com.zz.filemanager.core.search.SearchResult
import com.zz.filemanager.core.search.SearchResultSorter
import com.zz.filemanager.core.search.SearchScope
import com.zz.filemanager.core.search.SearchSort
import com.zz.filemanager.core.search.SearchTypeFilter
import com.zz.filemanager.core.search.SearchUiState
import com.zz.filemanager.core.search.SearchUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: SearchRepository,
    library: UserLibraryStore,
    private val currentLocation: BrowserLocation?,
    private val debounceMillis: Long = 300L,
    private val resultWindow: Int = 5_000,
) : ViewModel() {
    private val _query = MutableStateFlow(FileSearchQuery(scope = if (currentLocation == null) SearchScope.ALL_ACCESSIBLE_LOCATIONS else SearchScope.CURRENT_FOLDER))
    val query: StateFlow<FileSearchQuery> = _query.asStateFlow()
    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    val history = library.searchHistory
    private var searchJob: Job? = null
    private var generation = 0L

    fun setText(value: String) { update(_query.value.copy(text = value), debounce = true) }
    fun setScope(value: SearchScope) { update(_query.value.copy(scope = value)) }
    fun setType(value: SearchTypeFilter) { update(_query.value.copy(typeFilter = value)) }
    fun setSort(value: SearchSort) { update(_query.value.copy(sort = value)) }
    fun setExtensions(value: Set<String>) { update(_query.value.copy(extensions = value)) }
    fun setSize(min: Long?, max: Long?) { update(_query.value.copy(minSizeBytes = min, maxSizeBytes = max)) }
    fun setDates(after: Long?, before: Long?) { update(_query.value.copy(modifiedAfter = after, modifiedBefore = before)) }
    fun setHidden(value: Boolean) { update(_query.value.copy(includeHidden = value)) }
    fun setKinds(files: Boolean, directories: Boolean) { update(_query.value.copy(includeFiles = files, includeDirectories = directories)) }
    fun submit() { update(_query.value, debounce = false) }
    fun cancel() { generation++; searchJob?.cancel(); _state.value = SearchUiState.Cancelled }

    private fun update(value: FileSearchQuery, debounce: Boolean = false) {
        _query.value = value
        val requestGeneration = ++generation
        searchJob?.cancel()
        if (value.text.isBlank() && value.typeFilter == SearchTypeFilter.ALL && value.extensions.isEmpty()) {
            _state.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(debounceMillis)
            val results = ArrayList<SearchResult>()
            var total = 0L
            try {
                repository.search(value, currentLocation).collect { update ->
                    if (requestGeneration != generation) return@collect
                    when (update) {
                        is SearchUpdate.Batch -> {
                            results += update.results
                            val ordered = SearchResultSorter.sort(results, value.sort).take(resultWindow)
                            results.clear()
                            results.addAll(ordered)
                            total = update.totalMatches
                            _state.value = SearchUiState.Searching(results.toList(), update.scanned, total)
                        }
                        is SearchUpdate.Completed -> {
                            _state.value = if (total == 0L) SearchUiState.Empty(update.scanned, update.failures)
                            else SearchUiState.Results(results.toList(), update.scanned, total, update.failures)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestGeneration == generation) _state.value = SearchUiState.Cancelled
                throw cancelled
            } catch (error: Throwable) {
                if (requestGeneration == generation) _state.value = SearchUiState.Error(error.message ?: "Search failed")
            }
        }
    }

    class Factory(
        private val repository: SearchRepository,
        private val library: UserLibraryStore,
        private val currentLocation: BrowserLocation?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repository, library, currentLocation) as T
    }
}
