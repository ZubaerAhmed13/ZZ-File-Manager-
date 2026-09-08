package com.zz.filemanager.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormattersTest {
    @Test fun formatsBytesWithoutIntegerOverflow() {
        assertEquals("0 B", Formatters.bytes(0, Locale.US))
        assertEquals("1 B", Formatters.bytes(1, Locale.US))
        assertEquals("1.00 KB", Formatters.bytes(1024, Locale.US))
        assertEquals("2.00 GB", Formatters.bytes(2L * 1024 * 1024 * 1024, Locale.US))
        assertEquals("5.00 TB", Formatters.bytes(5L * 1024 * 1024 * 1024 * 1024, Locale.US))
    }
}
