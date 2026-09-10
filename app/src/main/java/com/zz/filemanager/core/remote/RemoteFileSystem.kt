package com.zz.filemanager.core.remote

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Canonical POSIX-style remote path helper. It never converts remote names to local File paths. */
object RemotePath {
    fun normalize(raw: String): String {
        val stack = ArrayDeque<String>()
        raw.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else throw IllegalArgumentException("Remote path escapes root")
                else -> {
                    require('\u0000' !in part) { "Remote path contains NUL" }
                    stack.addLast(part)
                }
            }
        }
        return "/" + stack.joinToString("/")
    }

    fun leaf(name: String): String {
        require(name.isNotBlank()) { "Name is required" }
        require(name != "." && name != "..") { "Unsafe name" }
        require('/' !in name && '\\' !in name && '\u0000' !in name) { "Name must be one path component" }
        return name
    }

    fun resolve(parent: String, name: String): String = normalize(normalize(parent).trimEnd('/') + "/" + leaf(name))

    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (normalized == "/") return null
        val parent = normalized.substringBeforeLast('/', "")
        return if (parent.isBlank()) "/" else parent
    }

    fun name(path: String): String = normalize(path).substringAfterLast('/').ifBlank { "/" }

    fun isWithin(root: String, candidate: String): Boolean {
        val normalizedRoot = normalize(root)
        val normalizedCandidate = normalize(candidate)
        return normalizedRoot == "/" || normalizedCandidate == normalizedRoot || normalizedCandidate.startsWith(normalizedRoot.trimEnd('/') + "/")
    }
}

data class RemoteSessionSecrets(
    val password: CharArray? = null,
    val privateKey: ByteArray? = null,
    val privateKeyPassphrase: CharArray? = null,
    val oauthRefreshToken: CharArray? = null,
) {
    fun clear() {
        password?.fill('\u0000')
        privateKey?.fill(0)
        privateKeyPassphrase?.fill('\u0000')
        oauthRefreshToken?.fill('\u0000')
    }
}

data class RemoteNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
    val mimeType: String? = null,
    val readable: Boolean = true,
    val writable: Boolean = true,
    val hidden: Boolean = name.startsWith('.'),
    val stableId: String? = null,
    val revision: String? = null,
)

data class RemoteFsCapabilities(
    val read: Boolean = true,
    val list: Boolean = true,
    val createFile: Boolean = true,
    val createDirectory: Boolean = true,
    val delete: Boolean = true,
    val rename: Boolean = true,
    val nativeMove: Boolean = true,
    val serverSideCopy: Boolean = false,
    val atomicReplace: Boolean = false,
    val seekRead: Boolean = false,
    val seekWrite: Boolean = false,
    val freeSpace: Boolean = false,
    val totalSpace: Boolean = false,
    val stableIdentity: Boolean = false,
    val readOnly: Boolean = false,
)

sealed class RemoteAccessException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class AuthenticationRequired(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class AuthenticationFailed(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class HostUnreachable(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class Timeout(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class Tls(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class Certificate(
        cause: Throwable? = null,
        val expected: String? = null,
        val observed: String? = null,
    ) : RemoteAccessException("Server certificate could not be trusted", cause)
    class HostKeyTrustRequired(val fingerprint: String, val algorithm: String? = null) : RemoteAccessException("SFTP host key trust required")
    class ServerIdentityChanged(val expected: String? = null, val observed: String? = null, cause: Throwable? = null) : RemoteAccessException("Server identity changed", cause)
    class PermissionDenied(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class ReadOnly(cause: Throwable? = null) : RemoteAccessException(cause = cause)
    class Protocol(cause: Throwable? = null) : RemoteAccessException(cause = cause)
}

interface RemoteFileSystem : Closeable {
    val capabilities: RemoteFsCapabilities
    fun list(path: String): List<RemoteNode>
    fun stat(path: String): RemoteNode?
    fun openInput(path: String, offset: Long = 0L): InputStream
    fun openOutput(path: String, offset: Long = 0L, truncate: Boolean = true): OutputStream
    fun createDirectory(path: String)
    fun delete(path: String, directory: Boolean)
    fun rename(sourcePath: String, destinationPath: String, replace: Boolean = false)
    fun copyServerSide(sourcePath: String, destinationPath: String): Boolean = false
    fun freeBytes(path: String): Long? = null
    fun totalBytes(path: String): Long? = null
}

interface RemoteFileSystemFactory {
    fun supports(protocol: RemoteProtocol): Boolean
    fun open(connection: NetworkConnection, secrets: RemoteSessionSecrets, settings: RemoteTransferSettings): RemoteFileSystem
}

class RemoteFileSystemFactoryRegistry(private val factories: List<RemoteFileSystemFactory>) {
    fun factoryFor(protocol: RemoteProtocol): RemoteFileSystemFactory = factories.firstOrNull { it.supports(protocol) }
        ?: throw RemoteAccessException.Protocol(IllegalStateException("No remote client is registered for $protocol"))
}
