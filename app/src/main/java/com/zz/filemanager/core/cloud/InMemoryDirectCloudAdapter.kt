package com.zz.filemanager.core.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Repository-safe deterministic direct-cloud adapter used only for architecture/certification tests. */
class InMemoryDirectCloudAdapter(
    override val providerKey: String = "test",
) : DirectCloudAdapter {
    private data class Stored(var item: CloudItem, var bytes: ByteArray = byteArrayOf())
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
            checkOpen(); require(pageSize in 1..1_000)
            val offset = pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val all = store.values.asSequence().map { it.item }
                .filter { it.parentNativeFileId == parentNativeFileId }
                .sortedWith(compareBy<CloudItem>({ !it.directory }, { it.displayName.lowercase() }, { it.nativeFileId }))
                .toList()
            val page = all.drop(offset).take(pageSize)
            return CloudPage(page, (offset + page.size).takeIf { it < all.size }?.toString())
        }

        override suspend fun stat(nativeFileId: String): CloudItem? { checkOpen(); return store[nativeFileId]?.item }

        override suspend fun openRead(nativeFileId: String, offset: Long): InputStream {
            checkOpen(); require(offset >= 0L)
            val stored = store[nativeFileId] ?: throw IllegalArgumentException("Missing cloud object")
            require(!stored.item.directory && offset <= stored.bytes.size.toLong())
            return ByteArrayInputStream(stored.bytes, offset.toInt(), stored.bytes.size - offset.toInt())
        }

        override suspend fun createDirectory(parentNativeFileId: String, name: String): CloudItem {
            checkOpen(); requireParent(parentNativeFileId); requireName(name)
            val id = UUID.randomUUID().toString()
            return CloudItem(id, parentNativeFileId, name, directory = true, revision = revision()).also {
                store[id] = Stored(it)
            }
        }

        override suspend fun beginUpload(
            parentNativeFileId: String,
            name: String,
            expectedSizeBytes: Long?,
            resume: CloudResumeToken?,
            targetNativeFileId: String?,
        ): CloudUploadSession {
            checkOpen(); requireParent(parentNativeFileId); requireName(name)
            expectedSizeBytes?.let { require(it >= 0L) }
            targetNativeFileId?.let { target -> require(store[target]?.item?.directory == false) { "Missing cloud upload target" } }
            return Upload(store, parentNativeFileId, name, expectedSizeBytes, resume, targetNativeFileId)
        }

        override suspend fun rename(nativeFileId: String, newName: String, ifRevision: String?): CloudItem {
            checkOpen(); requireName(newName)
            return mutate(nativeFileId, ifRevision) { it.copy(displayName = newName, revision = revision()) }
        }

        override suspend fun move(nativeFileId: String, newParentNativeFileId: String, ifRevision: String?): CloudItem {
            checkOpen(); requireParent(newParentNativeFileId)
            return mutate(nativeFileId, ifRevision) { it.copy(parentNativeFileId = newParentNativeFileId, revision = revision()) }
        }

        override suspend fun delete(nativeFileId: String, ifRevision: String?) {
            checkOpen(); require(nativeFileId != ROOT_ID)
            val existing = store[nativeFileId] ?: return
            if (ifRevision != null && existing.item.revision != ifRevision) throw IllegalStateException("Cloud revision changed")
            if (existing.item.directory && store.values.any { it.item.parentNativeFileId == nativeFileId }) throw IllegalStateException("Directory is not empty")
            store.remove(nativeFileId)
        }

        override fun close() { closed = true }
        private fun checkOpen() = check(!closed) { "Cloud session is closed" }
        private fun requireParent(id: String) = require(store[id]?.item?.directory == true) { "Cloud parent is missing" }
        private fun requireName(name: String) = require(name.isNotBlank() && '/' !in name) { "Invalid cloud name" }
        private fun mutate(id: String, ifRevision: String?, transform: (CloudItem) -> CloudItem): CloudItem {
            val stored = store[id] ?: throw IllegalArgumentException("Missing cloud object")
            if (ifRevision != null && stored.item.revision != ifRevision) throw IllegalStateException("Cloud revision changed")
            return transform(stored.item).also { stored.item = it }
        }
    }

    private class Upload(
        private val store: ConcurrentHashMap<String, Stored>,
        private val parentId: String,
        private val name: String,
        private val expectedSize: Long?,
        resume: CloudResumeToken?,
        private val targetId: String?,
    ) : CloudUploadSession {
        override val sessionId: String = resume?.uploadSessionId ?: UUID.randomUUID().toString()
        override val startOffset: Long = resume?.offset ?: 0L
        private val output = ByteArrayOutputStream()
        private var closed = false
        private var committed = false

        init { require(startOffset == 0L) { "In-memory adapter does not retain old upload-session bytes" } }
        override fun outputStream(): OutputStream { check(!closed && !committed); return output }

        override suspend fun commit(expectedTotalBytes: Long?, sourceRevision: String): CloudItem {
            check(!closed && !committed)
            val bytes = output.toByteArray()
            val expected = expectedTotalBytes ?: expectedSize
            if (expected != null && bytes.size.toLong() != expected) throw IllegalStateException("Cloud upload byte count mismatch")
            val id = targetId ?: UUID.randomUUID().toString()
            val previous = targetId?.let(store::get)?.item
            val item = CloudItem(
                nativeFileId = id,
                parentNativeFileId = previous?.parentNativeFileId ?: parentId,
                displayName = previous?.displayName ?: name,
                directory = false,
                sizeBytes = bytes.size.toLong(),
                mimeType = previous?.mimeType,
                revision = "$sourceRevision:${revision()}",
            )
            store[id] = Stored(item, bytes)
            committed = true
            return item
        }

        override suspend fun abort() { output.reset(); closed = true }
        override fun close() { closed = true }
    }

    companion object {
        const val ROOT_ID = "root"
        private fun revision(): String = "r-${UUID.randomUUID()}"
    }
}
