package com.zz.filemanager.core.cloud

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Stable account identity. Display names are never used as primary identity. */
data class CloudAccountIdentity(
    val providerKey: String,
    val accountId: String,
    val displayName: String,
) {
    val providerId: String get() = "cloud:$providerKey:$accountId"
}

data class CloudItem(
    val nativeFileId: String,
    val parentNativeFileId: String?,
    val displayName: String,
    val directory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
    val mimeType: String? = null,
    val revision: String? = null,
    val writable: Boolean = true,
)

data class CloudPage(
    val items: List<CloudItem>,
    val nextPageToken: String? = null,
)

/** Persistable resume metadata contains identifiers/proof only, never access or refresh tokens. */
data class CloudResumeToken(
    val uploadSessionId: String,
    val offset: Long,
    val sourceRevision: String,
    val destinationRevision: String? = null,
) {
    init { require(offset >= 0L) }
}

sealed class CloudAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ExpiredToken : CloudAuthException("Cloud access token expired")
    class RefreshFailed(cause: Throwable? = null) : CloudAuthException("Cloud token refresh failed", cause)
    class ReauthenticationRequired : CloudAuthException("Cloud account requires reauthentication")
}

/**
 * Direct provider boundary. Production adapters obtain a short-lived session from a Keystore-backed
 * refresh-token reference. Callers must clear the supplied refresh-token CharArray after open.
 */
interface DirectCloudAdapter {
    val providerKey: String
    fun open(account: CloudAccountIdentity, refreshToken: CharArray): DirectCloudSession
}

interface DirectCloudSession : Closeable {
    val account: CloudAccountIdentity

    suspend fun listPage(parentNativeFileId: String, pageToken: String?, pageSize: Int): CloudPage
    suspend fun stat(nativeFileId: String): CloudItem?
    suspend fun openRead(nativeFileId: String, offset: Long = 0L): InputStream
    suspend fun createDirectory(parentNativeFileId: String, name: String): CloudItem
    suspend fun beginUpload(
        parentNativeFileId: String,
        name: String,
        expectedSizeBytes: Long?,
        resume: CloudResumeToken? = null,
    ): CloudUploadSession
    suspend fun rename(nativeFileId: String, newName: String, ifRevision: String? = null): CloudItem
    suspend fun move(nativeFileId: String, newParentNativeFileId: String, ifRevision: String? = null): CloudItem
    suspend fun delete(nativeFileId: String, ifRevision: String? = null)
}

/** Upload finalization is explicit: sending bytes alone is never success. */
interface CloudUploadSession : Closeable {
    val sessionId: String
    val startOffset: Long
    fun outputStream(): OutputStream
    suspend fun commit(expectedTotalBytes: Long?, sourceRevision: String): CloudItem
    suspend fun abort()
}
