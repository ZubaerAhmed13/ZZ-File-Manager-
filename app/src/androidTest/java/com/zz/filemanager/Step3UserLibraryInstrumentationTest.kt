package com.zz.filemanager

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.UserLibraryRepository
import com.zz.filemanager.core.library.RecentFile
import com.zz.filemanager.core.library.ActivityEntry
import com.zz.filemanager.core.library.ActivityKind
import com.zz.filemanager.core.library.stableIdentity
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step3UserLibraryInstrumentationTest {
    @Test fun favoriteSearchAndRecentMetadataSurviveRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reference = FileReference("local", "step3-${System.nanoTime()}", path = "/tmp/step3")
        val id = reference.stableIdentity("/tmp", "test")
        val first = UserLibraryRepository(context)
        first.initialize()
        first.upsertFavorite(FavoriteItem(id, reference, "/tmp", "test", null, "step3.txt", FileEntryType.TEXT, 123L))
        first.recordSearch("Invoice", 456L)

        val recreated = UserLibraryRepository(context)
        recreated.initialize()
        assertEquals("step3.txt", recreated.favorites.value.first { it.id == id }.displayName)
        assertEquals("Invoice", recreated.searchHistory.value.first { it.normalizedQuery == "invoice" }.displayQuery)
        recreated.removeFavorite(id)
        recreated.clearSearchHistory()
    }

    @Test fun recentSearchAndActivityRetentionIsBoundedAndClearable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = UserLibraryRepository(context)
        repository.initialize()
        repository.clearRecentFiles()
        repository.clearSearchHistory()
        repository.clearActivityHistory()
        repeat(60) { index ->
            val reference = FileReference("local", "recent-$index", path = "/tmp/recent-$index")
            repository.recordRecentFile(RecentFile("recent-$index", reference, "/tmp", "test", null, "recent-$index.txt", FileEntryType.TEXT, index.toLong()))
            repository.recordSearch("query-$index", index.toLong())
        }
        repeat(110) { index -> repository.recordActivity(ActivityEntry("activity-$index", ActivityKind.COPIED, "Copied item", 1, index.toLong())) }
        assertEquals(UserLibraryRepository.MAX_RECENT_FILES, repository.recentFiles.value.size)
        assertEquals(UserLibraryRepository.MAX_SEARCH_HISTORY, repository.searchHistory.value.size)
        assertEquals(UserLibraryRepository.MAX_ACTIVITY_HISTORY, repository.activityHistory.value.size)
        repository.recordSearch("query-59", 1_000L)
        assertEquals(1, repository.searchHistory.value.count { it.normalizedQuery == "query-59" })
        repository.clearRecentFiles()
        repository.clearSearchHistory()
        repository.clearActivityHistory()
        assertTrue(repository.recentFiles.value.isEmpty())
        assertTrue(repository.searchHistory.value.isEmpty())
        assertTrue(repository.activityHistory.value.isEmpty())
    }
}
