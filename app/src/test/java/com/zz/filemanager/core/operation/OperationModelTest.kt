package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationModelTest {
    @Test
    fun jsonRoundTripPreservesCountersBeyondThirtyGigabytes() {
        val thirtyGiB = 30L * 1024L * 1024L * 1024L
        val source = OperationSource(
            reference = FileReference("fake", "large", path = "/large.bin"),
            rootReference = "/",
            storageId = "fake",
            name = "large.bin",
            isDirectory = false,
            sizeBytes = thirtyGiB,
            modifiedAtMillis = 10L,
            mimeType = "application/octet-stream",
        )
        val item = OperationItem("op:0", source, processedBytes = thirtyGiB - 1L, destinationRelativePath = source.name)
        val operation = FileOperation(
            id = "op",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(item),
            destination = location("/dest"),
            createdAtMillis = 1L,
            totalBytes = thirtyGiB,
            processedBytes = thirtyGiB - 1L,
        )

        val decoded = OperationJsonCodec.decode(OperationJsonCodec.encode(operation))

        assertEquals(thirtyGiB, decoded.totalBytes)
        assertEquals(thirtyGiB - 1L, decoded.processedBytes)
        assertTrue(decoded.processedBytes > Int.MAX_VALUE.toLong())
    }

    @Test
    fun hostInterruptionNeverMarksRunningOperationCompleteAndKeepsPartialTracked() = runBlocking {
        val partial = ScopedFileReference(
            reference = FileReference("fake", "partial", path = "/dest/.zzpart-running"),
            rootReference = "/",
            storageId = "fake",
        )
        val running = FileOperation(
            id = "running",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(
                OperationItem(
                    id = "running:0",
                    source = source("a.bin"),
                    state = OperationItemState.RUNNING,
                    processedBytes = 100L,
                    destinationRelativePath = "a.bin",
                    partialOutput = partial,
                ),
            ),
            destination = location("/dest"),
            createdAtMillis = 1L,
        )
        val store = RecoveryStore(running)

        store.markHostExecutionInterrupted(nowMillis = 20L)
        val reconciled = store.operations.value.single()

        assertEquals(FileOperationState.INTERRUPTED, reconciled.state)
        assertEquals(OperationItemState.QUEUED, reconciled.items.single().state)
        assertEquals(0L, reconciled.items.single().processedBytes)
        assertNotNull(reconciled.items.single().partialOutput)
        assertEquals("partial", reconciled.items.single().partialOutput?.reference?.opaqueId)
        assertFalse(reconciled.state.isTerminal)
    }

    @Test
    fun cancellingHostInterruptionBecomesCancelledWithoutPretendingPartialWasDeleted() = runBlocking {
        val partial = ScopedFileReference(
            reference = FileReference("fake", "cancel-partial", path = "/dest/.zzpart-cancel"),
            rootReference = "/",
            storageId = "fake",
        )
        val cancelling = FileOperation(
            id = "cancelling",
            type = FileOperationType.COPY,
            state = FileOperationState.CANCELLING,
            items = listOf(
                OperationItem(
                    id = "cancelling:0",
                    source = source("b.bin"),
                    state = OperationItemState.RUNNING,
                    processedBytes = 50L,
                    destinationRelativePath = "b.bin",
                    partialOutput = partial,
                ),
            ),
            destination = location("/dest"),
            createdAtMillis = 1L,
        )
        val store = RecoveryStore(cancelling)

        store.markHostExecutionInterrupted(nowMillis = 30L)
        val reconciled = store.operations.value.single()

        assertEquals(FileOperationState.CANCELLED, reconciled.state)
        assertEquals(OperationItemState.CANCELLED, reconciled.items.single().state)
        assertNotNull(reconciled.items.single().partialOutput)
        assertEquals(30L, reconciled.completedAtMillis)
    }

    @Test
    fun batchRenamePlannerPreservesExtensionsAndDetectsDuplicateOutputs() {
        val entries = listOf(
            fileEntry("IMG_001.jpg", "1"),
            fileEntry("IMG_002.jpg", "2"),
        )
        val preview = BatchRenamePlanner.preview(
            entries,
            BatchRenameRule(find = "IMG_", replaceWith = "Holiday_", sequenceEnabled = true, sequenceStart = 1L, sequencePadding = 3),
        )

        assertEquals("Holiday_001_001.jpg", preview[0].proposedName)
        assertEquals("Holiday_002_002.jpg", preview[1].proposedName)
        assertTrue(preview.all { it.valid })

        val duplicateEntries = listOf(fileEntry("same.jpg", "a"), fileEntry("same.jpg", "b"))
        val duplicates = BatchRenamePlanner.preview(duplicateEntries, BatchRenameRule())
        assertTrue(duplicates.all { !it.valid })
    }

    @Test
    fun keepBothNamingPreservesFileExtension() {
        assertEquals("report (1).pdf", FileNameRules.keepBothCandidate("report.pdf", 1, false))
        assertEquals("Photos (2)", FileNameRules.keepBothCandidate("Photos", 2, true))
    }

    private fun source(name: String) = OperationSource(
        FileReference("fake", name, path = "/$name"), "/", "fake", name, false, 10L, 1L, "application/octet-stream"
    )

    private fun location(path: String) = BrowserLocation("fake", "fake:$path", path, path, "/", "fake", true, true)

    private fun fileEntry(name: String, id: String) = com.zz.filemanager.core.model.FileEntry(
        id = id,
        reference = FileReference("fake", id, path = "/$name"),
        name = name,
        extension = name.substringAfterLast('.'),
        mimeType = "image/jpeg",
        type = com.zz.filemanager.core.model.FileEntryType.IMAGE,
        sizeBytes = 1L,
        modifiedAtMillis = 1L,
        createdAtMillis = null,
        isHidden = false,
        isReadable = true,
        isWritable = true,
        childCount = null,
        storageId = "fake",
        thumbnailKey = null,
    )
}

private class RecoveryStore(initial: FileOperation) : OperationStore {
    private val state = MutableStateFlow(listOf(initial))
    override val operations: StateFlow<List<FileOperation>> = state
    override suspend fun initialize() = Unit
    override suspend fun enqueue(operation: FileOperation) { state.value = state.value + operation }
    override suspend fun get(id: String): FileOperation? = state.value.firstOrNull { it.id == id }
    override suspend fun save(operation: FileOperation) {
        state.value = state.value.map { if (it.id == operation.id) operation else it }
    }
    override suspend fun nextRunnable(): FileOperation? = state.value.firstOrNull { it.state == FileOperationState.QUEUED }
    override suspend fun prune(nowMillis: Long) = Unit
}
