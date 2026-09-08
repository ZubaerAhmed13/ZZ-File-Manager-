package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.ProviderCapabilities
import com.zz.filemanager.core.storage.StorageAccessException
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
    fun cancelledCollisionCannotBeRevivedByLateDecision() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/report.pdf", byteArrayOf(1))
            file("/dest/report.pdf", byteArrayOf(2))
        }
        val store = MemoryOperationStore()
        var executionRequests = 0
        val controller = FileOperationController(store, OperationExecutionHost { executionRequests++ })
        val id = controller.enqueueCopy(listOf(provider.source("/src/report.pdf")), provider.location("/dest"))
        val engine = FileOperationEngine(store, provider)

        engine.runAvailable()
        assertEquals(FileOperationState.WAITING_FOR_USER, store.get(id)?.state)
        assertNotNull(store.get(id)?.pendingCollision)

        controller.cancel(id)
        controller.resolveCollision(id, CollisionPolicy.KEEP_BOTH, applyToAll = false)
        engine.runAvailable()

        assertEquals(FileOperationState.CANCELLED, store.get(id)?.state)
        assertEquals(null, store.get(id)?.pendingCollision)
        assertFalse(provider.has("/dest/report (1).pdf"))
        assertEquals(1, executionRequests)
    }

    @Test
    fun pauseDuringCopyCleansPartialThenResumeCompletes() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/movie.bin", ByteArray(64) { it.toByte() })
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/movie.bin")), provider.location("/dest"))
        provider.onWrite = { store.forceState(id, FileOperationState.PAUSING) }
        val engine = FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L)

        engine.runAvailable()

        val paused = store.get(id)
        assertEquals(FileOperationState.PAUSED, paused?.state)
        assertTrue(provider.has("/src/movie.bin"))
        assertFalse(provider.has("/dest/movie.bin"))
        assertFalse(provider.hasPartialOutput())
        assertEquals(0L, paused?.items?.single()?.processedBytes)

        controller.resume(id)
        engine.runAvailable()

        assertEquals(FileOperationState.COMPLETED, store.get(id)?.state)
        assertArrayEquals(ByteArray(64) { it.toByte() }, provider.bytes("/dest/movie.bin"))
        assertTrue(provider.has("/src/movie.bin"))
        assertFalse(provider.hasPartialOutput())
    }

    @Test
    fun cancelDuringCopyCleansPartialAndPreservesSource() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/keep.bin", ByteArray(64) { (it + 1).toByte() })
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/keep.bin")), provider.location("/dest"))
        provider.onWrite = { store.forceState(id, FileOperationState.CANCELLING) }
        val engine = FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L)

        engine.runAvailable()

        val cancelled = store.get(id)
        assertEquals(FileOperationState.CANCELLED, cancelled?.state)
        assertTrue(provider.has("/src/keep.bin"))
        assertFalse(provider.has("/dest/keep.bin"))
        assertFalse(provider.hasPartialOutput())
        assertTrue(cancelled?.items?.all { it.state == OperationItemState.CANCELLED } == true)
    }

    @Test
    fun queuedCancellationNeverCreatesDestination() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/queued.bin", byteArrayOf(4, 5, 6))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/queued.bin")), provider.location("/dest"))

        controller.cancel(id)
        FileOperationEngine(store, provider).runAvailable()

        assertEquals(FileOperationState.CANCELLED, store.get(id)?.state)
        assertTrue(provider.has("/src/queued.bin"))
        assertFalse(provider.has("/dest/queued.bin"))
    }

    @Test
    fun destinationDisappearsDuringCopyFailsAndKeepsPartialTracked() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/large.bin", ByteArray(64) { it.toByte() })
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/large.bin")), provider.location("/dest"))
        provider.onWrite = { provider.unavailable = true }

        FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L).runAvailable()

        val failed = store.get(id)
        assertEquals(FileOperationState.FAILED, failed?.state)
        assertTrue(provider.has("/src/large.bin"))
        assertFalse(provider.has("/dest/large.bin"))
        assertTrue(provider.hasPartialOutput())
        assertNotNull(failed?.items?.single()?.partialOutput)
        assertEquals(OperationFailureCode.PROVIDER_UNAVAILABLE, failed?.items?.single()?.failure?.code)
    }

    @Test
    fun staleClipboardSourceDeletedBeforePasteFailsSafely() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/stale.txt", "stale".encodeToByteArray())
        }
        val staleSnapshot = provider.source("/src/stale.txt")
        provider.forceRemove("/src/stale.txt")
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(staleSnapshot), provider.location("/dest"))

        FileOperationEngine(store, provider).runAvailable()

        val failed = store.get(id)
        assertEquals(FileOperationState.FAILED, failed?.state)
        assertEquals(OperationFailureCode.SOURCE_MISSING, failed?.items?.single()?.failure?.code)
        assertFalse(provider.has("/dest/stale.txt"))
    }

    @Test
    fun queuedOperationsCompleteInSubmissionOrder() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/a.bin", byteArrayOf(1))
            file("/src/b.bin", byteArrayOf(2))
            file("/src/c.bin", byteArrayOf(3))
        }
        val store = MemoryOperationStore()
        var tick = 0L
        val controller = FileOperationController(store, OperationExecutionHost {}, now = { ++tick })
        val a = controller.enqueueCopy(listOf(provider.source("/src/a.bin")), provider.location("/dest"))
        val b = controller.enqueueCopy(listOf(provider.source("/src/b.bin")), provider.location("/dest"))
        val c = controller.enqueueCopy(listOf(provider.source("/src/c.bin")), provider.location("/dest"))

        FileOperationEngine(store, provider, now = { ++tick }).runAvailable()

        assertEquals(listOf(a, b, c), store.terminalOrder)
        assertArrayEquals(byteArrayOf(1), provider.bytes("/dest/a.bin"))
        assertArrayEquals(byteArrayOf(2), provider.bytes("/dest/b.bin"))
        assertArrayEquals(byteArrayOf(3), provider.bytes("/dest/c.bin"))
    }

    @Test
    fun secondOperationQueuedAsFirstCompletesIsPickedUpBySameRun() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/first.bin", byteArrayOf(1, 1))
            file("/src/second.bin", byteArrayOf(2, 2))
        }
        val store = MemoryOperationStore()
        var tick = 0L
        val controller = FileOperationController(store, OperationExecutionHost {}, now = { ++tick })
        val firstId = controller.enqueueCopy(listOf(provider.source("/src/first.bin")), provider.location("/dest"))
        val secondId = "second-operation"
        val secondSource = provider.source("/src/second.bin")
        val second = FileOperation(
            id = secondId,
            type = FileOperationType.COPY,
            state = FileOperationState.QUEUED,
            items = listOf(OperationItem("$secondId:0", secondSource, destinationRelativePath = secondSource.name)),
            destination = provider.location("/dest"),
            createdAtMillis = 100L,
        )
        store.onTerminal = { completed ->
            if (completed.id == firstId && secondId !in store.operations.value.map { it.id }) {
                store.enqueueDirect(second)
            }
        }

        FileOperationEngine(store, provider, now = { ++tick }).runAvailable()

        assertEquals(listOf(firstId, secondId), store.terminalOrder)
        assertEquals(FileOperationState.COMPLETED, store.get(secondId)?.state)
        assertArrayEquals(byteArrayOf(2, 2), provider.bytes("/dest/second.bin"))
    }

    @Test
    fun folderCannotBeCopiedIntoItself() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            directory("/src/project")
            file("/src/project/a.txt", byteArrayOf(1))
        }
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/project")), provider.location("/src/project"))

        FileOperationEngine(store, provider).runAvailable()

        assertEquals(FileOperationState.FAILED, store.get(id)?.state)
        assertEquals(OperationFailureCode.DESCENDANT_TARGET, store.get(id)?.failure?.code)
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
    fun fileCopyOntoItselfStopsAtSameResourceCollision() = runBlocking {
        val provider = FakeWritableProvider().apply {
            directory("/src")
            file("/src/report.pdf", byteArrayOf(7, 8, 9))
        }
        val original = provider.bytes("/src/report.pdf").copyOf()
        val store = MemoryOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/report.pdf")), provider.location("/src"))

        FileOperationEngine(store, provider).runAvailable()

        val waiting = store.get(id)
        assertEquals(FileOperationState.WAITING_FOR_USER, waiting?.state)
        assertEquals(CollisionKind.SAME_RESOURCE, waiting?.pendingCollision?.kind)
        assertArrayEquals(original, provider.bytes("/src/report.pdf"))
        assertFalse(provider.hasPartialOutput())
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
    val terminalOrder = mutableListOf<String>()
    var onTerminal: ((FileOperation) -> Unit)? = null
    override val operations: StateFlow<List<FileOperation>> = state
    override suspend fun initialize() = Unit
    override suspend fun enqueue(operation: FileOperation) { state.value = state.value + operation }
    override suspend fun get(id: String): FileOperation? = state.value.firstOrNull { it.id == id }
    override suspend fun save(operation: FileOperation) {
        state.value = state.value.toMutableList().apply {
            val index = indexOfFirst { it.id == operation.id }
            if (index >= 0) set(index, operation) else add(operation)
        }
        if (operation.state.isTerminal && operation.id !in terminalOrder) {
            terminalOrder += operation.id
            onTerminal?.invoke(operation)
        }
    }
    override suspend fun nextRunnable(): FileOperation? = state.value.firstOrNull { it.state == FileOperationState.QUEUED }
    override suspend fun prune(nowMillis: Long) = Unit

    fun forceState(id: String, operationState: FileOperationState) {
        state.value = state.value.map { operation ->
            if (operation.id == id) operation.copy(state = operationState) else operation
        }
    }

    fun enqueueDirect(operation: FileOperation) {
        state.value = state.value + operation
    }
}

private class FakeWritableProvider : WritableStorageProvider, StorageProviderRegistry {
    private data class Node(var path: String, var directory: Boolean, var data: ByteArray = ByteArray(0))
    private val nodes = linkedMapOf<String, Node>()
    var freeSpace: Long? = Long.MAX_VALUE
    var failWrites: Boolean = false
    var unavailable: Boolean = false
    var onWrite: (() -> Unit)? = null
    override val id: String = "fake"

    init { directory("/") }

    fun directory(path: String) { nodes[normalize(path)] = Node(normalize(path), true) }
    fun file(path: String, data: ByteArray) { nodes[normalize(path)] = Node(normalize(path), false, data) }
    fun has(path: String): Boolean = normalize(path) in nodes
    fun bytes(path: String): ByteArray = nodes.getValue(normalize(path)).data
    fun forceRemove(path: String) { nodes.remove(normalize(path)) }
    fun hasPartialOutput(): Boolean = nodes.keys.any { it.substringAfterLast('/').startsWith(".zzpart-") }
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
        ensureAvailable()
        val parent = normalize(location.reference)
        return nodes.values.filter { it.path != parent && parentOf(it.path) == parent }.map(::entry)
    }
    override suspend fun getMetadata(item: FileReference): FileEntry? {
        ensureAvailable()
        return node(item)?.let(::entry)
    }
    override suspend fun openInputStream(item: FileReference): InputStream {
        ensureAvailable()
        return ByteArrayInputStream(node(item)?.data ?: error("missing source"))
    }
    override suspend fun exists(item: FileReference): Boolean {
        ensureAvailable()
        return node(item) != null
    }
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = if (normalize(location.reference) == "/") null else location(parentOf(location.reference))
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = listOf(Breadcrumb(location.displayName, location))
    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities {
        ensureAvailable()
        return ProviderCapabilities(StorageCapability.entries.toSet())
    }

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry {
        ensureAvailable()
        val path = childPath(parent.reference, name)
        check(path !in nodes)
        directory(path)
        return entry(nodes.getValue(path))
    }
    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry {
        ensureAvailable()
        val path = childPath(parent.reference, name)
        check(path !in nodes)
        file(path, ByteArray(0))
        return entry(nodes.getValue(path))
    }
    override suspend fun delete(item: ScopedFileReference): Boolean {
        ensureAvailable()
        val path = normalize(item.reference.path ?: item.reference.opaqueId.removePrefix("fake:"))
        if (nodes.values.any { parentOf(it.path) == path }) error("directory not empty")
        return nodes.remove(path) != null
    }
    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry {
        ensureAvailable()
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
        ensureAvailable()
        if (failWrites) error("simulated write failure")
        val node = node(item.reference) ?: error("missing destination")
        return object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                super.write(buffer, offset, length)
                onWrite?.let { callback ->
                    onWrite = null
                    callback()
                }
            }

            override fun close() {
                node.data = toByteArray()
                super.close()
            }
        }
    }
    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? {
        ensureAvailable()
        return nodes[childPath(parent.reference, name)]?.let(::entry)
    }
    override suspend fun freeBytes(location: BrowserLocation): Long? {
        ensureAvailable()
        return freeSpace
    }
    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean {
        ensureAvailable()
        val sourcePath = normalize(source.reference.path ?: source.reference.opaqueId.removePrefix("fake:"))
        val destinationPath = normalize(destination.reference)
        return destinationPath == sourcePath || destinationPath.startsWith("$sourcePath/")
    }
    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? {
        ensureAvailable()
        return null
    }

    private fun ensureAvailable() {
        if (unavailable) throw StorageAccessException.Unavailable()
    }
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
