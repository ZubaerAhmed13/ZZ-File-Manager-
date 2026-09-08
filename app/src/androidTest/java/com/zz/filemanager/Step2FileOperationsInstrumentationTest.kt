package com.zz.filemanager

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationEngine
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.OperationExecutionHost
import com.zz.filemanager.core.operation.OperationStore
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.LocalStorageProvider
import com.zz.filemanager.core.storage.StorageRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step2FileOperationsInstrumentationTest {
    @Test
    fun localProvider_createCopyMoveRenameDeleteNested() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "step2-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val sourceDir = File(root, "source").apply { mkdirs() }
            val destinationDir = File(root, "destination").apply { mkdirs() }
            val original = File(sourceDir, "alpha.txt").apply { writeText("alpha") }
            val storage = StorageRepository(context, PreferencesRepository(context))
            val store = AndroidTestOperationStore()
            val controller = FileOperationController(store, OperationExecutionHost {})
            val engine = FileOperationEngine(store, storage)
            val provider = storage.providerFor(LocalStorageProvider.ID)
            val rootLocation = location(root, root)
            val destinationLocation = location(destinationDir, root)
            val originalEntry = requireNotNull(provider.getMetadata(FileReference(LocalStorageProvider.ID, "local:${original.absolutePath}", path = original.absolutePath)))

            val copyId = controller.enqueueCopy(listOf(controller.source(originalEntry, rootLocation)), destinationLocation)
            engine.runAvailable()
            assertEquals(FileOperationState.COMPLETED, store.get(copyId)?.state)
            val copied = File(destinationDir, "alpha.txt")
            assertTrue(copied.exists())
            assertEquals("alpha", copied.readText())

            val copiedEntry = requireNotNull(provider.getMetadata(FileReference(LocalStorageProvider.ID, "local:${copied.absolutePath}", path = copied.absolutePath)))
            val moveTarget = File(root, "move-target").apply { mkdirs() }
            val moveId = controller.enqueueMove(listOf(controller.source(copiedEntry, destinationLocation)), location(moveTarget, root))
            engine.runAvailable()
            assertEquals(FileOperationState.COMPLETED, store.get(moveId)?.state)
            assertFalse(copied.exists())
            val moved = File(moveTarget, "alpha.txt")
            assertTrue(moved.exists())

            val movedEntry = requireNotNull(provider.getMetadata(FileReference(LocalStorageProvider.ID, "local:${moved.absolutePath}", path = moved.absolutePath)))
            val renameId = controller.enqueueRename(controller.source(movedEntry, location(moveTarget, root)), location(moveTarget, root), "renamed.txt")
            engine.runAvailable()
            assertEquals(FileOperationState.COMPLETED, store.get(renameId)?.state)
            val renamed = File(moveTarget, "renamed.txt")
            assertTrue(renamed.exists())

            val nested = File(root, "nested/a/b").apply { mkdirs() }
            File(nested, "payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val nestedRoot = File(root, "nested")
            val nestedEntry = requireNotNull(provider.getMetadata(FileReference(LocalStorageProvider.ID, "local:${nestedRoot.absolutePath}", path = nestedRoot.absolutePath)))
            val deleteId = controller.enqueueDelete(listOf(controller.source(nestedEntry, rootLocation)))
            engine.runAvailable()
            assertEquals(FileOperationState.COMPLETED, store.get(deleteId)?.state)
            assertFalse(nestedRoot.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun location(directory: File, root: File) = BrowserLocation(
        providerId = LocalStorageProvider.ID,
        id = "local:${directory.absolutePath}",
        displayName = directory.name.ifBlank { "root" },
        reference = directory.absolutePath,
        rootReference = root.absolutePath,
        storageId = "instrumentation",
        readable = true,
        writable = true,
    )
}

private class AndroidTestOperationStore : OperationStore {
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
