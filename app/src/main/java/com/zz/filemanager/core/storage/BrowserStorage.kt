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
    suspend fun resolveParent(location: BrowserLocation): BrowserLocation?
    suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb>
    suspend fun remember(location: BrowserLocation)
    fun openRequest(entry: FileEntry): OpenFileRequest?
}
