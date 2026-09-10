package com.zz.filemanager.core.storage

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.OpenFileRequest

/**
 * Narrow browser-facing contract so BrowserViewModel can be unit tested without Android storage APIs.
 */
interface BrowserStorage {
    suspend fun listChildren(location: BrowserLocation): List<FileEntry>

    /**
     * Incremental listing hook. Non-streaming providers keep the old behavior through this default;
     * remote/cloud providers override it so the browser can render the first bounded page before a
     * very large directory has finished enumerating.
     */
    suspend fun listChildrenIncrementally(
        location: BrowserLocation,
        pageSize: Int = IncrementalStorageProvider.DEFAULT_DIRECTORY_PAGE_SIZE,
        onPage: suspend (List<FileEntry>) -> Unit,
    ) {
        onPage(listChildren(location))
    }

    suspend fun resolveParent(location: BrowserLocation): BrowserLocation?
    suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb>
    suspend fun remember(location: BrowserLocation)
    fun openRequest(entry: FileEntry): OpenFileRequest?
}
