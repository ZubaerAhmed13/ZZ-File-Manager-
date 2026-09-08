package com.zz.filemanager

import android.Manifest
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationEngine
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.OperationExecutionHost
import com.zz.filemanager.core.operation.OperationSource
import com.zz.filemanager.core.operation.OperationStore
import com.zz.filemanager.core.storage.SafStorageProvider
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafStorageProviderInstrumentationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var provider: SafStorageProvider
    private lateinit var root: BrowserLocation

    @Before
    fun setUp() {
        // DocumentsProvider is correctly protected with MANAGE_DOCUMENTS. Instrumentation adopts
        // the shell identity only for this test, matching the system document UI's privileged access.
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)

        // Setup is intentionally strict: every test starts from a deterministic empty provider tree.
        // The provider owns the root; preserve it and clear its children rather than deleting the root.
        resetBackingRootContents()

        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Step2TestDocumentsProvider.AUTHORITY,
            Step2TestDocumentsProvider.ROOT_ID,
        )
        root = BrowserLocation(
            providerId = SafStorageProvider.ID,
            id = "saf:${Step2TestDocumentsProvider.ROOT_ID}",
            displayName = "Step 2 SAF Test",
            reference = treeUri.toString(),
            rootReference = treeUri.toString(),
            storageId = "saf:step2-test",
            readable = true,
            writable = true,
        )
        provider = SafStorageProvider(instrumentation.targetContext)
    }

    @After
    fun tearDown() {
        try {
            // Cleanup after successful assertions is deliberately best-effort. Android may tear down
            // the instrumentation provider/cache root before JUnit @After runs. The next test's strict
            // setUp performs the real isolation check, so teardown must never turn a passing SAF test
            // into a false failure merely because the test process is already being dismantled.
            clearBackingRootContentsIfPresent()
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun productionSafProviderSupportsTreeCrudNavigationAndAncestry() = runBlocking {
        assertTrue(provider.listChildren(root).isEmpty())

        val sourceDirectory = provider.createDirectory(root, "source")
        val sourceLocation = locationFor(sourceDirectory)
        val nestedDirectory = provider.createDirectory(sourceLocation, "nested")
        val nestedLocation = locationFor(nestedDirectory)
        val created = provider.createFile(sourceLocation, "report.txt", "text/plain")
        val createdScope = scope(created)
        val payload = "real SAF provider instrumentation".encodeToByteArray()

        provider.openOutputStream(createdScope, truncate = true).use { it.write(payload) }

        val refreshed = provider.getMetadata(created.reference)
        assertNotNull(refreshed)
        assertEquals(payload.size.toLong(), refreshed?.sizeBytes)
        assertArrayEquals(payload, provider.openInputStream(created.reference).use { it.readBytes() })
        assertEquals("report.txt", provider.findChild(sourceLocation, "report.txt")?.name)
        assertTrue(provider.listChildren(sourceLocation).any { it.name == "report.txt" })

        val renamed = provider.rename(createdScope, "renamed.txt")
        assertFalse(provider.exists(created.reference))
        assertTrue(provider.exists(renamed.reference))
        assertEquals("renamed.txt", provider.findChild(sourceLocation, "renamed.txt")?.name)

        val sourceScope = ScopedFileReference(sourceDirectory.reference, root.rootReference, root.storageId)
        assertTrue(provider.isSameOrDescendant(sourceScope, sourceLocation))
        assertTrue(provider.isSameOrDescendant(sourceScope, nestedLocation))
        assertFalse(provider.isSameOrDescendant(scope(nestedDirectory), sourceLocation))

        val resolvedParent = provider.resolveParent(nestedLocation)
        assertNotNull(resolvedParent)
        assertEquals(sourceLocation.reference, resolvedParent?.reference)
        val breadcrumbs = provider.breadcrumbs(nestedLocation)
        assertEquals(3, breadcrumbs.size)
        assertEquals("nested", breadcrumbs.last().label)

        assertTrue(provider.delete(scope(renamed)))
        assertFalse(provider.exists(renamed.reference))
        assertTrue(provider.delete(scope(nestedDirectory)))
        assertTrue(provider.delete(sourceScope))
        assertTrue(provider.listChildren(root).isEmpty())
    }

    @Test
    fun fileOperationEngineCopiesAndMovesAcrossRealSafTreeLocations() = runBlocking {
        val sourceDirectory = provider.createDirectory(root, "source")
        val destinationDirectory = provider.createDirectory(root, "destination")
        val sourceLocation = locationFor(sourceDirectory)
        val destinationLocation = locationFor(destinationDirectory)
        val registry = SingleSafRegistry(provider)
        val store = InstrumentedOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val engine = FileOperationEngine(store, registry, bufferSize = 32 * 1024, progressIntervalMillis = 0L)

        val copyPayload = ByteArray(384 * 1024) { index -> (index % 251).toByte() }
        val copyCreated = provider.createFile(sourceLocation, "copy.bin", "application/octet-stream")
        provider.openOutputStream(scope(copyCreated), truncate = true).use { it.write(copyPayload) }
        val copySource = provider.getMetadata(copyCreated.reference)?.toOperationSource()
            ?: error("SAF source metadata disappeared")

        val copyId = controller.enqueueCopy(listOf(copySource), destinationLocation)
        engine.runAvailable()

        assertEquals(FileOperationState.COMPLETED, store.get(copyId)?.state)
        val copied = provider.findChild(destinationLocation, "copy.bin") ?: error("copy output missing")
        assertArrayEquals(copyPayload, provider.openInputStream(copied.reference).use { it.readBytes() })
        assertTrue(provider.exists(copyCreated.reference))
        assertTrue(provider.listChildren(destinationLocation).none { it.name.startsWith(".zzpart-") })

        val movePayload = ByteArray(192 * 1024) { index -> (255 - (index % 255)).toByte() }
        val moveCreated = provider.createFile(sourceLocation, "move.bin", "application/octet-stream")
        provider.openOutputStream(scope(moveCreated), truncate = true).use { it.write(movePayload) }
        val moveSource = provider.getMetadata(moveCreated.reference)?.toOperationSource()
            ?: error("SAF move source metadata disappeared")

        val moveId = controller.enqueueMove(listOf(moveSource), destinationLocation)
        engine.runAvailable()

        assertEquals(FileOperationState.COMPLETED, store.get(moveId)?.state)
        val moved = provider.findChild(destinationLocation, "move.bin") ?: error("move output missing")
        assertArrayEquals(movePayload, provider.openInputStream(moved.reference).use { it.readBytes() })
        assertFalse(provider.exists(moveCreated.reference))
        assertTrue(provider.listChildren(destinationLocation).none { it.name.startsWith(".zzpart-") })
    }

    private fun resetBackingRootContents() {
        val backingRoot = backingRoot()
        check(backingRoot.isDirectory || backingRoot.mkdirs()) {
            "Could not prepare deterministic SAF test root"
        }
        backingRoot.listFiles().orEmpty().forEach { child ->
            check(child.deleteRecursively()) {
                "Could not clear deterministic SAF test child: ${child.name}"
            }
        }
    }

    private fun clearBackingRootContentsIfPresent() {
        val backingRoot = backingRoot()
        if (!backingRoot.isDirectory) return
        backingRoot.listFiles().orEmpty().forEach { child ->
            child.deleteRecursively()
        }
    }

    private fun backingRoot() =
        File(instrumentation.context.cacheDir, Step2TestDocumentsProvider.ROOT_DIRECTORY_NAME)

    private fun FileEntry.toOperationSource() = OperationSource(
        reference = reference,
        rootReference = root.rootReference,
        storageId = root.storageId,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        mimeType = mimeType,
        isSymbolicLink = isSymbolicLink,
    )

    private fun locationFor(entry: FileEntry) = BrowserLocation(
        providerId = SafStorageProvider.ID,
        id = entry.reference.opaqueId,
        displayName = entry.name,
        reference = entry.reference.uri ?: error("SAF entry missing URI"),
        rootReference = root.rootReference,
        storageId = root.storageId,
        readable = entry.isReadable,
        writable = entry.isWritable,
    )

    private fun scope(entry: FileEntry) = ScopedFileReference(entry.reference, root.rootReference, root.storageId)
}

private class SingleSafRegistry(
    private val provider: SafStorageProvider,
) : StorageProviderRegistry {
    override fun providerFor(providerId: String): StorageProvider {
        require(providerId == SafStorageProvider.ID)
        return provider
    }

    override fun writableProviderFor(providerId: String): WritableStorageProvider? =
        provider.takeIf { providerId == SafStorageProvider.ID }
}

private class InstrumentedOperationStore : OperationStore {
    private val state = MutableStateFlow<List<com.zz.filemanager.core.operation.FileOperation>>(emptyList())
    override val operations: StateFlow<List<com.zz.filemanager.core.operation.FileOperation>> = state

    override suspend fun initialize() = Unit

    override suspend fun enqueue(operation: com.zz.filemanager.core.operation.FileOperation) {
        state.value = state.value + operation
    }

    override suspend fun get(id: String): com.zz.filemanager.core.operation.FileOperation? =
        state.value.firstOrNull { it.id == id }

    override suspend fun save(operation: com.zz.filemanager.core.operation.FileOperation) {
        state.value = state.value.toMutableList().apply {
            val index = indexOfFirst { it.id == operation.id }
            if (index >= 0) set(index, operation) else add(operation)
        }
    }

    override suspend fun nextRunnable(): com.zz.filemanager.core.operation.FileOperation? =
        state.value.firstOrNull { it.state == FileOperationState.QUEUED }

    override suspend fun prune(nowMillis: Long) = Unit
}
