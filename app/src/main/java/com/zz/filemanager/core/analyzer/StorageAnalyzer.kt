package com.zz.filemanager.core.analyzer

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class StorageAnalyzer(
    private val providers: StorageProviderRegistry,
    private val storage: StorageRepository? = null,
) {
    suspend fun scan(
        root: BrowserLocation,
        topLimit: Int = 100,
        retainedEmptyLimit: Int = 1_000,
        onProgress: suspend (AnalyzerSnapshot) -> Unit = {},
    ): AnalyzerSnapshot = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val categories = StorageCategory.entries.associateWith { 0L }.toMutableMap()
        val extensions = hashMapOf<String, Long>()
        val largestFiles = PriorityQueue<AnalyzedFile>(compareBy { it.sizeBytes })
        val largestFolders = PriorityQueue<AnalyzedFolder>(compareBy { it.sizeBytes })
        val emptyFiles = mutableListOf<AnalyzedFile>()
        val emptyFolders = mutableListOf<BrowserLocation>()
        val warnings = mutableListOf<String>()
        val visited = hashSetOf<String>()
        var files = 0L
        var dirs = 0L
        var bytes = 0L
        var emptyFileCount = 0L
        var emptyFolderCount = 0L
        var current = root.displayName

        val capacity = runCatching { storage?.discoverStorageLocations()?.firstOrNull { it.id == root.storageId } }.getOrNull()
        val free = runCatching { providers.writableProviderFor(root.providerId)?.freeBytes(root) }.getOrNull() ?: capacity?.freeBytes

        class Frame(val location: BrowserLocation) {
            var children: Iterator<FileEntry>? = null
            var childCount = 0L
            var fileCount = 0L
            var folderCount = 0L
            var totalBytes = 0L
            var listingFailed = false
        }

        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root))
        visited += root.identity

        suspend fun snapshot(complete: Boolean): AnalyzerSnapshot = AnalyzerSnapshot(
            root = root,
            progress = AnalyzerProgress(files, dirs, bytes, current, System.currentTimeMillis() - started),
            complete = complete,
            categoryBytes = categories.toMap(),
            largestFiles = largestFiles.toList().sortedByDescending { it.sizeBytes },
            largestFolders = largestFolders.toList().sortedByDescending { it.sizeBytes },
            extensionCounts = extensions.toList().sortedByDescending { it.second }.take(100).toMap(),
            emptyFiles = emptyFiles.toList(),
            emptyDirectories = emptyFolders.toList(),
            totalEmptyFiles = emptyFileCount,
            totalEmptyDirectories = emptyFolderCount,
            accessibleCapacityBytes = capacity?.totalBytes,
            freeBytes = free,
            warnings = warnings.takeLast(50),
        )

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val frame = stack.last()
            if (frame.children == null) {
                current = frame.location.displayName
                dirs++
                val children = try {
                    providers.providerFor(frame.location.providerId).listChildren(frame.location)
                } catch (error: Throwable) {
                    frame.listingFailed = true
                    warnings += "Could not scan ${frame.location.displayName}: ${error.javaClass.simpleName}"
                    emptyList()
                }
                frame.children = children.iterator()
            }
            val iterator = frame.children!!
            if (iterator.hasNext()) {
                val entry = iterator.next()
                frame.childCount++
                current = entry.name
                if (entry.isDirectory && !entry.isSymbolicLink) {
                    val child = childLocation(frame.location, entry)
                    if (visited.add(child.identity)) stack.addLast(Frame(child))
                    else warnings += "Skipped storage loop at ${entry.name}"
                } else {
                    files++
                    val size = entry.sizeBytes?.coerceAtLeast(0L) ?: 0L
                    frame.totalBytes = safeAdd(frame.totalBytes, size)
                    frame.fileCount++
                    bytes = safeAdd(bytes, size)
                    categories[category(entry)] = safeAdd(categories.getValue(category(entry)), size)
                    val ext = entry.extension?.lowercase()?.takeIf { it.isNotBlank() } ?: "(none)"
                    extensions[ext] = safeAdd(extensions[ext] ?: 0L, 1L)
                    val analyzed = AnalyzedFile(entry, frame.location, size)
                    retainLargest(largestFiles, analyzed, topLimit) { it.sizeBytes }
                    if (size == 0L) {
                        emptyFileCount++
                        if (emptyFiles.size < retainedEmptyLimit) emptyFiles += analyzed
                    }
                }
                if ((files + dirs) % 250L == 0L) onProgress(snapshot(false))
            } else {
                stack.removeLast()
                val summary = AnalyzedFolder(frame.location, frame.totalBytes, frame.fileCount, frame.folderCount)
                retainLargest(largestFolders, summary, topLimit) { it.sizeBytes }
                if (!frame.listingFailed && frame.childCount == 0L) {
                    emptyFolderCount++
                    if (emptyFolders.size < retainedEmptyLimit) emptyFolders += frame.location
                }
                stack.lastOrNull()?.let { parent ->
                    parent.totalBytes = safeAdd(parent.totalBytes, frame.totalBytes)
                    parent.fileCount = safeAdd(parent.fileCount, frame.fileCount)
                    parent.folderCount = safeAdd(parent.folderCount, safeAdd(frame.folderCount, 1L))
                }
            }
        }
        snapshot(true).also { onProgress(it) }
    }

    suspend fun findDuplicates(
        root: BrowserLocation,
        onProgress: suspend (DuplicateProgress) -> Unit = {},
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        // Phase 1: exact size counts only. This deliberately does not retain a filesystem node
        // graph; unique sizes are discarded from later, expensive phases.
        val sizeCounts = hashMapOf<Long, Int>()
        var visitedFiles = 0L
        traverseFiles(root) { entry, _ ->
            val size = entry.sizeBytes ?: return@traverseFiles
            sizeCounts[size] = (sizeCounts[size] ?: 0) + 1
            visitedFiles++
            if (visitedFiles % 500L == 0L) onProgress(DuplicateProgress(1, visitedFiles, sizeCounts.count { it.value > 1 }.toLong(), 0L, entry.name))
        }
        val duplicateSizes = sizeCounts.filterValues { it > 1 }.keys
        sizeCounts.clear()
        if (duplicateSizes.isEmpty()) return@withContext emptyList()

        // Phase 2: sampled beginning/middle/end hash. A sample only narrows candidates and is
        // never surfaced as a duplicate result.
        val sampled = hashMapOf<String, MutableList<AnalyzedFile>>()
        var candidates = 0L
        var sampledBytes = 0L
        traverseFiles(root) { entry, parent ->
            val size = entry.sizeBytes ?: return@traverseFiles
            if (size !in duplicateSizes) return@traverseFiles
            val sample = runCatching { sampleHash(entry, size).also { sampledBytes = safeAdd(sampledBytes, it.second) } }.getOrNull() ?: return@traverseFiles
            val key = "$size:${sample.first}"
            sampled.getOrPut(key) { mutableListOf() }.add(AnalyzedFile(entry, parent, size))
            candidates++
            if (candidates % 100L == 0L) onProgress(DuplicateProgress(2, visitedFiles, candidates, sampledBytes, entry.name))
        }
        val fullCandidates = sampled.values.filter { it.size > 1 }.flatten()
        sampled.clear()
        if (fullCandidates.isEmpty()) return@withContext emptyList()

        // Phase 3: complete cryptographic digest of every surviving candidate.
        val verified = hashMapOf<String, MutableList<DuplicateMember>>()
        var fullBytes = 0L
        fullCandidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()
            val digest = runCatching {
                fullHash(candidate.entry) { delta ->
                    fullBytes = safeAdd(fullBytes, delta)
                }
            }.getOrNull() ?: return@forEachIndexed
            val key = "${candidate.sizeBytes}:$digest"
            verified.getOrPut(key) { mutableListOf() }.add(DuplicateMember(candidate.entry, candidate.parent, digest))
            onProgress(DuplicateProgress(3, visitedFiles, fullCandidates.size.toLong(), fullBytes, candidate.entry.name))
        }
        verified.values.filter { it.size > 1 }.map { members ->
            DuplicateGroup(members.first().entry.sizeBytes ?: 0L, members.first().sha256, members)
        }.sortedByDescending { it.sizeBytes * (it.members.size - 1L) }
    }

    private suspend fun traverseFiles(root: BrowserLocation, block: suspend (FileEntry, BrowserLocation) -> Unit) {
        val queue = ArrayDeque<BrowserLocation>()
        val visited = hashSetOf<String>()
        queue.add(root)
        visited += root.identity
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val location = queue.removeLast()
            val children = runCatching { providers.providerFor(location.providerId).listChildren(location) }.getOrElse { continue }
            for (entry in children) {
                coroutineContext.ensureActive()
                if (entry.isDirectory && !entry.isSymbolicLink) {
                    val child = childLocation(location, entry)
                    if (visited.add(child.identity)) queue.add(child)
                } else if (!entry.isDirectory) {
                    block(entry, location)
                }
            }
        }
    }

    private suspend fun sampleHash(entry: FileEntry, size: Long): Pair<String, Long> {
        val chunk = 64 * 1024
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(java.nio.ByteBuffer.allocate(java.lang.Long.BYTES).putLong(size).array())
        var read = 0L
        val offsets = linkedSetOf(0L, (size / 2L - chunk / 2L).coerceAtLeast(0L), (size - chunk).coerceAtLeast(0L))
        val path = entry.reference.path
        if (path != null && File(path).isFile) {
            RandomAccessFile(path, "r").use { raf ->
                val buffer = ByteArray(chunk)
                for (offset in offsets) {
                    coroutineContext.ensureActive()
                    raf.seek(offset)
                    val wanted = min(chunk.toLong(), (size - offset).coerceAtLeast(0L)).toInt()
                    val count = if (wanted > 0) raf.read(buffer, 0, wanted) else 0
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                        read = safeAdd(read, count.toLong())
                    }
                }
            }
        } else {
            for (offset in offsets) {
                coroutineContext.ensureActive()
                providers.providerFor(entry.reference.providerId).openInputStream(entry.reference).use { input ->
                    skipFully(input, offset)
                    val buffer = ByteArray(chunk)
                    val wanted = min(chunk.toLong(), (size - offset).coerceAtLeast(0L)).toInt()
                    var remaining = wanted
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, remaining)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        read = safeAdd(read, count.toLong())
                        remaining -= count
                    }
                }
            }
        }
        return digest.digest().toHex() to read
    }

    private suspend fun fullHash(entry: FileEntry, onBytes: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        providers.providerFor(entry.reference.providerId).openInputStream(entry.reference).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                onBytes(count.toLong())
            }
        }
        return digest.digest().toHex()
    }

    private fun skipFully(input: java.io.InputStream, target: Long) {
        var remaining = target
        val scratch = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(scratch, 0, min(scratch.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read.toLong()
            }
        }
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

    private fun category(entry: FileEntry): StorageCategory = when (entry.type) {
        FileEntryType.IMAGE -> StorageCategory.IMAGES
        FileEntryType.VIDEO -> StorageCategory.VIDEO
        FileEntryType.AUDIO -> StorageCategory.AUDIO
        FileEntryType.TEXT, FileEntryType.PDF, FileEntryType.DOCUMENT, FileEntryType.SPREADSHEET, FileEntryType.PRESENTATION -> StorageCategory.DOCUMENTS
        FileEntryType.ARCHIVE -> StorageCategory.ARCHIVES
        FileEntryType.APK -> StorageCategory.APKS
        else -> StorageCategory.OTHER
    }

    private fun <T> retainLargest(queue: PriorityQueue<T>, value: T, limit: Int, bytes: (T) -> Long) {
        if (limit <= 0) return
        queue.add(value)
        if (queue.size > limit) queue.poll()
    }

    private fun safeAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw IllegalStateException("64-bit analyzer accounting overflow", error)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
