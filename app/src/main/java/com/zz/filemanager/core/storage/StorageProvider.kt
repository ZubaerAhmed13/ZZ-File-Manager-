package com.zz.filemanager.core.storage

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import java.io.InputStream
import java.io.OutputStream

sealed class StorageAccessException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class PermissionRequired(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Unavailable(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class ReadOnly(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Io(cause: Throwable? = null) : StorageAccessException(cause = cause)
}

enum class StorageCapability { READ, WRITE, CREATE_FILE, CREATE_DIRECTORY, DELETE, RENAME, MOVE_NATIVE, RANDOM_ACCESS, ATOMIC_RENAME, SET_MODIFIED_TIME }

data class ProviderCapabilities(val values: Set<StorageCapability>) {
    operator fun contains(capability: StorageCapability): Boolean = capability in values
    fun supports(capability: StorageCapability): Boolean = capability in values
    companion object { val ReadOnly = ProviderCapabilities(setOf(StorageCapability.READ)) }
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

interface WritableStorageProvider : StorageProvider {
    suspend fun capabilities(location: BrowserLocation): ProviderCapabilities
    suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry
    suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry
    suspend fun delete(item: ScopedFileReference): Boolean
    suspend fun rename(item: ScopedFileReference, newName: String): FileEntry
    suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean = true): OutputStream
    suspend fun findChild(parent: BrowserLocation, name: String): FileEntry?
    suspend fun freeBytes(location: BrowserLocation): Long?
    suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean

    /**
     * Non-mutating feasibility check used only for planning/free-space decisions. Returning false
     * must never prevent a later safe copy fallback. Returning true means [moveNative] is expected
     * to move this item into [destination] without allocating another full copy of its payload.
     */
    suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean = false

    suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = null

    /**
     * Optional atomic staged-file replacement. Implementations must either replace [existing] with
     * [staged] atomically and return the committed entry, return null without mutating either item
     * when atomic replacement is unavailable, or throw while preserving the pre-call destination.
     * The engine falls back to a reversible backup/commit transaction when this returns null.
     */
    suspend fun replaceAtomically(
        staged: ScopedFileReference,
        existing: ScopedFileReference,
        finalName: String,
    ): FileEntry? = null
}

interface StorageProviderRegistry {
    fun providerFor(providerId: String): StorageProvider
    fun writableProviderFor(providerId: String): WritableStorageProvider?
}

class BrowserHistory {
    private val backStack = ArrayDeque<BrowserLocation>()
    private val forwardStack = ArrayDeque<BrowserLocation>()
    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    fun recordNavigation(from: BrowserLocation?, to: BrowserLocation) { if (from != null && from.identity != to.identity) { backStack.addLast(from); forwardStack.clear() } }
    fun back(current: BrowserLocation): BrowserLocation? { if (backStack.isEmpty()) return null; val target = backStack.removeLast(); forwardStack.addLast(current); return target }
    fun forward(current: BrowserLocation): BrowserLocation? { if (forwardStack.isEmpty()) return null; val target = forwardStack.removeLast(); backStack.addLast(current); return target }
    fun clear() { backStack.clear(); forwardStack.clear() }
}
