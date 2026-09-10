package com.zz.filemanager.core.archive

import android.content.Context
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.WrongPasswordException
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.step4.SafeOutputWriter
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

class ArchiveManager(
    context: Context,
    private val providers: StorageProviderRegistry,
    private val safeWriter: SafeOutputWriter,
) {
    private val appContext = context.applicationContext
    private val cacheRoot = File(appContext.cacheDir, "step4-archives").apply { mkdirs() }

    fun detect(name: String, mimeType: String? = null): ArchiveFormat {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> ArchiveFormat.TAR_BZ2
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            lower.endsWith(".tar") -> ArchiveFormat.TAR
            lower.endsWith(".zip") || mimeType?.contains("zip", ignoreCase = true) == true -> ArchiveFormat.ZIP
            lower.endsWith(".7z") || mimeType?.contains("7z", ignoreCase = true) == true -> ArchiveFormat.SEVEN_Z
            lower.endsWith(".rar") || mimeType?.contains("rar", ignoreCase = true) == true -> ArchiveFormat.RAR
            else -> ArchiveFormat.UNKNOWN
        }
    }

    suspend fun list(source: ArchiveSource, password: CharArray? = null): ArchiveListing = withContext(Dispatchers.IO) {
        val format = detect(source.entry.name, source.entry.mimeType)
        when (format) {
            ArchiveFormat.ZIP -> listZip(source, password)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> listTar(source, format)
            ArchiveFormat.SEVEN_Z -> listSevenZ(source, password)
            ArchiveFormat.RAR -> listRar(source, password)
            ArchiveFormat.UNKNOWN -> throw ArchiveFailure.UnsupportedFormat(format)
        }
    }

    suspend fun extract(
        request: ArchiveExtractRequest,
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val format = detect(request.source.entry.name, request.source.entry.mimeType)
        when (format) {
            ArchiveFormat.ZIP -> extractZip(request, onProgress)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> extractTar(request, format, onProgress)
            ArchiveFormat.SEVEN_Z -> extractSevenZ(request, onProgress)
            ArchiveFormat.RAR -> extractRar(request, onProgress)
            ArchiveFormat.UNKNOWN -> throw ArchiveFailure.UnsupportedFormat(format)
        }
    }

    suspend fun create(
        request: ArchiveCreateRequest,
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): FileEntry = withContext(Dispatchers.IO) {
        require(request.sources.isNotEmpty()) { "At least one source is required" }
        if (request.format !in setOf(ArchiveFormat.ZIP, ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ)) {
            throw ArchiveFailure.UnsupportedFormat(request.format)
        }
        val result = safeWriter.writeGenerated(
            parent = request.destination,
            requestedName = request.archiveName,
            mimeType = archiveMime(request.format),
            expectedBytes = null,
            collisionPolicy = CollisionPolicy.KEEP_BOTH,
        ) { raw ->
            when (request.format) {
                ArchiveFormat.ZIP -> createZip(raw, request, onProgress)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> createTar(raw, request, onProgress)
                else -> error("validated above")
            }
        }
        when (result) {
            is com.zz.filemanager.core.step4.SafeWriteResult.Written -> result.entry
            is com.zz.filemanager.core.step4.SafeWriteResult.Skipped ->
                throw IllegalStateException("Keep Both archive creation unexpectedly skipped output")
        }

    }

    private suspend fun listZip(source: ArchiveSource, password: CharArray?): ArchiveListing {
        return withRandomAccessFile(source) { file, staged ->
            try {
                val zip = if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password)
                val entries = zip.fileHeaders.map { header ->
                    val path = ArchivePathValidator.normalize(header.fileName)
                    ArchiveEntryInfo(
                        path = path,
                        name = path.substringAfterLast('/'),
                        isDirectory = header.isDirectory,
                        compressedSize = header.compressedSize.takeIf { it >= 0L },
                        uncompressedSize = header.uncompressedSize.takeIf { it >= 0L },
                        encrypted = header.isEncrypted,
                    )
                }
                ArchivePathValidator.validateUnique(entries.map { it.path })
                ArchiveListing(ArchiveFormat.ZIP, entries, zip.isEncrypted, staged)
            } catch (error: ZipException) {
                throw mapZipError(error)
            }
        }
    }

    private suspend fun extractZip(request: ArchiveExtractRequest, onProgress: suspend (ArchiveProgress) -> Unit) {
        val free = providers.writableProviderFor(request.destination.providerId)?.freeBytes(request.destination)
        val guard = ArchiveExpansionGuard(free, request.allowSuspiciousExpansion)
        withRandomAccessFile(request.source) { file, _ ->
            try {
                val zip = if (request.password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, request.password)
                val headers = zip.fileHeaders
                val normalized = headers.associateWith { ArchivePathValidator.normalize(it.fileName) }
                ArchivePathValidator.validateUnique(normalized.values)
                normalized.forEach { (header, _) -> guard.observeHeader(header.compressedSize, header.uncompressedSize) }
                val selected = normalized.filterValues { selected(it, request.selectedPaths) }
                var completed = 0L
                var processedBytes = 0L
                for ((header, path) in selected) {
                    coroutineContext.ensureActive()
                    onProgress(ArchiveProgress("extract", path, processedBytes, null, completed, selected.size.toLong()))
                    if (header.isDirectory) {
                        ensureDirectoryPath(request.destination, path)
                    } else {
                        val parentAndName = ensureParent(request.destination, path)
                        zip.getInputStream(header).use { input ->
                            val base = processedBytes
                            var observedFileBytes = 0L
                            safeWriter.write(
                                parent = parentAndName.first,
                                requestedName = parentAndName.second,
                                mimeType = null,
                                input = input,
                                expectedBytes = header.uncompressedSize.takeIf { it >= 0L },
                                collisionPolicy = request.collisionPolicy,
                            ) { fileBytes ->
                                val delta = (fileBytes - observedFileBytes).coerceAtLeast(0L)
                                guard.observeActual(delta)
                                observedFileBytes = fileBytes
                                onProgress(ArchiveProgress("extract", path, base + fileBytes, null, completed, selected.size.toLong()))
                            }
                            processedBytes = safeAdd(processedBytes, header.uncompressedSize.coerceAtLeast(0L))
                        }
                    }
                    completed++
                }
                onProgress(ArchiveProgress("complete", null, processedBytes, processedBytes, completed, selected.size.toLong()))
            } catch (error: ZipException) {
                throw mapZipError(error)
            }
        }
    }

    private suspend fun listTar(source: ArchiveSource, format: ArchiveFormat): ArchiveListing {
        val entries = mutableListOf<ArchiveEntryInfo>()
        sourceInput(source).use { raw ->
            tarInput(raw, format).use { tar ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    val path = ArchivePathValidator.normalize(entry.name)
                    entries += ArchiveEntryInfo(path, path.substringAfterLast('/'), entry.isDirectory, null, entry.size.takeIf { it >= 0L }, entry.lastModifiedDate?.time)
                }
            }
        }
        ArchivePathValidator.validateUnique(entries.map { it.path })
        return ArchiveListing(format, entries, encrypted = false, stagedForRandomAccess = false)
    }

    private suspend fun extractTar(request: ArchiveExtractRequest, format: ArchiveFormat, onProgress: suspend (ArchiveProgress) -> Unit) {
        val free = providers.writableProviderFor(request.destination.providerId)?.freeBytes(request.destination)
        val guard = ArchiveExpansionGuard(free, request.allowSuspiciousExpansion)
        var processed = 0L
        var count = 0L
        val seen = hashSetOf<String>()
        sourceInput(request.source).use { raw ->
            tarInput(raw, format).use { tar ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    val path = ArchivePathValidator.normalize(entry.name)
                    if (!seen.add(path.lowercase())) throw ArchiveFailure.DuplicatePath(path)
                    if (entry.isSymbolicLink || entry.isLink) continue
                    guard.observeHeader(null, entry.size.takeIf { it >= 0L })
                    if (!selected(path, request.selectedPaths)) continue
                    onProgress(ArchiveProgress("extract", path, processed, null, count, null))
                    if (entry.isDirectory) {
                        ensureDirectoryPath(request.destination, path)
                    } else {
                        val parentAndName = ensureParent(request.destination, path)
                        val nonClosing = NonClosingInputStream(tar)
                        val base = processed
                        safeWriter.write(
                            parentAndName.first,
                            parentAndName.second,
                            null,
                            nonClosing,
                            entry.size.takeIf { it >= 0L },
                            request.collisionPolicy,
                        ) { fileBytes ->
                            onProgress(ArchiveProgress("extract", path, base + fileBytes, null, count, null))
                        }
                        guard.observeActual(entry.size.coerceAtLeast(0L))
                        processed = safeAdd(processed, entry.size.coerceAtLeast(0L))
                    }
                    count++
                }
            }
        }
        onProgress(ArchiveProgress("complete", processedBytes = processed, totalBytes = processed, processedEntries = count, totalEntries = count))
    }

    private suspend fun listSevenZ(source: ArchiveSource, password: CharArray?): ArchiveListing = withRandomAccessFile(source) { file, staged ->
        try {
            sevenZ(file, password).use { seven ->
                val entries = seven.entries.map { entry ->
                    val path = ArchivePathValidator.normalize(entry.name ?: throw ArchiveFailure.UnsafePath("<unnamed>"))
                    ArchiveEntryInfo(path, path.substringAfterLast('/'), entry.isDirectory, null, entry.size.takeIf { it >= 0L })
                }.toList()
                ArchivePathValidator.validateUnique(entries.map { it.path })
                ArchiveListing(ArchiveFormat.SEVEN_Z, entries, encrypted = password != null, stagedForRandomAccess = staged)
            }
        } catch (error: ArchiveFailure) {
            throw error
        } catch (error: Throwable) {
            throw mapReaderFailure("7z", error)
        }
    }

    private suspend fun extractSevenZ(request: ArchiveExtractRequest, onProgress: suspend (ArchiveProgress) -> Unit) {
        val free = providers.writableProviderFor(request.destination.providerId)?.freeBytes(request.destination)
        val guard = ArchiveExpansionGuard(free, request.allowSuspiciousExpansion)
        withRandomAccessFile(request.source) { file, _ ->
            try {
                sevenZ(file, request.password).use { seven ->
                    val all = seven.entries.toList()
                    val paths = all.associateWith { ArchivePathValidator.normalize(it.name ?: throw ArchiveFailure.UnsafePath("<unnamed>")) }
                    ArchivePathValidator.validateUnique(paths.values)
                    val totalDeclared = paths.keys.fold(0L) { total, entry ->
            safeAdd(total, if (entry.isDirectory) 0L else entry.size.coerceAtLeast(0L))
        }
        // SevenZArchiveEntry intentionally keeps per-entry compressed size package-private.
        // Use the whole archive byte size against total declared output for the expansion-ratio
        // guard, then continue enforcing actual extracted bytes and free-space during writes.
        guard.observeHeader(request.source.entry.sizeBytes?.takeIf { it > 0L }, totalDeclared)
                    val selected = paths.filterValues { selected(it, request.selectedPaths) }
                    var done = 0L
                    var processed = 0L
                    for ((entry, path) in selected) {
                        coroutineContext.ensureActive()
                        if (entry.isDirectory) {
                            ensureDirectoryPath(request.destination, path)
                        } else {
                            val parentAndName = ensureParent(request.destination, path)
                            seven.getInputStream(entry).use { input ->
                                val base = processed
                                safeWriter.write(parentAndName.first, parentAndName.second, null, input, entry.size.takeIf { it >= 0L }, request.collisionPolicy) { current ->
                                    onProgress(ArchiveProgress("extract", path, base + current, null, done, selected.size.toLong()))
                                }
                            }
                            guard.observeActual(entry.size.coerceAtLeast(0L))
                            processed = safeAdd(processed, entry.size.coerceAtLeast(0L))
                        }
                        done++
                    }
                }
            } catch (error: ArchiveFailure) {
                throw error
            } catch (error: Throwable) {
                throw mapReaderFailure("7z", error)
            }
        }
    }

    private suspend fun listRar(source: ArchiveSource, password: CharArray?): ArchiveListing {
        try {
            sourceInput(source).use { input ->
                rar(input, password).use { archive ->
                    val entries = archive.fileHeaders.map { header ->
                        val path = ArchivePathValidator.normalize(header.fileName)
                        ArchiveEntryInfo(path, path.substringAfterLast('/'), header.isDirectory, header.fullPackSize.takeIf { it >= 0L }, header.fullUnpackSize.takeIf { it >= 0L }, encrypted = header.isEncrypted)
                    }
                    ArchivePathValidator.validateUnique(entries.map { it.path })
                    return ArchiveListing(ArchiveFormat.RAR, entries, archive.isPasswordProtected, stagedForRandomAccess = false)
                }
            }
        } catch (error: WrongPasswordException) {
            throw ArchiveFailure.BadPassword(error)
        } catch (error: ArchiveFailure) {
            throw error
        } catch (error: Throwable) {
            throw mapReaderFailure("RAR", error)
        }
    }

    private suspend fun extractRar(request: ArchiveExtractRequest, onProgress: suspend (ArchiveProgress) -> Unit) {
        val free = providers.writableProviderFor(request.destination.providerId)?.freeBytes(request.destination)
        val guard = ArchiveExpansionGuard(free, request.allowSuspiciousExpansion)
        try {
            sourceInput(request.source).use { input ->
                rar(input, request.password).use { archive ->
                    val headers = archive.fileHeaders
                    val paths = headers.associateWith { ArchivePathValidator.normalize(it.fileName) }
                    ArchivePathValidator.validateUnique(paths.values)
                    paths.forEach { (header, _) -> guard.observeHeader(header.fullPackSize, header.fullUnpackSize) }
                    val selected = paths.filterValues { selected(it, request.selectedPaths) }
                    var done = 0L
                    var processed = 0L
                    for ((header, path) in selected) {
                        coroutineContext.ensureActive()
                        if (header.isDirectory) {
                            ensureDirectoryPath(request.destination, path)
                        } else {
                            val parentAndName = ensureParent(request.destination, path)
                            archive.getInputStream(header).use { entryInput ->
                                val base = processed
                                safeWriter.write(parentAndName.first, parentAndName.second, null, entryInput, header.fullUnpackSize.takeIf { it >= 0L }, request.collisionPolicy) { current ->
                                    onProgress(ArchiveProgress("extract", path, base + current, null, done, selected.size.toLong()))
                                }
                            }
                            guard.observeActual(header.fullUnpackSize.coerceAtLeast(0L))
                            processed = safeAdd(processed, header.fullUnpackSize.coerceAtLeast(0L))
                        }
                        done++
                    }
                }
            }
        } catch (error: WrongPasswordException) {
            throw ArchiveFailure.BadPassword(error)
        } catch (error: ArchiveFailure) {
            throw error
        } catch (error: Throwable) {
            throw mapReaderFailure("RAR", error)
        }
    }

    private suspend fun createZip(raw: OutputStream, request: ArchiveCreateRequest, onProgress: suspend (ArchiveProgress) -> Unit) {
        val password = request.password
        val zip = if (password.isNullOrEmpty()) ZipOutputStream(raw) else ZipOutputStream(raw, password)
        zip.use { output ->
            var bytes = 0L
            var entries = 0L
            for (source in request.sources) {
                val rootName = ArchivePathValidator.normalize(source.entry.name)
                walk(source, rootName) { entry, _, archivePath ->
                    coroutineContext.ensureActive()
                    val path = if (entry.isDirectory) archivePath.trimEnd('/') + "/" else archivePath
                    val params = ZipParameters().apply {
                        fileNameInZip = path
                        compressionMethod = if (entry.isDirectory) CompressionMethod.STORE else CompressionMethod.DEFLATE
                        compressionLevel = when (request.zipCompressionLevel) {
                            ZipCompressionLevel.FASTEST -> CompressionLevel.FASTEST
                            ZipCompressionLevel.FAST -> CompressionLevel.FAST
                            ZipCompressionLevel.NORMAL -> CompressionLevel.NORMAL
                            ZipCompressionLevel.MAXIMUM -> CompressionLevel.MAXIMUM
                        }
                        if (!entry.isDirectory && !password.isNullOrEmpty()) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    output.putNextEntry(params)
                    if (!entry.isDirectory) {
                        providers.providerFor(entry.reference.providerId).openInputStream(entry.reference).use { input ->
                            bytes = copyToArchive(input, output, bytes) { total -> onProgress(ArchiveProgress("create", path, total, null, entries, null)) }
                        }
                    }
                    output.closeEntry()
                    entries++
                }
            }
            onProgress(ArchiveProgress("complete", processedBytes = bytes, totalBytes = bytes, processedEntries = entries, totalEntries = entries))
        }
    }

    private suspend fun createTar(raw: OutputStream, request: ArchiveCreateRequest, onProgress: suspend (ArchiveProgress) -> Unit) {
        val compressed = when (request.format) {
            ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(raw)
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw)
            ArchiveFormat.TAR_XZ -> XZCompressorOutputStream(raw)
            else -> raw
        }
        TarArchiveOutputStream(compressed).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            var bytes = 0L
            var entries = 0L
            for (source in request.sources) {
                val rootName = ArchivePathValidator.normalize(source.entry.name)
                walk(source, rootName) { entry, _, archivePath ->
                    coroutineContext.ensureActive()
                    val path = if (entry.isDirectory) archivePath.trimEnd('/') + "/" else archivePath
                    val tarEntry = TarArchiveEntry(path).apply {
                        size = if (entry.isDirectory) 0L else entry.sizeBytes ?: 0L
                        entry.modifiedAtMillis?.takeIf { it > 0L }?.let { setModTime(it) }
                    }
                    tar.putArchiveEntry(tarEntry)
                    if (!entry.isDirectory) {
                        providers.providerFor(entry.reference.providerId).openInputStream(entry.reference).use { input ->
                            bytes = copyToArchive(input, tar, bytes) { total -> onProgress(ArchiveProgress("create", path, total, null, entries, null)) }
                        }
                    }
                    tar.closeArchiveEntry()
                    entries++
                }
            }
            tar.finish()
            onProgress(ArchiveProgress("complete", processedBytes = bytes, totalBytes = bytes, processedEntries = entries, totalEntries = entries))
        }
    }

    private suspend fun walk(source: ArchiveSource, path: String, block: suspend (FileEntry, BrowserLocation, String) -> Unit) {
        block(source.entry, source.parent, path)
        if (!source.entry.isDirectory || source.entry.isSymbolicLink) return
        val location = childLocation(source.parent, source.entry)
        val provider = providers.providerFor(location.providerId)
        for (child in provider.listChildren(location)) {
            coroutineContext.ensureActive()
            walk(ArchiveSource(child, location), "$path/${ArchivePathValidator.normalize(child.name)}", block)
        }
    }

    private suspend fun ensureParent(root: BrowserLocation, safePath: String): Pair<BrowserLocation, String> {
        val parts = safePath.split('/').filter { it.isNotBlank() }
        require(parts.isNotEmpty())
        val parent = ensureDirectorySegments(root, parts.dropLast(1))
        return parent to parts.last()
    }

    private suspend fun ensureDirectoryPath(root: BrowserLocation, safePath: String): BrowserLocation =
        ensureDirectorySegments(root, safePath.split('/').filter { it.isNotBlank() })

    private suspend fun ensureDirectorySegments(root: BrowserLocation, parts: List<String>): BrowserLocation {
        var current = root
        val provider = providers.writableProviderFor(root.providerId) ?: throw ArchiveFailure.ProviderLimit("Destination is read-only")
        for (segment in parts) {
            val safe = ArchivePathValidator.normalize(segment)
            val existing = provider.findChild(current, safe)
            val directory = when {
                existing == null -> provider.createDirectory(current, safe)
                existing.isDirectory -> existing
                else -> throw IllegalStateException("A file blocks archive directory '$safe'")
            }
            current = childLocation(current, directory)
        }
        return current
    }

    private fun childLocation(parent: BrowserLocation, entry: FileEntry) = BrowserLocation(
        providerId = entry.reference.providerId,
        id = entry.id,
        displayName = entry.name,
        reference = entry.reference.uri ?: entry.reference.path ?: entry.reference.opaqueId,
        rootReference = parent.rootReference,
        storageId = parent.storageId,
        readable = entry.isReadable,
        writable = entry.isWritable,
    )

    private suspend fun sourceInput(source: ArchiveSource): InputStream = providers.providerFor(source.entry.reference.providerId).openInputStream(source.entry.reference)

    private fun tarInput(input: InputStream, format: ArchiveFormat): TarArchiveInputStream {
        val decoded = when (format) {
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(input, true)
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(input, true)
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(input, true)
            else -> input
        }
        return TarArchiveInputStream(decoded)
    }

    private suspend fun <T> withRandomAccessFile(source: ArchiveSource, block: suspend (File, Boolean) -> T): T {
        val direct = source.entry.reference.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        if (direct != null) return block(direct, false)
        val expected = source.entry.sizeBytes
        val usable = cacheRoot.usableSpace
        if (expected != null && usable >= 0L && expected > usable) throw ArchiveFailure.InsufficientSpace(expected, usable)
        val staged = File(cacheRoot, ".zzarchive-${UUID.randomUUID()}")
        try {
            sourceInput(source).use { input ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied = safeAdd(copied, count.toLong())
                        if (usable >= 0L && copied > usable) throw ArchiveFailure.InsufficientSpace(copied, usable)
                    }
                }
            }
            return block(staged, true)
        } finally {
            staged.delete()
        }
    }

    private fun sevenZ(file: File, password: CharArray?): SevenZFile {
        val builder = SevenZFile.builder().setFile(file).setMaxMemoryLimitKiB(256 * 1024)
        if (!password.isNullOrEmpty()) builder.setPassword(password)
        return builder.get()
    }

    private fun rar(input: InputStream, password: CharArray?): Archive {
        val optionsBuilder = ArchiveOptions.builder().maxDictionarySize(1024L * 1024L * 1024L)
        if (!password.isNullOrEmpty()) optionsBuilder.password(password)
        return Archive(input, optionsBuilder.build())
    }

    private fun selected(path: String, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return true
        return selected.any { raw ->
            val wanted = ArchivePathValidator.normalize(raw).trimEnd('/')
            path == wanted || path.startsWith("$wanted/")
        }
    }

    private suspend fun uniqueName(provider: WritableStorageProvider, parent: BrowserLocation, requested: String): String {
        if (provider.findChild(parent, requested) == null) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$ext"
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }

    private suspend fun copyToArchive(input: InputStream, output: OutputStream, initial: Long, onProgress: suspend (Long) -> Unit): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = initial
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            total = safeAdd(total, count.toLong())
            onProgress(total)
        }
        return total
    }

    private fun archiveMime(format: ArchiveFormat): String = when (format) {
        ArchiveFormat.ZIP -> "application/zip"
        ArchiveFormat.TAR -> "application/x-tar"
        ArchiveFormat.TAR_GZ -> "application/gzip"
        ArchiveFormat.TAR_BZ2 -> "application/x-bzip2"
        ArchiveFormat.TAR_XZ -> "application/x-xz"
        ArchiveFormat.SEVEN_Z -> "application/x-7z-compressed"
        ArchiveFormat.RAR -> "application/vnd.rar"
        ArchiveFormat.UNKNOWN -> "application/octet-stream"
    }

    private fun mapZipError(error: ZipException): ArchiveFailure {
        val message = error.message.orEmpty()
        return if (message.contains("password", true)) ArchiveFailure.BadPassword(error) else ArchiveFailure.Corrupted(error)
    }

    private fun mapReaderFailure(label: String, error: Throwable): ArchiveFailure {
        val message = error.message.orEmpty()
        return when {
            message.contains("password", true) -> ArchiveFailure.BadPassword(error)
            message.contains("unsupported", true) || message.contains("not supported", true) || message.contains("RAR5", true) ->
                ArchiveFailure.ProviderLimit("This $label variant or compression method is not supported by the packaged reader")
            else -> ArchiveFailure.Corrupted(error)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right > 0L && left > Long.MAX_VALUE - right) throw ArchiveFailure.SuspiciousExpansion("Archive byte accounting overflow was prevented")
        return left + right
    }

    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() = Unit
    }
}
