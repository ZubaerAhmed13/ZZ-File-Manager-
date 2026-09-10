package com.zz.filemanager.core.cloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCloudArchitectureTest {
    @Test
    fun nativeIdsSurviveRenameAndMove() = runTest {
        val adapter = InMemoryDirectCloudAdapter()
        val account = adapter.registerAccount("account-a")
        val token = "test-refresh-token".toCharArray()
        adapter.open(account, token).use { session ->
            val folderA = session.createDirectory(InMemoryDirectCloudAdapter.ROOT_ID, "A")
            val folderB = session.createDirectory(InMemoryDirectCloudAdapter.ROOT_ID, "B")
            val upload = session.beginUpload(folderA.nativeFileId, "hello.txt", 5L)
            upload.outputStream().use { it.write("hello".toByteArray()) }
            val created = upload.commit(5L, "source-r1")
            val renamed = session.rename(created.nativeFileId, "renamed.txt", created.revision)
            val moved = session.move(renamed.nativeFileId, folderB.nativeFileId, renamed.revision)

            assertEquals(created.nativeFileId, renamed.nativeFileId)
            assertEquals(created.nativeFileId, moved.nativeFileId)
            assertEquals(folderB.nativeFileId, moved.parentNativeFileId)
            assertEquals("renamed.txt", moved.displayName)
        }
        token.fill('\u0000')
    }

    @Test
    fun pagingAndMultipleAccountsAreNamespaced() = runTest {
        val adapter = InMemoryDirectCloudAdapter("mock-drive")
        val a = adapter.registerAccount("one")
        val b = adapter.registerAccount("two")
        assertNotEquals(a.providerId, b.providerId)

        adapter.open(a, charArrayOf('x')).use { session ->
            repeat(5) { session.createDirectory(InMemoryDirectCloudAdapter.ROOT_ID, "folder-$it") }
            val first = session.listPage(InMemoryDirectCloudAdapter.ROOT_ID, null, 2)
            val second = session.listPage(InMemoryDirectCloudAdapter.ROOT_ID, first.nextPageToken, 2)
            val third = session.listPage(InMemoryDirectCloudAdapter.ROOT_ID, second.nextPageToken, 2)
            assertEquals(2, first.items.size)
            assertEquals(2, second.items.size)
            assertEquals(1, third.items.size)
            assertNull(third.nextPageToken)
        }
        adapter.open(b, charArrayOf('y')).use { session ->
            assertTrue(session.listPage(InMemoryDirectCloudAdapter.ROOT_ID, null, 10).items.isEmpty())
        }
    }

    @Test
    fun resumeTokenUsesLongBeyondThirtyGiBWithoutOverflow() {
        val offset = 30L * 1024L * 1024L * 1024L + 123_456_789L
        val token = CloudResumeToken("session", offset, "source-r1", "dest-r1")
        assertEquals(offset, token.offset)
        assertTrue(token.offset > Int.MAX_VALUE.toLong())
    }
}
