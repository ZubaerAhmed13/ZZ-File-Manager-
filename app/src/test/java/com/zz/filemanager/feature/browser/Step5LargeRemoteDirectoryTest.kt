package com.zz.filemanager.feature.browser

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Step5LargeRemoteDirectoryTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun hundredThousandEntryRemoteDirectoryRendersFirstPageBeforeEnumerationCompletes() = runTest(dispatcher) {
        val root = BrowserLocation(
            providerId = "sftp:large-ci",
            id = "large-root",
            displayName = "Large remote",
            reference = "/",
            rootReference = "/",
            storageId = "sftp:large-ci",
            writable = true,
        )
        val releaseRemainingPages = CompletableDeferred<Unit>()
        val storage = HundredThousandEntryStorage(releaseRemainingPages)
        val viewModel = BrowserViewModel(storage, FixedBrowserPreferences(), dispatcher)

        viewModel.start(root)
        runCurrent()

        val early = viewModel.state.value as BrowserUiState.Content
        assertEquals(PAGE_SIZE, early.entries.size)
        assertFalse(releaseRemainingPages.isCompleted)
        assertFalse(storage.enumerationCompleted)

        releaseRemainingPages.complete(Unit)
        advanceUntilIdle()

        val complete = viewModel.state.value as BrowserUiState.Content
        assertEquals(TOTAL_ENTRIES, complete.entries.size)
        assertEquals(1, storage.rememberCount)
        assertEquals("file-000000.txt", complete.entries.first().name)
        assertEquals("file-100000.txt", complete.entries.last().name)
    }

    private class HundredThousandEntryStorage(
        private val release: CompletableDeferred<Unit>,
    ) : BrowserStorage {
        var enumerationCompleted = false
        var rememberCount = 0

        override suspend fun listChildren(location: BrowserLocation): List<FileEntry> =
            error("Large remote certification must use the incremental boundary")

        override suspend fun listChildrenIncrementally(
            location: BrowserLocation,
            pageSize: Int,
            onPage: suspend (List<FileEntry>) -> Unit,
        ) {
            assertEquals(PAGE_SIZE, pageSize)
            onPage(page(0, PAGE_SIZE, location.storageId))
            release.await()
            var start = PAGE_SIZE
            while (start < TOTAL_ENTRIES) {
                val end = minOf(start + PAGE_SIZE, TOTAL_ENTRIES)
                onPage(page(start, end, location.storageId))
                start = end
            }
            enumerationCompleted = true
        }

        override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
        override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = listOf(Breadcrumb(location.displayName, location))
        override suspend fun remember(location: BrowserLocation) { rememberCount += 1 }
        override fun openRequest(entry: FileEntry): OpenFileRequest? = null

        private fun page(start: Int, end: Int, storageId: String): List<FileEntry> =
            (start until end).map { index ->
                val name = "file-${index.toString().padStart(6, '0')}.txt"
                FileEntry(
                    id = "entry-$index",
                    reference = FileReference("sftp:large-ci", "/$name"),
                    name = name,
                    extension = "txt",
                    mimeType = "text/plain",
                    type = FileEntryType.TEXT,
                    sizeBytes = index.toLong(),
                    modifiedAtMillis = index.toLong(),
                    createdAtMillis = null,
                    isHidden = false,
                    isReadable = true,
                    isWritable = true,
                    childCount = null,
                    storageId = storageId,
                    thumbnailKey = null,
                )
            }
    }

    private class FixedBrowserPreferences : BrowserPreferences {
        private val mode = MutableStateFlow(ViewMode.LIST)
        private val hidden = MutableStateFlow(false)
        private val sort = MutableStateFlow(SortConfiguration(SortField.NAME, SortDirection.ASCENDING, foldersFirst = true))
        override val viewMode: Flow<ViewMode> = mode
        override val showHidden: Flow<Boolean> = hidden
        override val sortConfiguration: Flow<SortConfiguration> = sort
        override suspend fun setViewMode(value: ViewMode) { mode.value = value }
        override suspend fun setShowHidden(value: Boolean) { hidden.value = value }
        override suspend fun setSortField(value: SortField) { sort.value = sort.value.copy(field = value) }
        override suspend fun setSortDirection(value: SortDirection) { sort.value = sort.value.copy(direction = value) }
    }

    companion object {
        private const val PAGE_SIZE = 256
        private const val TOTAL_ENTRIES = 100_001
    }
}
