package com.zz.filemanager.core.util

import com.zz.filemanager.core.model.FileEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileClassifierTest {
    @Test fun recognizesRepresentativeTypes() {
        assertEquals(FileEntryType.IMAGE, FileClassifier.classify("photo.JPG", null))
        assertEquals(FileEntryType.VIDEO, FileClassifier.classify("video.MP4", null))
        assertEquals(FileEntryType.PDF, FileClassifier.classify("document.pdf", null))
        assertEquals(FileEntryType.ARCHIVE, FileClassifier.classify("archive.zip", null))
        assertEquals(FileEntryType.APK, FileClassifier.classify("app.apk", null))
        assertEquals(FileEntryType.TEXT, FileClassifier.classify("some.file.name.txt", null))
        assertEquals(FileEntryType.UNKNOWN, FileClassifier.classify("README", null))
    }

    @Test fun hiddenDotFileIsNotTreatedAsExtensionOnly() {
        assertNull(FileClassifier.extensionFor(".env"))
    }

    @Test fun mimeWinsOverExtensionWhenReliable() {
        assertEquals(FileEntryType.IMAGE, FileClassifier.classify("odd.bin", "image/jpeg"))
    }
}
