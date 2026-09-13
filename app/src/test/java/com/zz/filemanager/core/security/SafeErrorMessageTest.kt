package com.zz.filemanager.core.security

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class SafeErrorMessageTest {
    @Test fun credentialsAndAuthorizationHeadersAreRedacted() {
        val result = SafeErrorMessage.redact("password=hunter2 Authorization:BearerSecret token=abc123")
        assertFalse(result.contains("hunter2")); assertFalse(result.contains("BearerSecret")); assertFalse(result.contains("abc123")); assertTrue(result.contains("[redacted]"))
    }
    @Test fun credentialsInsideUrlsAreRedacted() { assertEquals("https://user:[redacted]@server/share", SafeErrorMessage.redact("https://user:secret@server/share")) }
    @Test fun timeoutGetsStableUserMessage() { assertEquals("Connection timed out.", SafeErrorMessage.from(SocketTimeoutException("detail"))) }
}
