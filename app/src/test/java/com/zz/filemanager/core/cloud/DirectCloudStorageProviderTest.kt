package com.zz.filemanager.core.cloud

import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.remote.AndroidKeystoreCredentialStore
import com.zz.filemanager.core.remote.SecureCredentialStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCloudStorageProviderTest {
    @Test
    fun directCloudProviderRegistersNativeIdsPagesAndCommittedWrites() = runTest {
        val adapter = InMemoryDirectCloudAdapter("test-drive")
        val account = adapter.registerAccount("account-a", "CI Drive")
        val credentials = MemoryCredentialStore()
        credentials.putChars(AndroidKeystoreCredentialStore.oauthRefreshReference(account.providerId), "refresh".toCharArray())
        val provider = DirectCloudStorageProvider(
            account = account,
            credentials = credentials,
            adapters = DirectCloudAdapterRegistry(listOf(adapter)),
        )
        val root = provider.rootLocation()

        val folder = provider.createDirectory(root, "folder")
        val folderLocation = root.copy(
            id = folder.id,
            displayName = folder.name,
            reference = folder.reference.opaqueId,
        )
        repeat(300) { provider.createDirectory(folderLocation, "child-$it") }
        val pages = mutableListOf<Int>()
        provider.listChildrenIncrementally(folderLocation, pageSize = 64) { pages += it.size }
        assertEquals(300, pages.sum())
        assertTrue(pages.size >= 5)
        assertTrue(pages.all { it in 1..64 })

        val file = provider.createFile(folderLocation, "hello.txt", "text/plain")
        val scoped = ScopedFileReference(file.reference, root.rootReference, provider.id)
        provider.openOutputStream(scoped, truncate = true).use { it.write("hello-cloud".encodeToByteArray()) }
        assertArrayEquals("hello-cloud".encodeToByteArray(), provider.openInputStream(file.reference).use { it.readBytes() })

        val renamed = provider.rename(scoped, "renamed.txt")
        assertEquals(file.reference.opaqueId, renamed.reference.opaqueId)
        val moved = provider.moveNative(
            ScopedFileReference(renamed.reference, root.rootReference, provider.id),
            root,
            "moved.txt",
        )!!
        assertEquals(file.reference.opaqueId, moved.reference.opaqueId)
        assertEquals("moved.txt", moved.name)
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
