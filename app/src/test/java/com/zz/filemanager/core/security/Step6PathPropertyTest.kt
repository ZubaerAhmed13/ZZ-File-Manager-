package com.zz.filemanager.core.security

import com.zz.filemanager.core.archive.ArchiveFailure
import com.zz.filemanager.core.archive.ArchivePathValidator
import com.zz.filemanager.core.operation.FileNameRules
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class Step6PathPropertyTest {
    @Test fun traversalVariantsNeverNormalizeOutsideArchiveRoot() {
        val unsafe = listOf("../x", "..\\x", "/root", "C:/root", "a/../../b", "a/../b", "\\\\server\\share", "safe/\\u0000bad")
        unsafe.forEach { raw -> assertTrue(runCatching { ArchivePathValidator.normalize(raw.replace("\\u0000", "\u0000")) }.exceptionOrNull() is ArchiveFailure.UnsafePath) }
    }
    @Test fun generatedSafeNamesRemainLeavesAndKeepBothPreservesExtension() {
        val random = Random(6)
        repeat(5_000) { index ->
            val stem = buildString { repeat(random.nextInt(1, 40)) { append(('a'.code + random.nextInt(26)).toChar()) } }
            val original = "$stem.txt"
            assertEquals(null, FileNameRules.validateLeafName(original))
            val candidate = FileNameRules.keepBothCandidate(original, index + 1, false)
            assertEquals(null, FileNameRules.validateLeafName(candidate)); assertTrue(candidate.endsWith(".txt"))
        }
    }
    @Test fun separatorsNulAndReservedLeavesAreAlwaysRejected() { listOf("", " ", ".", "..", "a/b", "a\\b", "a\u0000b").forEach { assertNotNull(FileNameRules.validateLeafName(it)) } }
}
