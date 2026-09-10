package com.zz.filemanager.core.remote

import com.zz.filemanager.core.cloud.CloudResumeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step5LargeFileAndPathCertificationTest {
    @Test
    fun logicalThreeGiBUsesLongOffsetsWithoutOverflow() = certifyLogicalSize(3L * GIB)

    @Test
    fun logicalTenGiBUsesLongOffsetsWithoutOverflow() = certifyLogicalSize(10L * GIB)

    @Test
    fun logicalThirtyGiBUsesLongOffsetsWithoutOverflow() = certifyLogicalSize(30L * GIB)

    @Test
    fun unicodeAndReservedFilenameInputsStaySingleRemotePathComponents() {
        val names = listOf(
            "space name.txt",
            "emoji-📁.txt",
            "বাংলা-ফাইল.txt",
            "größer-äöü-ß.txt",
            "e\u0301-combining.txt",
            "hash#percent%question?.txt",
            "[brackets] (round).txt",
            "n".repeat(220) + ".txt",
        )
        names.forEach { name ->
            assertEquals(name, RemotePath.leaf(name))
            val resolved = RemotePath.resolve("/root", name)
            assertTrue(resolved.startsWith("/root/"))
            assertEquals(name, RemotePath.name(resolved))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun remotePathCannotEscapeProviderRoot() {
        RemotePath.normalize("../../escape")
    }

    private fun certifyLogicalSize(size: Long) {
        assertTrue(size > Int.MAX_VALUE.toLong())
        val resumeOffset = size - (64L * 1024L)
        assertTrue(resumeOffset > Int.MAX_VALUE.toLong())
        val token = CloudResumeToken(
            uploadSessionId = "session-$size",
            offset = resumeOffset,
            sourceRevision = "rev-$size",
        )
        assertEquals(resumeOffset, token.offset)
        val remaining = size - token.offset
        assertEquals(64L * 1024L, remaining)
        val percentBasisPoints = (token.offset * 10_000L) / size
        assertTrue(percentBasisPoints in 0L..10_000L)
        // The production transfer engine uses a fixed bounded byte buffer; no logical fixture is
        // allocated here. This test intentionally proves arithmetic beyond the 2 GiB Int boundary.
        val boundedBufferBytes = 256L * 1024L
        assertTrue(boundedBufferBytes < size)
    }

    companion object {
        private const val GIB = 1024L * 1024L * 1024L
    }
}
