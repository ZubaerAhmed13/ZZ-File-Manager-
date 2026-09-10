package com.zz.filemanager.core.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.SAXParserFactory

/** HTTP/HTTPS WebDAV with encoded URL segments, default TLS validation and bounded streaming PUT. */
class WebDavRemoteFileSystemFactory : RemoteFileSystemFactory {
    override fun supports(protocol: RemoteProtocol): Boolean = protocol == RemoteProtocol.WEBDAV

    override fun open(
        connection: NetworkConnection,
        secrets: RemoteSessionSecrets,
        settings: RemoteTransferSettings,
    ): RemoteFileSystem {
        require(connection.protocol == RemoteProtocol.WEBDAV)
        val base = createBaseUrl(connection)
        if (connection.tlsMode == RemoteTlsMode.HTTPS && !base.isHttps) {
            throw RemoteAccessException.Tls(IllegalArgumentException("HTTPS WebDAV requires an https:// endpoint"))
        }
        if (connection.tlsMode == RemoteTlsMode.NONE && base.isHttps) {
            throw RemoteAccessException.Protocol(IllegalArgumentException("HTTPS endpoint requires HTTPS WebDAV mode"))
        }

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(settings.connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(settings.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(settings.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((settings.connectTimeoutMillis + settings.readTimeoutMillis + settings.writeTimeoutMillis).toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)

        if (base.isHttps && connection.certificatePolicy == RemoteCertificatePolicy.PINNED) {
            val expected = connection.certificateSha256 ?: throw RemoteAccessException.Certificate()
            val trustManager = PinnedLeafTrustManager(expected)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            clientBuilder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }

        val password = secrets.password?.concatToString()
        return WebDavRemoteFileSystem(connection, base, clientBuilder.build(), password, settings)
    }

    private fun createBaseUrl(connection: NetworkConnection): HttpUrl {
        val raw = connection.host.trim()
        val supplied = if ("://" in raw) raw.toHttpUrlOrNull() else null
        val base = supplied ?: HttpUrl.Builder()
            .scheme(if (connection.tlsMode == RemoteTlsMode.HTTPS) "https" else "http")
            .host(raw)
            .port(connection.port)
            .build()
        val builder = base.newBuilder()
        if (!base.encodedPath.endsWith('/')) builder.addPathSegment("")
        return builder.build()
    }
}

private class WebDavRemoteFileSystem(
    private val connection: NetworkConnection,
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    private val password: String?,
    private val settings: RemoteTransferSettings,
) : RemoteFileSystem {
    private val discovered = discoverCapabilities()

    override val capabilities: RemoteFsCapabilities = RemoteFsCapabilities(
        read = true,
        list = discovered.dav,
        createFile = discovered.put,
        createDirectory = discovered.mkcol,
        delete = discovered.delete,
        rename = discovered.move,
        nativeMove = discovered.move,
        serverSideCopy = discovered.copy,
        atomicReplace = false,
        seekRead = false,
        seekWrite = false,
        stableIdentity = false,
    )

    override fun list(path: String): List<RemoteNode> {
        val output = ArrayList<RemoteNode>()
        kotlinx.coroutines.runBlocking {
            listPages(path, RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE) { output.addAll(it) }
        }
        return output
    }

    /** SAX emits bounded pages while the PROPFIND body is still being consumed. */
    override suspend fun listPages(path: String, pageSize: Int, onPage: suspend (List<RemoteNode>) -> Unit) {
        require(pageSize in 1..RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE)
        val normalized = RemotePath.normalize(path)
        propfindPages(normalized, depth = 1, pageSize = pageSize) { page ->
            val children = page.filterNot { it.path == normalized }
            if (children.isNotEmpty()) onPage(children)
        }
    }

    override fun stat(path: String): RemoteNode? {
        val normalized = RemotePath.normalize(path)
        return try {
            propfind(normalized, depth = 0).firstOrNull { it.path == normalized }
                ?: propfind(normalized, depth = 0).firstOrNull()
        } catch (error: RemoteAccessException.Protocol) {
            val http = error.cause as? DavHttpException
            if (http?.code == 404) null else throw error
        } catch (error: DavHttpException) {
            if (error.code == 404) null else throw mapDavError(error)
        }
    }

    override fun openInput(path: String, offset: Long): InputStream {
        require(offset >= 0L)
        val headers = if (offset > 0L) mapOf("Range" to "bytes=$offset-") else emptyMap()
        val response = execute("GET", path, headers = headers)
        if (offset > 0L && response.code != 206) {
            response.close()
            throw RemoteAccessException.Protocol(IllegalStateException("WebDAV server did not honor ranged GET"))
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw mapDavError(DavHttpException(code))
        }
        val body = response.body ?: run {
            response.close()
            throw RemoteAccessException.Protocol(IllegalStateException("WebDAV GET returned no body"))
        }
        return ResponseInputStream(body.byteStream(), response)
    }

    override fun openOutput(path: String, offset: Long, truncate: Boolean): OutputStream {
        require(offset >= 0L)
        if (offset != 0L || !truncate) {
            throw RemoteAccessException.Protocol(IllegalStateException("Standard WebDAV PUT does not guarantee offset writes"))
        }
        val input = PipedInputStream(STREAM_BUFFER_BYTES)
        val output = PipedOutputStream(input)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "zz-webdav-put").apply { isDaemon = true }
        }
        val future = executor.submit<Unit> {
            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    input.source().use { source -> sink.writeAll(source) }
                }
            }
            execute("PUT", path, body = requestBody).use { response ->
                if (!response.isSuccessful) throw DavHttpException(response.code)
            }
        }
        return StreamingDavOutputStream(output, input, executor, future, settings.writeTimeoutMillis + settings.readTimeoutMillis)
    }

    override fun createDirectory(path: String) {
        execute("MKCOL", path, body = EMPTY_BODY).use { response ->
            if (response.code !in setOf(201, 204, 405)) throw mapDavError(DavHttpException(response.code))
            if (response.code == 405 && stat(path) == null) throw mapDavError(DavHttpException(response.code))
        }
    }

    override fun delete(path: String, directory: Boolean) {
        execute("DELETE", path).use { response ->
            if (!response.isSuccessful && response.code != 404) throw mapDavError(DavHttpException(response.code))
        }
    }

    override fun rename(sourcePath: String, destinationPath: String, replace: Boolean) {
        val destination = urlFor(destinationPath).toString()
        execute(
            "MOVE",
            sourcePath,
            headers = mapOf("Destination" to destination, "Overwrite" to if (replace) "T" else "F"),
            body = EMPTY_BODY,
        ).use { response ->
            if (!response.isSuccessful) throw mapDavError(DavHttpException(response.code))
        }
    }

    override fun copyServerSide(sourcePath: String, destinationPath: String): Boolean {
        if (!discovered.copy) return false
        val destination = urlFor(destinationPath).toString()
        execute(
            "COPY",
            sourcePath,
            headers = mapOf("Destination" to destination, "Overwrite" to "F"),
            body = EMPTY_BODY,
        ).use { response ->
            if (response.code in 200..299) return true
            if (response.code in setOf(405, 501)) return false
            throw mapDavError(DavHttpException(response.code))
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        password?.let { /* Header values are request-scoped; no value is persisted by this adapter. */ }
    }

    private fun propfind(path: String, depth: Int): List<RemoteNode> {
        val output = ArrayList<RemoteNode>()
        kotlinx.coroutines.runBlocking {
            propfindPages(path, depth, RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE) { output.addAll(it) }
        }
        return output
    }

    private suspend fun propfindPages(
        path: String,
        depth: Int,
        pageSize: Int,
        onPage: suspend (List<RemoteNode>) -> Unit,
    ) {
        val xml = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
        execute(
            "PROPFIND",
            path,
            headers = mapOf("Depth" to depth.toString()),
            body = xml.toRequestBody("application/xml; charset=utf-8".toMediaType()),
        ).use { response ->
            if (response.code != 207 && !response.isSuccessful) throw mapDavError(DavHttpException(response.code))
            val body = response.body ?: throw RemoteAccessException.Protocol(IllegalStateException("WebDAV PROPFIND returned no body"))
            parseMultiStatusPages(body.byteStream(), pageSize, onPage)
        }
    }

    private fun discoverCapabilities(): DavCapabilities {
        return try {
            execute("OPTIONS", "/").use { response ->
                if (!response.isSuccessful) return DavCapabilities.minimum()
                val allow = response.headers.values("Allow").flatMap { it.split(',') }.map { it.trim().uppercase() }.toSet()
                val dav = response.header("DAV")?.isNotBlank() == true || "PROPFIND" in allow
                DavCapabilities(
                    dav = dav,
                    put = "PUT" in allow || dav,
                    mkcol = "MKCOL" in allow || dav,
                    delete = "DELETE" in allow || dav,
                    move = "MOVE" in allow || dav,
                    copy = "COPY" in allow,
                )
            }
        } catch (error: Throwable) {
            // TLS/certificate identity failures must remain visible to the test/connect workflow.
            if (mapDavError(error) is RemoteAccessException.Certificate) throw mapDavError(error)
            DavCapabilities.minimum()
        }
    }

    private fun execute(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): Response {
        try {
            val builder = Request.Builder().url(urlFor(path))
            connection.username?.takeIf { it.isNotBlank() }?.let { username ->
                val secret = password ?: ""
                builder.header("Authorization", Credentials.basic(username, secret, Charsets.UTF_8))
            }
            headers.forEach(builder::header)
            val actualBody = body ?: if (method in BODY_METHODS) EMPTY_BODY else null
            return client.newCall(builder.method(method, actualBody).build()).execute().also { response ->
                if (response.code in 300..399) {
                    response.close()
                    throw RemoteAccessException.Protocol(IllegalStateException("WebDAV redirect requires explicit endpoint review"))
                }
            }
        } catch (error: Throwable) {
            throw mapDavError(error)
        }
    }

    private fun urlFor(path: String): HttpUrl {
        val normalized = RemotePath.normalize(path)
        val builder = baseUrl.newBuilder()
        normalized.trim('/').split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun pathFromHref(href: String): String? {
        val resolved = baseUrl.resolve(href.trim()) ?: return null
        if (resolved.host != baseUrl.host || resolved.port != baseUrl.port || resolved.scheme != baseUrl.scheme) return null
        val baseSegments = baseUrl.pathSegments.filter(String::isNotEmpty)
        val resolvedSegments = resolved.pathSegments.filter(String::isNotEmpty)
        if (resolvedSegments.size < baseSegments.size || resolvedSegments.take(baseSegments.size) != baseSegments) return null
        val remoteSegments = resolvedSegments.drop(baseSegments.size)
        return RemotePath.normalize("/" + remoteSegments.joinToString("/"))
    }

    private suspend fun parseMultiStatusPages(
        stream: InputStream,
        pageSize: Int,
        onPage: suspend (List<RemoteNode>) -> Unit,
    ) {
        val page = ArrayList<RemoteNode>(pageSize)
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val handler = object : DefaultHandler() {
            private var current: MutableDavNode? = null
            private var tag: String? = null
            private val text = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                val name = (localName?.takeIf(String::isNotBlank) ?: qName.orEmpty()).substringAfter(':').lowercase()
                if (name == "response") current = MutableDavNode()
                if (name == "collection") current?.directory = true
                tag = name
                text.setLength(0)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (tag in VALUE_TAGS) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = (localName?.takeIf(String::isNotBlank) ?: qName.orEmpty()).substringAfter(':').lowercase()
                val value = text.toString().trim()
                when (name) {
                    "href" -> current?.href = value
                    "displayname" -> current?.displayName = value
                    "getcontentlength" -> current?.size = value.toLongOrNull()
                    "getlastmodified" -> current?.modified = parseHttpDate(value)
                    "getetag" -> current?.etag = value.takeIf(String::isNotBlank)
                    "response" -> {
                        val item = current
                        val remotePath = item?.href?.let(::pathFromHref)
                        if (item != null && remotePath != null) {
                            page += RemoteNode(
                                path = remotePath,
                                name = item.displayName?.takeIf(String::isNotBlank) ?: RemotePath.name(remotePath),
                                directory = item.directory,
                                sizeBytes = if (item.directory) null else item.size,
                                modifiedAtMillis = item.modified,
                                hidden = (item.displayName ?: RemotePath.name(remotePath)).startsWith('.'),
                                revision = item.etag ?: if (item.size != null || item.modified != null) "webdav:size=${item.size ?: -1};mtime=${item.modified ?: -1}" else null,
                            )
                            if (page.size == pageSize) {
                                val ready = page.toList()
                                page.clear()
                                kotlinx.coroutines.runBlocking { onPage(ready) }
                            }
                        }
                        current = null
                    }
                }
                tag = null
                text.setLength(0)
            }
        }
        try {
            stream.use { factory.newSAXParser().parse(it, handler) }
            if (page.isNotEmpty()) onPage(page.toList())
        } catch (error: Throwable) {
            throw RemoteAccessException.Protocol(error)
        }
    }

    private fun parseHttpDate(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private data class MutableDavNode(
        var href: String? = null,
        var displayName: String? = null,
        var directory: Boolean = false,
        var size: Long? = null,
        var modified: Long? = null,
        var etag: String? = null,
    )

    private data class DavCapabilities(
        val dav: Boolean,
        val put: Boolean,
        val mkcol: Boolean,
        val delete: Boolean,
        val move: Boolean,
        val copy: Boolean,
    ) {
        companion object {
            fun minimum() = DavCapabilities(dav = true, put = true, mkcol = true, delete = true, move = true, copy = false)
        }
    }

    companion object {
        private val BODY_METHODS = setOf("PUT", "PROPFIND", "MKCOL", "MOVE", "COPY")
        private val VALUE_TAGS = setOf("href", "displayname", "getcontentlength", "getlastmodified", "getetag")
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

private class ResponseInputStream(input: InputStream, private val response: Response) : FilterInputStream(input) {
    override fun close() {
        try { super.close() } finally { response.close() }
    }
}

private class StreamingDavOutputStream(
    private val output: PipedOutputStream,
    private val input: PipedInputStream,
    private val executor: java.util.concurrent.ExecutorService,
    private val future: Future<Unit>,
    private val completionTimeoutMillis: Int,
) : OutputStream() {
    private var closed = false

    override fun write(b: Int) = output.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)
    override fun flush() = output.flush()

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try { output.close() } catch (error: Throwable) { failure = error }
        try {
            future.get(completionTimeoutMillis.toLong().coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            failure = failure ?: (error.cause ?: error)
        } catch (error: TimeoutException) {
            future.cancel(true)
            failure = failure ?: RemoteAccessException.Timeout(error)
        } catch (error: Throwable) {
            failure = failure ?: error
        } finally {
            runCatching { input.close() }
            executor.shutdownNow()
        }
        failure?.let { throw mapDavError(it) }
    }
}

private class DavHttpException(val code: Int) : IOException("WebDAV HTTP $code")

/**
 * Per-connection pin trust. It intentionally accepts a currently valid self-signed leaf when its
 * SHA-256 pin matches; OkHttp's normal hostname verifier still validates the endpoint name.
 */
private class PinnedLeafTrustManager(expectedSha256: String) : X509TrustManager {
    private val expected = expectedSha256
    private val pin = CertificatePinPolicy(expectedSha256)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        leaf.checkValidity()
        if (!pin.matches(leaf.encoded)) {
            throw PinnedWebDavCertificateException(expected, CertificatePinPolicy.display(leaf.encoded))
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class PinnedWebDavCertificateException(
    val expected: String,
    val observed: String,
) : CertificateException("Server certificate fingerprint changed")

private fun findPinnedWebDavCertificateFailure(error: Throwable): PinnedWebDavCertificateException? {
    var current: Throwable? = error
    while (current != null) {
        if (current is PinnedWebDavCertificateException) return current
        current = current.cause
    }
    return null
}

private fun mapDavError(error: Throwable): RemoteAccessException {
    findPinnedWebDavCertificateFailure(error)?.let { pin ->
        return RemoteAccessException.Certificate(error, expected = pin.expected, observed = pin.observed)
    }
    return when (error) {
        is RemoteAccessException -> error
        is DavHttpException -> when (error.code) {
            401 -> RemoteAccessException.AuthenticationFailed(error)
            403 -> RemoteAccessException.PermissionDenied(error)
            408, 504 -> RemoteAccessException.Timeout(error)
            else -> RemoteAccessException.Protocol(error)
        }
        is SSLHandshakeException, is CertificateException -> RemoteAccessException.Certificate(error)
        is SocketTimeoutException -> RemoteAccessException.Timeout(error)
        is ConnectException -> RemoteAccessException.HostUnreachable(error)
        is SecurityException -> RemoteAccessException.PermissionDenied(error)
        is IOException -> RemoteAccessException.Protocol(error)
        else -> RemoteAccessException.Protocol(error)
    }
}
