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
                // Hostname verification remains mandatory even when a user explicitly pins a
                // self-signed leaf. Pinning replaces CA trust only; it never disables endpoint ID.
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
            if (!client.login(username, password)) throw RemoteAccessException.AuthenticationFailed()

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

    private fun mapConnectFailure(error: Throwable): Throwable {
        findPinnedCertificateFailure(error)?.let { pin ->
            return RemoteAccessException.Certificate(error, expected = pin.expected, observed = pin.observed)
        }
        return when (error) {
            is RemoteAccessException -> error
            is SocketTimeoutException -> RemoteAccessException.Timeout(error)
            is SSLHandshakeException, is CertificateException -> RemoteAccessException.Certificate(error)
            is IOException -> RemoteAccessException.HostUnreachable(error)
            else -> RemoteAccessException.Protocol(error)
        }
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
        val output = ArrayList<RemoteNode>()
        kotlinx.coroutines.runBlocking {
            listPages(path, RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE) { output.addAll(it) }
        }
        return output
    }

    /** Apache Commons Net's FTPListParseEngine exposes true bounded pages over one listing session. */
    override suspend fun listPages(path: String, pageSize: Int, onPage: suspend (List<RemoteNode>) -> Unit) {
        require(pageSize in 1..RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE)
        val normalized = RemotePath.normalize(path)
        try {
            val engine = client.initiateListParsing(normalized)
            while (engine.hasNext()) {
                val page = engine.getNext(pageSize)
                    .asSequence()
                    .filter { it.isValid && it.name != "." && it.name != ".." }
                    .map { it.toNode(RemotePath.resolve(normalized, it.name)) }
                    .toList()
                if (page.isNotEmpty()) onPage(page)
            }
        } catch (error: Throwable) {
            throw mapFtpOperationError(error)
        }
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

/** Explicit per-connection trust decision: only the pinned, currently valid leaf certificate is accepted. */
private class PinnedCertificateTrustManager(expectedSha256: String) : X509TrustManager {
    private val expected = expectedSha256
    private val pin = CertificatePinPolicy(expectedSha256)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        certificate.checkValidity()
        if (!pin.matches(certificate.encoded)) {
            throw PinnedCertificateException(expected, CertificatePinPolicy.display(certificate.encoded))
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class PinnedCertificateException(
    val expected: String,
    val observed: String,
) : CertificateException("Server certificate fingerprint changed")

private fun findPinnedCertificateFailure(error: Throwable): PinnedCertificateException? {
    var current: Throwable? = error
    while (current != null) {
        if (current is PinnedCertificateException) return current
        current = current.cause
    }
    return null
}

private fun mapFtpOperationError(error: Throwable): RemoteAccessException = when (error) {
    is RemoteAccessException -> error
    is SocketTimeoutException -> RemoteAccessException.Timeout(error)
    is IOException -> RemoteAccessException.Protocol(error)
    is SecurityException -> RemoteAccessException.PermissionDenied(error)
    else -> RemoteAccessException.Protocol(error)
}
