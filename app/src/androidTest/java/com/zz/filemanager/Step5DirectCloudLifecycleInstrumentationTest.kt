package com.zz.filemanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zz.filemanager.core.cloud.CloudAccountIdentity
import com.zz.filemanager.core.cloud.DirectCloudAccountManager
import com.zz.filemanager.core.cloud.DirectCloudAdapterRegistry
import com.zz.filemanager.core.cloud.DirectCloudOAuthDriver
import com.zz.filemanager.core.cloud.DirectCloudOAuthDriverRegistry
import com.zz.filemanager.core.cloud.InMemoryDirectCloudAdapter
import com.zz.filemanager.core.remote.AndroidKeystoreCredentialStore
import com.zz.filemanager.core.remote.NetworkConnectionRepository
import com.zz.filemanager.core.remote.RemoteConnectionState
import com.zz.filemanager.core.remote.RemoteProtocol
import com.zz.filemanager.core.remote.SecureCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step5DirectCloudLifecycleInstrumentationTest {
    @Test
    fun directCloudOAuthLifecycleConnectRefreshDisconnectAndRevokeIsComplete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = NetworkConnectionRepository(context)
        val credentials = MemoryCredentialStore()
        val providerKey = "ci-cloud-${System.nanoTime()}"
        val accountId = "account-${System.nanoTime()}"
        val adapter = InMemoryDirectCloudAdapter(providerKey)
        val account = adapter.registerAccount(accountId, "CI Cloud")
        val driver = RecordingOAuthDriver(providerKey)
        val manager = DirectCloudAccountManager(
            repository,
            credentials,
            DirectCloudAdapterRegistry(listOf(adapter)),
            DirectCloudOAuthDriverRegistry(listOf(driver)),
        )
        val connectionId = DirectCloudAccountManager.stableConnectionId(account)
        val secretRef = AndroidKeystoreCredentialStore.oauthRefreshReference(account.providerId)

        repository.remove(connectionId)
        try {
            val initial = "refresh-one".toCharArray()
            manager.connect(account, initial)
            assertTrue(initial.all { it == '\u0000' })
            val connected = repository.get(connectionId)!!
            assertEquals(RemoteProtocol.DIRECT_CLOUD, connected.protocol)
            assertEquals(providerKey, connected.host)
            assertEquals(accountId, connected.oauthAccountId)
            assertEquals("refresh-one", credentials.getChars(secretRef)?.concatToString())

            manager.refresh(providerKey, accountId)
            assertEquals(1, driver.refreshCount)
            assertEquals("refresh-two", credentials.getChars(secretRef)?.concatToString())

            manager.disconnect(providerKey, accountId)
            assertNull(credentials.getChars(secretRef))
            assertEquals(RemoteConnectionState.AUTH_REQUIRED, repository.get(connectionId)?.connectionState)

            manager.connect(account, "refresh-three".toCharArray())
            manager.revoke(providerKey, accountId)
            assertEquals(1, driver.revokeCount)
            assertNull(credentials.getChars(secretRef))
            assertEquals(RemoteConnectionState.AUTH_REQUIRED, repository.get(connectionId)?.connectionState)

            manager.removeLocalAccount(providerKey, accountId)
            assertNull(repository.get(connectionId))
        } finally {
            credentials.remove(secretRef)
            repository.remove(connectionId)
        }
    }

    private class RecordingOAuthDriver(override val providerKey: String) : DirectCloudOAuthDriver {
        var refreshCount = 0
        var revokeCount = 0
        override suspend fun refresh(account: CloudAccountIdentity, refreshToken: CharArray): CharArray {
            assertFalse(refreshToken.isEmpty())
            refreshCount += 1
            return "refresh-two".toCharArray()
        }
        override suspend fun revoke(account: CloudAccountIdentity, refreshToken: CharArray) {
            assertFalse(refreshToken.isEmpty())
            revokeCount += 1
        }
    }

    private class MemoryCredentialStore : SecureCredentialStore {
        private val values = mutableMapOf<String, ByteArray>()
        override fun putBytes(reference: String, secret: ByteArray) { values[reference] = secret.copyOf() }
        override fun getBytes(reference: String): ByteArray? = values[reference]?.copyOf()
        override fun putChars(reference: String, secret: CharArray) { values[reference] = secret.concatToString().encodeToByteArray() }
        override fun getChars(reference: String): CharArray? = values[reference]?.toString(Charsets.UTF_8)?.toCharArray()
        override fun remove(reference: String) { values.remove(reference)?.fill(0) }
    }
}
