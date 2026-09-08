package com.zz.filemanager.feature.browser

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.BrowserProblem
import com.zz.filemanager.core.model.BrowserUiState
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.OpenFileRequest
import com.zz.filemanager.core.model.SortConfiguration
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import com.zz.filemanager.core.model.ViewMode
import com.zz.filemanager.core.preferences.BrowserPreferences
import com.zz.filemanager.core.storage.BrowserStorage
import com.zz.filemanager.core.storage.StorageAccessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun start_filtersHidden_sortsByName_andRemembersLocation() = runTest(mainDispatcherRule.dispatcher) {
        val root = location("root")
        val storage = FakeBrowserStorage().apply {
            children[root.identity] = listOf(
                file("Zulu.txt"),
                file(".secret.txt", hidden = true),
                file("Alpha.txt"),
            )
        }
        val preferences = FakeBrowserPreferences()
        val viewModel = BrowserViewModel(storage, preferences, mainDispatcherRule.dispatcher)

        viewModel.start(root)
        advanceUntilIdle()

        val state = viewModel.state.value as BrowserUiState.Content
        assertEquals(listOf("Alpha.txt", "Zulu.txt"), state.entries.map { it.name })
        assertFalse(state.showHidden)
        assertEquals(listOf(root), storage.remembered)
    }

    @Test
    fun navigation_supportsBackAndForwardHistory() = runTest(mainDispatcherRule.dispatcher) {
        val root = location("root")
        val child = location("child")
        val storage = FakeBrowserStorage().apply {
            children[root.identity] = listOf(file("root.txt"))
            children[child.identity] = listOf(file("child.txt"))
            parents[child.identity] = root
        }
        val viewModel = BrowserViewModel(storage, FakeBrowserPreferences(), mainDispatcherRule.dispatcher)

        viewModel.start(root)
        advanceUntilIdle()
        viewModel.navigateTo(child)
        advanceUntilIdle()
        var state = viewModel.state.value as BrowserUiState.Content
        assertEquals(child.identity, state.location.identity)
        assertTrue(state.canGoBack)

        viewModel.goBack()
        advanceUntilIdle()
        state = viewModel.state.value as BrowserUiState.Content
        assertEquals(root.identity, state.location.identity)
        assertTrue(state.canGoForward)

        viewModel.goForward()
        advanceUntilIdle()
        state = viewModel.state.value as BrowserUiState.Content
        assertEquals(child.identity, state.location.identity)
        assertTrue(state.canGoBack)
    }

    @Test
    fun permissionFailure_mapsToPermissionProblem() = runTest(mainDispatcherRule.dispatcher) {
        val root = location("root")
        val storage = FakeBrowserStorage().apply {
            failures[root.identity] = StorageAccessException.PermissionRequired()
        }
        val viewModel = BrowserViewModel(storage, FakeBrowserPreferences(), mainDispatcherRule.dispatcher)

        viewModel.start(root)
        advanceUntilIdle()

        assertEquals(
            BrowserUiState.Problem(root, BrowserProblem.PERMISSION_REQUIRED),
            viewModel.state.value,
        )
    }

    @Test
    fun unavailableAndIoFailures_mapToTypedProblems() = runTest(mainDispatcherRule.dispatcher) {
        val unavailable = location("unavailable")
        val io = location("io")
        val storage = FakeBrowserStorage().apply {
            failures[unavailable.identity] = StorageAccessException.Unavailable()
            failures[io.identity] = StorageAccessException.Io()
        }
        val viewModel = BrowserViewModel(storage, FakeBrowserPreferences(), mainDispatcherRule.dispatcher)

        viewModel.start(unavailable)
        advanceUntilIdle()
        assertEquals(BrowserProblem.UNAVAILABLE, (viewModel.state.value as BrowserUiState.Problem).problem)

        viewModel.navigateTo(io)
        advanceUntilIdle()
        assertEquals(BrowserProblem.IO_ERROR, (viewModel.state.value as BrowserUiState.Problem).problem)
    }

    @Test
    fun openFile_emitsExternalOpenRequest() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakeBrowserStorage()
        val viewModel = BrowserViewModel(storage, FakeBrowserPreferences(), mainDispatcherRule.dispatcher)
        val expected = OpenFileRequest("content://test/report.pdf", "application/pdf")
        storage.openRequests["report.pdf"] = expected
        val event = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.first() }

        viewModel.openEntry(file("report.pdf", mime = "application/pdf", uri = expected.uri))

        assertEquals(BrowserEvent.OpenFile(expected), event.await())
    }

    @Test
    fun preferenceChanges_refreshVisibleBrowserState() = runTest(mainDispatcherRule.dispatcher) {
        val root = location("root")
        val storage = FakeBrowserStorage().apply {
            children[root.identity] = listOf(file("visible.txt"), file(".hidden.txt", hidden = true))
        }
        val preferences = FakeBrowserPreferences()
        val viewModel = BrowserViewModel(storage, preferences, mainDispatcherRule.dispatcher)

        viewModel.start(root)
        advanceUntilIdle()
        viewModel.setViewMode(ViewMode.GRID)
        viewModel.toggleHidden()
        viewModel.toggleSortDirection()
        advanceUntilIdle()

        val state = viewModel.state.value as BrowserUiState.Content
        assertEquals(ViewMode.GRID, state.viewMode)
        assertTrue(state.showHidden)
        assertEquals(SortDirection.DESCENDING, state.sort.direction)
        assertEquals(2, state.entries.size)
    }

    private fun location(id: String) = BrowserLocation(
        providerId = "fake",
        id = id,
        displayName = id,
        reference = "/$id",
        rootReference = "/root",
        storageId = "test",
        readable = true,
        writable = true,
    )

    private fun file(
        name: String,
        hidden: Boolean = false,
        mime: String? = "text/plain",
        uri: String? = null,
    ) = FileEntry(
        id = "file:$name",
        reference = FileReference("fake", "file:$name", uri = uri, path = if (uri == null) "/root/$name" else null),
        name = name,
        extension = name.substringAfterLast('.', "").ifBlank { null },
        mimeType = mime,
        type = if (mime == "application/pdf") FileEntryType.PDF else FileEntryType.TEXT,
        sizeBytes = 10L,
        modifiedAtMillis = 100L,
        createdAtMillis = null,
        isHidden = hidden,
        isReadable = true,
        isWritable = true,
        childCount = null,
        storageId = "test",
        thumbnailKey = null,
    )
}

private class FakeBrowserStorage : BrowserStorage {
    val children = mutableMapOf<String, List<FileEntry>>()
    val parents = mutableMapOf<String, BrowserLocation?>()
    val failures = mutableMapOf<String, StorageAccessException>()
    val remembered = mutableListOf<BrowserLocation>()
    val openRequests = mutableMapOf<String, OpenFileRequest?>()

    override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
        failures[location.identity]?.let { throw it }
        return children[location.identity].orEmpty()
    }

    override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = parents[location.identity]

    override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = listOf(Breadcrumb(location.displayName, location))

    override suspend fun remember(location: BrowserLocation) {
        remembered += location
    }

    override fun openRequest(entry: FileEntry): OpenFileRequest? = openRequests[entry.name]
}

private class FakeBrowserPreferences : BrowserPreferences {
    private val viewModeState = MutableStateFlow(ViewMode.LIST)
    private val showHiddenState = MutableStateFlow(false)
    private val sortState = MutableStateFlow(SortConfiguration())

    override val viewMode: Flow<ViewMode> = viewModeState
    override val showHidden: Flow<Boolean> = showHiddenState
    override val sortConfiguration: Flow<SortConfiguration> = sortState

    override suspend fun setViewMode(value: ViewMode) {
        viewModeState.value = value
    }

    override suspend fun setShowHidden(value: Boolean) {
        showHiddenState.value = value
    }

    override suspend fun setSortField(value: SortField) {
        sortState.value = sortState.value.copy(field = value)
    }

    override suspend fun setSortDirection(value: SortDirection) {
        sortState.value = sortState.value.copy(direction = value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
