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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationSafetyRegressionTest {
    private val oldHoliday = "known-good-existing-destination".encodeToByteArray()
    private val newHoliday = ByteArray(96) { index -> (index % 251).toByte() }

    @Test
    fun replaceWriteFailurePreservesExistingDestination() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.failWriteAfterBytes = 8

        fixture.resolveReplaceAndRun()

        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
        assertArrayEquals(oldHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun replaceSourceReadFailurePreservesExistingDestination() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.failReadAfterBytes = 16

        fixture.resolveReplaceAndRun()

        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
        assertArrayEquals(oldHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun replaceProviderDisappearancePreservesExistingDestination() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.disappearAfterFirstWrite = true

        fixture.resolveReplaceAndRun()

        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
        assertArrayEquals(oldHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        // The staged file may be intentionally retained/tracked because the provider vanished,
        // but the previously good final destination must remain untouched.
        assertTrue(fixture.provider.rawHas("/dest/holiday.mp4"))
    }

    @Test
    fun replaceCancellationPreservesExistingDestination() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.onFirstWrite = {
            fixture.store.forceState(fixture.id, FileOperationState.CANCELLING)
        }

        fixture.resolveReplaceAndRun()

        assertEquals(FileOperationState.CANCELLED, fixture.store.get(fixture.id)?.state)
        assertArrayEquals(oldHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun replaceFinalRenameFailureRollsBackExistingDestination() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzpart-") && to == "holiday.mp4" },
            afterMutation = false,
            throwable = IOException("simulated staged-to-final failure"),
        )

        fixture.resolveReplaceAndRun()

        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
        assertArrayEquals(oldHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun replaceProcessDeathAfterBackupMutationRestoresOldThenCompletesReplacement() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from == "holiday.mp4" && to.startsWith(".zzreplace-backup-") },
            afterMutation = true,
            throwable = CancellationException("simulated process death after destination-to-backup rename"),
        )

        fixture.controller.resolveCollision(fixture.id, CollisionPolicy.REPLACE, applyToAll = false)
        var interrupted = false
        try {
            fixture.engine.runAvailable()
        } catch (_: CancellationException) {
            interrupted = true
        }

        assertTrue("Failure injection must hit the Replace transaction window", interrupted)
        val journaled = fixture.store.get(fixture.id)
        assertEquals(ReplacePhase.BACKUP_PLANNED, journaled?.items?.single()?.replacePhase)
        assertFalse(fixture.provider.rawHas("/dest/holiday.mp4"))
        assertTrue(fixture.provider.rawHasHiddenTransferArtifacts())

        fixture.provider.renameFaults.clear()
        fixture.store.forceState(fixture.id, FileOperationState.INTERRUPTED)
        fixture.controller.resume(fixture.id)
        fixture.engine.runAvailable()

        assertEquals(FileOperationState.COMPLETED, fixture.store.get(fixture.id)?.state)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
        assertEquals(ReplacePhase.NONE, fixture.store.get(fixture.id)?.items?.single()?.replacePhase)
    }

    @Test
    fun renameWithoutDeleteDoesNotOfferUnsafeReplace() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/holiday.mp4", newHoliday)
            file("/dest/holiday.mp4", oldHoliday)
            advertisedCapabilities = setOf(
                StorageCapability.READ,
                StorageCapability.WRITE,
                StorageCapability.CREATE_FILE,
                StorageCapability.CREATE_DIRECTORY,
                StorageCapability.RENAME,
            )
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost { }) { 1L }
        val engine = FileOperationEngine(store, provider, now = { 1L }, bufferSize = 16, progressIntervalMillis = 1L)
        val id = controller.enqueueCopy(listOf(provider.source("/src/holiday.mp4")), provider.location("/dest"))

        engine.runAvailable()

        val collision = store.get(id)?.pendingCollision
        assertEquals(FileOperationState.WAITING_FOR_USER, store.get(id)?.state)
        assertTrue(collision != null)
        assertFalse(CollisionPolicy.REPLACE in collision!!.allowedPolicies)
        assertTrue(CollisionPolicy.KEEP_BOTH in collision.allowedPolicies)
        assertArrayEquals(oldHoliday, provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun providerWithoutRenameNeverCreatesVisiblePartialFinal() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/movie.mp4", ByteArray(64) { it.toByte() })
            advertisedCapabilities = setOf(
                StorageCapability.READ,
                StorageCapability.WRITE,
                StorageCapability.CREATE_FILE,
                StorageCapability.CREATE_DIRECTORY,
            )
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/movie.mp4")), provider.location("/dest"))

        FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L).runAvailable()

        assertEquals(OperationFailureCode.SAFE_FINALIZATION_UNSUPPORTED, store.get(id)?.items?.single()?.failure?.code)
        assertFalse(provider.rawHas("/dest/movie.mp4"))
        assertFalse(provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun nativeMoveWithThirtyGiBLogicalSourceAndOneGiBFreeIsAllowed() = runBlocking {
        val thirtyGiB = 30L * 1024L * 1024L * 1024L
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/movie.mkv", byteArrayOf(1, 2, 3), logicalSize = thirtyGiB)
            freeSpace = 1L * 1024L * 1024L * 1024L
            nativeMoveEnabled = true
            advertisedCapabilities = setOf(
                StorageCapability.READ,
                StorageCapability.WRITE,
                StorageCapability.CREATE_FILE,
                StorageCapability.CREATE_DIRECTORY,
                StorageCapability.DELETE,
                StorageCapability.RENAME,
                StorageCapability.MOVE_NATIVE,
            )
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueMove(listOf(provider.source("/src/movie.mkv")), provider.location("/dest"))

        FileOperationEngine(store, provider).runAvailable()

        assertEquals(FileOperationState.COMPLETED, store.get(id)?.state)
        assertFalse(provider.rawHas("/src/movie.mkv"))
        assertTrue(provider.rawHas("/dest/movie.mkv"))
        assertEquals(1, provider.nativeMoveCount)
    }

    @Test
    fun batchRenamePhaseTwoFailureRollsBackEveryItemToOriginalName() = runBlocking {
        val fixture = batchFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzrename-") && to == "NewC.txt" },
            afterMutation = false,
            throwable = IOException("simulated final-phase failure"),
        )

        fixture.engine.runAvailable()

        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
        fixture.assertOriginalNamesOnly()
    }

    @Test
    fun batchRenameRollbackFailureRemainsRecoverableAndResumeRestoresOriginals() = runBlocking {
        val fixture = batchFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzrename-") && to == "NewC.txt" },
            afterMutation = false,
            throwable = IOException("simulated forward failure"),
        )
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from == "NewB.txt" && to.startsWith(".zzrename-") },
            afterMutation = false,
            throwable = IOException("simulated rollback failure"),
        )

        fixture.engine.runAvailable()

        val interrupted = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.INTERRUPTED, interrupted?.state)
        assertTrue(interrupted?.batchRenameRollbackRequired == true)
        assertEquals(OperationFailureCode.TRANSACTION_ROLLBACK_FAILED, interrupted?.failure?.code)

        fixture.provider.renameFaults.clear()
        fixture.controller.resume(fixture.id)
        fixture.engine.runAvailable()

        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
        assertFalse(fixture.store.get(fixture.id)?.batchRenameRollbackRequired == true)
        fixture.assertOriginalNamesOnly()
    }

    @Test
    fun batchRenameRecoversWhenProcessDiesAfterOriginalToTempMutation() = runBlocking {
        val fixture = batchFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from == "A.txt" && to.startsWith(".zzrename-") },
            afterMutation = true,
            throwable = CancellationException("simulated process death after temp rename"),
        )

        fixture.runUntilProcessInterruptionThenResume()

        fixture.assertFinalNamesOnly()
    }

    @Test
    fun batchRenameRecoversWhenProcessDiesBetweenTemporaryAndFinalPhases() = runBlocking {
        val fixture = batchFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzrename-") && to == "NewA.txt" },
            afterMutation = false,
            throwable = CancellationException("simulated process death before first final rename"),
        )

        fixture.runUntilProcessInterruptionThenResume()

        fixture.assertFinalNamesOnly()
    }

    @Test
    fun batchRenameRecoversWhenProcessDiesAfterFinalMutationBeforeJournalSave() = runBlocking {
        val fixture = batchFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzrename-") && to == "NewA.txt" },
            afterMutation = true,
            throwable = CancellationException("simulated process death after final rename"),
        )

        fixture.runUntilProcessInterruptionThenResume()

        fixture.assertFinalNamesOnly()
    }

    @Test
    fun replacePostCommitVerificationFailureNeverReportsCompleted() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.metadataSizeOverride = { path, actual ->
            if (path == "/dest/holiday.mp4" && actual == newHoliday.size.toLong()) actual + 1L else actual
        }

        fixture.resolveReplaceAndRun()

        val interrupted = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.INTERRUPTED, interrupted?.state)
        assertFalse(interrupted?.state == FileOperationState.COMPLETED)
        assertEquals(null, interrupted?.completedAtMillis)
        assertEquals(ReplacePhase.COMMITTING, interrupted?.items?.single()?.replacePhase)
        assertTrue(interrupted?.items?.single()?.replaceBackupReference != null)
        assertTrue(interrupted?.items?.single()?.resultReference != null)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertTrue(fixture.provider.rawHasHiddenTransferArtifacts())

        fixture.provider.metadataSizeOverride = null
        fixture.controller.resume(fixture.id)
        fixture.engine.runAvailable()

        val completed = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.COMPLETED, completed?.state)
        assertEquals(ReplacePhase.NONE, completed?.items?.single()?.replacePhase)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun finishFromItemsCannotCompleteWithRunningItem() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/running.bin", byteArrayOf(1, 2, 3, 4))
        }
        val store = SafetyOperationStore()
        val source = provider.source("/src/running.bin")
        val item = OperationItem(
            id = "running:0",
            source = source,
            state = OperationItemState.RUNNING,
            destinationRelativePath = source.name,
        )
        val operation = FileOperation(
            id = "running",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(item),
            destination = provider.location("/dest"),
            createdAtMillis = 1L,
        )
        store.enqueue(operation)
        val engine = FileOperationEngine(store, provider, now = { 2L })

        engine.finishFromItems(operation)

        val saved = store.get(operation.id)
        assertEquals(FileOperationState.INTERRUPTED, saved?.state)
        assertEquals(null, saved?.completedAtMillis)
        assertEquals(OperationItemState.RUNNING, saved?.items?.single()?.state)
        assertFalse(saved?.state == FileOperationState.COMPLETED)
    }

    @Test
    fun finishFromItemsCannotCompleteWithUnresolvedReplaceLedger() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/holiday.mp4", newHoliday)
        }
        val store = SafetyOperationStore()
        val source = provider.source("/src/holiday.mp4")
        val item = OperationItem(
            id = "replace-unresolved:0",
            source = source,
            state = OperationItemState.COMPLETED,
            destinationRelativePath = source.name,
            replacePhase = ReplacePhase.COMMITTING,
            replaceFinalName = "holiday.mp4",
            replaceBackupName = ".zzreplace-backup-test",
        )
        val operation = FileOperation(
            id = "replace-unresolved",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(item),
            destination = provider.location("/dest"),
            createdAtMillis = 1L,
        )
        store.enqueue(operation)
        val engine = FileOperationEngine(store, provider, now = { 2L })

        engine.finishFromItems(operation)

        val saved = store.get(operation.id)
        assertEquals(FileOperationState.INTERRUPTED, saved?.state)
        assertEquals(null, saved?.completedAtMillis)
        assertEquals(ReplacePhase.COMMITTING, saved?.items?.single()?.replacePhase)
        assertEquals(".zzreplace-backup-test", saved?.items?.single()?.replaceBackupName)
        assertFalse(saved?.state == FileOperationState.COMPLETED)
    }

    @Test
    fun replaceProcessDeathAfterStagedToFinalMutationBeforeCommittedJournalSave() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzpart-") && to == "holiday.mp4" },
            afterMutation = true,
            throwable = CancellationException("simulated process death after staged-to-final rename"),
        )

        fixture.controller.resolveCollision(fixture.id, CollisionPolicy.REPLACE, applyToAll = false)
        var interruptedByProcessDeath = false
        try {
            fixture.engine.runAvailable()
        } catch (_: CancellationException) {
            interruptedByProcessDeath = true
        }

        assertTrue("Failure injection must hit the staged-to-final mutation window", interruptedByProcessDeath)
        val journaled = fixture.store.get(fixture.id)
        assertEquals(ReplacePhase.COMMITTING, journaled?.items?.single()?.replacePhase)
        assertTrue(journaled?.items?.single()?.partialOutput != null)
        assertTrue(journaled?.items?.single()?.replaceBackupReference != null)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertTrue(fixture.provider.rawHasHiddenTransferArtifacts())

        fixture.provider.renameFaults.clear()
        fixture.store.forceState(fixture.id, FileOperationState.INTERRUPTED)
        fixture.controller.resume(fixture.id)
        fixture.engine.runAvailable()

        val completed = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.COMPLETED, completed?.state)
        assertEquals(ReplacePhase.NONE, completed?.items?.single()?.replacePhase)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    private suspend fun replaceFixture(): ReplaceFixture {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/holiday.mp4", newHoliday)
            file("/dest/holiday.mp4", oldHoliday)
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/src/holiday.mp4")), provider.location("/dest"))
        val engine = FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L)
        engine.runAvailable()
        assertEquals(FileOperationState.WAITING_FOR_USER, store.get(id)?.state)
        assertArrayEquals(oldHoliday, provider.rawBytes("/dest/holiday.mp4"))
        return ReplaceFixture(provider, store, controller, engine, id)
    }

    private suspend fun batchFixture(): BatchFixture {
        val provider = SafetyProvider().apply {
            directory("/batch")
            file("/batch/A.txt", byteArrayOf(1))
            file("/batch/B.txt", byteArrayOf(2))
            file("/batch/C.txt", byteArrayOf(3))
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val sources = listOf(
            provider.source("/batch/A.txt"),
            provider.source("/batch/B.txt"),
            provider.source("/batch/C.txt"),
        )
        val proposed = mapOf(
            sources[0].reference.opaqueId to "NewA.txt",
            sources[1].reference.opaqueId to "NewB.txt",
            sources[2].reference.opaqueId to "NewC.txt",
        )
        val id = controller.enqueueBatchRename(sources, provider.location("/batch"), proposed)
        return BatchFixture(
            provider = provider,
            store = store,
            controller = controller,
            engine = FileOperationEngine(store, provider),
            id = id,
        )
    }

    private data class ReplaceFixture(
        val provider: SafetyProvider,
        val store: SafetyOperationStore,
        val controller: FileOperationController,
        val engine: FileOperationEngine,
        val id: String,
    ) {
        suspend fun resolveReplaceAndRun() {
            controller.resolveCollision(id, CollisionPolicy.REPLACE, applyToAll = false)
            engine.runAvailable()
        }
    }

    private data class BatchFixture(
        val provider: SafetyProvider,
        val store: SafetyOperationStore,
        val controller: FileOperationController,
        val engine: FileOperationEngine,
        val id: String,
    ) {
        suspend fun runUntilProcessInterruptionThenResume() {
            var interrupted = false
            try {
                engine.runAvailable()
            } catch (_: CancellationException) {
                interrupted = true
            }
            assertTrue("Failure injection must reach the intended process-death window", interrupted)
            provider.renameFaults.clear()
            store.forceState(id, FileOperationState.INTERRUPTED)
            controller.resume(id)
            engine.runAvailable()
            assertEquals(FileOperationState.COMPLETED, store.get(id)?.state)
            assertFalse(store.get(id)?.batchRenameRollbackRequired == true)
        }

        fun assertOriginalNamesOnly() {
            assertTrue(provider.rawHas("/batch/A.txt"))
            assertTrue(provider.rawHas("/batch/B.txt"))
            assertTrue(provider.rawHas("/batch/C.txt"))
            assertFalse(provider.rawHas("/batch/NewA.txt"))
            assertFalse(provider.rawHas("/batch/NewB.txt"))
            assertFalse(provider.rawHas("/batch/NewC.txt"))
            assertFalse(provider.rawHasHiddenRenameArtifacts())
        }

        fun assertFinalNamesOnly() {
            assertFalse(provider.rawHas("/batch/A.txt"))
            assertFalse(provider.rawHas("/batch/B.txt"))
            assertFalse(provider.rawHas("/batch/C.txt"))
            assertTrue(provider.rawHas("/batch/NewA.txt"))
            assertTrue(provider.rawHas("/batch/NewB.txt"))
            assertTrue(provider.rawHas("/batch/NewC.txt"))
            assertFalse(provider.rawHasHiddenRenameArtifacts())
        }
    }
}

private data class RenameFault(
    val predicate: (fromName: String, toName: String) -> Boolean,
    val afterMutation: Boolean,
    val throwable: Throwable,
)

private class SafetyOperationStore : OperationStore {
    private val state = MutableStateFlow<List<FileOperation>>(emptyList())
    override val operations: StateFlow<List<FileOperation>> = state

    override suspend fun initialize() = Unit

    override suspend fun enqueue(operation: FileOperation) {
        state.value = state.value + operation
    }

    override suspend fun get(id: String): FileOperation? = state.value.firstOrNull { it.id == id }

    override suspend fun save(operation: FileOperation) {
        state.value = state.value.toMutableList().apply {
            val index = indexOfFirst { it.id == operation.id }
            if (index >= 0) set(index, operation) else add(operation)
        }
    }

    override suspend fun nextRunnable(): FileOperation? =
        state.value.firstOrNull { it.state == FileOperationState.QUEUED }

    override suspend fun prune(nowMillis: Long) = Unit

    fun forceState(id: String, newState: FileOperationState) {
        state.value = state.value.map { operation ->
            if (operation.id == id) operation.copy(state = newState) else operation
        }
    }
}

private class SafetyProvider : WritableStorageProvider, StorageProviderRegistry {
    private data class Node(
        var path: String,
        val directory: Boolean,
        var data: ByteArray = ByteArray(0),
        var logicalSize: Long? = null,
    )

    private val nodes = linkedMapOf<String, Node>()
    override val id: String = "safety"
    var freeSpace: Long? = Long.MAX_VALUE
    var advertisedCapabilities: Set<StorageCapability> = setOf(
        StorageCapability.READ,
        StorageCapability.WRITE,
        StorageCapability.CREATE_FILE,
        StorageCapability.CREATE_DIRECTORY,
        StorageCapability.DELETE,
        StorageCapability.RENAME,
    )
    var nativeMoveEnabled: Boolean = false
    var nativeMoveCount: Int = 0
    var failWriteAfterBytes: Int? = null
    var failReadAfterBytes: Int? = null
    var disappearAfterFirstWrite: Boolean = false
    var onFirstWrite: (() -> Unit)? = null
    var metadataSizeOverride: ((String, Long) -> Long)? = null
    val renameFaults = mutableListOf<RenameFault>()
    private var available: Boolean = true

    init {
        directory("/")
    }

    fun directory(path: String) {
        val normalized = normalize(path)
        nodes[normalized] = Node(normalized, directory = true)
    }

    fun file(path: String, data: ByteArray, logicalSize: Long? = null) {
        val normalized = normalize(path)
        nodes[normalized] = Node(normalized, directory = false, data = data.copyOf(), logicalSize = logicalSize)
    }

    fun source(path: String): OperationSource {
        val node = nodes.getValue(normalize(path))
        return OperationSource(
            reference = reference(node),
            rootReference = "/",
            storageId = id,
            name = node.path.substringAfterLast('/').ifBlank { "root" },
            isDirectory = node.directory,
            sizeBytes = if (node.directory) null else node.logicalSize ?: node.data.size.toLong(),
            modifiedAtMillis = 1L,
            mimeType = if (node.directory) null else "application/octet-stream",
        )
    }

    fun location(path: String): BrowserLocation {
        val normalized = normalize(path)
        return BrowserLocation(
            providerId = id,
            id = "$id:$normalized",
            displayName = normalized.substringAfterLast('/').ifBlank { "root" },
            reference = normalized,
            rootReference = "/",
            storageId = id,
            readable = true,
            writable = true,
        )
    }

    fun rawHas(path: String): Boolean = normalize(path) in nodes
    fun rawBytes(path: String): ByteArray = nodes.getValue(normalize(path)).data.copyOf()
    fun rawHasHiddenTransferArtifacts(): Boolean = nodes.keys.any {
        val name = it.substringAfterLast('/')
        name.startsWith(".zzpart-") || name.startsWith(".zzreplace-backup-")
    }
    fun rawHasHiddenRenameArtifacts(): Boolean = nodes.keys.any { it.substringAfterLast('/').startsWith(".zzrename-") }

    override fun providerFor(providerId: String): StorageProvider = this
    override fun writableProviderFor(providerId: String): WritableStorageProvider? = this.takeIf { providerId == id }

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
        ensureAvailable()
        val parent = normalize(location.reference)
        return nodes.values
            .filter { it.path != parent && parentOf(it.path) == parent }
            .map(::entry)
    }

    override suspend fun getMetadata(item: FileReference): FileEntry? {
        ensureAvailable()
        return node(item)?.let(::entry)
    }

    override suspend fun openInputStream(item: FileReference): InputStream {
        ensureAvailable()
        val node = node(item) ?: throw StorageAccessException.Unavailable()
        val source = ByteArrayInputStream(node.data)
        val failureAt = failReadAfterBytes
        if (failureAt == null) return source
        return object : InputStream() {
            private var consumed = 0
            override fun read(): Int {
                if (consumed >= failureAt) throw IOException("simulated source read failure")
                val value = source.read()
                if (value >= 0) consumed++
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (consumed >= failureAt) throw IOException("simulated source read failure")
                val allowed = minOf(length, failureAt - consumed)
                val read = source.read(buffer, offset, allowed)
                if (read > 0) consumed += read
                return read
            }
        }
    }

    override suspend fun exists(item: FileReference): Boolean {
        ensureAvailable()
        return node(item) != null
    }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? =
        if (normalize(location.reference) == "/") null else location(parentOf(location.reference))

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> =
        listOf(Breadcrumb(location.displayName, location))

    override suspend fun capabilities(location: BrowserLocation): ProviderCapabilities {
        ensureAvailable()
        return ProviderCapabilities(advertisedCapabilities)
    }

    override suspend fun createDirectory(parent: BrowserLocation, name: String): FileEntry {
        ensureAvailable()
        val path = childPath(parent.reference, name)
        if (path in nodes) throw IOException("destination exists")
        directory(path)
        return entry(nodes.getValue(path))
    }

    override suspend fun createFile(parent: BrowserLocation, name: String, mimeType: String?): FileEntry {
        ensureAvailable()
        val path = childPath(parent.reference, name)
        if (path in nodes) throw IOException("destination exists")
        file(path, ByteArray(0))
        return entry(nodes.getValue(path))
    }

    override suspend fun delete(item: ScopedFileReference): Boolean {
        ensureAvailable()
        val path = scopedPath(item)
        if (nodes.values.any { parentOf(it.path) == path }) throw IOException("directory not empty")
        return nodes.remove(path) != null
    }

    override suspend fun rename(item: ScopedFileReference, newName: String): FileEntry {
        ensureAvailable()
        val oldPath = scopedPath(item)
        val oldName = oldPath.substringAfterLast('/')
        val beforeFaultIndex = renameFaults.indexOfFirst {
            !it.afterMutation && it.predicate(oldName, newName)
        }
        if (beforeFaultIndex >= 0) {
            throw renameFaults.removeAt(beforeFaultIndex).throwable
        }

        val moving = nodes[oldPath] ?: throw IOException("missing source")
        val newPath = childPath(parentOf(oldPath), newName)
        if (newPath != oldPath && newPath in nodes) throw IOException("destination exists")

        val descendants = nodes.values.filter { it.path.startsWith("$oldPath/") }.toList()
        nodes.remove(oldPath)
        descendants.forEach { nodes.remove(it.path) }
        moving.path = newPath
        nodes[newPath] = moving
        descendants.forEach { child ->
            child.path = newPath + child.path.removePrefix(oldPath)
            nodes[child.path] = child
        }

        val afterFaultIndex = renameFaults.indexOfFirst {
            it.afterMutation && it.predicate(oldName, newName)
        }
        if (afterFaultIndex >= 0) {
            throw renameFaults.removeAt(afterFaultIndex).throwable
        }
        return entry(moving)
    }

    override suspend fun openOutputStream(item: ScopedFileReference, truncate: Boolean): OutputStream {
        ensureAvailable()
        val destination = node(item.reference) ?: throw StorageAccessException.Unavailable()
        return object : ByteArrayOutputStream() {
            private var written = 0
            private var firstWrite = true

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                val limit = failWriteAfterBytes
                if (limit != null && written >= limit) throw IOException("simulated destination write failure")
                val allowed = if (limit == null) length else minOf(length, limit - written)
                if (allowed > 0) {
                    super.write(buffer, offset, allowed)
                    written += allowed
                }
                if (firstWrite) {
                    firstWrite = false
                    onFirstWrite?.let { callback ->
                        onFirstWrite = null
                        callback()
                    }
                    if (disappearAfterFirstWrite) {
                        available = false
                    }
                }
                if (allowed < length) throw IOException("simulated destination write failure")
                if (!available) throw StorageAccessException.Unavailable()
            }

            override fun close() {
                destination.data = toByteArray()
                destination.logicalSize = destination.data.size.toLong()
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
        val sourcePath = scopedPath(source)
        val destinationPath = normalize(destination.reference)
        return destinationPath == sourcePath || destinationPath.startsWith("$sourcePath/")
    }

    override suspend fun canMoveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): Boolean {
        ensureAvailable()
        if (!nativeMoveEnabled || StorageCapability.MOVE_NATIVE !in advertisedCapabilities) return false
        return childPath(destination.reference, newName) !in nodes
    }

    override suspend fun moveNative(item: ScopedFileReference, destination: BrowserLocation, newName: String): FileEntry? {
        ensureAvailable()
        if (!canMoveNative(item, destination, newName)) return null
        val oldPath = scopedPath(item)
        val moving = nodes.remove(oldPath) ?: return null
        val newPath = childPath(destination.reference, newName)
        moving.path = newPath
        nodes[newPath] = moving
        nativeMoveCount++
        return entry(moving)
    }

    private fun ensureAvailable() {
        if (!available) throw StorageAccessException.Unavailable()
    }

    private fun node(reference: FileReference): Node? =
        nodes[normalize(reference.path ?: reference.opaqueId.removePrefix("$id:"))]

    private fun scopedPath(item: ScopedFileReference): String =
        normalize(item.reference.path ?: item.reference.opaqueId.removePrefix("$id:"))

    private fun reference(node: Node) = FileReference(
        providerId = id,
        opaqueId = "$id:${node.path}",
        path = node.path,
    )

    private fun entry(node: Node) = FileEntry(
        id = "$id:${node.path}",
        reference = reference(node),
        name = node.path.substringAfterLast('/').ifBlank { "root" },
        extension = if (node.directory) null else node.path.substringAfterLast('.').takeIf { '.' in node.path },
        mimeType = if (node.directory) null else "application/octet-stream",
        type = if (node.directory) FileEntryType.DIRECTORY else FileEntryType.GENERIC,
        sizeBytes = if (node.directory) {
            null
        } else {
            val actualSize = node.logicalSize ?: node.data.size.toLong()
            metadataSizeOverride?.invoke(node.path, actualSize) ?: actualSize
        },
        modifiedAtMillis = 1L,
        createdAtMillis = null,
        isHidden = node.path.substringAfterLast('/').startsWith('.'),
        isReadable = true,
        isWritable = true,
        childCount = null,
        storageId = id,
        thumbnailKey = null,
    )

    private fun normalize(path: String): String =
        if (path == "/") "/" else "/" + path.trim('/').replace("//", "/")

    private fun parentOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/', "").ifBlank { "/" }
    }

    private fun childPath(parent: String, name: String): String =
        normalize(if (normalize(parent) == "/") "/$name" else "${normalize(parent)}/$name")
}
