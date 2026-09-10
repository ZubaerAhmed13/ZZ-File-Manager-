package com.zz.filemanager.core.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository-safe deterministic adapter used for direct-cloud certification without provider
 * credentials. It is never registered as a production account provider. Real adapters implement
 * the same session contract and keep OAuth client registration outside source control.
 */
class InMemoryDirectCloudAdapter(
    override val providerKey: String = "test",
) : DirectCloudAdapter {
    private data class Stored(
        var item: CloudItem,
        var bytes: ByteArray = byteArrayOf(),
    )

    private val accounts = ConcurrentHashMap<String, ConcurrentHashMap<String, Stored>>()

    fun registerAccount(accountId: String, displayName: String = accountId): CloudAccountIdentity {
        val account = CloudAccountIdentity(providerKey, accountId, displayName)
        accounts.computeIfAbsent(accountId) {
            ConcurrentHashMap<String, Stored>().apply {
                put(ROOT_ID, Stored(CloudItem(ROOT_ID, null, "Root", directory = true, revision = "root-1")))
            }
        }
        return account
    }

    override fun open(account: CloudAccountIdentity, refreshToken: CharArray): DirectCloudSession {
        require(account.providerKey == providerKey) { "Wrong cloud provider" }
        if (refreshToken.isEmpty()) throw CloudAuthException.ReauthenticationRequired()
        val store = accounts[account.accountId] ?: throw CloudAuthException.ReauthenticationRequired()
        return Session(account, store)
    }

    private class Session(
        override val account: CloudAccountIdentity,
        private val store: ConcurrentHashMap<String, Stored>,
    ) : DirectCloudSession {
        @Volatile private var closed = false

        override suspend fun listPage(parentNativeFileId: String, pageToken: String?, pageSize: Int): CloudPage {
            checkOpen()
            require(pageSize in 1..1_000)
            val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val all = store.values.asSequence()
                .map { it.item }
                .filter { it.parentNativeFileId == parentNativeFileId }
                .sortedWith(compareBy<CloudItem>({ !it.directory }, { it.displayName.lowercase() }, { it.nativeFileId }))
                .toList()
            val page = all.drop(offset).take(pageSize)
            val next = (offset + page.size).takeIf { it < all.size }?.toString()
            return CloudPage(page, next)
        }

        override suspend fun stat(nativeFileId: String): CloudItem? {
            checkOpen()
            return store[nativeFileId]?.item
        }

        override suspend fun openRead(nativeFileId: String, offset: Long): InputStream {
            checkOpen()
            require(offset >= 0L)
            val stored = store[nativeFileId] ?: throw IllegalArgumentException("Missing cloud object")
            require(!stored.item.directory)
            require(offset <= stored.bytes.size.toLong())
            return ByteArrayInputStream(stored.bytes, offset.toInt(), stored.bytes.size - offset.toInt())
        }

        override suspend fun createDirectory(parentNativeFileId: String, name: String): CloudItem {
            checkOpen()
            requireParent(parentNativeFileId)
            require(name.isNotBlank() && '/' !in name)
            val id = UUID.randomUUID().toString()
            val item = CloudItem(id, parentNativeFileId, name, directory = true, revision = revision())
            store[id] = Stored(item)
            return item
        }

        override suspend fun beginUpload(
            parentNativeFileId: String,
            name: String,
            expectedSizeBytes: Long?,
            resume: CloudResumeToken?,
        ): CloudUploadSession {
            checkOpen()
            requireParent(parentNativeFileId)
            require(name.isNotBlank() && '/' !in name)
            expectedSizeBytes?.let { require(it >= 0L) }
            return Upload(store, parentNativeFileId, name, expectedSizeBytes, resume)
        }

        override suspend fun rename(nativeFileId: String, newName: String, ifRevision: String?): CloudItem {
            checkOpen()
            require(newName.isNotBlank() && '/' !in newName)
            return mutate(nativeFileId, ifRevision) { it.copy(displayName = newName, revision = revision()) }
        }

        override suspend fun move(nativeFileId: String, newParentNativeFileId: String, ifRevision: String?): CloudItem {
            checkOpen()
            requireParent(newParentNativeFileId)
            return mutate(nativeFileId, ifRevision) { it.copy(parentNativeFileId = newParentNativeFileId, revision = revision()) }
        }

        override suspend fun delete(nativeFileId: String, ifRevision: String?) {
            checkOpen()
            require(nativeFileId != ROOT_ID)
            val existing = store[nativeFileId] ?: return
            if (ifRevision != null && existing.item.revision != ifRevision) throw IllegalStateException("Cloud revision changed")
            if (existing.item.directory && store.values.any { it.item.parentNativeFileId == nativeFileId }) {
                throw IllegalStateException("Directory is not empty")
            }
            store.remove(nativeFileId)
        }

        override fun close() { closed = true }

        private fun checkOpen() = check(!closed) { "Cloud session is closed" }
        private fun requireParent(id: String) = require(store[id]?.item?.directory == true) { "Cloud parent is missing" }

        private fun mutate(id: String, ifRevision: String?, transform: (CloudItem) -> CloudItem): CloudItem {
            val stored = store[id] ?: throw IllegalArgumentException("Missing cloud object")
            if (ifRevision != null && stored.item.revision != ifRevision) throw IllegalStateException("Cloud revision changed")
            val changed = transform(stored.item)
            stored.item = changed
            return changed
        }
    }

    private class Upload(
        private val store: ConcurrentHashMap<String, Stored>,
        private val parentId: String,
        private val name: String,
        private val expectedSize: Long?,
        resume: CloudResumeToken?,
    ) : CloudUploadSession {
        override val sessionId: String = resume?.uploadSessionId ?: UUID.randomUUID().toString()
        override val startOffset: Long = resume?.offset ?: 0L
        private val output = ByteArrayOutputStream()
        private var closed = false
        private var committed = false

        init {
            // The in-memory adapter intentionally supports only bounded test fixtures. The shared
            // production contract keeps offsets as Long and does not impose this mock's RAM limit.
            require(startOffset == 0L) { "In-memory certification adapter does not retain old session bytes" }
        }

        override fun outputStream(): OutputStream {
            check(!closed && !committed)
            return output
        }

        override suspend fun commit(expectedTotalBytes: Long?, sourceRevision: String): CloudItem {
            check(!closed && !committed)
            val bytes = output.toByteArray()
            val expected = expectedTotalBytes ?: expectedSize
            if (expected != null && bytes.size.toLong() != expected) throw IllegalStateException("Cloud upload byte count mismatch")
            val id = UUID.randomUUID().toString()
            val item = CloudItem(
                nativeFileId = id,
                parentNativeFileId = parentId,
                displayName = name,
                directory = false,
                sizeBytes = bytes.size.toLong(),
                revision = "${sourceRevision}:${revision()}",
            )
            store[id] = Stored(item, bytes)
            committed = true
            return item
        }

        override suspend fun abort() {
            output.reset()
            closed = true
        }

        override fun close() { closed = true }
    }

    companion object {
        const val ROOT_ID = "root"
        private fun revision(): String = "r-${UUID.randomUUID()}"
    }
}
