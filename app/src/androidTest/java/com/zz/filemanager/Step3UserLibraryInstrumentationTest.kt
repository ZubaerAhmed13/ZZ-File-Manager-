package com.zz.filemanager

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.library.FavoriteItem
import com.zz.filemanager.core.library.UserLibraryRepository
import com.zz.filemanager.core.library.stableIdentity
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
