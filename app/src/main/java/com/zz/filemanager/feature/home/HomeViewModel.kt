package com.zz.filemanager.feature.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.HomeUiState
import com.zz.filemanager.core.model.MediaCategory
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(
    private val storage: StorageRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val locations = runCatching { storage.discoverStorageLocations() }.getOrDefault(emptyList())
            val saf = runCatching { storage.validSafLocations() }.getOrDefault(emptyList())
            val recent = preferences.recentLocations.first().filter { recentLocation ->
                recentLocation.providerId != "saf" || saf.any { it.rootReference == recentLocation.rootReference && it.readable }
            }
            _state.value = HomeUiState(
                loading = false,
                storageLocations = locations,
                recentLocations = recent,
                safLocations = saf,
                broadStorageAccess = storage.broadStorageAccess(),
            )
        }
    }

    fun addSafLocation(uri: Uri) {
        viewModelScope.launch {
            runCatching { storage.registerSafLocation(uri) }
            refresh()
        }
    }

    fun removeSafLocation(location: BrowserLocation) {
        viewModelScope.launch {
            storage.removeSafLocation(location)
            refresh()
        }
    }

    fun mediaLocation(category: MediaCategory): BrowserLocation = storage.mediaLocation(category)
    fun hasBroadStorageAccess(): Boolean = storage.broadStorageAccess()

    class Factory(
        private val storage: StorageRepository,
        private val preferences: PreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(storage, preferences) as T
    }
}
