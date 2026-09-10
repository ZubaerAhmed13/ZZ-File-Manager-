package com.zz.filemanager.core.remote

import java.net.URI
import java.nio.charset.StandardCharsets
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        start()
    }

    val port: Int get() = server.port

    override fun close() {
        server.shutdown()
    }

    @Synchronized
    private fun handle(request: RecordedRequest): MockResponse {
        val path = requestPath(request)
        return when (request.method?.uppercase()) {
            "OPTIONS" -> response(200)
                .addHeader("DAV", "1,2")
                .addHeader("Allow", "OPTIONS, PROPFIND, GET, PUT, MKCOL, DELETE, MOVE, COPY")
            "PROPFIND" -> propfind(request, path)
            "MKCOL" -> {
                nodes[path] = Node(directory = true)
                response(201)
            }
            "PUT" -> {
                nodes[path] = Node(directory = false, bytes = request.body.readByteArray())
                response(201)
            }
            "GET" -> {
                val node = nodes[path]
                if (node == null || node.directory) response(404)
                else response(200, node.bytes, "application/octet-stream")
            }
            "MOVE" -> {
                val destination = destinationPath(request)
                val node = nodes.remove(path)
                if (node == null) response(404)
                else {
                    nodes[destination] = node
                    moveDescendants(path, destination)
                    response(201)
                }
            }
            "COPY" -> {
                val destination = destinationPath(request)
                val node = nodes[path]
                if (node == null) response(404)
                else {
                    nodes[destination] = Node(node.directory, node.bytes.copyOf())
                    response(201)
                }
            }
            "DELETE" -> {
                nodes.remove(path)
                nodes.keys.filter { it.startsWith(path.trimEnd('/') + "/") }.toList().forEach(nodes::remove)
                response(204)
            }
            else -> response(405)
        }
    }

    private fun propfind(request: RecordedRequest, path: String): MockResponse {
        val node = nodes[path] ?: return response(404)
        val depth = request.getHeader("Depth") ?: "0"
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
        return response(207, xml, "application/xml; charset=utf-8")
    }

    private fun requestPath(request: RecordedRequest): String {
        val encoded = request.path ?: "/"
        return normalize(URI("http://localhost$encoded").path)
    }

    private fun destinationPath(request: RecordedRequest): String {
        val raw = request.getHeader("Destination") ?: error("Destination required")
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

    private fun response(code: Int, body: ByteArray = ByteArray(0), contentType: String? = null): MockResponse {
        val response = MockResponse().setResponseCode(code)
        contentType?.let { response.addHeader("Content-Type", it) }
        if (body.isNotEmpty()) response.setBody(Buffer().write(body))
        return response
    }
}
