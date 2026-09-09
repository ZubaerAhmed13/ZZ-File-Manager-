package com.zz.filemanager.core.text

import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.step4.SafeOutputWriter
import com.zz.filemanager.core.step4.SafeWriteResult
import com.zz.filemanager.core.storage.StorageProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class TextFileEngine(
    private val providers: StorageProviderRegistry,
    private val safeWriter: SafeOutputWriter,
    private val runtimeMaxMemory: () -> Long = { Runtime.getRuntime().maxMemory() },
) {
    private val textExtensions = setOf(
        "txt", "md", "json", "xml", "csv", "log", "ini", "cfg", "yaml", "yml",
        "properties", "kt", "java", "js", "ts", "html", "css", "py", "sh",
    )

    suspend fun isLikelyText(source: TextSource): Boolean = withContext(Dispatchers.IO) {
        val entry = source.entry
        if (entry.type == FileEntryType.TEXT || entry.mimeType?.startsWith("text/", true) == true) return@withContext true
        if (entry.extension?.lowercase() in textExtensions) return@withContext true
        val provider = providers.providerFor(entry.reference.providerId)
        provider.openInputStream(entry.reference).use { input ->
            val sample = ByteArray(4096)
            val count = input.read(sample)
            if (count <= 0) return@withContext true
            looksTextual(sample.copyOf(count))
        }
    }

    suspend fun open(source: TextSource, encodingOverride: TextEncoding? = null): TextDocument = withContext(Dispatchers.IO) {
        if (!isLikelyText(source)) throw TextFailure.BinaryFile()
        val size = source.entry.sizeBytes
        val limit = editableLimitBytes()
        val provider = providers.providerFor(source.entry.reference.providerId)
        val prefix = provider.openInputStream(source.entry.reference).use { readPrefix(it, 64 * 1024) }
        val detected = encodingOverride ?: detectEncoding(prefix)
        val lineEnding = detectLineEnding(prefix, detected)
        val fingerprint = fingerprint(source)
        if (size != null && size > limit) {
            val window = readWindowInternal(source, detected, startLine = 1L, maxLines = 400)
            return@withContext TextDocument(source, TextOpenMode.LARGE_READ_ONLY, detected, lineEnding, limit, window.first, 1L, window.second, fingerprint)
        }

        val bytes = provider.openInputStream(source.entry.reference).use { readBounded(it, limit + 1L) }
        if (bytes.size.toLong() > limit) {
            val window = readWindowInternal(source, detected, 1L, 400)
            return@withContext TextDocument(source, TextOpenMode.LARGE_READ_ONLY, detected, lineEnding, limit, window.first, 1L, window.second, fingerprint)
        }
        val text = decode(bytes, detected)
        TextDocument(source, TextOpenMode.EDITABLE, detected, lineEnding, limit, text, 1L, text.lineSequence().count(), fingerprint)
    }

    suspend fun readWindow(document: TextDocument, startLine: Long, maxLines: Int = 400): TextDocument = withContext(Dispatchers.IO) {
        require(document.mode == TextOpenMode.LARGE_READ_ONLY)
        val safeStart = startLine.coerceAtLeast(1L)
        val window = readWindowInternal(document.source, document.encoding, safeStart, maxLines.coerceIn(1, 5_000))
        document.copy(text = window.first, windowStartLine = safeStart, windowLineCount = window.second)
    }

    suspend fun search(
        document: TextDocument,
        query: String,
        caseSensitive: Boolean = false,
        maxMatches: Int = 10_000,
        onProgress: suspend (TextSearchProgress) -> Unit = {},
    ): List<TextSearchMatch> = withContext(Dispatchers.IO) {
        if (query.isEmpty() || maxMatches <= 0) return@withContext emptyList()
        val provider = providers.providerFor(document.source.entry.reference.providerId)
        val counting = CountingInputStream(provider.openInputStream(document.source.entry.reference))
        skipBom(counting, document.encoding)
        val matches = mutableListOf<TextSearchMatch>()
        BufferedReader(InputStreamReader(counting, charset(document.encoding)), 64 * 1024).use { reader ->
            var lineNumber = 0L
            while (matches.size < maxMatches) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                lineNumber++
                var fromIndex = 0
                while (fromIndex <= line.length - query.length && matches.size < maxMatches) {
                    val column = if (caseSensitive) line.indexOf(query, fromIndex) else line.indexOf(query, fromIndex, ignoreCase = true)
                    if (column < 0) break
                    matches += TextSearchMatch(lineNumber, column, line.take(240))
                    fromIndex = (column + query.length).coerceAtLeast(column + 1)
                }
                if (lineNumber % 256L == 0L) onProgress(TextSearchProgress(counting.count, document.source.entry.sizeBytes, matches.size.toLong()))
            }
        }
        onProgress(TextSearchProgress(counting.count, document.source.entry.sizeBytes, matches.size.toLong()))
        matches
    }

    suspend fun save(
        document: TextDocument,
        editedText: String,
        forceOverwriteExternalChange: Boolean = false,
    ): TextDocument = withContext(Dispatchers.IO) {
        if (document.mode != TextOpenMode.EDITABLE) throw TextFailure.TooLargeToEdit()
        val current = fingerprint(document.source)
        if (!forceOverwriteExternalChange && materiallyChanged(document.fingerprint, current)) throw TextFailure.ExternalModification()
        val encoded = encode(normalizeLineEndings(editedText, document.lineEnding), document.encoding)
        val result = try {
            safeWriter.write(
                parent = document.source.parent,
                requestedName = document.source.entry.name,
                mimeType = document.source.entry.mimeType,
                input = ByteArrayInputStream(encoded),
                expectedBytes = encoded.size.toLong(),
                collisionPolicy = CollisionPolicy.REPLACE,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            if (error.message?.contains("cannot safely replace", ignoreCase = true) == true) throw TextFailure.SaveAsRequired()
            throw TextFailure.Io(error)
        } catch (error: Throwable) {
            throw TextFailure.Io(error)
        }
        val entry = (result as? SafeWriteResult.Written)?.entry ?: throw TextFailure.Io(IllegalStateException("Save was unexpectedly skipped"))
        val updatedSource = TextSource(entry, document.source.parent)
        document.copy(source = updatedSource, text = editedText, fingerprint = fingerprint(updatedSource))
    }

    suspend fun saveAs(document: TextDocument, editedText: String, newName: String): TextDocument = withContext(Dispatchers.IO) {
        if (document.mode != TextOpenMode.EDITABLE) throw TextFailure.TooLargeToEdit()
        val encoded = encode(normalizeLineEndings(editedText, document.lineEnding), document.encoding)
        val result = try {
            safeWriter.write(
                parent = document.source.parent,
                requestedName = newName,
                mimeType = document.source.entry.mimeType,
                input = ByteArrayInputStream(encoded),
                expectedBytes = encoded.size.toLong(),
                collisionPolicy = CollisionPolicy.KEEP_BOTH,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw TextFailure.Io(error)
        }
        val entry = (result as? SafeWriteResult.Written)?.entry ?: throw TextFailure.Io(IllegalStateException("Save As was unexpectedly skipped"))
        val source = TextSource(entry, document.source.parent)
        document.copy(source = source, text = editedText, fingerprint = fingerprint(source))
    }

    fun replaceOne(text: String, query: String, replacement: String, startIndex: Int = 0, caseSensitive: Boolean = true): Pair<String, Int>? {
        if (query.isEmpty()) return null
        val index = if (caseSensitive) text.indexOf(query, startIndex) else text.indexOf(query, startIndex, ignoreCase = true)
        if (index < 0) return null
        return text.replaceRange(index, index + query.length, replacement) to index
    }

    fun replaceAll(text: String, query: String, replacement: String, caseSensitive: Boolean = true): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        if (caseSensitive) {
            val count = text.windowed(query.length, 1, partialWindows = false).count { it == query }
            return text.replace(query, replacement) to count
        }
        val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        var count = 0
        val replaced = regex.replace(text) { count++; replacement }
        return replaced to count
    }

    fun editableLimitBytes(): Long {
        val heap = runtimeMaxMemory().coerceAtLeast(32L * 1024L * 1024L)
        return (heap / 16L).coerceIn(4L * 1024L * 1024L, 128L * 1024L * 1024L)
    }

    private suspend fun readWindowInternal(source: TextSource, encoding: TextEncoding, startLine: Long, maxLines: Int): Pair<String, Int> {
        val provider = providers.providerFor(source.entry.reference.providerId)
        val builder = StringBuilder()
        var collected = 0
        provider.openInputStream(source.entry.reference).use { raw ->
            skipBom(raw, encoding)
            BufferedReader(InputStreamReader(raw, charset(encoding)), 64 * 1024).use { reader ->
                var lineNo = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (lineNo < startLine) continue
                    if (collected >= maxLines) break
                    if (collected > 0) builder.append('\n')
                    builder.append(line)
                    collected++
                }
            }
        }
        return builder.toString() to collected
    }

    private suspend fun fingerprint(source: TextSource): TextFingerprint {
        val provider = providers.providerFor(source.entry.reference.providerId)
        val metadata = provider.getMetadata(source.entry.reference) ?: source.entry
        return TextFingerprint(metadata.reference.providerId, metadata.reference.opaqueId, metadata.sizeBytes, metadata.modifiedAtMillis)
    }

    private fun materiallyChanged(before: TextFingerprint, after: TextFingerprint): Boolean =
        before.providerId != after.providerId || before.opaqueId != after.opaqueId ||
            (before.sizeBytes != null && after.sizeBytes != null && before.sizeBytes != after.sizeBytes) ||
            (before.modifiedAtMillis != null && after.modifiedAtMillis != null && before.modifiedAtMillis != after.modifiedAtMillis)

    private fun detectEncoding(prefix: ByteArray): TextEncoding {
        if (prefix.size >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()) return TextEncoding.UTF8_BOM
        if (prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte()) return TextEncoding.UTF16_LE
        if (prefix.size >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte()) return TextEncoding.UTF16_BE
        if (prefix.isEmpty()) return TextEncoding.UTF8
        if (validUtf8(prefix)) return TextEncoding.UTF8
        val pairs = prefix.size / 2
        if (pairs > 8) {
            var evenZero = 0
            var oddZero = 0
            repeat(pairs) { i ->
                if (prefix[i * 2] == 0.toByte()) evenZero++
                if (prefix[i * 2 + 1] == 0.toByte()) oddZero++
            }
            if (oddZero > pairs / 3 && evenZero < pairs / 20) return TextEncoding.UTF16_LE
            if (evenZero > pairs / 3 && oddZero < pairs / 20) return TextEncoding.UTF16_BE
        }
        if (!looksTextual(prefix)) throw TextFailure.BinaryFile()
        return TextEncoding.SYSTEM_FALLBACK
    }

    private fun looksTextual(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) return true
        var controls = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            if (value == 0) return false
            if (value < 0x20 && value !in setOf(0x09, 0x0A, 0x0D, 0x0C, 0x08)) controls++
        }
        return controls.toDouble() / bytes.size.toDouble() < 0.02
    }

    private fun validUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun detectLineEnding(bytes: ByteArray, encoding: TextEncoding): LineEnding {
        val sample = runCatching { decode(bytes, encoding) }.getOrDefault("")
        return if (sample.contains("\r\n")) LineEnding.CRLF else LineEnding.LF
    }

    private fun decode(bytes: ByteArray, encoding: TextEncoding): String {
        val offset = bomLength(bytes, encoding)
        return String(bytes, offset, bytes.size - offset, charset(encoding))
    }

    private fun encode(text: String, encoding: TextEncoding): ByteArray {
        val body = text.toByteArray(charset(encoding))
        return when (encoding) {
            TextEncoding.UTF8_BOM -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
            TextEncoding.UTF16_LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
            TextEncoding.UTF16_BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + body
            else -> body
        }
    }

    private fun charset(encoding: TextEncoding): Charset = when (encoding) {
        TextEncoding.UTF8, TextEncoding.UTF8_BOM -> StandardCharsets.UTF_8
        TextEncoding.UTF16_LE -> StandardCharsets.UTF_16LE
        TextEncoding.UTF16_BE -> StandardCharsets.UTF_16BE
        TextEncoding.SYSTEM_FALLBACK -> Charset.defaultCharset()
    }

    private fun normalizeLineEndings(text: String, ending: LineEnding): String {
        val lf = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (ending == LineEnding.CRLF) lf.replace("\n", "\r\n") else lf
    }

    private fun bomLength(bytes: ByteArray, encoding: TextEncoding): Int = when (encoding) {
        TextEncoding.UTF8_BOM -> if (bytes.size >= 3) 3 else 0
        TextEncoding.UTF16_LE, TextEncoding.UTF16_BE -> if (bytes.size >= 2) 2 else 0
        else -> 0
    }

    private fun skipBom(input: InputStream, encoding: TextEncoding) {
        val count = when (encoding) { TextEncoding.UTF8_BOM -> 3; TextEncoding.UTF16_LE, TextEncoding.UTF16_BE -> 2; else -> 0 }
        repeat(count) { if (input.read() < 0) return }
    }

    private fun readPrefix(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream(min(maximum, 8192))
        val buffer = ByteArray(8192)
        var remaining = maximum
        while (remaining > 0) {
            val count = input.read(buffer, 0, min(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream(min(maximum.coerceAtMost(16L * 1024L * 1024L).toInt(), 16 * 1024 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count.toLong()
            if (total > maximum) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray().let { bytes ->
            if (total > maximum) bytes + byteArrayOf(0) else bytes
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) count += it.toLong() }
    }
}

class TextUndoRedoBuffer(initial: String, private val maxSnapshots: Int = 64) {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    var text: String = initial
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun update(next: String) {
        if (next == text) return
        undo.addLast(text)
        while (undo.size > maxSnapshots) undo.removeFirst()
        text = next
        redo.clear()
    }

    fun undo(): String {
        if (undo.isEmpty()) return text
        redo.addLast(text)
        text = undo.removeLast()
        return text
    }

    fun redo(): String {
        if (redo.isEmpty()) return text
        undo.addLast(text)
        text = redo.removeLast()
        return text
    }
}
