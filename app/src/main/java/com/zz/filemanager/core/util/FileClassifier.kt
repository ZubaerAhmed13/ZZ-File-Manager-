package com.zz.filemanager.core.util

import com.zz.filemanager.core.model.FileEntryType
import java.util.Locale

object FileClassifier {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "amr")
    private val text = setOf(
        "txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "ini", "cfg", "properties",
        "kt", "java", "html", "css", "js", "ts", "py", "sh",
    )
    private val docs = setOf("doc", "docx", "odt", "rtf")
    private val sheets = setOf("xls", "xlsx", "ods")
    private val presentations = setOf("ppt", "pptx", "odp")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "txz")

    fun extensionFor(name: String): String? {
        val index = name.lastIndexOf('.')
        if (index <= 0 || index == name.lastIndex) return null
        return name.substring(index + 1).lowercase(Locale.ROOT)
    }

    fun classify(name: String, mimeType: String?, directory: Boolean = false): FileEntryType {
        if (directory) return FileEntryType.DIRECTORY
        val mime = mimeType?.lowercase(Locale.ROOT)
        if (mime != null) {
            when {
                mime.startsWith("image/") -> return FileEntryType.IMAGE
                mime.startsWith("video/") -> return FileEntryType.VIDEO
                mime.startsWith("audio/") -> return FileEntryType.AUDIO
                mime == "application/pdf" -> return FileEntryType.PDF
                mime.startsWith("text/") -> return FileEntryType.TEXT
                mime == "application/vnd.android.package-archive" -> return FileEntryType.APK
                mime.contains("spreadsheet") || mime.contains("excel") -> return FileEntryType.SPREADSHEET
                mime.contains("presentation") || mime.contains("powerpoint") -> return FileEntryType.PRESENTATION
                mime.contains("wordprocessing") || mime.contains("msword") || mime.contains("opendocument.text") -> return FileEntryType.DOCUMENT
                mime.contains("zip") || mime.contains("rar") || mime.contains("7z") || mime.contains("compressed") || mime.contains("tar") || mime.contains("gzip") || mime.contains("bzip") || mime.contains("xz") -> return FileEntryType.ARCHIVE
            }
        }
        return when (extensionFor(name)) {
            in image -> FileEntryType.IMAGE
            in video -> FileEntryType.VIDEO
            in audio -> FileEntryType.AUDIO
            "pdf" -> FileEntryType.PDF
            in text -> FileEntryType.TEXT
            in docs -> FileEntryType.DOCUMENT
            in sheets -> FileEntryType.SPREADSHEET
            in presentations -> FileEntryType.PRESENTATION
            in archives -> FileEntryType.ARCHIVE
            "apk" -> FileEntryType.APK
            null -> FileEntryType.UNKNOWN
            else -> FileEntryType.GENERIC
        }
    }
}
