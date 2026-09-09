package com.zz.filemanager.feature.search

import com.zz.filemanager.core.library.ActivityEntry
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.library.SearchHistoryItem
import com.zz.filemanager.core.library.TrashRecord
import com.zz.filemanager.core.library.UserLibraryStore
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.search.SearchCoordinator
import com.zz.filemanager.core.search.SearchRepository
import com.zz.filemanager.core.search.SearchRootSource
import com.zz.filemanager.core.search.SearchUiState
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun newerFastQueryRemainsAuthoritativeAfterSlowQueryIsCancelled() = runTest(dispatcher) {
        val root = BrowserLocation("fake", "root", "Root", "root", "root", "root", true, true)
        var calls = 0
        val provider = object : StorageProvider {
            override val id = "fake"
            override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
                calls++
                if (calls == 1) delay(1_000)
                return listOf(file("slow.txt"), file("fast.txt"))
            }
            override suspend fun getMetadata(item: FileReference): FileEntry? = null
            override suspend fun openInputStream(item: FileReference) = ByteArrayInputStream(ByteArray(0))
            override suspend fun exists(item: FileReference) = true
            override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
            override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
        }
        val registry = object : StorageProviderRegistry {
            override fun providerFor(providerId: String): StorageProvider = provider
            override fun writableProviderFor(providerId: String): WritableStorageProvider? = null
        }
        val library = TestLibraryStore()
        val viewModel = SearchViewModel(SearchRepository(SearchCoordinator(registry, SearchRootSource { listOf(root) }), library), library, root)

        viewModel.setText("slow")
        advanceTimeBy(301)
        runCurrent()
        viewModel.setText("fast")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is SearchUiState.Results)
        assertEquals(listOf("fast.txt"), (state as SearchUiState.Results).results.map { it.name })
        assertEquals(listOf("fast"), library.searchHistory.value.map { it.displayQuery })
    }

    private fun file(name: String) = FileEntry(
        "fake:$name", FileReference("fake", "fake:$name", path = "root/$name"), name, "txt", "text/plain",
        FileEntryType.TEXT, 10L, 10L, null, false, true, true, null, "root", null,
    )
}

private class TestLibraryStore : UserLibraryStore {
    override val favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    override val recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    override val searchHistory = MutableStateFlow<List<SearchHistoryItem>>(emptyList())
    override val activityHistory = MutableStateFlow<List<ActivityEntry>>(emptyList())
    override val trashRecords = MutableStateFlow<List<TrashRecord>>(emptyList())
    override suspend fun initialize() = Unit
    override suspend fun upsertFavorite(item: FavoriteItem) { favorites.value = listOf(item) }
    override suspend fun removeFavorite(id: String) { favorites.value = favorites.value.filterNot { it.id == id } }
    override suspend fun updateFavorite(item: FavoriteItem) = upsertFavorite(item)
    override suspend fun recordRecentFile(item: RecentFile) { recentFiles.value = listOf(item) }
    override suspend fun recordSearch(query: String, usedAtMillis: Long) { searchHistory.value = listOf(SearchHistoryItem(query.lowercase(), query, usedAtMillis)) }
    override suspend fun recordActivity(entry: ActivityEntry) { activityHistory.value = listOf(entry) }
    override suspend fun upsertTrash(record: TrashRecord) { trashRecords.value = listOf(record) }
    override suspend fun removeTrash(id: String) { trashRecords.value = trashRecords.value.filterNot { it.id == id } }
    override suspend fun clearRecentFiles() { recentFiles.value = emptyList() }
    override suspend fun clearSearchHistory() { searchHistory.value = emptyList() }
    override suspend fun clearActivityHistory() { activityHistory.value = emptyList() }
}
