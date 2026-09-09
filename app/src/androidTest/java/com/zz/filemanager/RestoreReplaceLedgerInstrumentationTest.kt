package com.zz.filemanager

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.library.RestoreReplacePhase
import com.zz.filemanager.core.library.TrashBackendType
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.TrashState
import com.zz.filemanager.core.library.UserLibraryRepository
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreReplaceLedgerInstrumentationTest {
    @Test fun committingRestoreReplaceProofSurvivesRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = UserLibraryRepository(context)
        repository.initialize()
        val token = System.nanoTime().toString()
        val parent = BrowserLocation("local", "local:/tmp", "tmp", "/tmp", "/tmp", "test", true, true)
        val source = FileReference("local", "source-$token", path = "/tmp/report.pdf")
        val stage = ScopedFileReference(FileReference("local", "stage-$token", path = "/tmp/.zzrestore-$token"), "/tmp", "test")
        val container = ScopedFileReference(FileReference("local", "container-$token", path = "/tmp/.ZZFileManagerRecycle/$token"), "/tmp", "test")
        val committed = ScopedFileReference(FileReference("local", "final-$token", path = "/tmp/report.pdf"), "/tmp", "test")
        val id = "restore-replace-ledger-$token"
        val record = TrashRecord(
            id = id,
            backend = TrashBackendType.APP_MANAGED,
            originalReference = source,
            originalParent = parent,
            originalName = "report.pdf",
            type = FileEntryType.GENERIC,
            sizeBytes = 30L * 1024L * 1024L * 1024L,
            modifiedAtMillis = 123L,
            trashReference = stage,
            containerReference = container,
            trashedAtMillis = 100L,
            updatedAtMillis = 200L,
            state = TrashState.RESTORING,
            restoreDestination = parent,
            restoreName = "report.pdf",
            restoreReplace = true,
            restoreReplacePhase = RestoreReplacePhase.COMMITTING,
            restoreStageName = ".zzrestore-$token",
            restoreCommitIdentity = "dev=1,ino=42",
            restoreCommittedReference = committed,
        )
        repository.upsertTrash(record)

        val recreated = UserLibraryRepository(context)
        recreated.initialize()
        val restored = recreated.trashRecords.value.first { it.id == id }

        assertEquals(RestoreReplacePhase.COMMITTING, restored.restoreReplacePhase)
        assertEquals(".zzrestore-$token", restored.restoreStageName)
        assertEquals("dev=1,ino=42", restored.restoreCommitIdentity)
        assertEquals(committed.reference.path, restored.restoreCommittedReference?.reference?.path)
        assertNotNull(restored.trashReference)
        recreated.removeTrash(id)
    }
}
