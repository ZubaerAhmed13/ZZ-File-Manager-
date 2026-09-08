package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationControllerTest {
    @Test
    fun retryCarriesTrackedPartialIntoNewOperationForCleanup() = runBlocking {
        val partial = ScopedFileReference(
            reference = FileReference("fake", "partial", path = "/dest/.zzpart-old"),
            rootReference = "/",
            storageId = "fake",
        )
        val source = source("video.mp4")
        val failed = FileOperation(
            id = "failed",
            type = FileOperationType.COPY,
            state = FileOperationState.FAILED,
            items = listOf(
                OperationItem(
                    id = "failed:0",
                    source = source,
                    state = OperationItemState.FAILED,
                    destinationRelativePath = source.name,
                    processedBytes = 1024L,
                    failure = OperationFailure(OperationFailureCode.PROVIDER_UNAVAILABLE, "Storage disappeared."),
                    partialOutput = partial,
                ),
            ),
            destination = location("/dest"),
            createdAtMillis = 1L,
            completedAtMillis = 2L,
        )
        val store = ControllerStore(failed)
        var executionRequests = 0
        val controller = FileOperationController(store, OperationExecutionHost { executionRequests++ }, now = { 10L })

        val retryId = controller.retry(failed.id)

        assertNotNull(retryId)
        assertNotEquals(failed.id, retryId)
        val retry = store.get(retryId!!)!!
        assertEquals(FileOperationState.QUEUED, retry.state)
        assertEquals(OperationItemState.QUEUED, retry.items.single().state)
        assertEquals(0L, retry.items.single().processedBytes)
        assertEquals(partial, retry.items.single().partialOutput)
        assertEquals(failed.id, retry.retryOfOperationId)
        assertEquals(1, executionRequests)
    }

    @Test
    fun waitingCollisionCancelIsImmediateAndLateDecisionCannotRequeue() = runBlocking {
        val source = source("report.pdf")
        val item = OperationItem("waiting:0", source, destinationRelativePath = source.name)
        val collision = PendingCollision(
            id = "collision",
            itemId = item.id,
            sourceName = source.name,
            destinationName = source.name,
            kind = CollisionKind.FILE_TO_FILE,
            allowedPolicies = setOf(CollisionPolicy.KEEP_BOTH, CollisionPolicy.SKIP),
        )
        val waiting = FileOperation(
            id = "waiting",
            type = FileOperationType.COPY,
            state = FileOperationState.WAITING_FOR_USER,
            items = listOf(item),
            destination = location("/dest"),
            createdAtMillis = 1L,
            pendingCollision = collision,
        )
        val store = ControllerStore(waiting)
        var executionRequests = 0
        val controller = FileOperationController(store, OperationExecutionHost { executionRequests++ }, now = { 20L })

        controller.cancel(waiting.id)
        controller.resolveCollision(waiting.id, CollisionPolicy.KEEP_BOTH, applyToAll = true)

        val cancelled = store.get(waiting.id)!!
        assertEquals(FileOperationState.CANCELLED, cancelled.state)
        assertNull(cancelled.pendingCollision)
        assertEquals(20L, cancelled.completedAtMillis)
        assertEquals(0, executionRequests)
    }

    @Test
    fun interruptedCancelIsTerminalAndPreservesTrackedPartialRecord() = runBlocking {
        val partial = ScopedFileReference(
            reference = FileReference("fake", "partial-cancel", path = "/dest/.zzpart-cancel"),
            rootReference = "/",
            storageId = "fake",
        )
        val source = source("large.bin")
        val interrupted = FileOperation(
            id = "interrupted",
            type = FileOperationType.COPY,
            state = FileOperationState.INTERRUPTED,
            items = listOf(OperationItem("interrupted:0", source, destinationRelativePath = source.name, partialOutput = partial)),
            destination = location("/dest"),
            createdAtMillis = 1L,
        )
        val store = ControllerStore(interrupted)
        val controller = FileOperationController(store, OperationExecutionHost {}, now = { 30L })

        controller.cancel(interrupted.id)

        val cancelled = store.get(interrupted.id)!!
        assertEquals(FileOperationState.CANCELLED, cancelled.state)
        assertEquals(partial, cancelled.items.single().partialOutput)
        assertTrue(cancelled.state.isTerminal)
    }

    @Test
    fun duplicateSubmissionWithinGuardWindowReturnsOriginalQueuedId() = runBlocking {
        val store = ControllerStore()
        var executionRequests = 0
        var tick = 100L
        val controller = FileOperationController(store, OperationExecutionHost { executionRequests++ }, now = { tick })
        val sources = listOf(source("report.pdf"))
        val destination = location("/dest")

        val firstId = controller.enqueueCopy(sources, destination)
        tick = 200L
        val duplicateId = controller.enqueueCopy(sources, destination)

        assertEquals(firstId, duplicateId)
        assertEquals(1, store.operations.value.size)
        assertNotNull(store.get(duplicateId))
        assertEquals(1, executionRequests)
    }

    @Test
    fun sameSubmissionAfterGuardWindowCreatesNewOperation() = runBlocking {
        val store = ControllerStore()
        var executionRequests = 0
        var tick = 100L
        val controller = FileOperationController(store, OperationExecutionHost { executionRequests++ }, now = { tick })
        val sources = listOf(source("report.pdf"))
        val destination = location("/dest")

        val firstId = controller.enqueueCopy(sources, destination)
        tick = 850L
        val secondId = controller.enqueueCopy(sources, destination)

        assertNotEquals(firstId, secondId)
        assertEquals(2, store.operations.value.size)
        assertNotNull(store.get(secondId))
        assertEquals(2, executionRequests)
    }

    @Test
    fun tenThousandSourceCopyQueuesAllMetadataWithoutFilePayloadAllocation() = runBlocking {
        val sources = List(10_000) { index ->
            source("item-${index.toString().padStart(5, '0')}.bin")
        }
        val store = ControllerStore()
        var executionRequests = 0
        var tick = 100L
        val controller = FileOperationController(store, OperationExecutionHost { executionRequests++ }, now = { ++tick })

        val id = controller.enqueueCopy(sources, location("/dest"))

        val queued = store.get(id)!!
        assertEquals(FileOperationState.QUEUED, queued.state)
        assertEquals(10_000, queued.items.size)
        assertEquals(10_000L, queued.totalItems)
        assertEquals("item-00000.bin", queued.items.first().source.name)
        assertEquals("item-09999.bin", queued.items.last().source.name)
        assertEquals(10_000, queued.items.map { it.id }.toSet().size)
        assertEquals(1, executionRequests)
    }

    private fun source(name: String) = OperationSource(
        reference = FileReference("fake", name, path = "/src/$name"),
        rootReference = "/",
        storageId = "fake",
        name = name,
        isDirectory = false,
        sizeBytes = 1024L,
        modifiedAtMillis = 1L,
        mimeType = "application/octet-stream",
    )

    private fun location(path: String) = BrowserLocation(
        providerId = "fake",
        id = "fake:$path",
        displayName = path.substringAfterLast('/').ifBlank { "root" },
        reference = path,
        rootReference = "/",
        storageId = "fake",
        readable = true,
        writable = true,
    )
}

private class ControllerStore(vararg initial: FileOperation) : OperationStore {
    private val state = MutableStateFlow(initial.toList())
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
