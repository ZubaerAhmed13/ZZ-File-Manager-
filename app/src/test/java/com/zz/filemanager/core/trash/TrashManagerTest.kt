package com.zz.filemanager.core.trash

import com.zz.filemanager.core.library.ActivityEntry
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.library.SearchHistoryItem
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.UserLibraryManager
import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.library.LibraryItemStatus
import com.zz.filemanager.core.library.stableIdentity
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.ProviderCapabilities
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.OperationExecutionHost
import com.zz.filemanager.core.operation.OperationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class TrashManagerTest {
    @Test fun logicalThirtyGiBFileUsesNativeMoveAndRestoresWithoutCopySpace() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val huge = provider.addFile("/root/movie.mkv", 30L * 1024L * 1024L * 1024L)

        val trashed = manager.trash(huge, provider.root)
        assertTrue(trashed is TrashResult.Success)
        assertFalse(provider.exists(huge.reference))
        val record = store.trashRecords.value.single()
        assertTrue(provider.exists(requireNotNull(record.trashReference).reference))

        val restored = manager.restore(record.id)
        assertTrue(restored is TrashResult.Success)
        assertTrue(provider.exists(huge.reference))
        assertTrue(store.trashRecords.value.isEmpty())
        assertEquals(0, provider.openOutputCount)
    }

    @Test fun duplicateNamesReceiveIndependentTrashIds() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val first = provider.addFile("/root/report.pdf", 10)
        assertTrue(manager.trash(first, provider.root) is TrashResult.Success)
        assertTrue(manager.trash(first, provider.root) is TrashResult.Failed)
        val second = provider.addFile("/root/report.pdf", 20)
        assertTrue(manager.trash(second, provider.root) is TrashResult.Success)
        assertEquals(2, store.trashRecords.value.map { it.id }.distinct().size)
    }

    @Test fun restoreCollisionRequiresDeliberatePolicy() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val old = provider.addFile("/root/report.pdf", 10)
        manager.trash(old, provider.root)
        provider.addFile("/root/report.pdf", 20)
        val record = store.trashRecords.value.single()
        assertTrue(manager.restore(record.id) is TrashResult.Collision)
        assertTrue(manager.restore(record.id, RestoreCollisionPolicy.KEEP_BOTH) is TrashResult.Success)
        assertTrue(provider.hasPath("/root/report (1).pdf"))
    }

    @Test fun nonNativeTrashQueuesStep2MoveWithoutDeletingSourceEarly() = runTest {
        val provider = FakeWritableProvider(nativeMoves = false)
        val store = MemoryLibraryStore()
        val operations = MemoryOperationStore()
        val controller = FileOperationController(operations, OperationExecutionHost {})
        val manager = TrashManager(FakeRegistry(provider), store, UserLibraryManager(store, FakeRegistry(provider)), operationController = controller, operationStore = operations)
        val source = provider.addFile("/root/archive.bin", 10_000)

        val result = manager.trash(source, provider.root)

        assertTrue(result is TrashResult.Queued)
        assertTrue(provider.exists(source.reference))
        assertEquals(com.zz.filemanager.core.library.TrashState.COPYING, store.trashRecords.value.single().state)
        assertEquals(FileOperationState.QUEUED, operations.operations.value.single().state)
        assertEquals(com.zz.filemanager.core.operation.FileOperationType.MOVE, operations.operations.value.single().type)
    }

    @Test fun startupReconciliationFinishesNativeMoveAfterInterruptedCatalogWrite() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val source = provider.addFile("/root/recover.txt", 10)
        manager.trash(source, provider.root)
        val record = store.trashRecords.value.single()
        store.upsertTrash(record.copy(trashReference = record.containerReference, state = com.zz.filemanager.core.library.TrashState.MOVING))

        manager.reconcile()

        assertEquals(com.zz.filemanager.core.library.TrashState.TRASHED, store.trashRecords.value.single().state)
        assertTrue(requireNotNull(store.trashRecords.value.single().trashReference).reference.path!!.endsWith("/recover.txt"))
        assertTrue(manager.restore(record.id) is TrashResult.Success)
    }

    @Test fun favoriteAndRecentRemainLogicallyLinkedAcrossTrashAndRestore() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val library = UserLibraryManager(store, FakeRegistry(provider), now = { 100L })
        val manager = TrashManager(FakeRegistry(provider), store, library, now = { 200L })
        val source = provider.addFile("/root/linked.txt", 10)
        library.toggleFavorite(source, provider.root)
        library.recordOpened(source, provider.root)

        assertTrue(manager.trash(source, provider.root) is TrashResult.Success)
        assertEquals(LibraryItemStatus.TRASHED, store.favorites.value.single().status)
        assertEquals(LibraryItemStatus.TRASHED, store.recentFiles.value.single().status)
        val record = store.trashRecords.value.single()
        assertTrue(manager.restore(record.id) is TrashResult.Success)
        assertEquals(LibraryItemStatus.AVAILABLE, store.favorites.value.single().status)
        assertEquals(null, store.favorites.value.single().trashId)
        assertEquals(LibraryItemStatus.AVAILABLE, store.recentFiles.value.single().status)
    }

    @Test fun favoritesAreUniqueRemovableAndValidateMissingReferences() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val library = UserLibraryManager(store, FakeRegistry(provider), now = { 100L })
        val source = provider.addFile("/root/favorite.txt", 10)
        assertTrue(library.toggleFavorite(source, provider.root))
        assertFalse(library.toggleFavorite(source, provider.root))
        assertTrue(store.favorites.value.isEmpty())
        library.toggleFavorite(source, provider.root)
        val scoped = ScopedFileReference(source.reference, provider.root.rootReference, provider.root.storageId)
        provider.delete(scoped)
        library.validateFavorites()
        assertEquals(LibraryItemStatus.UNAVAILABLE, store.favorites.value.single().status)
        store.removeFavorite(source.reference.stableIdentity(provider.root.rootReference, provider.root.storageId))
        assertTrue(store.favorites.value.isEmpty())
    }

    @Test fun missingOriginalParentCanRestoreToDeliberatelyChosenDestination() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val originalFolder = provider.addDirectory("/root/original")
        val originalLocation = BrowserLocation("fake", originalFolder.id, "original", "/root/original", "/root", "root", true, true)
        val alternateFolder = provider.addDirectory("/root/alternate")
        val alternate = BrowserLocation("fake", alternateFolder.id, "alternate", "/root/alternate", "/root", "root", true, true)
        val source = provider.addFile("/root/original/recover.txt", 10)
        manager.trash(source, originalLocation)
        provider.delete(ScopedFileReference(originalFolder.reference, "/root", "root"))
        val record = store.trashRecords.value.single()

        assertTrue(manager.restore(record.id) is TrashResult.MissingOriginal)
        assertTrue(manager.restoreTo(record.id, alternate) is TrashResult.Success)
        assertTrue(provider.hasPath("/root/alternate/recover.txt"))
        assertTrue(store.trashRecords.value.isEmpty())
    }

    @Test fun startupReconciliationFinishesProvenRestoreAfterMoveBeforeCatalogWrite() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val source = provider.addFile("/root/restoring.txt", 10)
        manager.trash(source, provider.root)
        val record = store.trashRecords.value.single()
        val payload = requireNotNull(record.trashReference)
        store.upsertTrash(record.copy(state = com.zz.filemanager.core.library.TrashState.RESTORING, operationId = null, restoreDestination = provider.root, restoreName = record.originalName))
        provider.moveNative(payload, provider.root, record.originalName)

        manager.reconcile()

        assertTrue(provider.hasPath("/root/restoring.txt"))
        assertTrue(store.trashRecords.value.isEmpty())
    }

    @Test fun failedPermanentDeleteRetainsRecoverableCatalogRecord() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val source = provider.addFile("/root/keep.txt", 10)
        manager.trash(source, provider.root)
        val record = store.trashRecords.value.single()
        provider.refuseDeletePaths += requireNotNull(record.trashReference).reference.path!!

        assertFalse(manager.deletePermanently(record.id))
        assertEquals(com.zz.filemanager.core.library.TrashState.FAILED, store.trashRecords.value.single().state)
        assertTrue(provider.exists(requireNotNull(record.trashReference).reference))
    }

    @Test fun retentionDeletesOnlyExpiredProvenTrash() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = TrashManager(FakeRegistry(provider), store, UserLibraryManager(store, FakeRegistry(provider)), now = { 10_000L })
        manager.trash(provider.addFile("/root/expired.txt", 10), provider.root)
        manager.trash(provider.addFile("/root/interrupted.txt", 10), provider.root)
        val expired = store.trashRecords.value.first { it.originalName == "expired.txt" }
        val interrupted = store.trashRecords.value.first { it.originalName == "interrupted.txt" }
        store.upsertTrash(expired.copy(trashedAtMillis = 1L))
        store.upsertTrash(interrupted.copy(trashedAtMillis = 1L, state = com.zz.filemanager.core.library.TrashState.INTERRUPTED))

        val result = manager.cleanupExpired(5_000L)

        assertEquals(1, result.deleted)
        assertEquals(listOf("interrupted.txt"), store.trashRecords.value.map { it.originalName })
    }

    @Test fun directoryHierarchyAndEmptyDirectoryRoundTrip() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val directory = provider.addDirectory("/root/project")
        provider.addDirectory("/root/project/empty")
        provider.addFile("/root/project/readme.txt", 10)

        assertTrue(manager.trash(directory, provider.root) is TrashResult.Success)
        assertFalse(provider.hasPath("/root/project"))
        assertTrue(manager.restore(store.trashRecords.value.single().id) is TrashResult.Success)
        assertTrue(provider.hasPath("/root/project/empty"))
        assertTrue(provider.hasPath("/root/project/readme.txt"))
    }

    @Test fun restoreReplaceFailurePreservesExistingAndTrashPayload() = runTest {
        val provider = FakeWritableProvider()
        val store = MemoryLibraryStore()
        val manager = manager(provider, store)
        val old = provider.addFile("/root/report.pdf", 10)
        manager.trash(old, provider.root)
        provider.addFile("/root/report.pdf", 20)
        provider.failAtomicReplace = true
        val record = store.trashRecords.value.single()

        assertTrue(manager.restore(record.id, RestoreCollisionPolicy.REPLACE) is TrashResult.Failed)
        assertEquals(20L, provider.getMetadata(FileReference("fake", "new", path = "/root/report.pdf"))?.sizeBytes)
        val retained = store.trashRecords.value.single()
        assertTrue(provider.exists(requireNotNull(retained.trashReference).reference))
    }

    private fun manager(provider: FakeWritableProvider, store: MemoryLibraryStore): TrashManager {
        val registry = FakeRegistry(provider)
        return TrashManager(registry, store, UserLibraryManager(store, registry), now = { 1_000L })
    }
}

private class FakeRegistry(private val provider: FakeWritableProvider) : StorageProviderRegistry {
    override fun providerFor(providerId: String): StorageProvider = provider
    override fun writableProviderFor(providerId: String): WritableStorageProvider = provider
}

private data class Node(var path: String, val directory: Boolean, val size: Long?)

private class FakeWritableProvider(private val nativeMoves: Boolean = true) : WritableStorageProvider {
    override val id = "fake"
    val root = BrowserLocation(id, "/root", "Root", "/root", "/root", "root", true, true)
    private val nodes = linkedMapOf("/root" to Node("/root", true, null))
    var openOutputCount = 0
    val refuseDeletePaths = mutableSetOf<String>()
    var failAtomicReplace = false

    fun addFile(path: String, size: Long): FileEntry { nodes[path] = Node(path, false, size); return entry(requireNotNull(nodes[path])) }
    fun addDirectory(path: String): FileEntry { nodes[path] = Node(path, true, null); return entry(requireNotNull(nodes[path])) }
    fun hasPath(path: String) = path in nodes
    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
        val prefix = location.reference.trimEnd('/') + "/"
        return nodes.values.filter { it.path.startsWith(prefix) && '/' !in it.path.removePrefix(prefix) }.map(::entry)
    }
    override suspend fun getMetadata(item: FileReference): FileEntry? = item.path?.let(nodes::get)?.let(::entry)
    override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(ByteArray(0))
    override suspend fun exists(item: FileReference): Boolean = item.path in nodes
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
    override suspend fun capabilities(location: BrowserLocation) = ProviderCapabilities(setOf(StorageCapability.READ, StorageCapability.WRITE, StorageCapability.CREATE_DIRECTORY, StorageCapability.CREATE_FILE, StorageCapability.DELETE, StorageCapability.RENAME, StorageCapability.MOVE_NATIVE, StorageCapability.ATOMIC_RENAME))
    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry {
        val path = parent.reference.trimEnd('/') + "/" + name
        check(path !in nodes)
        return entry(Node(path, true, null).also { nodes[path] = it })
    }
    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry {
        val path = parent.reference.trimEnd('/') + "/" + name
        return entry(Node(path, false, 0).also { nodes[path] = it })
    }
    override suspend fun delete(item: ScopedFileReference): Boolean {
        val path = requireNotNull(item.reference.path)
        if (path in refuseDeletePaths) return false
        if (nodes.keys.any { it.startsWith("$path/") }) return false
        return nodes.remove(path) != null
    }
    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry {
        val source = requireNotNull(item.reference.path)
        val destination = source.substringBeforeLast('/') + "/" + newName
        return move(source, destination)
    }
    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream { openOutputCount++; return ByteArrayOutputStream() }
    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = nodes[parent.reference.trimEnd('/') + "/" + name]?.let(::entry)
    override suspend fun freeBytes(location: BrowserLocation): Long = 1L * 1024L * 1024L * 1024L
    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean = destination.reference.startsWith(requireNotNull(source.reference.path))
    override suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean = nativeMoves && destination.storageId == item.storageId && findChild(destination, newName) == null
    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = move(requireNotNull(item.reference.path), destination.reference.trimEnd('/') + "/" + newName)
    override suspend fun replaceAtomically(staged: ScopedFileReference, existing: ScopedFileReference, finalName: String): FileEntry? {
        if (failAtomicReplace) return null
        nodes.remove(requireNotNull(existing.reference.path))
        return move(requireNotNull(staged.reference.path), requireNotNull(existing.reference.path))
    }
    private fun move(source: String, destination: String): FileEntry {
        val node = requireNotNull(nodes.remove(source))
        val descendants = nodes.keys.filter { it.startsWith("$source/") }
        node.path = destination
        nodes[destination] = node
        descendants.forEach { old -> val child = requireNotNull(nodes.remove(old)); child.path = destination + old.removePrefix(source); nodes[child.path] = child }
        return entry(node)
    }
    private fun entry(node: Node) = FileEntry(
        "fake:${node.path}", FileReference(id, "fake:${node.path}", path = node.path), node.path.substringAfterLast('/'),
        node.path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.isNotEmpty() }, null,
        if (node.directory) FileEntryType.DIRECTORY else FileEntryType.GENERIC, node.size, 1L, null, node.path.substringAfterLast('/').startsWith('.'), true, true, null, "root", null,
    )
}

private class MemoryOperationStore : OperationStore {
    override val operations = MutableStateFlow<List<FileOperation>>(emptyList())
    override suspend fun initialize() = Unit
    override suspend fun enqueue(operation: FileOperation) { operations.value = operations.value + operation }
    override suspend fun get(id: String): FileOperation? = operations.value.firstOrNull { it.id == id }
    override suspend fun save(operation: FileOperation) { operations.value = operations.value.map { if (it.id == operation.id) operation else it } }
    override suspend fun nextRunnable(): FileOperation? = operations.value.firstOrNull { it.state == FileOperationState.QUEUED }
    override suspend fun prune(nowMillis: Long) = Unit
}

private class MemoryLibraryStore : UserLibraryStore {
    override val favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    override val recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    override val searchHistory = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    override val activityHistory = MutableStateFlow<List<ActivityEntry>>(emptyList())
    override val trashRecords = MutableStateFlow<List<TrashRecord>>(emptyList())
    override suspend fun initialize() = Unit
    override suspend fun upsertFavorite(item: FavoriteItem) { favorites.value = listOf(item) + favorites.value.filterNot { it.id == item.id } }
    override suspend fun removeFavorite(id: String) { favorites.value = favorites.value.filterNot { it.id == id } }
    override suspend fun updateFavorite(item: FavoriteItem) = upsertFavorite(item)
    override suspend fun recordRecentFile(item: RecentFile) { recentFiles.value = listOf(item) + recentFiles.value.filterNot { it.id == item.id } }
    override suspend fun recordSearch(query: String, usedAtMillis: Long) { searchHistory.value = listOf(SearchHistoryItem(query.lowercase(), query, usedAtMillis)) }
    override suspend fun recordActivity(entry: ActivityEntry) { activityHistory.value = listOf(entry) + activityHistory.value }
    override suspend fun upsertTrash(record: TrashRecord) { trashRecords.value = listOf(record) + trashRecords.value.filterNot { it.id == record.id } }
    override suspend fun removeTrash(id: String) { trashRecords.value = trashRecords.value.filterNot { it.id == id } }
    override suspend fun clearRecentFiles() { recentFiles.value = emptyList() }
    override suspend fun clearSearchHistory() { searchHistory.value = emptyList() }
    override suspend fun clearActivityHistory() { activityHistory.value = emptyList() }
}
