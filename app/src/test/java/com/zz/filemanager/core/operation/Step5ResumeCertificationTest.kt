package com.zz.filemanager.core.operation

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.storage.ProviderCapabilities
import com.zz.filemanager.core.storage.ResumableWritableStorageProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step5ResumeCertificationTest {
    @Test
    fun resumeRejectsChangedRemoteRevision() = runBlocking {
        val source = ResumeProofProvider("remote-source").apply {
            sourceResumeIdentity = "revision-new"
        }
        val destination = ResumeProofProvider("local-destination").apply {
            stagedExists = true
            stagedMutationIdentity = "stage-id"
            stagedSize = 4_294_967_296L
        }
        val sourceRef = FileReference(source.id, "source-native-id")
        val staged = ScopedFileReference(
            FileReference(destination.id, "hidden-stage-native-id"),
            rootReference = "root",
            storageId = destination.id,
        )
        val operationSource = OperationSource(
            reference = sourceRef,
            rootReference = "remote-root",
            storageId = source.id,
            name = "large.bin",
            isDirectory = false,
            sizeBytes = 8_589_934_592L,
            modifiedAtMillis = 1L,
            mimeType = "application/octet-stream",
        )
        val item = OperationItem(
            id = "resume-item",
            source = operationSource,
            state = OperationItemState.QUEUED,
            processedBytes = 4_294_967_296L,
            partialOutput = staged,
            resumeSourceIdentity = "revision-old",
            resumeStagedIdentity = "stage-id",
            resumeOffset = 4_294_967_296L,
        )

        val decision = TransferResumeCoordinator.evaluate(item, source, destination)

        assertTrue(decision is TransferResumeCoordinator.Decision.SourceChanged)
        assertEquals(4_294_967_296L, item.resumeOffset)
    }

    @Test
    fun validResumeRequiresExactStagedLengthAndIdentity() = runBlocking {
        val source = ResumeProofProvider("remote-source").apply { sourceResumeIdentity = "revision-1" }
        val destination = ResumeProofProvider("remote-destination").apply {
            stagedExists = true
            stagedMutationIdentity = "stage-1"
            stagedSize = 5_000_000_000L
        }
        val staged = ScopedFileReference(FileReference(destination.id, "stage"), "root", destination.id)
        val item = OperationItem(
            id = "resume-valid",
            source = OperationSource(FileReference(source.id, "source"), "root", source.id, "x.bin", false, 10_000_000_000L, 1L, null),
            processedBytes = 5_000_000_000L,
            partialOutput = staged,
            resumeSourceIdentity = "revision-1",
            resumeStagedIdentity = "stage-1",
            resumeOffset = 5_000_000_000L,
        )

        val valid = TransferResumeCoordinator.evaluate(item, source, destination)
        assertTrue(valid is TransferResumeCoordinator.Decision.Resume)

        destination.stagedSize = 4_999_999_999L
        val wrongLength = TransferResumeCoordinator.evaluate(item, source, destination)
        assertTrue(wrongLength is TransferResumeCoordinator.Decision.Restart)
    }
}

private class ResumeProofProvider(override val id: String) : ResumableWritableStorageProvider {
    var sourceResumeIdentity: String? = null
    var stagedMutationIdentity: String? = null
    var stagedExists: Boolean = false
    var stagedSize: Long? = null

    override suspend fun resumeIdentity(item: FileReference): String? = sourceResumeIdentity
    override suspend fun mutationIdentity(item: FileReference): String? = stagedMutationIdentity
    override suspend fun exists(item: FileReference): Boolean = stagedExists
    override suspend fun getMetadata(item: FileReference): FileEntry? = stagedSize?.let { size ->
        FileEntry(
            id = item.opaqueId,
            reference = item,
            name = "stage",
            extension = null,
            mimeType = "application/octet-stream",
            type = FileEntryType.GENERIC,
            sizeBytes = size,
            modifiedAtMillis = 1L,
            createdAtMillis = null,
            isHidden = true,
            isReadable = true,
            isWritable = true,
            childCount = null,
            storageId = id,
            thumbnailKey = null,
        )
    }

    override suspend fun openInputStreamAt(item: FileReference, offset: Long): InputStream = ByteArrayInputStream(ByteArray(0))
    override suspend fun openOutputStreamAt(item: ScopedFileReference, offset: Long): OutputStream = ByteArrayOutputStream()
    override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(ByteArray(0))
    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = emptyList()
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities = ProviderCapabilities(emptySet())
    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry = error("unused")
    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry = error("unused")
    override suspend fun delete(item: ScopedFileReference): Boolean = true
    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry = error("unused")
    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream = ByteArrayOutputStream()
    override suspend fun findChild(parent: BrowserLocation, name: String): FileEntry? = null
    override suspend fun freeBytes(location: BrowserLocation): Long? = null
    override suspend fun totalBytes(location: BrowserLocation): Long? = null
    override suspend fun isSameOrDescendant(source: ScopedFileReference, destination: BrowserLocation): Boolean = false
}
