package com.zz.filemanager.core.search

import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.model.BrowserLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion

class SearchRepository(
    private val coordinator: SearchCoordinator,
    private val library: UserLibraryStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun search(query: FileSearchQuery, currentLocation: BrowserLocation?): Flow<SearchUpdate> =
        coordinator.search(query, currentLocation).onCompletion { failure ->
            if (failure == null && query.text.isNotBlank()) library.recordSearch(query.text, now())
        }
}
