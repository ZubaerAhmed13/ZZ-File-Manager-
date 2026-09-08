package com.zz.filemanager.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.BrowserProblem
import com.zz.filemanager.core.model.BrowserUiState
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.preferences.BrowserPreferences
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.BrowserHistory
import com.zz.filemanager.core.storage.BrowserStorage
import com.zz.filemanager.core.storage.StorageAccessException
import com.zz.filemanager.core.storage.StorageRepository
import com.zz.filemanager.core.util.FileSorter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BrowserEvent {
    data class OpenFile(val request: OpenFileRequest) : BrowserEvent
    data object OpenFailed : BrowserEvent
}

class BrowserViewModel(
    private val storage: BrowserStorage,
    private val preferences: BrowserPreferences,
    private val sortDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val history = BrowserHistory()
    private val _state = MutableStateFlow<BrowserUiState>(BrowserUiState.Loading)
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<BrowserEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<BrowserEvent> = _events.asSharedFlow()

    private var currentLocation: BrowserLocation? = null
    private var loadJob: Job? = null
    private var upResolutionJob: Job? = null
    private var navigationGeneration: Long = 0L

    fun start(initial: BrowserLocation) {
        if (currentLocation == null) load(initial, recordHistory = false)
    }

    fun refresh() { currentLocation?.let { load(it, recordHistory = false) } }

    fun openEntry(entry: FileEntry) {
        if (!entry.isDirectory) {
            val request = storage.openRequest(entry)
            if (request != null) _events.tryEmit(BrowserEvent.OpenFile(request)) else _events.tryEmit(BrowserEvent.OpenFailed)
            return
        }
        val parent = currentLocation ?: return
        val reference = entry.reference.uri ?: entry.reference.path ?: entry.reference.opaqueId
        load(
            BrowserLocation(
                providerId = entry.reference.providerId,
                id = entry.id,
                displayName = entry.name,
                reference = reference,
                rootReference = parent.rootReference,
                storageId = parent.storageId,
                readable = entry.isReadable,
                writable = entry.isWritable,
            ),
            recordHistory = true,
        )
    }

    fun navigateTo(location: BrowserLocation) = load(location, recordHistory = true)

    fun goBack() {
        val current = currentLocation ?: return
        val target = history.back(current) ?: return
        load(target, recordHistory = false)
    }

    fun goForward() {
        val current = currentLocation ?: return
        val target = history.forward(current) ?: return
        load(target, recordHistory = false)
    }

    fun goUp() {
        val current = currentLocation ?: return
        val generationAtRequest = navigationGeneration
        upResolutionJob?.cancel()
        upResolutionJob = viewModelScope.launch {
            val parent = storage.resolveParent(current) ?: return@launch
            if (navigationGeneration == generationAtRequest && currentLocation?.identity == current.identity) {
                load(parent, recordHistory = true)
            }
        }
    }

    fun setViewMode(mode: ViewMode) = viewModelScope.launch {
        preferences.setViewMode(mode)
        refresh()
    }

    fun setSortField(field: SortField) = viewModelScope.launch {
        preferences.setSortField(field)
        refresh()
    }

    fun toggleSortDirection() = viewModelScope.launch {
        val current = preferences.sortConfiguration.first().direction
        preferences.setSortDirection(if (current == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING)
        refresh()
    }

    fun toggleHidden() = viewModelScope.launch {
        preferences.setShowHidden(!preferences.showHidden.first())
        refresh()
    }

    private fun load(location: BrowserLocation, recordHistory: Boolean) {
        val requestGeneration = ++navigationGeneration
        val previous = currentLocation
        if (recordHistory) history.recordNavigation(previous, location)
        currentLocation = location
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = BrowserUiState.Loading
            try {
                val showHidden = preferences.showHidden.first()
                val viewMode = preferences.viewMode.first()
                val sort = preferences.sortConfiguration.first()
                val parent = storage.resolveParent(location)
                val crumbs = storage.breadcrumbs(location)
                val raw = storage.listChildren(location)
                val entries = withContext(sortDispatcher) {
                    FileSorter.sort(if (showHidden) raw else raw.filterNot { it.isHidden }, sort)
                }
                if (requestGeneration != navigationGeneration || currentLocation?.identity != location.identity) return@launch
                storage.remember(location)
                if (requestGeneration != navigationGeneration || currentLocation?.identity != location.identity) return@launch
                _state.value = if (entries.isEmpty()) {
                    BrowserUiState.Empty(location, crumbs, viewMode, sort, showHidden, history.canGoBack, history.canGoForward, parent != null)
                } else {
                    BrowserUiState.Content(location, entries, crumbs, viewMode, sort, showHidden, history.canGoBack, history.canGoForward, parent != null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: StorageAccessException.PermissionRequired) {
                if (requestGeneration == navigationGeneration) _state.value = BrowserUiState.Problem(location, BrowserProblem.PERMISSION_REQUIRED)
            } catch (error: StorageAccessException.Unavailable) {
                if (requestGeneration == navigationGeneration) _state.value = BrowserUiState.Problem(location, BrowserProblem.UNAVAILABLE)
            } catch (error: StorageAccessException.Io) {
                if (requestGeneration == navigationGeneration) _state.value = BrowserUiState.Problem(location, BrowserProblem.IO_ERROR)
            } catch (_: SecurityException) {
                if (requestGeneration == navigationGeneration) _state.value = BrowserUiState.Problem(location, BrowserProblem.PERMISSION_REQUIRED)
            } catch (_: Throwable) {
                if (requestGeneration == navigationGeneration) _state.value = BrowserUiState.Problem(location, BrowserProblem.UNKNOWN)
            }
        }
    }

    class Factory(
        private val storage: StorageRepository,
        private val preferences: PreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(storage, preferences) as T
    }
}
