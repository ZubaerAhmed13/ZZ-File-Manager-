package com.zz.filemanager.core.performance

import com.zz.filemanager.core.model.*
import com.zz.filemanager.core.util.FileSorter
import org.junit.Assert.*
import org.junit.Test

class Step6PerformanceCertificationTest {
    @Test fun hundredThousandEntriesUseBoundedPartialSnapshotSchedule() {
        val policy = LargeDirectoryEmissionPolicy(256)
        var emissions = 0
        var first = -1
        for (size in 256..100_000 step 256) if (policy.shouldEmit(size)) {
            if (first < 0) first = size
            emissions++
            policy.onEmitted(size)
        }
        assertEquals(256, first)
        assertTrue("Exponential snapshots must stay bounded", emissions <= 10)
    }

    @Test fun hundredThousandMetadataEntriesSortWithLongSafeSizes() {
        val entries = List(100_000) { index -> FileEntry(
            id = index.toString(), reference = FileReference("fake", index.toString()),
            name = "file-${100_000 - index}.bin", extension = "bin", mimeType = "application/octet-stream",
            type = FileEntryType.GENERIC, sizeBytes = 30L * 1024L * 1024L * 1024L + index,
            modifiedAtMillis = index.toLong(), createdAtMillis = null, isHidden = false,
            isReadable = true, isWritable = false, childCount = null, storageId = "synthetic", thumbnailKey = null,
        ) }
        val sorted = FileSorter.sort(entries, SortConfiguration(SortField.SIZE, SortDirection.ASCENDING, false))
        assertEquals(100_000, sorted.size)
        assertTrue(sorted.first().sizeBytes!! > Int.MAX_VALUE.toLong())
        assertTrue(sorted.last().sizeBytes!! > sorted.first().sizeBytes!!)
    }
}
