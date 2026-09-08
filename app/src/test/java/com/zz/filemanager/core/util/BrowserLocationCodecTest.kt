package com.zz.filemanager.core.util

import com.zz.filemanager.core.model.BrowserLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserLocationCodecTest {
    @Test fun roundTripsUnicodeUrisAndDelimiters() {
        val original = BrowserLocation("saf", "id|1", "旅行 / Projects", "content://tree/a%2Fb|x", "content://tree/a%2Fb", "saf:旅行", true, false)
        assertEquals(original, BrowserLocationCodec.decode(BrowserLocationCodec.encode(original)))
    }

    @Test fun listKeepsNewestOrder() {
        val values = listOf(
            BrowserLocation("test", "2", "Two", "2", "root", "test"),
            BrowserLocation("test", "1", "One", "1", "root", "test"),
        )
        assertEquals(values, BrowserLocationCodec.decodeList(BrowserLocationCodec.encodeList(values)))
    }
}
