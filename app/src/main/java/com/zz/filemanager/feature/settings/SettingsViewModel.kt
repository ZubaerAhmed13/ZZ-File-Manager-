package com.zz.filemanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.ThemeMode
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.remote.RemoteConnectionService
import com.zz.filemanager.core.remote.RemoteTransferSettings
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch


data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val broadStorageAccess: Boolean = false,
    val safLocations: List<BrowserLocation> = emptyList(),
    val trashRetentionDays: Int = 30,
    val remote: RemoteTransferSettings = RemoteTransferSettings(),
)

class SettingsViewModel(
    private val storage: StorageRepository,
    private val preferences: PreferencesRepository,
    private val remoteConnections: RemoteConnectionService,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState(remote = remoteConnections.settings()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(preferences.theme, preferences.viewMode, preferences.showHidden, preferences.foldersFirst, preferences.trashRetentionDays) { theme, view, hidden, folders, retention ->
                SettingsUiState(
                    theme = theme,
                    viewMode = view,
                    showHidden = hidden,
                    foldersFirst = folders,
                    broadStorageAccess = storage.broadStorageAccess(),
                    safLocations = emptyList(),
                    trashRetentionDays = retention,
                    remote = remoteConnections.settings(),
                )
            }.collect { base ->
                _state.value = base.copy(safLocations = storage.validSafLocations())
            }
        }
    }

    fun refreshStorageStatus() = viewModelScope.launch {
        _state.value = _state.value.copy(
            broadStorageAccess = storage.broadStorageAccess(),
            safLocations = storage.validSafLocations(),
            remote = remoteConnections.settings(),
        )
    }

    fun setTheme(theme: ThemeMode) = viewModelScope.launch { preferences.setTheme(theme) }
    fun setViewMode(mode: ViewMode) = viewModelScope.launch { preferences.setViewMode(mode) }
    fun setShowHidden(value: Boolean) = viewModelScope.launch { preferences.setShowHidden(value) }
    fun setFoldersFirst(value: Boolean) = viewModelScope.launch { preferences.setFoldersFirst(value) }
    fun setTrashRetentionDays(value: Int) = viewModelScope.launch { preferences.setTrashRetentionDays(value) }
    fun removeSaf(location: BrowserLocation) = viewModelScope.launch { storage.removeSafLocation(location); refreshStorageStatus() }

    fun setRemoteSettings(transform: (RemoteTransferSettings) -> RemoteTransferSettings) {
        val next = transform(_state.value.remote)
        remoteConnections.setSettings(next)
        _state.value = _state.value.copy(remote = next)
    }

    fun setConnectTimeoutMillis(value: Int) = setRemoteSettings { it.copy(connectTimeoutMillis = value) }
    fun setRetryCount(value: Int) = setRemoteSettings { it.copy(retryCount = value) }
    fun setWifiOnlyBackgroundTransfers(value: Boolean) = setRemoteSettings { it.copy(wifiOnlyBackgroundTransfers = value) }
    fun setWarnOnMeteredNetwork(value: Boolean) = setRemoteSettings { it.copy(warnOnMeteredNetwork = value) }
    fun setAutoResumeInterruptedTransfers(value: Boolean) = setRemoteSettings { it.copy(autoResumeInterruptedTransfers = value) }
    fun setShowHiddenRemoteFiles(value: Boolean) = setRemoteSettings { it.copy(showHiddenRemoteFiles = value) }
    fun setLanDiscoveryEnabled(value: Boolean) = setRemoteSettings { it.copy(lanDiscoveryEnabled = value) }
    fun setWarnOnInsecureProtocols(value: Boolean) = setRemoteSettings { it.copy(warnOnInsecureProtocols = value) }

    class Factory(
        private val storage: StorageRepository,
        private val preferences: PreferencesRepository,
        private val remoteConnections: RemoteConnectionService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(storage, preferences, remoteConnections) as T
    }
}
