package com.zz.filemanager.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSecurityTest {
    @Test
    fun normalizesSafeRelativePaths() {
        assertEquals("folder/report.txt", ArchivePathValidator.normalize("folder/./report.txt"))
        assertEquals("nested/file.bin", ArchivePathValidator.normalize("nested\\file.bin"))
    }

    @Test(expected = ArchiveFailure.UnsafePath::class)
    fun rejectsParentTraversal() {
        ArchivePathValidator.normalize("../../outside.txt")
    }

    @Test(expected = ArchiveFailure.UnsafePath::class)
    fun rejectsAbsolutePath() {
        ArchivePathValidator.normalize("/system/secret")
    }

    @Test(expected = ArchiveFailure.UnsafePath::class)
    fun rejectsWindowsDrivePrefix() {
        ArchivePathValidator.normalize("C:/escape.txt")
    }

    @Test(expected = ArchiveFailure.UnsafePath::class)
    fun rejectsEmbeddedTraversalAfterNormalization() {
        ArchivePathValidator.normalize("safe/../escape.txt")
    }

    @Test(expected = ArchiveFailure.DuplicatePath::class)
    fun rejectsDuplicateNormalizedPathsCaseInsensitively() {
        ArchivePathValidator.validateUnique(listOf("Folder/File.txt", "folder/file.txt"))
    }

    @Test(expected = ArchiveFailure.SuspiciousExpansion::class)
    fun detectsExtremeDeclaredExpansionRatio() {
        ArchiveExpansionGuard(destinationFreeBytes = null, allowSuspicious = false)
            .observeHeader(compressedBytes = 1024L, uncompressedBytes = 300L * 1024L * 1024L)
    }

    @Test(expected = ArchiveFailure.InsufficientSpace::class)
    fun detectsInsufficientDestinationSpace() {
        ArchiveExpansionGuard(destinationFreeBytes = 100L, allowSuspicious = false)
            .observeHeader(compressedBytes = 90L, uncompressedBytes = 101L)
    }

    @Test
    fun keepsLargeArchiveByteAccountingInLongRange() {
        val guard = ArchiveExpansionGuard(destinationFreeBytes = null, allowSuspicious = false)
        val twentyGiB = 20L * 1024L * 1024L * 1024L
        guard.observeActual(twentyGiB)
        guard.observeActual(twentyGiB)
        val observation = guard.snapshot()
        assertEquals(40L * 1024L * 1024L * 1024L, observation.actualExtractedBytes)
        assertTrue(observation.actualExtractedBytes > Int.MAX_VALUE.toLong())
    }
}
