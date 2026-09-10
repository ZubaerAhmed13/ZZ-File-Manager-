package com.zz.filemanager.core.remote

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Credential-free TLS identity inspection used only to show a leaf-certificate fingerprint before
 * an explicit trust decision. The probe never sends usernames, passwords, HTTP Authorization, or
 * file data and never installs a process-wide trust manager.
 */
object CertificateFingerprintProbe {
    fun probe(connection: NetworkConnection, settings: RemoteTransferSettings): String? {
        if (connection.protocol !in setOf(RemoteProtocol.FTPS, RemoteProtocol.WEBDAV)) return null
        if (connection.protocol == RemoteProtocol.WEBDAV && connection.tlsMode != RemoteTlsMode.HTTPS) return null
        return runCatching {
            when {
                connection.protocol == RemoteProtocol.FTPS && connection.tlsMode == RemoteTlsMode.EXPLICIT ->
                    probeExplicitFtps(connection.host, connection.port, settings)
                else -> probeDirectTls(connection.host, connection.port, settings)
            }
        }.getOrNull()
    }

    private fun probeDirectTls(host: String, port: Int, settings: RemoteTransferSettings): String {
        val capture = CapturingTrustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(capture), SecureRandom())
        }
        Socket().use { raw ->
            raw.connect(InetSocketAddress(host, port), settings.connectTimeoutMillis)
            raw.soTimeout = settings.readTimeoutMillis
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.use {
                it.soTimeout = settings.readTimeoutMillis
                it.startHandshake()
            }
        }
        return capture.fingerprint ?: throw IllegalStateException("Server did not present a certificate")
    }

    private fun probeExplicitFtps(host: String, port: Int, settings: RemoteTransferSettings): String {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), settings.connectTimeoutMillis)
            raw.soTimeout = settings.readTimeoutMillis
            val reader = BufferedReader(InputStreamReader(raw.getInputStream(), Charsets.US_ASCII))
            val writer = BufferedWriter(OutputStreamWriter(raw.getOutputStream(), Charsets.US_ASCII))
            require(readReply(reader) in 200..399) { "FTPS server did not send a usable greeting" }
            writer.write("AUTH TLS\r\n")
            writer.flush()
            require(readReply(reader) in setOf(234, 334)) { "FTPS server rejected AUTH TLS" }

            val capture = CapturingTrustManager()
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(capture), SecureRandom())
            }
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.use {
                it.soTimeout = settings.readTimeoutMillis
                it.startHandshake()
            }
            return capture.fingerprint ?: throw IllegalStateException("Server did not present a certificate")
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun readReply(reader: BufferedReader): Int {
        val first = reader.readLine() ?: throw IllegalStateException("Server closed before greeting")
        val code = first.take(3).toIntOrNull() ?: throw IllegalStateException("Invalid FTP response")
        if (first.length >= 4 && first[3] == '-') {
            val endPrefix = "$code "
            while (true) {
                val line = reader.readLine() ?: throw IllegalStateException("Incomplete FTP response")
                if (line.startsWith(endPrefix)) break
            }
        }
        return code
    }

    private class CapturingTrustManager : X509TrustManager {
        @Volatile var fingerprint: String? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw IllegalStateException("Missing server certificate")
            fingerprint = CertificatePinPolicy.display(leaf.encoded)
            // This is observation only. Normal/pinned connection code performs the real trust,
            // validity and hostname checks before any credentials or data are sent.
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
