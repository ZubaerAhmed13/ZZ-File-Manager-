package com.zz.filemanager.feature.remote

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedInputStreamTest {
    @Test
    fun privateKeyImportReadStopsAtConfiguredSentinelLimit() {
        val limit = 1024 * 1024 + 1
        val source = CountingInfiniteInputStream()
        val bytes = source.readBytes(limit)
        assertEquals(limit, bytes.size)
        assertEquals(limit.toLong(), source.bytesRead)
        assertTrue(source.read() >= 0)
        assertEquals(limit.toLong() + 1L, source.bytesRead)
    }

    private class CountingInfiniteInputStream : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            bytesRead += 1L
            return 0x41
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            java.util.Arrays.fill(buffer, offset, offset + length, 0x41.toByte())
            bytesRead += length.toLong()
            return length
        }
    }
}
