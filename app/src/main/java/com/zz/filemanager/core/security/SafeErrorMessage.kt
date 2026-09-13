package com.zz.filemanager.core.security

import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Converts external failures into actionable UI copy without echoing credentials or headers. */
object SafeErrorMessage {
    private val sensitive = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)"),
        Regex("(?i)((?:password|passwd|token|refresh_token|client_secret|passphrase)\\s*[:=]\\s*)([^\\s,;]+)"),
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*"),
        Regex("(?i)(https?://[^:/\\s]+:)[^@/\\s]+(?=@)"),
    )

    fun from(error: Throwable, fallback: String = "The operation could not be completed."): String {
        val friendly = when (error) {
            is SocketTimeoutException -> "Connection timed out."
            is UnknownHostException -> "Server address could not be found."
            is SecurityException -> "Permission denied."
            is FileNotFoundException -> "The file is no longer available."
            else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
        }
        return redact(friendly).take(300).ifBlank { fallback }
    }

    fun redact(message: String): String = sensitive.fold(message) { safe, pattern ->
        pattern.replace(safe) { match -> "${match.groupValues[1]}[redacted]" }
    }
}
