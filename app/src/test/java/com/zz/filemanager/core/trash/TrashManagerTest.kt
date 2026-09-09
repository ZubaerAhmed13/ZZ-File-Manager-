package com.zz.filemanager.core.trash

import com.zz.filemanager.core.library.ActivityEntry
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.library.SearchHistoryItem
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.UserLibraryManager
import com.zz.filemanager.core.library.UserLibraryStore
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

private class FakeWritableProvider : WritableStorageProvider {
    override val id = "fake"
    val root = BrowserLocation(id, "/root", "Root", "/root", "/root", "root", true, true)
    private val nodes = linkedMapOf("/root" to Node("/root", true, null))
    var openOutputCount = 0

    fun addFile(path: String, size: Long): FileEntry { nodes[path] = Node(path, false, size); return entry(requireNotNull(nodes[path])) }
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
    override suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean = destination.storageId == item.storageId && findChild(destination, newName) == null
    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = move(requireNotNull(item.reference.path), destination.reference.trimEnd('/') + "/" + newName)
    override suspend fun replaceAtomically(staged: ScopedFileReference, existing: ScopedFileReference, finalName: String): FileEntry? {
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
