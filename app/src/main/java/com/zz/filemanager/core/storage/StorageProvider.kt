package com.zz.filemanager.core.storage

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import java.io.InputStream

sealed class StorageAccessException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class PermissionRequired(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Unavailable(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Io(cause: Throwable? = null) : StorageAccessException(cause = cause)
}

interface StorageProvider {
    val id: String
    suspend fun listChildren(location: BrowserLocation): List<FileEntry>
    suspend fun getMetadata(item: FileReference): FileEntry?
    suspend fun openInputStream(item: FileReference): InputStream
    suspend fun exists(item: FileReference): Boolean
    suspend fun resolveParent(location: BrowserLocation): BrowserLocation?
    suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb>
}

class BrowserHistory {
    private val backStack = ArrayDeque<BrowserLocation>()
    private val forwardStack = ArrayDeque<BrowserLocation>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    fun recordNavigation(from: BrowserLocation?, to: BrowserLocation) {
        if (from != null && from.identity != to.identity) {
            backStack.addLast(from)
            forwardStack.clear()
        }
    }

    fun back(current: BrowserLocation): BrowserLocation? {
        if (backStack.isEmpty()) return null
        val target = backStack.removeLast()
        forwardStack.addLast(current)
        return target
    }

    fun forward(current: BrowserLocation): BrowserLocation? {
        if (forwardStack.isEmpty()) return null
        val target = forwardStack.removeLast()
        backStack.addLast(current)
        return target
    }

    fun clear() {
        backStack.clear()
        forwardStack.clear()
    }
}
