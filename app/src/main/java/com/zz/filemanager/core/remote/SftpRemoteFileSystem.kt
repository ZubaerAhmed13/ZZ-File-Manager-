package com.zz.filemanager.core.remote

import com.hierynomus.sshj.sftp.RemoteResourceSelector
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/** SFTP implementation with mandatory SHA-256 host-key trust and offset-safe streaming. */
class SftpRemoteFileSystemFactory : RemoteFileSystemFactory {
    override fun supports(protocol: RemoteProtocol): Boolean = protocol == RemoteProtocol.SFTP

    override fun open(
        connection: NetworkConnection,
        secrets: RemoteSessionSecrets,
        settings: RemoteTransferSettings,
    ): RemoteFileSystem {
        require(connection.protocol == RemoteProtocol.SFTP)
        val ssh = SSHClient()
        ssh.connectTimeout = settings.connectTimeoutMillis
        ssh.timeout = settings.readTimeoutMillis
        val verifier = PinnedSha256HostKeyVerifier(connection.sshHostKeySha256)
        ssh.addHostKeyVerifier(verifier)

        try {
            ssh.connect(connection.host, connection.port)
        } catch (error: Throwable) {
            runCatching { ssh.close() }
            verifier.failure()?.let { throw it }
            throw mapSftpError(error)
        }

        try {
            val username = connection.username?.takeIf { it.isNotBlank() }
                ?: throw RemoteAccessException.AuthenticationRequired()
            when (connection.authenticationType) {
                RemoteAuthenticationType.PASSWORD -> {
                    val password = secrets.password ?: throw RemoteAccessException.AuthenticationRequired()
                    ssh.authPassword(username, password.copyOf())
                }
                RemoteAuthenticationType.PRIVATE_KEY -> {
                    val keyBytes = secrets.privateKey ?: throw RemoteAccessException.AuthenticationRequired()
                    val keyText = keyBytes.toString(Charsets.UTF_8)
                    val finder = secrets.privateKeyPassphrase?.let { PasswordUtils.createOneOff(it.copyOf()) }
                    val provider = ssh.loadKeys(keyText, null as String?, finder)
                    ssh.authPublickey(username, provider)
                }
                else -> throw RemoteAccessException.AuthenticationRequired()
            }
            ssh.connection.timeoutMs = settings.readTimeoutMillis
            return SftpRemoteFileSystem(ssh, ssh.newSFTPClient())
        } catch (error: Throwable) {
            runCatching { ssh.close() }
            throw mapSftpError(error)
        }
    }
}

internal class PinnedSha256HostKeyVerifier(private val expectedRaw: String?) : HostKeyVerifier {
    @Volatile private var observed: String? = null
    @Volatile private var algorithm: String? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = sha256Fingerprint(key)
        observed = fingerprint
        algorithm = key.algorithm
        val expected = expectedRaw?.trim()?.takeIf { it.isNotBlank() }
        return expected != null && normalizeFingerprint(expected) == normalizeFingerprint(fingerprint)
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    fun failure(): RemoteAccessException? {
        val current = observed ?: return null
        val expected = expectedRaw?.trim()?.takeIf { it.isNotBlank() }
        return if (expected == null) {
            RemoteAccessException.HostKeyTrustRequired(current, algorithm)
        } else if (normalizeFingerprint(expected) != normalizeFingerprint(current)) {
            RemoteAccessException.ServerIdentityChanged(expected = expected, observed = current)
        } else null
    }

    private fun normalizeFingerprint(value: String): String = value.trim().removeSuffix("=")

    private fun sha256Fingerprint(key: PublicKey): String {
        val wireKey = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wireKey)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}

private class SftpRemoteFileSystem(
    private val ssh: SSHClient,
    private val sftp: SFTPClient,
) : RemoteFileSystem {
    override val capabilities = RemoteFsCapabilities(
        read = true,
        list = true,
        createFile = true,
        createDirectory = true,
        delete = true,
        rename = true,
        nativeMove = true,
        serverSideCopy = false,
        atomicReplace = false,
        seekRead = true,
        seekWrite = true,
        freeSpace = false,
        totalSpace = false,
        stableIdentity = false,
    )

    override fun list(path: String): List<RemoteNode> {
        val output = ArrayList<RemoteNode>()
        kotlinx.coroutines.runBlocking {
            listPages(path, RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE) { output.addAll(it) }
        }
        return output
    }

    /**
     * SSHJ's RemoteDirectory.scan reads SFTP READDIR packets incrementally. A selector returning
     * CONTINUE prevents SSHJ from accumulating its own result list; pages are emitted as packets are
     * decoded, keeping memory bounded for very large directories.
     */
    override suspend fun listPages(path: String, pageSize: Int, onPage: suspend (List<RemoteNode>) -> Unit) {
        require(pageSize in 1..RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE)
        val normalized = RemotePath.normalize(path)
        val page = ArrayList<RemoteNode>(pageSize)
        try {
            sftp.sftpEngine.openDir(normalized).use { directory ->
                directory.scan(RemoteResourceSelector { info ->
                    page += info.toNode()
                    if (page.size == pageSize) {
                        val ready = page.toList()
                        page.clear()
                        kotlinx.coroutines.runBlocking { onPage(ready) }
                    }
                    RemoteResourceSelector.Result.CONTINUE
                })
            }
            if (page.isNotEmpty()) onPage(page.toList())
        } catch (error: Throwable) {
            throw mapSftpError(error)
        }
    }

    override fun stat(path: String): RemoteNode? = try {
        val normalized = RemotePath.normalize(path)
        val attrs = sftp.statExistence(normalized) ?: return null
        val directory = attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY
        RemoteNode(
            path = normalized,
            name = RemotePath.name(normalized),
            directory = directory,
            sizeBytes = if (directory) null else attrs.getSizeOrNull(),
            modifiedAtMillis = attrs.getModifiedMillisOrNull(),
            revision = attrs.revisionToken(),
        )
    } catch (error: Throwable) {
        throw mapSftpError(error)
    }

    override fun openInput(path: String, offset: Long): InputStream = try {
        require(offset >= 0L)
        val file = sftp.open(RemotePath.normalize(path), EnumSet.of(OpenMode.READ))
        ClosingRemoteInputStream(file.RemoteFileInputStream(offset), file)
    } catch (error: Throwable) {
        throw mapSftpError(error)
    }

    override fun openOutput(path: String, offset: Long, truncate: Boolean): OutputStream = try {
        require(offset >= 0L)
        val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        if (truncate) modes.add(OpenMode.TRUNC)
        val file = sftp.open(RemotePath.normalize(path), modes)
        ClosingRemoteOutputStream(file.RemoteFileOutputStream(offset), file)
    } catch (error: Throwable) {
        throw mapSftpError(error)
    }

    override fun createDirectory(path: String) {
        try { sftp.mkdir(RemotePath.normalize(path)) } catch (error: Throwable) { throw mapSftpError(error) }
    }

    override fun delete(path: String, directory: Boolean) {
        try {
            val normalized = RemotePath.normalize(path)
            if (directory) sftp.rmdir(normalized) else sftp.rm(normalized)
        } catch (error: Throwable) {
            throw mapSftpError(error)
        }
    }

    override fun rename(sourcePath: String, destinationPath: String, replace: Boolean) {
        try {
            val source = RemotePath.normalize(sourcePath)
            val target = RemotePath.normalize(destinationPath)
            if (replace) runCatching { stat(target) }.getOrNull()?.let { delete(target, it.directory) }
            sftp.rename(source, target)
        } catch (error: Throwable) {
            throw mapSftpError(error)
        }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
        runCatching { ssh.close() }
    }
}

private fun RemoteResourceInfo.toNode(): RemoteNode {
    val attrs = attributes
    return RemoteNode(
        path = RemotePath.normalize(path),
        name = name,
        directory = isDirectory,
        sizeBytes = if (isDirectory) null else attrs.getSizeOrNull(),
        modifiedAtMillis = attrs.getModifiedMillisOrNull(),
        hidden = name.startsWith('.'),
        revision = attrs.revisionToken(),
    )
}

private class ClosingRemoteInputStream(input: InputStream, private val remote: RemoteFile) : FilterInputStream(input) {
    override fun close() {
        var failure: Throwable? = null
        try { super.close() } catch (error: Throwable) { failure = error }
        try { remote.close() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        failure?.let { throw it }
    }
}

private class ClosingRemoteOutputStream(output: OutputStream, private val remote: RemoteFile) : FilterOutputStream(output) {
    override fun close() {
        var failure: Throwable? = null
        try { super.close() } catch (error: Throwable) { failure = error }
        try { remote.close() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) }
        failure?.let { throw it }
    }
}

private fun FileAttributes.getSizeOrNull(): Long? = if (has(FileAttributes.Flag.SIZE)) size else null
private fun FileAttributes.getModifiedMillisOrNull(): Long? = if (has(FileAttributes.Flag.ACMODTIME)) mtime * 1_000L else null
private fun FileAttributes.revisionToken(): String? {
    val sizePart = getSizeOrNull()
    val timePart = getModifiedMillisOrNull()
    return if (sizePart != null || timePart != null) "sftp:size=${sizePart ?: -1};mtime=${timePart ?: -1}" else null
}

private fun mapSftpError(error: Throwable): RemoteAccessException = when (error) {
    is RemoteAccessException -> error
    is UserAuthException -> RemoteAccessException.AuthenticationFailed(error)
    is SocketTimeoutException -> RemoteAccessException.Timeout(error)
    is ConnectException -> RemoteAccessException.HostUnreachable(error)
    is SecurityException -> RemoteAccessException.PermissionDenied(error)
    else -> RemoteAccessException.Protocol(error)
}
