package com.zz.filemanager.feature.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads at most [limit] bytes from this stream.
 *
 * This intentionally shadows Kotlin's deprecated readBytes(estimatedSize) overload inside
 * the remote package. Callers can request one byte beyond their accepted maximum and reject
 * that sentinel-sized result without ever loading an unbounded credential file into memory.
 */
internal fun InputStream.readBytes(limit: Int): ByteArray {
    require(limit >= 0) { "limit must be non-negative" }
    if (limit == 0) return ByteArray(0)

    val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit

    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        remaining -= read
    }

    return output.toByteArray()
}
