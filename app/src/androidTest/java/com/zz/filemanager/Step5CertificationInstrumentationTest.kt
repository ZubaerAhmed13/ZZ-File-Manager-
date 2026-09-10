package com.zz.filemanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.remote.AndroidKeystoreCredentialStore
import com.zz.filemanager.core.remote.NetworkConnectionRepository
import com.zz.filemanager.core.storage.SafLocationKind
import com.zz.filemanager.core.util.BrowserLocationCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step5CertificationInstrumentationTest {
    @Test
    fun keystoreCredentialRoundTripNeverPersistsPlaintextSecret() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidKeystoreCredentialStore(context)
        val reference = AndroidKeystoreCredentialStore.passwordReference("instrumentation-${System.nanoTime()}")
        val secret = "S3cret-${System.nanoTime()}".toCharArray()
        try {
            store.putChars(reference, secret)
            val restored = store.getChars(reference)
            try {
                assertArrayEquals(secret, restored)
                val rawPreferences = context.getSharedPreferences("zz_step5_secure_credentials", Context.MODE_PRIVATE).all
                val raw = rawPreferences[reference]?.toString().orEmpty()
                assertTrue(raw.startsWith("v1:"))
                assertFalse(raw.contains(secret.concatToString()))
            } finally {
                restored?.fill('\u0000')
            }
        } finally {
            secret.fill('\u0000')
            store.remove(reference)
        }
    }

    @Test
    fun networkSettingsRoundTripIncludesStep5SafetyControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = NetworkConnectionRepository(context)
        val original = repository.settings()
        val changed = original.copy(
            connectTimeoutMillis = 10_000,
            retryCount = 3,
            wifiOnlyBackgroundTransfers = true,
            warnOnMeteredNetwork = true,
            autoResumeInterruptedTransfers = false,
            showHiddenRemoteFiles = true,
            lanDiscoveryEnabled = false,
            warnOnInsecureProtocols = true,
        )
        try {
            repository.setSettings(changed)
            assertEquals(changed, repository.settings())
        } finally {
            repository.setSettings(original)
        }
    }

    @Test
    fun safCloudReferenceRemainsUriNativeAcrossPersistenceCodec() {
        val uri = "content://com.example.documents/tree/cloud-account-root"
        val location = BrowserLocation(
            providerId = "saf",
            id = "saf:$uri",
            displayName = "Cloud · Test Provider",
            reference = uri,
            rootReference = uri,
            storageId = "saf:cloud:test",
            readable = true,
            writable = true,
        )

        val restored = BrowserLocationCodec.decode(BrowserLocationCodec.encode(location))
        assertEquals(location, restored)
        assertTrue(restored?.reference?.startsWith("content://") == true)
        assertFalse(restored?.reference?.startsWith("/") == true)
        assertEquals("CLOUD", SafLocationKind.CLOUD.name)
    }
}
