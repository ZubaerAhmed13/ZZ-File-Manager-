package com.zz.filemanager

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.model.SortConfiguration
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.operation.CollisionKind
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.operation.FileOperation
import com.zz.filemanager.core.operation.FileOperationController
import com.zz.filemanager.core.operation.FileOperationState
import com.zz.filemanager.core.operation.FileOperationType
import com.zz.filemanager.core.operation.OperationClipboardRepository
import com.zz.filemanager.core.operation.OperationExecutionHost
import com.zz.filemanager.core.operation.OperationItem
import com.zz.filemanager.core.operation.OperationSource
import com.zz.filemanager.core.operation.OperationStore
import com.zz.filemanager.core.operation.PendingCollision
import com.zz.filemanager.core.preferences.BrowserPreferences
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.BrowserStorage
import com.zz.filemanager.core.storage.StorageRepository
import com.zz.filemanager.core.util.ThumbnailRepository
import com.zz.filemanager.feature.browser.BrowserOperationsViewModel
import com.zz.filemanager.feature.browser.BrowserScreen
import com.zz.filemanager.feature.browser.BrowserViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step2BrowserUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearClipboardBeforeTest() {
        OperationClipboardRepository(context).clear()
    }

    @After
    fun clearClipboardAfterTest() {
        OperationClipboardRepository(context).clear()
    }

    @Test
    fun selectionCountActionsSelectAllClearAndPasteBarAreVisible() {
        val fixture = fixture()
        fixture.render()

        longPress("alpha.txt")
        composeRule.onNodeWithText("1 selected").assertExists()
        composeRule.onNodeWithText("Copy").assertExists()
        // The CUT clipboard action is intentionally surfaced to users as "Move".
        composeRule.onNodeWithText("Move").assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
        composeRule.onNodeWithText("More").assertExists()

        composeRule.onNodeWithText("beta.txt").performClick()
        composeRule.onNodeWithText("2 selected").assertExists()

        composeRule.onNodeWithContentDescription("Select all").performClick()
        composeRule.onNodeWithText("3 selected").assertExists()
        composeRule.onNodeWithContentDescription("Clear selection").performClick()
        composeRule.onNodeWithText("3 selected").assertDoesNotExist()

        longPress("alpha.txt")
        composeRule.onNodeWithText("Copy").performClick()
        composeRule.onNodeWithText("1 items ready to copy").assertExists()
        composeRule.onNodeWithText("Paste").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun destructiveDeleteRenameAndBatchRenameDialogsAreReachableFromSelection() {
        val fixture = fixture()
        fixture.render()

        longPress("alpha.txt")
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete permanently").assertExists()
        composeRule.onNodeWithText("Delete “alpha.txt” permanently?").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNodeWithText("Rename").assertExists()
        composeRule.onNodeWithText("Name").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("beta.txt").performClick()
        composeRule.onNodeWithText("2 selected").assertExists()
        composeRule.onNodeWithText("More").performClick()
        composeRule.onNodeWithText("Batch rename").performClick()
        composeRule.onNodeWithText("Batch rename").assertExists()
        composeRule.onNodeWithText("Find").assertExists()
        composeRule.onNodeWithText("Replace with").assertExists()
        composeRule.onNodeWithText("Preview").assertExists()
    }

    @Test
    fun pendingCollisionShowsProfessionalConflictChoices() {
        val fixture = fixture()
        fixture.store.seed(waitingCollisionOperation(fixture.location))
        fixture.render()

        composeRule.onNodeWithText("File conflict").assertExists()
        composeRule.onNodeWithText("Replace").assertExists()
        composeRule.onNodeWithText("Keep both").assertExists()
        composeRule.onNodeWithText("Skip").assertExists()
        composeRule.onNodeWithText("Apply to all compatible conflicts").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun operationSheetShowsCurrentItemProgressAndPauseCancelControls() {
        val fixture = fixture()
        fixture.store.seed(runningOperation(fixture.location))
        fixture.render()

        composeRule.onNodeWithContentDescription("File operations").performClick()
        composeRule.onNodeWithText("File operations").assertExists()
        composeRule.onNodeWithText("Copying files").assertExists()
        composeRule.onNodeWithText("Running").assertExists()
        composeRule.onNodeWithText("alpha.txt").assertExists()
        composeRule.onNodeWithText("Pause").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    private fun longPress(name: String) {
        composeRule.onNodeWithText(name).performTouchInput { longClick() }
    }

    private fun fixture(): UiFixture {
        val location = BrowserLocation(
            providerId = "ui",
            id = "ui-root",
            displayName = "Test folder",
            reference = "/test",
            rootReference = "/test",
            storageId = "ui-test",
            readable = true,
            writable = true,
        )
        val entries = listOf(
            file("alpha.txt", "alpha"),
            file("beta.txt", "beta"),
            file("gamma.txt", "gamma"),
        )
        val browserStorage = UiBrowserStorage(location, entries)
        val browserPreferences = UiBrowserPreferences()
        val browserViewModel = BrowserViewModel(browserStorage, browserPreferences)
        val store = UiOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val clipboard = OperationClipboardRepository(context)
        val storageRepository = StorageRepository(context, PreferencesRepository(context))
        val operationsViewModel = BrowserOperationsViewModel(controller, clipboard, storageRepository)
        return UiFixture(location, browserViewModel, operationsViewModel, store, ThumbnailRepository(context))
    }

    private inner class UiFixture(
        val location: BrowserLocation,
        val browserViewModel: BrowserViewModel,
        val operationsViewModel: BrowserOperationsViewModel,
        val store: UiOperationStore,
        val thumbnails: ThumbnailRepository,
    ) {
        fun render() {
            composeRule.setContent {
                MaterialTheme {
                    BrowserScreen(
                        viewModel = browserViewModel,
                        operationsViewModel = operationsViewModel,
                        initialLocation = location,
                        thumbnails = thumbnails,
                        onExitBrowser = {},
                        onRequestStorageAccess = {},
                    )
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("alpha.txt").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun file(name: String, id: String) = FileEntry(
        id = id,
        reference = FileReference("ui", "ui:$id", path = "/test/$name"),
        name = name,
        extension = "txt",
        mimeType = "text/plain",
        type = FileEntryType.TEXT,
        sizeBytes = 32L,
        modifiedAtMillis = 1L,
        createdAtMillis = null,
        isHidden = false,
        isReadable = true,
        isWritable = true,
        childCount = null,
        storageId = "ui-test",
        thumbnailKey = null,
    )

    private fun waitingCollisionOperation(location: BrowserLocation): FileOperation {
        val source = operationSource("alpha.txt", "alpha")
        val item = OperationItem("collision:0", source, destinationRelativePath = source.name)
        val collision = PendingCollision(
            id = "collision-id",
            itemId = item.id,
            sourceName = source.name,
            destinationName = source.name,
            kind = CollisionKind.FILE_TO_FILE,
            allowedPolicies = setOf(CollisionPolicy.REPLACE, CollisionPolicy.KEEP_BOTH, CollisionPolicy.SKIP),
        )
        return FileOperation(
            id = "collision",
            type = FileOperationType.COPY,
            state = FileOperationState.WAITING_FOR_USER,
            items = listOf(item),
            destination = location,
            createdAtMillis = 1L,
            pendingCollision = collision,
            currentItemName = source.name,
        )
    }

    private fun runningOperation(location: BrowserLocation): FileOperation {
        val source = operationSource("alpha.txt", "alpha")
        val item = OperationItem(
            id = "running:0",
            source = source,
            state = com.zz.filemanager.core.operation.OperationItemState.RUNNING,
            destinationRelativePath = source.name,
            processedBytes = 32L,
        )
        return FileOperation(
            id = "running",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(item),
            destination = location,
            createdAtMillis = 2L,
            totalBytes = 64L,
            processedBytes = 32L,
            totalItems = 1L,
            currentItemName = source.name,
        )
    }

    private fun operationSource(name: String, id: String) = OperationSource(
        reference = FileReference("ui", "ui:$id", path = "/test/$name"),
        rootReference = "/test",
        storageId = "ui-test",
        name = name,
        isDirectory = false,
        sizeBytes = 64L,
        modifiedAtMillis = 1L,
        mimeType = "text/plain",
    )
}

private class UiBrowserStorage(
    private val root: BrowserLocation,
    private val entries: List<FileEntry>,
) : BrowserStorage {
    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = entries
    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = listOf(Breadcrumb(root.displayName, root))
    override suspend fun remember(location: BrowserLocation) = Unit
    override fun openRequest(entry: FileEntry): OpenFileRequest? = null
}

private class UiBrowserPreferences : BrowserPreferences {
    private val viewModeState = MutableStateFlow(ViewMode.LIST)
    private val hiddenState = MutableStateFlow(false)
    private val sortState = MutableStateFlow(SortConfiguration())
    override val viewMode: Flow<ViewMode> = viewModeState
    override val showHidden: Flow<Boolean> = hiddenState
    override val sortConfiguration: Flow<SortConfiguration> = sortState
    override suspend fun setViewMode(value: ViewMode) { viewModeState.value = value }
    override suspend fun setShowHidden(value: Boolean) { hiddenState.value = value }
    override suspend fun setSortField(value: SortField) { sortState.value = sortState.value.copy(field = value) }
    override suspend fun setSortDirection(value: SortDirection) { sortState.value = sortState.value.copy(direction = value) }
}

private class UiOperationStore : OperationStore {
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

    fun seed(operation: FileOperation) = runBlocking {
        assertEquals(null, get(operation.id))
        enqueue(operation)
    }
}
