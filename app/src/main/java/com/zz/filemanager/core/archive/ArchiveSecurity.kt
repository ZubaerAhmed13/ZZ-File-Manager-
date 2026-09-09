package com.zz.filemanager.core.archive

import java.util.Locale

object ArchivePathValidator {
    private val drivePrefix = Regex("^[A-Za-z]:($|/)")

    fun normalize(raw: String): String {
        if (raw.indexOf('\u0000') >= 0) throw ArchiveFailure.UnsafePath(raw)
        val slash = raw.replace('\\', '/').trim()
        if (slash.isBlank()) throw ArchiveFailure.UnsafePath(raw)
        if (slash.startsWith('/') || slash.startsWith("//") || drivePrefix.containsMatchIn(slash)) {
            throw ArchiveFailure.UnsafePath(raw)
        }
        val parts = slash.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) throw ArchiveFailure.UnsafePath(raw)
        val normalized = parts.joinToString("/")
        if (normalized.startsWith("../") || normalized == "..") throw ArchiveFailure.UnsafePath(raw)
        return normalized
    }

    fun validateUnique(entries: Iterable<String>): Map<String, String> {
        val normalized = linkedMapOf<String, String>()
        entries.forEach { raw ->
            val safe = normalize(raw)
            val key = safe.lowercase(Locale.ROOT)
            val previous = normalized.putIfAbsent(key, safe)
            if (previous != null) throw ArchiveFailure.DuplicatePath(safe)
        }
        return normalized
    }
}

data class ExpansionObservation(
    val entryCount: Long,
    val declaredCompressedBytes: Long,
    val declaredUncompressedBytes: Long,
    val actualExtractedBytes: Long,
)

/**
 * Bomb protection uses observed structure, ratios and real destination capacity rather than an
 * arbitrary product file-size ceiling. Large legitimate archives remain supported when space and
 * structure are sane.
 */
class ArchiveExpansionGuard(
    private val destinationFreeBytes: Long?,
    private val allowSuspicious: Boolean,
) {
    private var entries = 0L
    private var compressed = 0L
    private var declared = 0L
    private var actual = 0L

    fun observeHeader(compressedBytes: Long?, uncompressedBytes: Long?) {
        entries++
        if (compressedBytes != null && compressedBytes >= 0L) compressed = safeAdd(compressed, compressedBytes)
        if (uncompressedBytes != null && uncompressedBytes >= 0L) declared = safeAdd(declared, uncompressedBytes)
        evaluate(structuralOnly = true)
    }

    fun observeActual(bytes: Long) {
        if (bytes <= 0L) return
        actual = safeAdd(actual, bytes)
        evaluate(structuralOnly = false)
    }

    fun snapshot() = ExpansionObservation(entries, compressed, declared, actual)

    private fun evaluate(structuralOnly: Boolean) {
        if (allowSuspicious) return
        if (entries > 2_000_000L) {
            throw ArchiveFailure.SuspiciousExpansion("Archive contains an unusually large number of entries; confirmation is required")
        }
        val referenceUncompressed = maxOf(declared, actual)
        if (compressed > 0L && referenceUncompressed >= 256L * 1024L * 1024L) {
            val ratio = referenceUncompressed.toDouble() / compressed.toDouble()
            if (ratio > 2_000.0) {
                throw ArchiveFailure.SuspiciousExpansion("Archive expansion ratio is suspicious (${ratio.toLong()}:1); confirmation is required")
            }
        }
        val free = destinationFreeBytes
        if (free != null && free >= 0L && referenceUncompressed > free) {
            throw ArchiveFailure.InsufficientSpace(referenceUncompressed, free)
        }
        if (!structuralOnly && free != null && free >= 0L && actual > free) {
            throw ArchiveFailure.InsufficientSpace(actual, free)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            throw ArchiveFailure.SuspiciousExpansion("Archive byte accounting overflow was prevented")
        }
        return left + right
    }
}
