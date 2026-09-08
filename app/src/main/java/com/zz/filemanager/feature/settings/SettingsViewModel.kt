package com.zz.filemanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.ThemeMode
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val broadStorageAccess: Boolean = false,
    val safLocations: List<BrowserLocation> = emptyList(),
)

class SettingsViewModel(
    private val storage: StorageRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.theme, preferences.viewMode, preferences.showHidden, preferences.foldersFirst) { theme, view, hidden, folders ->
                SettingsUiState(theme, view, hidden, folders, storage.broadStorageAccess(), emptyList())
            }.collect { base ->
                _state.value = base.copy(safLocations = storage.validSafLocations())
            }
        }
    }

    fun refreshStorageStatus() = viewModelScope.launch {
        _state.value = _state.value.copy(
            broadStorageAccess = storage.broadStorageAccess(),
            safLocations = storage.validSafLocations(),
        )
    }

    fun setTheme(theme: ThemeMode) = viewModelScope.launch { preferences.setTheme(theme) }
    fun setViewMode(mode: ViewMode) = viewModelScope.launch { preferences.setViewMode(mode) }
    fun setShowHidden(value: Boolean) = viewModelScope.launch { preferences.setShowHidden(value) }
    fun setFoldersFirst(value: Boolean) = viewModelScope.launch { preferences.setFoldersFirst(value) }
    fun removeSaf(location: BrowserLocation) = viewModelScope.launch { storage.removeSafLocation(location); refreshStorageStatus() }

    class Factory(
        private val storage: StorageRepository,
        private val preferences: PreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(storage, preferences) as T
    }
}
