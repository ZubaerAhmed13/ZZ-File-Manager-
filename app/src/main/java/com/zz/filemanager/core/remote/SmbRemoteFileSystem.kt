package com.zz.filemanager.core.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** SMB2/SMB3 only. SMB1 is never offered to the negotiator. */
class SmbRemoteFileSystemFactory : RemoteFileSystemFactory {
    override fun supports(protocol: RemoteProtocol): Boolean = protocol == RemoteProtocol.SMB

    override fun open(
        connection: NetworkConnection,
        secrets: RemoteSessionSecrets,
        settings: RemoteTransferSettings,
    ): RemoteFileSystem {
        require(connection.protocol == RemoteProtocol.SMB)
        val shareName = connection.share?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw RemoteAccessException.Protocol(IllegalArgumentException("SMB share is required"))
        val ioTimeout = maxOf(settings.readTimeoutMillis, settings.writeTimeoutMillis).coerceAtLeast(1_000)
        val config = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_2_0_2,
            )
            .withTimeout(ioTimeout.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(ioTimeout.toLong(), TimeUnit.MILLISECONDS)
            .withSigningEnabled(true)
            .build()
        val client = SMBClient(config)
        var transport: Connection? = null
        var session: Session? = null
        try {
            transport = client.connect(connection.host, connection.port)
            val auth = when (connection.authenticationType) {
                RemoteAuthenticationType.GUEST -> AuthenticationContext.guest()
                RemoteAuthenticationType.NONE -> AuthenticationContext.anonymous()
                RemoteAuthenticationType.PASSWORD -> {
                    val username = connection.username?.takeIf { it.isNotBlank() }
                        ?: throw RemoteAccessException.AuthenticationRequired()
                    val password = secrets.password ?: throw RemoteAccessException.AuthenticationRequired()
                    AuthenticationContext(username, password, connection.domain?.takeIf { it.isNotBlank() })
                }
                else -> throw RemoteAccessException.AuthenticationRequired()
            }
            session = transport.authenticate(auth)
            val share = session.connectShare(shareName) as? DiskShare
                ?: throw RemoteAccessException.Protocol(IllegalStateException("Selected SMB share is not a disk share"))
            return SmbRemoteFileSystem(client, transport, session, share)
        } catch (error: Throwable) {
            runCatching { session?.close() }
            runCatching { transport?.close() }
            runCatching { client.close() }
            throw mapSmbError(error)
        }
    }
}

private class SmbRemoteFileSystem(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : RemoteFileSystem {
    override val capabilities = RemoteFsCapabilities(
        read = true,
        list = true,
        createFile = true,
        createDirectory = true,
        delete = true,
        rename = true,
        nativeMove = true,
        serverSideCopy = true,
        atomicReplace = true,
        seekRead = true,
        seekWrite = true,
        freeSpace = false,
        totalSpace = false,
        stableIdentity = true,
    )

    override fun list(path: String): List<RemoteNode> = try {
        val output = ArrayList<RemoteNode>()
        kotlinx.coroutines.runBlocking {
            listPages(path, RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE) { output.addAll(it) }
        }
        output
    } catch (error: Throwable) {
        throw mapSmbError(error)
    }

    /** SMBJ Directory.iterator() fetches SMB2 QUERY_DIRECTORY buffers lazily instead of collecting DiskShare.list(). */
    override suspend fun listPages(path: String, pageSize: Int, onPage: suspend (List<RemoteNode>) -> Unit) {
        require(pageSize in 1..RemoteFileSystem.MAX_REMOTE_DIRECTORY_PAGE_SIZE)
        val normalized = RemotePath.normalize(path)
        val directory = try {
            share.openDirectory(
                toSmbPath(normalized),
                EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                allShareAccess(),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (error: Throwable) {
            throw mapSmbError(error)
        }
        try {
            val page = ArrayList<RemoteNode>(pageSize)
            val iterator = directory.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (info.fileName == "." || info.fileName == "..") continue
                page += info.toNode(normalized)
                if (page.size == pageSize) {
                    onPage(page.toList())
                    page.clear()
                }
            }
            if (page.isNotEmpty()) onPage(page.toList())
        } catch (error: Throwable) {
            throw mapSmbError(error)
        } finally {
            runCatching { directory.close() }
        }
    }

    override fun stat(path: String): RemoteNode? {
        return try {
            val normalized = RemotePath.normalize(path)
            if (normalized == "/") {
                RemoteNode(
                    path = "/",
                    name = "/",
                    directory = true,
                    stableId = "smb-share-root",
                    revision = "smb-share-root",
                )
            } else {
                val parent = RemotePath.parent(normalized) ?: "/"
                val name = RemotePath.name(normalized)
                share.list(toSmbPath(parent), name)
                    .firstOrNull { it.fileName == name }
                    ?.toNode(parent)
                    ?: share.list(toSmbPath(parent), name)
                        .firstOrNull { it.fileName.equals(name, ignoreCase = true) }
                        ?.toNode(parent)
            }
        } catch (error: SMBApiException) {
            if (isMissing(error)) null else throw mapSmbError(error)
        } catch (error: Throwable) {
            throw mapSmbError(error)
        }
    }

    override fun openInput(path: String, offset: Long): InputStream = try {
        require(offset >= 0L)
        val file = share.openFile(
            toSmbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            allShareAccess(),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        SmbOffsetInputStream(file, offset)
    } catch (error: Throwable) {
        throw mapSmbError(error)
    }

    override fun openOutput(path: String, offset: Long, truncate: Boolean): OutputStream = try {
        require(offset >= 0L)
        val disposition = if (truncate && offset == 0L) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_OPEN_IF
        val file = share.openFile(
            toSmbPath(path),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.FILE_READ_ATTRIBUTES),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            allShareAccess(),
            disposition,
            null,
        )
        if (truncate && offset > 0L) file.setLength(offset)
        SmbOffsetOutputStream(file, offset)
    } catch (error: Throwable) {
        throw mapSmbError(error)
    }

    override fun createDirectory(path: String) {
        try { share.mkdir(toSmbPath(path)) } catch (error: Throwable) { throw mapSmbError(error) }
    }

    override fun delete(path: String, directory: Boolean) {
        try {
            val smbPath = toSmbPath(path)
            if (directory) share.rmdir(smbPath, false) else share.rm(smbPath)
        } catch (error: Throwable) {
            throw mapSmbError(error)
        }
    }

    override fun rename(sourcePath: String, destinationPath: String, replace: Boolean) {
        try {
            val source = toSmbPath(sourcePath)
            val target = toSmbPath(destinationPath)
            val node = stat(sourcePath) ?: throw RemoteAccessException.Protocol(IOException("SMB source no longer exists"))
            openRenameHandle(source, node.directory).use { entry -> entry.rename(target, replace) }
        } catch (error: Throwable) {
            throw mapSmbError(error)
        }
    }

    override fun copyServerSide(sourcePath: String, destinationPath: String): Boolean {
        return try {
            val source = share.openFile(
                toSmbPath(sourcePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                allShareAccess(),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            source.use { src ->
                val destination = share.openFile(
                    toSmbPath(destinationPath),
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.FILE_READ_ATTRIBUTES),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    allShareAccess(),
                    SMB2CreateDisposition.FILE_CREATE,
                    null,
                )
                destination.use { dst -> src.remoteCopyTo(dst) }
            }
            true
        } catch (error: SMBApiException) {
            if (error.statusCode == NtStatus.STATUS_NOT_SUPPORTED.value) false else throw mapSmbError(error)
        } catch (error: Throwable) {
            if (error.message?.contains("not supported", ignoreCase = true) == true ||
                error.message?.contains("invalid device request", ignoreCase = true) == true
            ) false else throw mapSmbError(error)
        }
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private fun openRenameHandle(path: String, directory: Boolean): DiskEntry {
        val access = EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES)
        return if (directory) {
            share.openDirectory(path, access, null, allShareAccess(), SMB2CreateDisposition.FILE_OPEN, null)
        } else {
            share.openFile(path, access, null, allShareAccess(), SMB2CreateDisposition.FILE_OPEN, null)
        }
    }
}

private class SmbOffsetInputStream(private val file: SmbFile, offset: Long) : InputStream() {
    private var position = offset
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Stream is closed" }
        if (length == 0) return 0
        val requested = minOf(length, SMB_STREAM_CHUNK_BYTES)
        val count = file.read(buffer, position, offset, requested)
        if (count > 0) position += count.toLong()
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        file.close()
    }
}

private class SmbOffsetOutputStream(private val file: SmbFile, offset: Long) : OutputStream() {
    private var position = offset
    private var closed = false

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Stream is closed" }
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val request = minOf(remaining, SMB_STREAM_CHUNK_BYTES)
            val written = file.write(buffer, position, sourceOffset, request).toInt()
            if (written <= 0) throw IOException("SMB server made no write progress")
            position += written.toLong()
            sourceOffset += written
            remaining -= written
        }
    }

    override fun flush() = Unit

    override fun close() {
        if (closed) return
        closed = true
        file.close()
    }
}

private fun FileIdBothDirectoryInformation.toNode(parent: String): RemoteNode {
    val directory = EnumWithValue.EnumUtils.isSet(fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
    val hidden = EnumWithValue.EnumUtils.isSet(fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN)
    val path = RemotePath.resolve(parent, fileName)
    val modified = lastWriteTime?.toEpochMillis()
    val id = "smb-fileid:$fileId"
    return RemoteNode(
        path = path,
        name = fileName,
        directory = directory,
        sizeBytes = if (directory) null else endOfFile,
        modifiedAtMillis = modified,
        hidden = hidden,
        stableId = id,
        revision = "$id:size=$endOfFile;mtime=${modified ?: -1};hidden=$hidden",
    )
}

private fun toSmbPath(path: String): String = RemotePath.normalize(path).trim('/').replace('/', '\\')
private fun allShareAccess(): EnumSet<SMB2ShareAccess> = EnumSet.allOf(SMB2ShareAccess::class.java)

private fun isMissing(error: SMBApiException): Boolean = error.statusCode in setOf(
    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value,
    NtStatus.STATUS_NO_SUCH_FILE.value,
)

private fun mapSmbError(error: Throwable): RemoteAccessException {
    if (error is RemoteAccessException) return error
    if (error is SMBApiException) {
        return when (error.statusCode) {
            NtStatus.STATUS_LOGON_FAILURE.value -> RemoteAccessException.AuthenticationFailed(error)
            NtStatus.STATUS_ACCESS_DENIED.value -> RemoteAccessException.PermissionDenied(error)
            else -> RemoteAccessException.Protocol(error)
        }
    }
    var cursor: Throwable? = error
    while (cursor != null) {
        when (cursor) {
            is SocketTimeoutException, is TimeoutException -> return RemoteAccessException.Timeout(error)
            is ConnectException -> return RemoteAccessException.HostUnreachable(error)
        }
        cursor = cursor.cause
    }
    return when (error) {
        is SecurityException -> RemoteAccessException.PermissionDenied(error)
        is IOException -> RemoteAccessException.HostUnreachable(error)
        else -> RemoteAccessException.Protocol(error)
    }
}

private const val SMB_STREAM_CHUNK_BYTES = 64 * 1024
