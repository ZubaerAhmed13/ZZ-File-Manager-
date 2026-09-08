package com.zz.filemanager.core.operation

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationEngineTest {
    @Test
    fun queuedCopy_streamsBytesAndCompletes() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/report.bin", byteArrayOf(1, 2, 3, 4))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        controller.enqueueCopy(listOf(provider.source("/src/report.bin")), provider.location("/dest"))

        FileOperationEngine(store, provider).runAvailable()

        val result = store.operations.value.single()
        assertEquals(FileOperationState.COMPLETED, result.state)
        assertEquals(4L, result.processedBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), provider.bytes("/dest/report.bin"))
    }

    @Test
    fun moveDeletesSourceOnlyAfterDestinationCompletes() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/movie.dat", byteArrayOf(9, 8, 7))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        controller.enqueueMove(listOf(provider.source("/src/movie.dat")), provider.location("/dest"))

        FileOperationEngine(store, provider).runAvailable()

        assertFalse(provider.has("/src/movie.dat"))
        assertArrayEquals(byteArrayOf(9, 8, 7), provider.bytes("/dest/movie.dat"))
        assertEquals(FileOperationState.COMPLETED, store.operations.value.single().state)
    }

    @Test
    fun copyFailureKeepsSourceIntact() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/keep.txt", "keep".encodeToByteArray())
            failWrites = true
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        controller.enqueueMove(listOf(provider.source("/src/keep.txt")), provider.location("/dest"))

        FileOperationEngine(store, provider).runAvailable()

        assertTrue(provider.has("/src/keep.txt"))
        assertTrue(store.operations.value.single().state in setOf(FileOperationState.FAILED, FileOperationState.COMPLETED_WITH_WARNINGS))
    }

    @Test
    fun collisionWaitsForDecisionThenKeepBothUsesPredictableName() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/report.pdf", byteArrayOf(1))
            file("/dest/report.pdf", byteArrayOf(2))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/report.pdf")), provider.location("/dest"))
        val engine = FileOperationEngine(store, provider)

        engine.runAvailable()
        val waiting = store.get(id)
        assertEquals(FileOperationState.WAITING_FOR_USER, waiting?.state)
        assertNotNull(waiting?.pendingCollision)

        controller.resolveCollision(id, CollisionPolicy.KEEP_BOTH, applyToAll = false)
        engine.runAvailable()

        assertTrue(provider.has("/dest/report (1).pdf"))
        assertEquals(FileOperationState.COMPLETED, store.get(id)?.state)
    }

    @Test
    fun folderCannotBeCopiedIntoItsDescendant() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/src/project")
            directory("/src/project/backup")
            file("/src/project/a.txt", byteArrayOf(1))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/project")), provider.location("/src/project/backup"))

        FileOperationEngine(store, provider).runAvailable()

        val result = store.get(id)
        assertEquals(FileOperationState.FAILED, result?.state)
        assertEquals(OperationFailureCode.DESCENDANT_TARGET, result?.failure?.code)
    }

    @Test
    fun permanentDeleteRemovesChildrenBeforeDirectory() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/trash")
            directory("/trash/nested")
            file("/trash/nested/a.txt", byteArrayOf(1))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        controller.enqueueDelete(listOf(provider.source("/trash")))

        FileOperationEngine(store, provider).runAvailable()

        assertFalse(provider.has("/trash/nested/a.txt"))
        assertFalse(provider.has("/trash/nested"))
        assertFalse(provider.has("/trash"))
        assertEquals(FileOperationState.COMPLETED, store.operations.value.single().state)
    }

    @Test
    fun knownInsufficientSpaceFailsBeforeCreatingOutput() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/big.bin", ByteArray(32))
            freeSpace = 8L
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/big.bin")), provider.location("/dest"))

        FileOperationEngine(store, provider).runAvailable()

        assertEquals(OperationFailureCode.INSUFFICIENT_SPACE, store.get(id)?.failure?.code)
        assertFalse(provider.has("/dest/big.bin"))
    }
}

private class MemoryOperationStore : OperationStore {
    private val state = MutableStateFlow<List<FileOperation>>(emptyList())
    override val operations: StateFlow<List<FileOperation>> = state
    override suspend fun initialize() = Unit
    override suspend fun enqueue(operation: FileOperation) { state.value = state.value + operation }
    override suspend fun get(id: String): FileOperation? = state.value.firstOrNull { it.id == id }
    override suspend fun save(operation: FileOperation) {
        state.value = state.value.toMutableList().apply {
            val index = indexOfFirst { it.id == operation.id }
            if (index >= 0) set(index, operation) else add(operation)
        }
    }
    override suspend fun nextRunnable(): FileOperation? = state.value.firstOrNull { it.state == FileOperationState.QUEUED }
    override suspend fun prune(nowMillis: Long) = Unit
}

private class FakeWritableProvider : WritableStorageProvider, StorageProviderRegistry {
    private data class Node(var path: String, var directory: Boolean, var data: ByteArray = ByteArray(0))
    private val nodes = linkedMapOf<String, Node>()
    var freeSpace: Long? = Long.MAX_VALUE
    var failWrites: Boolean = false
    override val id: String = "fake"

    init { directory("/") }

    fun directory(path: String) { nodes[normalize(path)] = Node(normalize(path), true) }
    fun file(path: String, data: ByteArray) { nodes[normalize(path)] = Node(normalize(path), false, data) }
    fun has(path: String): Boolean = normalize(path) in nodes
    fun bytes(path: String): ByteArray = nodes.getValue(normalize(path)).data
    fun location(path: String): BrowserLocation {
        val normalized = normalize(path)
        return BrowserLocation(id, "fake:$normalized", normalized.substringAfterLast('/').ifBlank { "root" }, normalized, "/", "fake", true, true)
    }
    fun source(path: String): OperationSource {
        val node = nodes.getValue(normalize(path))
        return OperationSource(reference(node), "/", "fake", node.path.substringAfterLast('/').ifBlank { "root" }, node.directory, if (node.directory) null else node.data.size.toLong(), 1L, if (node.directory) null else "application/octet-stream")
    }

    override fun providerFor(providerId: String): StorageProvider = this
    override fun writableProviderFor(providerId: String): WritableStorageProvider? = this.takeIf { providerId == id }

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
        val parent = normalize(location.reference)
        return nodes.values.filter { it.path != parent && parentOf(it.path) == parent }.map(::entry)
    }
    override suspend fun getMetadata(item: FileReference): FileEntry? = node(item)?.let(::entry)
    override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(node(item)?.data ?: error("missing source"))
    override suspend fun exists(item: FileReference): Boolean = node(item) != null
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = if (normalize(location.reference) == "/") null else location(parentOf(location.reference))
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = listOf(Breadcrumb(location.displayName, location))
    override suspend fun capabilities(location: BrowserLocation) = ProviderCapabilities(StorageCapability.entries.toSet())

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry {
        val path = childPath(parent.reference, name)
        check(path !in nodes)
        directory(path)
        return entry(nodes.getValue(path))
    }
    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry {
        val path = childPath(parent.reference, name)
        check(path !in nodes)
        file(path, ByteArray(0))
        return entry(nodes.getValue(path))
    }
    override suspend fun delete(item: ScopedFileReference): Boolean {
        val path = normalize(item.reference.path ?: item.reference.opaqueId.removePrefix("fake:"))
        if (nodes.values.any { parentOf(it.path) == path }) error("directory not empty")
        return nodes.remove(path) != null
    }
    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry {
        val oldPath = normalize(item.reference.path ?: item.reference.opaqueId.removePrefix("fake:"))
        val node = nodes.remove(oldPath) ?: error("missing")
        val newPath = childPath(parentOf(oldPath), newName)
        check(newPath !in nodes)
        val descendants = nodes.values.filter { it.path.startsWith("$oldPath/") }.toList()
        descendants.forEach { nodes.remove(it.path) }
        node.path = newPath
        nodes[newPath] = node
        descendants.forEach { child -> child.path = newPath + child.path.removePrefix(oldPath); nodes[child.path] = child }
        return entry(node)
    }
    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream {
        if (failWrites) error("simulated write failure")
        val node = node(item.reference) ?: error("missing destination")
        return object : ByteArrayOutputStream() {
            override fun close() {
                node.data = toByteArray()
                super.close()
            }
        }
    }
    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = nodes[childPath(parent.reference, name)]?.let(::entry)
    override suspend fun freeBytes(location: BrowserLocation): Long? = freeSpace
    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean {
        val sourcePath = normalize(source.reference.path ?: source.reference.opaqueId.removePrefix("fake:"))
        val destinationPath = normalize(destination.reference)
        return destinationPath == sourcePath || destinationPath.startsWith("$sourcePath/")
    }
    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? = null

    private fun node(reference: FileReference): Node? = nodes[normalize(reference.path ?: reference.opaqueId.removePrefix("fake:"))]
    private fun reference(node: Node) = FileReference(id, "fake:${node.path}", path = node.path)
    private fun entry(node: Node) = FileEntry(
        id = "fake:${node.path}",
        reference = reference(node),
        name = node.path.substringAfterLast('/').ifBlank { "root" },
        extension = if (node.directory) null else node.path.substringAfterLast('.').takeIf { '.' in node.path },
        mimeType = if (node.directory) null else "application/octet-stream",
        type = if (node.directory) FileEntryType.DIRECTORY else FileEntryType.GENERIC,
        sizeBytes = if (node.directory) null else node.data.size.toLong(),
        modifiedAtMillis = 1L,
        createdAtMillis = null,
        isHidden = false,
        isReadable = true,
        isWritable = true,
        childCount = null,
        storageId = "fake",
        thumbnailKey = null,
    )
    private fun normalize(path: String): String = if (path == "/") "/" else "/" + path.trim('/').replace("//", "/")
    private fun parentOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        val parent = normalized.substringBeforeLast('/', "")
        return parent.ifBlank { "/" }
    }
    private fun childPath(parent: String, name: String): String = normalize(if (normalize(parent) == "/") "/$name" else "${normalize(parent)}/$name")
}
