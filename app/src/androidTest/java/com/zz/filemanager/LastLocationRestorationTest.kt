package com.zz.filemanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.storage.LocalStorageProvider
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LastLocationRestorationTest {
    @Test
    fun validLocalDirectory_isRestored_andRemovedDirectoryIsRejected() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PreferencesRepository(context)
        val storage = StorageRepository(context, preferences)
        val directory = File(context.cacheDir, "last-location-restoration-test").apply { mkdirs() }
        val location = BrowserLocation(
            providerId = LocalStorageProvider.ID,
            id = "local:${directory.canonicalPath}",
            displayName = "Restore test",
            reference = directory.canonicalPath,
            rootReference = directory.canonicalPath,
            storageId = "test",
            readable = true,
            writable = true,
        )

        try {
            preferences.setLastLocation(location)
            assertEquals(location.identity, storage.restorableLastLocation()?.identity)

            directory.deleteRecursively()
            assertNull(storage.restorableLastLocation())
        } finally {
            preferences.clearLastLocation()
            directory.deleteRecursively()
        }
    }
}
