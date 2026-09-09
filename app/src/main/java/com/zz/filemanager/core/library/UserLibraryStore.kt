package com.zz.filemanager.core.library

import kotlinx.coroutines.flow.StateFlow

interface UserLibraryStore {
    val favorites: StateFlow<List<FavoriteItem>>
    val recentFiles: StateFlow<List<RecentFile>>
    val searchHistory: StateFlow<List<SearchHistoryItem>>
    val activityHistory: StateFlow<List<ActivityEntry>>
    val trashRecords: StateFlow<List<TrashRecord>>

    suspend fun initialize()
    suspend fun upsertFavorite(item: FavoriteItem)
    suspend fun removeFavorite(id: String)
    suspend fun updateFavorite(item: FavoriteItem)
    suspend fun recordRecentFile(item: RecentFile)
    suspend fun removeRecentFile(id: String)
    suspend fun recordSearch(query: String, usedAtMillis: Long)
    suspend fun recordActivity(entry: ActivityEntry)
    suspend fun upsertTrash(record: TrashRecord)
    suspend fun removeTrash(id: String)
    suspend fun clearRecentFiles()
    suspend fun clearSearchHistory()
    suspend fun clearActivityHistory()
}
