package com.zz.filemanager.core.preferences

import com.zz.filemanager.core.model.SortConfiguration
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ViewMode
import kotlinx.coroutines.flow.Flow

/** Browser-only preference surface used by BrowserViewModel and its JVM tests. */
interface BrowserPreferences {
    val viewMode: Flow<ViewMode>
    val showHidden: Flow<Boolean>
    val sortConfiguration: Flow<SortConfiguration>

    suspend fun setViewMode(value: ViewMode)
    suspend fun setShowHidden(value: Boolean)
    suspend fun setSortField(value: SortField)
    suspend fun setSortDirection(value: SortDirection)
}
