package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.FileReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class Step5AutoResumePolicyTest {
    @Test
    fun autoResumeInterruptedTransfersHonorsSettingAndDoesNotReplayDelete() = runBlocking {
        val copy = interrupted("copy", FileOperationType.COPY, resumeOffset = 4096L)
        val delete = interrupted("delete", FileOperationType.DELETE)
        val disabledStore = FakeStore(listOf(copy, delete))
        assertEquals(0, disabledStore.autoResumeInterruptedTransfers(enabled = false))
        assertEquals(FileOperationState.INTERRUPTED, disabledStore.get("copy")!!.state)

        val store = FakeStore(listOf(copy, delete))
        assertEquals(1, store.autoResumeInterruptedTransfers(enabled = true, nowMillis = 99L))
        val resumed = store.get("copy")!!
        assertEquals(FileOperationState.QUEUED, resumed.state)
        assertEquals(4096L, resumed.items.single().resumeOffset)
        assertEquals("src-r1", resumed.items.single().resumeSourceIdentity)
        assertEquals("dst-r1", resumed.items.single().resumeStagedIdentity)
        assertEquals(FileOperationState.INTERRUPTED, store.get("delete")!!.state)
    }

    private fun interrupted(id: String, type: FileOperationType, resumeOffset: Long = 0L): FileOperation {
        val source = OperationSource(
            reference = FileReference("sftp:test", "/source.bin"),
            rootReference = "/",
            storageId = "sftp:test",
            name = "source.bin",
            isDirectory = false,
            sizeBytes = 8192L,
            modifiedAtMillis = 1L,
            mimeType = "application/octet-stream",
        )
        return FileOperation(
            id = id,
            type = type,
            state = FileOperationState.INTERRUPTED,
            items = listOf(
                OperationItem(
                    id = "$id-item",
                    source = source,
                    state = OperationItemState.QUEUED,
                    processedBytes = resumeOffset,
                    partialOutput = if (resumeOffset > 0L) com.zz.filemanager.core.model.ScopedFileReference(
                        FileReference("webdav:test", "/.zzpart"),
                        "/",
                        "webdav:test",
                    ) else null,
                    resumeSourceIdentity = if (resumeOffset > 0L) "src-r1" else null,
                    resumeStagedIdentity = if (resumeOffset > 0L) "dst-r1" else null,
                    resumeOffset = resumeOffset,
                ),
            ),
            createdAtMillis = 1L,
        )
    }

    private class FakeStore(initial: List<FileOperation>) : OperationStore {
        private val mutable = MutableStateFlow(initial)
        override val operations: StateFlow<List<FileOperation>> = mutable
        override suspend fun initialize() = Unit
        override suspend fun enqueue(operation: FileOperation) { mutable.value += operation }
        override suspend fun get(id: String): FileOperation? = mutable.value.firstOrNull { it.id == id }
        override suspend fun save(operation: FileOperation) {
            mutable.value = mutable.value.map { if (it.id == operation.id) operation else it }
        }
        override suspend fun nextRunnable(): FileOperation? = mutable.value.firstOrNull { it.state == FileOperationState.QUEUED }
        override suspend fun prune(nowMillis: Long) = Unit
    }
}
