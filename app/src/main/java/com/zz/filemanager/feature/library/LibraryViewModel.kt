package com.zz.filemanager.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.UserLibraryManager
import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.preferences.PreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val store: UserLibraryStore,
    private val manager: UserLibraryManager,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    val favorites = store.favorites
    val recentFiles = store.recentFiles
    val activity = store.activityHistory
    val recentLocations = preferences.recentLocations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { viewModelScope.launch { store.initialize(); manager.validateFavorites(); manager.validateRecents() } }
    fun removeFavorite(item: FavoriteItem) { viewModelScope.launch { store.removeFavorite(item.id) } }
    fun clearRecentFiles() { viewModelScope.launch { store.clearRecentFiles() } }
    fun clearRecentLocations() { viewModelScope.launch { preferences.clearRecentLocations() } }
    fun clearActivity() { viewModelScope.launch { store.clearActivityHistory() } }

    class Factory(
        private val store: UserLibraryStore,
        private val manager: UserLibraryManager,
        private val preferences: PreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(store, manager, preferences) as T
    }
}
