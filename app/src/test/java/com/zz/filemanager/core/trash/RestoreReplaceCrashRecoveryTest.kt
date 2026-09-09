package com.zz.filemanager.core.trash

import com.zz.filemanager.core.library.ActivityEntry
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.library.RestoreReplacePhase
import com.zz.filemanager.core.library.SearchHistoryItem
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.TrashState
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreReplaceCrashRecoveryTest {
    @Test fun replaceSucceedsWithDurableIdentityAndRemovesTrashRecord() = runTest {
        val provider = IdentityWritableProvider()
        val store = TestLibraryStore()
        val manager = manager(provider, store)
        val old = provider.addFile("/root/report.pdf", 10L)
        assertTrue(manager.trash(old, provider.root) is TrashResult.Success)
        provider.addFile("/root/report.pdf", 20L)
        val record = store.trashRecords.value.single()

        val result = manager.restore(record.id, RestoreCollisionPolicy.REPLACE)

        assertTrue(result is TrashResult.Success)
        assertTrue(store.trashRecords.value.isEmpty())
        assertEquals(10L, provider.findChild(provider.root, "report.pdf")?.sizeBytes)
        assertFalse(provider.hasPath("/root/.zzrestore-${record.id}"))
    }

    @Test fun processDeathAfterAtomicReplaceBeforeCatalogWriteFinalizesOnlyByDurableIdentity() = runTest {
        val provider = IdentityWritableProvider()
        val store = TestLibraryStore()
        val manager = manager(provider, store)
        val old = provider.addFile("/root/report.pdf", 10L)
        manager.trash(old, provider.root)
        provider.addFile("/root/report.pdf", 20L)
        val original = store.trashRecords.value.single()
        val payload = requireNotNull(original.trashReference)
        val stageName = ".zzrestore-${original.id}"
        val stagedEntry = requireNotNull(provider.moveNative(payload, provider.root, stageName))
        val staged = ScopedFileReference(stagedEntry.reference, provider.root.rootReference, provider.root.storageId)
        val identity = requireNotNull(provider.mutationIdentity(staged.reference))
        store.upsertTrash(original.copy(
            state = TrashState.RESTORING,
            restoreDestination = provider.root,
            restoreName = original.originalName,
            restoreReplace = true,
            restoreReplacePhase = RestoreReplacePhase.COMMITTING,
            restoreStageName = stageName,
            restoreCommitIdentity = identity,
            trashReference = staged,
        ))
        val conflict = requireNotNull(provider.findChild(provider.root, original.originalName))
        requireNotNull(provider.replaceAtomically(
            staged,
            ScopedFileReference(conflict.reference, provider.root.rootReference, provider.root.storageId),
            original.originalName,
        ))
        // Simulated process death here: no COMMITTED write and no removeTrash().

        manager.reconcile()

        assertTrue(store.trashRecords.value.isEmpty())
        assertEquals(10L, provider.findChild(provider.root, "report.pdf")?.sizeBytes)
        assertFalse(provider.hasPath("/root/$stageName"))
    }

    @Test fun processDeathAfterStageMoveBeforeStageCatalogWriteRecoversStagedPayload() = runTest {
        val provider = IdentityWritableProvider()
        val store = TestLibraryStore()
        val manager = manager(provider, store)
        val old = provider.addFile("/root/report.pdf", 10L)
        manager.trash(old, provider.root)
        provider.addFile("/root/report.pdf", 20L)
        val original = store.trashRecords.value.single()
        val payload = requireNotNull(original.trashReference)
        val stageName = ".zzrestore-${original.id}"
        store.upsertTrash(original.copy(
            state = TrashState.RESTORING,
            restoreDestination = provider.root,
            restoreName = original.originalName,
            restoreReplace = true,
            restoreReplacePhase = RestoreReplacePhase.STAGING,
            restoreStageName = stageName,
        ))
        provider.moveNative(payload, provider.root, stageName)
        // Simulated process death here: the record still points at the old recycle payload.

        manager.reconcile()

        val recovered = store.trashRecords.value.single()
        assertEquals(TrashState.INTERRUPTED, recovered.state)
        assertEquals(RestoreReplacePhase.STAGED, recovered.restoreReplacePhase)
        assertTrue(requireNotNull(recovered.trashReference).reference.path!!.endsWith("/$stageName"))
        assertNotNull(recovered.restoreCommitIdentity)
        assertEquals(20L, provider.findChild(provider.root, "report.pdf")?.sizeBytes)
    }

    @Test fun sameNamedFinalWithWrongIdentityIsNeverGuessedAsSuccessfulRestore() = runTest {
        val provider = IdentityWritableProvider()
        val store = TestLibraryStore()
        val manager = manager(provider, store)
        val old = provider.addFile("/root/report.pdf", 10L)
        manager.trash(old, provider.root)
        provider.addFile("/root/report.pdf", 20L)
        val original = store.trashRecords.value.single()
        val payload = requireNotNull(original.trashReference)
        val stageName = ".zzrestore-${original.id}"
        val stagedEntry = requireNotNull(provider.moveNative(payload, provider.root, stageName))
        val staged = ScopedFileReference(stagedEntry.reference, provider.root.rootReference, provider.root.storageId)
        val identity = requireNotNull(provider.mutationIdentity(staged.reference))
        store.upsertTrash(original.copy(
            state = TrashState.RESTORING,
            restoreDestination = provider.root,
            restoreName = original.originalName,
            restoreReplace = true,
            restoreReplacePhase = RestoreReplacePhase.COMMITTING,
            restoreStageName = stageName,
            restoreCommitIdentity = identity,
            trashReference = staged,
        ))
        // Simulate an ambiguous external mutation: the stage disappeared, but the conflicting final
        // is still the unrelated object and therefore has a different mutation identity.
        provider.delete(staged)

        manager.reconcile()

        val unresolved = store.trashRecords.value.single()
        assertEquals(TrashState.INTERRUPTED, unresolved.state)
        assertEquals(RestoreReplacePhase.COMMITTING, unresolved.restoreReplacePhase)
        assertEquals(20L, provider.findChild(provider.root, "report.pdf")?.sizeBytes)
        assertTrue(unresolved.failureReason.orEmpty().contains("cannot be proven"))
    }

    private fun manager(provider: IdentityWritableProvider, store: TestLibraryStore): TrashManager {
        val registry = IdentityRegistry(provider)
        return TrashManager(registry, store, UserLibraryManager(store, registry), now = { 1_000L })
    }
}

private class IdentityRegistry(private val provider: IdentityWritableProvider) : StorageProviderRegistry {
    override fun providerFor(providerId: String): StorageProvider = provider
    override fun writableProviderFor(providerId: String): WritableStorageProvider = provider
}

private data class IdentityNode(
    var path: String,
    val directory: Boolean,
    val size: Long?,
    val mutationId: Long,
)

private class IdentityWritableProvider : WritableStorageProvider {
    override val id: String = "fake"
    val root = BrowserLocation(id, "/root", "Root", "/root", "/root", "root", true, true)
    private var nextMutationId = 1L
    private val nodes = linkedMapOf("/root" to IdentityNode("/root", true, null, nextMutationId++))

    fun addFile(path: String, size: Long): FileEntry {
        nodes[path] = IdentityNode(path, false, size, nextMutationId++)
        return entry(requireNotNull(nodes[path]))
    }

    fun hasPath(path: String): Boolean = path in nodes

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
        val prefix = location.reference.trimEnd('/') + "/"
        return nodes.values.filter { it.path.startsWith(prefix) && '/' !in it.path.removePrefix(prefix) }.map(::entry)
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? = item.path?.let(nodes::get)?.let(::entry)
    override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(ByteArray(0))
    override suspend fun exists(item: FileReference): Boolean = item.path in nodes
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
    override suspend fun mutationIdentity(item: FileReference): String? = item.path?.let(nodes::get)?.mutationId?.toString()

    override suspend fun capabilities(location: BrowserLocation) = ProviderCapabilities(setOf(
        StorageCapability.READ,
        StorageCapability.WRITE,
        StorageCapability.CREATE_DIRECTORY,
        StorageCapability.CREATE_FILE,
        StorageCapability.DELETE,
        StorageCapability.RENAME,
        StorageCapability.MOVE_NATIVE,
        StorageCapability.ATOMIC_RENAME,
    ))

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry {
        val path = parent.reference.trimEnd('/') + "/" + name
        check(path !in nodes)
        val node = IdentityNode(path, true, null, nextMutationId++)
        nodes[path] = node
        return entry(node)
    }

    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry {
        val path = parent.reference.trimEnd('/') + "/" + name
        check(path !in nodes)
        val node = IdentityNode(path, false, 0L, nextMutationId++)
        nodes[path] = node
        return entry(node)
    }

    override suspend fun delete(item: ScopedFileReference): Boolean {
        val path = requireNotNull(item.reference.path)
        if (nodes.keys.any { it.startsWith("$path/") }) return false
        return nodes.remove(path) != null
    }

    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry {
        val source = requireNotNull(item.reference.path)
        return move(source, source.substringBeforeLast('/') + "/" + newName)
    }

    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream = ByteArrayOutputStream()
    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = nodes[parent.reference.trimEnd('/') + "/" + name]?.let(::entry)
    override suspend fun freeBytes(location: BrowserLocation): Long = Long.MAX_VALUE
    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean = destination.reference.startsWith(requireNotNull(source.reference.path))

    override suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean =
        destination.storageId == item.storageId && findChild(destination, newName) == null && exists(item.reference)

    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? {
        if (!canMoveNative(item, destination, newName)) return null
        return move(requireNotNull(item.reference.path), destination.reference.trimEnd('/') + "/" + newName)
    }

    override suspend fun replaceAtomically(staged: ScopedFileReference, existing: ScopedFileReference, finalName: String): FileEntry? {
        val stagedPath = requireNotNull(staged.reference.path)
        val existingPath = requireNotNull(existing.reference.path)
        if (stagedPath !in nodes || existingPath !in nodes) return null
        nodes.remove(existingPath)
        return move(stagedPath, existingPath)
    }

    private fun move(source: String, destination: String): FileEntry {
        val node = requireNotNull(nodes.remove(source))
        val descendants = nodes.keys.filter { it.startsWith("$source/") }
        node.path = destination
        nodes[destination] = node
        descendants.forEach { old ->
            val child = requireNotNull(nodes.remove(old))
            child.path = destination + old.removePrefix(source)
            nodes[child.path] = child
        }
        return entry(node)
    }

    private fun entry(node: IdentityNode) = FileEntry(
        id = "fake:${node.path}",
        reference = FileReference(id, "fake:${node.path}", path = node.path),
        name = node.path.substringAfterLast('/'),
        extension = node.path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.isNotEmpty() },
        mimeType = null,
        type = if (node.directory) FileEntryType.DIRECTORY else FileEntryType.GENERIC,
        sizeBytes = node.size,
        modifiedAtMillis = node.mutationId,
        createdAtMillis = null,
        isHidden = node.path.substringAfterLast('/').startsWith('.'),
        isReadable = true,
        isWritable = true,
        childCount = null,
        storageId = "root",
        thumbnailKey = null,
    )
}

private class TestLibraryStore : UserLibraryStore {
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
    override suspend fun removeRecentFile(id: String) { recentFiles.value = recentFiles.value.filterNot { it.id == id } }
    override suspend fun recordSearch(query: String, usedAtMillis: Long) { searchHistory.value = listOf(SearchHistoryItem(query.lowercase(), query, usedAtMillis)) }
    override suspend fun recordActivity(entry: ActivityEntry) { activityHistory.value = listOf(entry) + activityHistory.value }
    override suspend fun upsertTrash(record: TrashRecord) { trashRecords.value = listOf(record) + trashRecords.value.filterNot { it.id == record.id } }
    override suspend fun removeTrash(id: String) { trashRecords.value = trashRecords.value.filterNot { it.id == id } }
    override suspend fun clearRecentFiles() { recentFiles.value = emptyList() }
    override suspend fun clearSearchHistory() { searchHistory.value = emptyList() }
    override suspend fun clearActivityHistory() { activityHistory.value = emptyList() }
}
