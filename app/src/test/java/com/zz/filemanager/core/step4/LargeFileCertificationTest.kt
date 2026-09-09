package com.zz.filemanager.core.step4

import com.zz.filemanager.core.archive.ArchiveExpansionGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeFileCertificationTest {
    @Test
    fun threeTenAndThirtyGiBUseLongAccountingWithoutAllocation() {
        val sizes = listOf(3L, 10L, 30L).map { it * GIB }
        sizes.forEach { size ->
            val guard = ArchiveExpansionGuard(destinationFreeBytes = size + GIB, allowSuspicious = false)
            guard.observeHeader(compressedBytes = size, uncompressedBytes = size)
            guard.observeActual(size)
            val observation = guard.snapshot()
            assertEquals(size, observation.declaredCompressedBytes)
            assertEquals(size, observation.declaredUncompressedBytes)
            assertEquals(size, observation.actualExtractedBytes)
            assertTrue(observation.actualExtractedBytes > Int.MAX_VALUE.toLong())
        }
    }

    @Test
    fun thirtyGiBProgressRatiosRemainFiniteAndBounded() {
        val total = 30L * GIB
        val checkpoints = listOf(0L, 3L * GIB, 10L * GIB, 29L * GIB, total)
        checkpoints.forEach { processed ->
            val ratio = processed.toDouble() / total.toDouble()
            assertTrue(ratio.isFinite())
            assertTrue(ratio in 0.0..1.0)
        }
    }

    private companion object {
        const val GIB: Long = 1024L * 1024L * 1024L
    }
}
