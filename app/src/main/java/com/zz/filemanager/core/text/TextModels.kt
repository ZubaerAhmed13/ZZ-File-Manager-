package com.zz.filemanager.core.text

import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry

enum class TextEncoding(val displayName: String) {
    UTF8("UTF-8"),
    UTF8_BOM("UTF-8 BOM"),
    UTF16_LE("UTF-16 LE"),
    UTF16_BE("UTF-16 BE"),
    SYSTEM_FALLBACK("System fallback"),
}

enum class LineEnding(val sequence: String) { LF("\n"), CRLF("\r\n") }
enum class TextOpenMode { EDITABLE, LARGE_READ_ONLY }

data class TextSource(val entry: FileEntry, val parent: BrowserLocation)

data class TextFingerprint(
    val providerId: String,
    val opaqueId: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
)

data class TextDocument(
    val source: TextSource,
    val mode: TextOpenMode,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    val editableLimitBytes: Long,
    val text: String,
    val windowStartLine: Long = 1L,
    val windowLineCount: Int = 0,
    val fingerprint: TextFingerprint,
)

data class TextSearchMatch(val lineNumber: Long, val column: Int, val preview: String)

data class TextSearchProgress(val bytesRead: Long, val totalBytes: Long?, val matches: Long)

sealed class TextFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BinaryFile : TextFailure("This file does not appear to contain text")
    class EncodingUncertain : TextFailure("Text encoding could not be determined safely")
    class ExternalModification : TextFailure("The file changed while it was open")
    class SaveAsRequired : TextFailure("This provider cannot safely overwrite the original; use Save As")
    class TooLargeToEdit : TextFailure("This file is opened in large-file read-only mode")
    class Io(cause: Throwable) : TextFailure("Text operation failed", cause)
}
