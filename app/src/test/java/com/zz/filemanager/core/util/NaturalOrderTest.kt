package com.zz.filemanager.core.util

import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.model.SortConfiguration
import com.zz.filemanager.core.model.SortDirection
import com.zz.filemanager.core.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun naturalNumbersSortAsHumansExpect() {
        val input = listOf("file10", "file2", "file1", "file3")
        assertEquals(listOf("file1", "file2", "file3", "file10"), input.sortedWith(NaturalOrder::compare))
    }

    @Test fun foldersStayFirstEvenWhenDescending() {
        val entries = listOf(entry("z.txt", false, 10), entry("Folder2", true, null), entry("Folder10", true, null), entry("a.txt", false, 20))
        val sorted = FileSorter.sort(entries, SortConfiguration(SortField.NAME, SortDirection.DESCENDING, foldersFirst = true))
        assertEquals(listOf("Folder10", "Folder2", "z.txt", "a.txt"), sorted.map { it.name })
    }

    @Test fun sizeSortHandlesValuesAboveTwoGigabytes() {
        val entries = listOf(entry("small", false, 1L), entry("huge", false, 50L * 1024 * 1024 * 1024))
        assertEquals(listOf("small", "huge"), FileSorter.sort(entries, SortConfiguration(SortField.SIZE)).map { it.name })
    }

    private fun entry(name: String, folder: Boolean, size: Long?) = FileEntry(
        id = name, reference = FileReference("test", name), name = name, extension = FileClassifier.extensionFor(name), mimeType = null,
        type = if (folder) FileEntryType.DIRECTORY else FileEntryType.GENERIC, sizeBytes = size, modifiedAtMillis = null,
        createdAtMillis = null, isHidden = false, isReadable = true, isWritable = false, childCount = null,
        storageId = "test", thumbnailKey = null,
    )
}
