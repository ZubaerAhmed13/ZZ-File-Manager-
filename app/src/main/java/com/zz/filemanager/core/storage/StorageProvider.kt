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
    class AuthenticationRequired(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class AuthenticationFailed(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Timeout(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Tls(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Certificate(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class ServerIdentityChanged(cause: Throwable? = null) : StorageAccessException(cause = cause)
    class Protocol(cause: Throwable? = null) : StorageAccessException(cause = cause)
}

enum class StorageCapability {
    READ,
    WRITE,
    CREATE_FILE,
    CREATE_DIRECTORY,
    DELETE,
    RENAME,
    MOVE_NATIVE,
    RANDOM_ACCESS,
    ATOMIC_RENAME,
    SET_MODIFIED_TIME,
    COPY_SERVER_SIDE,
    RESUME_READ,
    RESUME_WRITE,
    QUERY_FREE_SPACE,
    QUERY_TOTAL_SPACE,
    HASH_SERVER_SIDE,
    SEARCH_SERVER_SIDE,
    TRASH,
    WATCH_CHANGES,
    STABLE_IDENTITY,
}

data class ProviderCapabilities(val values: Set<StorageCapability>) {
    operator fun contains(capability: StorageCapability): Boolean = capability in values
    fun supports(capability: StorageCapability): Boolean = capability in values
    companion object { val ReadOnly = ProviderCapabilities(setOf(StorageCapability.READ)) }
}

/** Provider-neutral file access contract. Provider ids are stable logical identities, not paths. */
interface StorageProvider {
    val id: String
    suspend fun listChildren(location: BrowserLocation): List<FileEntry>
    suspend fun getMetadata(item: FileReference): FileEntry?
    suspend fun openInputStream(item: FileReference): InputStream
    suspend fun exists(item: FileReference): Boolean
    suspend fun resolveParent(location: BrowserLocation): BrowserLocation?
    suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb>

    /** Stable identity for mutation proof. Return null when the provider cannot prove identity. */
    suspend fun mutationIdentity(item: FileReference): String? = null

    /** Revision/ETag/file-key proof used by Step 5 safe resume. Null means resume must not assume sameness. */
    suspend fun revisionIdentity(item: FileReference): String? = null
}

/**
 * Providers that can produce directory results incrementally implement this contract. The provider
 * owns the underlying session for the duration of the call and invokes [onPage] as soon as each
 * bounded page is available. This avoids a mandatory 10k/100k-entry materialization boundary.
 */
interface IncrementalStorageProvider : StorageProvider {
    suspend fun listChildrenIncrementally(
        location: BrowserLocation,
        pageSize: Int = DEFAULT_DIRECTORY_PAGE_SIZE,
        onPage: suspend (List<FileEntry>) -> Unit,
    )

    companion object {
        const val DEFAULT_DIRECTORY_PAGE_SIZE = 256
    }
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
    suspend fun totalBytes(location: BrowserLocation): Long? = null
    suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean

    suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean = false
    suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = null

    /** Optional same-provider server-side copy. Null means use the existing streamed copy engine. */
    suspend fun copyServerSide(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = null

    /**
     * Optional atomic staged-file replacement. Implementations must either replace [existing] with
     * [staged] atomically and return the committed entry, return null without mutating either item,
     * or throw while preserving the pre-call destination.
     */
    suspend fun replaceAtomically(
        staged: ScopedFileReference,
        existing: ScopedFileReference,
        finalName: String,
    ): FileEntry? = null
}

/**
 * Implement only when a provider can seek to an exact byte offset and validate object identity.
 * The operation engine must never use these methods unless the persisted resume proof still matches.
 */
interface ResumableStorageProvider : StorageProvider {
    suspend fun openInputStreamAt(item: FileReference, offset: Long): InputStream
    suspend fun resumeIdentity(item: FileReference): String?
}

interface ResumableWritableStorageProvider : WritableStorageProvider, ResumableStorageProvider {
    suspend fun openOutputStreamAt(item: ScopedFileReference, offset: Long): OutputStream
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
