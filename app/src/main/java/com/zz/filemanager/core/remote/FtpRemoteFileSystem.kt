package com.zz.filemanager.core.remote

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class FtpRemoteFileSystemFactory : RemoteFileSystemFactory {
    override fun supports(protocol: RemoteProtocol): Boolean = protocol == RemoteProtocol.FTP || protocol == RemoteProtocol.FTPS

    override fun open(connection: NetworkConnection, secrets: RemoteSessionSecrets, settings: RemoteTransferSettings): RemoteFileSystem {
        val client: FTPClient = when (connection.protocol) {
            RemoteProtocol.FTP -> FTPClient()
            RemoteProtocol.FTPS -> FTPSClient(connection.tlsMode == RemoteTlsMode.IMPLICIT).apply {
                // Commons Net validates the certificate chain by default, but hostname checking is
                // opt-in. Keep endpoint identification enabled for every FTPS connection.
                setEndpointCheckingEnabled(true)
                if (connection.certificatePolicy == RemoteCertificatePolicy.PINNED) {
                    val expected = connection.certificateSha256 ?: throw RemoteAccessException.Certificate()
                    setTrustManager(PinnedCertificateTrustManager(expected))
                }
            }
            else -> throw RemoteAccessException.Protocol()
        }

        try {
            client.setConnectTimeout(settings.connectTimeoutMillis)
            client.setDefaultTimeout(settings.readTimeoutMillis)
            client.setDataTimeout(Duration.ofMillis(settings.readTimeoutMillis.toLong()))
            client.setControlEncoding("UTF-8")
            client.setRemoteVerificationEnabled(true)
            client.connect(connection.host, connection.port)
            client.setSoTimeout(settings.readTimeoutMillis)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw RemoteAccessException.HostUnreachable()

            val username = when (connection.authenticationType) {
                RemoteAuthenticationType.GUEST, RemoteAuthenticationType.NONE -> connection.username ?: "anonymous"
                else -> connection.username ?: throw RemoteAccessException.AuthenticationRequired()
            }
            val password = secrets.password?.concatToString().orEmpty()
            try {
                if (!client.login(username, password)) throw RemoteAccessException.AuthenticationFailed()
            } finally {
                // Commons Net requires String credentials at this boundary. The String is not
                // retained by our repositories or logs; the source CharArray is cleared upstream.
            }

            if (client is FTPSClient) {
                client.execPBSZ(0L)
                client.execPROT("P")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.setFileTransferMode(FTP.STREAM_TRANSFER_MODE)
            return FtpRemoteFileSystem(client)
        } catch (error: Throwable) {
            runCatching { if (client.isConnected) client.disconnect() }
            throw mapConnectFailure(error)
        }
    }

    private fun mapConnectFailure(error: Throwable): Throwable = when (error) {
        is RemoteAccessException -> error
        is SocketTimeoutException -> RemoteAccessException.Timeout(error)
        is SSLHandshakeException, is CertificateException -> RemoteAccessException.Certificate(error)
        is IOException -> RemoteAccessException.HostUnreachable(error)
        else -> RemoteAccessException.Protocol(error)
    }
}

private class FtpRemoteFileSystem(private val client: FTPClient) : RemoteFileSystem {
    private val restSupported: Boolean = runCatching {
        client.hasFeature("REST", "STREAM") || client.hasFeature("REST")
    }.getOrDefault(false)

    override val capabilities: RemoteFsCapabilities = RemoteFsCapabilities(
        read = true,
        list = true,
        createFile = true,
        createDirectory = true,
        delete = true,
        rename = true,
        nativeMove = true,
        serverSideCopy = false,
        atomicReplace = false,
        seekRead = restSupported,
        seekWrite = restSupported,
        stableIdentity = false,
    )

    override fun list(path: String): List<RemoteNode> {
        val normalized = RemotePath.normalize(path)
        val files = client.listFiles(normalized) ?: throw RemoteAccessException.Protocol()
        return files.asSequence()
            .filter { it.isValid && it.name != "." && it.name != ".." }
            .map { it.toNode(RemotePath.resolve(normalized, it.name)) }
            .toList()
    }

    override fun stat(path: String): RemoteNode? {
        val normalized = RemotePath.normalize(path)
        if (normalized == "/") return RemoteNode("/", "/", directory = true, stableId = null, revision = null)
        val direct = runCatching { client.mlistFile(normalized) }.getOrNull()
        if (direct != null && direct.isValid) return direct.toNode(normalized)
        val parent = RemotePath.parent(normalized) ?: return null
        val name = RemotePath.name(normalized)
        return runCatching { client.listFiles(parent) }
            .getOrNull()
            ?.firstOrNull { it.isValid && it.name == name }
            ?.toNode(normalized)
    }

    override fun openInput(path: String, offset: Long): InputStream {
        require(offset >= 0L)
        if (offset > 0L && !restSupported) throw RemoteAccessException.Protocol(IllegalStateException("FTP REST resume is unavailable"))
        if (offset > 0L) client.setRestartOffset(offset)
        val stream = client.retrieveFileStream(RemotePath.normalize(path)) ?: throw RemoteAccessException.Protocol()
        return PendingInput(stream, client)
    }

    override fun openOutput(path: String, offset: Long, truncate: Boolean): OutputStream {
        require(offset >= 0L)
        if (offset > 0L && !restSupported) throw RemoteAccessException.Protocol(IllegalStateException("FTP REST resume is unavailable"))
        val normalized = RemotePath.normalize(path)
        if (offset > 0L) client.setRestartOffset(offset)
        val stream = when {
            !truncate && offset == 0L -> client.appendFileStream(normalized)
            else -> client.storeFileStream(normalized)
        } ?: throw RemoteAccessException.Protocol()
        return PendingOutput(stream, client)
    }

    override fun createDirectory(path: String) {
        if (!client.makeDirectory(RemotePath.normalize(path))) throw RemoteAccessException.PermissionDenied()
    }

    override fun delete(path: String, directory: Boolean) {
        val ok = if (directory) client.removeDirectory(RemotePath.normalize(path)) else client.deleteFile(RemotePath.normalize(path))
        if (!ok) throw RemoteAccessException.PermissionDenied()
    }

    override fun rename(sourcePath: String, destinationPath: String, replace: Boolean) {
        // FTP RNFR/RNTO overwrite semantics vary by server, so this adapter never claims atomic
        // replacement. The shared transaction coordinator handles safe replace by backup/commit.
        if (replace && stat(destinationPath) != null) throw RemoteAccessException.Protocol(IllegalStateException("Atomic FTP replace is not guaranteed"))
        if (!client.rename(RemotePath.normalize(sourcePath), RemotePath.normalize(destinationPath))) throw RemoteAccessException.PermissionDenied()
    }

    override fun close() {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }

    private fun FTPFile.toNode(path: String): RemoteNode {
        val modified = runCatching { timestamp?.timeInMillis }.getOrNull()
        val size = if (isDirectory) null else size.takeIf { it >= 0L }
        val revision = if (!isDirectory) "ftp:${size ?: -1L}:${modified ?: -1L}" else null
        return RemoteNode(
            path = path,
            name = name,
            directory = isDirectory,
            sizeBytes = size,
            modifiedAtMillis = modified,
            readable = true,
            writable = true,
            hidden = name.startsWith('.'),
            stableId = null,
            revision = revision,
        )
    }

    private class PendingInput(delegate: InputStream, private val client: FTPClient) : FilterInputStream(delegate) {
        override fun close() {
            var closeError: Throwable? = null
            try { super.close() } catch (error: Throwable) { closeError = error }
            val complete = runCatching { client.completePendingCommand() }.getOrDefault(false)
            if (closeError != null) throw closeError
            if (!complete) throw IOException("FTP server did not confirm transfer completion")
        }
    }

    private class PendingOutput(delegate: OutputStream, private val client: FTPClient) : FilterOutputStream(delegate) {
        override fun close() {
            var closeError: Throwable? = null
            try { super.close() } catch (error: Throwable) { closeError = error }
            val complete = runCatching { client.completePendingCommand() }.getOrDefault(false)
            if (closeError != null) throw closeError
            if (!complete) throw IOException("FTP server did not confirm upload completion")
        }
    }
}

/** Explicit per-connection trust decision: only the pinned leaf certificate is accepted. */
private class PinnedCertificateTrustManager(expectedSha256: String) : X509TrustManager {
    private val expected = normalizeFingerprint(expectedSha256)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        certificate.checkValidity()
        val actual = sha256(certificate.encoded)
        if (actual != expected) throw CertificateException("Server certificate fingerprint changed")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        private fun normalizeFingerprint(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
