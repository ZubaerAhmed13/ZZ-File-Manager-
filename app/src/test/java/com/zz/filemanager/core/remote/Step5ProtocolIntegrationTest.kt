package com.zz.filemanager.core.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step5ProtocolIntegrationTest {
    @Test
    fun protocolRegistryContainsEveryStep5NetworkProtocol() {
        val registry = RemoteFileSystemFactoryRegistry(
            listOf(
                SmbRemoteFileSystemFactory(),
                FtpRemoteFileSystemFactory(),
                SftpRemoteFileSystemFactory(),
                WebDavRemoteFileSystemFactory(),
            ),
        )
        listOf(RemoteProtocol.SMB, RemoteProtocol.FTP, RemoteProtocol.FTPS, RemoteProtocol.SFTP, RemoteProtocol.WEBDAV)
            .forEach { protocol -> assertTrue(registry.factoryFor(protocol).supports(protocol)) }
    }

    @Test
    fun webDavClientPerformsBrowseUploadDownloadRenameCopyAndDeleteAgainstDisposableServer() {
        DisposableWebDavServer().use { server ->
            val connection = NetworkConnection(
                protocol = RemoteProtocol.WEBDAV,
                displayName = "CI WebDAV",
                host = "127.0.0.1",
                port = server.port,
                authenticationType = RemoteAuthenticationType.NONE,
                tlsMode = RemoteTlsMode.NONE,
                rootPath = "/",
            )
            WebDavRemoteFileSystemFactory().open(
                connection,
                RemoteSessionSecrets(),
                RemoteTransferSettings(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000),
            ).use { fs ->
                assertTrue(fs.capabilities.list)
                assertTrue(fs.capabilities.createFile)
                assertTrue(fs.capabilities.createDirectory)
                assertTrue(fs.capabilities.delete)
                assertTrue(fs.capabilities.rename)
                assertTrue(fs.capabilities.serverSideCopy)
                assertFalse(fs.capabilities.seekWrite)

                fs.createDirectory("/folder")
                val payload = "hello-webdav".encodeToByteArray()
                fs.openOutput("/folder/hello.txt", 0L, truncate = true).use { it.write(payload) }
                assertEquals(payload.size.toLong(), fs.stat("/folder/hello.txt")?.sizeBytes)
                assertTrue(fs.list("/folder").any { it.name == "hello.txt" })
                assertArrayEquals(payload, fs.openInput("/folder/hello.txt").use { it.readBytes() })

                fs.rename("/folder/hello.txt", "/folder/renamed.txt", replace = false)
                assertTrue(fs.stat("/folder/renamed.txt") != null)
                assertTrue(fs.copyServerSide("/folder/renamed.txt", "/folder/copied.txt"))
                assertArrayEquals(payload, fs.openInput("/folder/copied.txt").use { it.readBytes() })

                fs.delete("/folder/copied.txt", directory = false)
                fs.delete("/folder/renamed.txt", directory = false)
                fs.delete("/folder", directory = true)
                assertTrue(fs.stat("/folder") == null)
            }
        }
    }
}

private class DisposableWebDavServer : AutoCloseable {
    private data class Node(val directory: Boolean, var bytes: ByteArray = ByteArray(0))

    private val nodes = linkedMapOf("/" to Node(directory = true))
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { exchange -> handle(exchange) }
        start()
    }
    val port: Int get() = server.address.port

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = normalize(exchange.requestURI.path)
            when (exchange.requestMethod.uppercase()) {
                "OPTIONS" -> {
                    exchange.responseHeaders.add("DAV", "1,2")
                    exchange.responseHeaders.add("Allow", "OPTIONS, PROPFIND, GET, PUT, MKCOL, DELETE, MOVE, COPY")
                    send(exchange, 200)
                }
                "PROPFIND" -> propfind(exchange, path)
                "MKCOL" -> {
                    nodes[path] = Node(directory = true)
                    send(exchange, 201)
                }
                "PUT" -> {
                    nodes[path] = Node(directory = false, bytes = exchange.requestBody.use { it.readBytes() })
                    send(exchange, 201)
                }
                "GET" -> {
                    val node = nodes[path]
                    if (node == null || node.directory) send(exchange, 404)
                    else send(exchange, 200, node.bytes, "application/octet-stream")
                }
                "MOVE" -> {
                    val destination = destinationPath(exchange)
                    val node = nodes.remove(path)
                    if (node == null) send(exchange, 404)
                    else {
                        nodes[destination] = node
                        moveDescendants(path, destination)
                        send(exchange, 201)
                    }
                }
                "COPY" -> {
                    val destination = destinationPath(exchange)
                    val node = nodes[path]
                    if (node == null) send(exchange, 404)
                    else {
                        nodes[destination] = Node(node.directory, node.bytes.copyOf())
                        send(exchange, 201)
                    }
                }
                "DELETE" -> {
                    nodes.remove(path)
                    nodes.keys.filter { it.startsWith(path.trimEnd('/') + "/") }.toList().forEach(nodes::remove)
                    send(exchange, 204)
                }
                else -> send(exchange, 405)
            }
        } catch (_: Throwable) {
            runCatching { send(exchange, 500) }
        } finally {
            exchange.close()
        }
    }

    private fun propfind(exchange: HttpExchange, path: String) {
        val node = nodes[path]
        if (node == null) {
            send(exchange, 404)
            return
        }
        val depth = exchange.requestHeaders.getFirst("Depth") ?: "0"
        val selected = buildList {
            add(path to node)
            if (depth == "1") {
                nodes.forEach { (candidatePath, candidateNode) ->
                    if (candidatePath != path && parent(candidatePath) == path) add(candidatePath to candidateNode)
                }
            }
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">")
            selected.forEach { (candidatePath, candidate) ->
                append("<d:response><d:href>").append(xml(candidatePath)).append("</d:href><d:propstat><d:prop>")
                append("<d:displayname>").append(xml(candidatePath.substringAfterLast('/').ifBlank { "/" })).append("</d:displayname>")
                if (candidate.directory) append("<d:resourcetype><d:collection/></d:resourcetype>") else append("<d:resourcetype/>")
                if (!candidate.directory) append("<d:getcontentlength>").append(candidate.bytes.size).append("</d:getcontentlength>")
                append("<d:getetag>\"").append(candidate.bytes.contentHashCode()).append("\"</d:getetag>")
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }.toByteArray(StandardCharsets.UTF_8)
        send(exchange, 207, xml, "application/xml; charset=utf-8")
    }

    private fun destinationPath(exchange: HttpExchange): String {
        val raw = exchange.requestHeaders.getFirst("Destination") ?: error("Destination required")
        return normalize(URI(raw).path)
    }

    private fun moveDescendants(source: String, destination: String) {
        val prefix = source.trimEnd('/') + "/"
        nodes.keys.filter { it.startsWith(prefix) }.toList().forEach { old ->
            val child = nodes.remove(old) ?: return@forEach
            nodes[destination.trimEnd('/') + old.removePrefix(source)] = child
        }
    }

    private fun parent(path: String): String {
        val normalized = normalize(path)
        if (normalized == "/") return "/"
        return normalized.substringBeforeLast('/', "").ifBlank { "/" }
    }

    private fun normalize(path: String): String = RemotePath.normalize(path)

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun send(exchange: HttpExchange, code: Int, body: ByteArray = ByteArray(0), contentType: String? = null) {
        contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
        if (code == 204) {
            exchange.sendResponseHeaders(code, -1L)
            return
        }
        exchange.sendResponseHeaders(code, body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }
}
