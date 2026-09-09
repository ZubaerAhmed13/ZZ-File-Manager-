package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaceLedgerPersistenceTest {
    @Test
    fun replaceRecoveryLedgerSurvivesJsonRoundTrip() {
        val original = scoped("/dest/holiday.mp4")
        val staged = scoped("/dest/.zzpart-op-item")
        val backup = scoped("/dest/.zzreplace-backup-op-item")
        val operation = FileOperation(
            id = "op",
            type = FileOperationType.COPY,
            state = FileOperationState.INTERRUPTED,
            items = listOf(
                OperationItem(
                    id = "item",
                    source = OperationSource(
                        reference = reference("/src/holiday.mp4"),
                        rootReference = "/",
                        storageId = "test",
                        name = "holiday.mp4",
                        isDirectory = false,
                        sizeBytes = 30L * 1024L * 1024L * 1024L,
                        modifiedAtMillis = 1234L,
                        mimeType = "video/mp4",
                    ),
                    state = OperationItemState.QUEUED,
                    destinationRelativePath = "holiday.mp4",
                    partialOutput = staged,
                    replacePhase = ReplacePhase.COMMITTING,
                    replaceFinalName = "holiday.mp4",
                    replaceOriginalReference = original,
                    replaceOriginalSizeBytes = 20L * 1024L * 1024L * 1024L,
                    replaceOriginalModifiedAtMillis = 9876L,
                    replaceBackupName = ".zzreplace-backup-op-item",
                    replaceBackupReference = backup,
                ),
            ),
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )

        val decoded = OperationJsonCodec.decode(OperationJsonCodec.encode(operation))
        val item = decoded.items.single()

        assertEquals(FileOperationState.INTERRUPTED, decoded.state)
        assertEquals(ReplacePhase.COMMITTING, item.replacePhase)
        assertEquals("holiday.mp4", item.replaceFinalName)
        assertEquals(original, item.replaceOriginalReference)
        assertEquals(20L * 1024L * 1024L * 1024L, item.replaceOriginalSizeBytes)
        assertEquals(9876L, item.replaceOriginalModifiedAtMillis)
        assertEquals(".zzreplace-backup-op-item", item.replaceBackupName)
        assertEquals(backup, item.replaceBackupReference)
        assertEquals(staged, item.partialOutput)
        assertEquals(30L * 1024L * 1024L * 1024L, item.source.sizeBytes)
    }

    private fun scoped(path: String) = ScopedFileReference(reference(path), "/", "test")

    private fun reference(path: String) = FileReference(
        providerId = "test",
        opaqueId = "test:$path",
        path = path,
    )
}
