package com.zz.filemanager.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {
    @Test fun normalizesWithoutEscapingRoot() {
        assertEquals("/folder/a b/বাংলা.txt", RemotePath.normalize("//folder/./a b/বাংলা.txt"))
        assertEquals("/folder/file.txt", RemotePath.normalize("/folder/child/../file.txt"))
    }

    @Test fun rejectsRootEscapeAndUnsafeLeaf() {
        assertThrows(IllegalArgumentException::class.java) { RemotePath.normalize("../../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.leaf("../secret") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.leaf("a/b") }
    }

    @Test fun descendantCheckIsSegmentAware() {
        assertTrue(RemotePath.isWithin("/safe/root", "/safe/root/child/file"))
        assertTrue(RemotePath.isWithin("/safe/root", "/safe/root"))
        assertFalse(RemotePath.isWithin("/safe/root", "/safe/root2/file"))
    }
}

class NetworkConnectionValidationTest {
    @Test fun ftpIsExplicitlyUnencryptedByDefault() {
        val connection = NetworkConnection(protocol = RemoteProtocol.FTP, displayName = "FTP", host = "example.invalid")
        assertFalse(connection.protocol.encryptedByDefault)
        assertEquals(RemoteTlsMode.NONE, connection.tlsMode)
        assertTrue(connection.validationErrors().isEmpty())
    }

    @Test fun smbRequiresShareAndPinnedTlsRequiresFingerprint() {
        val smb = NetworkConnection(protocol = RemoteProtocol.SMB, displayName = "NAS", host = "nas.local", share = null)
        assertTrue(smb.validationErrors().any { it.contains("share", ignoreCase = true) })

        val ftps = NetworkConnection(
            protocol = RemoteProtocol.FTPS,
            displayName = "FTPS",
            host = "files.example.invalid",
            certificatePolicy = RemoteCertificatePolicy.PINNED,
            certificateSha256 = null,
        )
        assertTrue(ftps.validationErrors().any { it.contains("fingerprint", ignoreCase = true) })
    }

    @Test fun providerIdentityIsConnectionStableNotDisplayPath() {
        val connection = NetworkConnection(id = "abc", protocol = RemoteProtocol.SFTP, displayName = "Server", host = "s.example")
        assertEquals("sftp:abc", connection.providerId)
        assertEquals("sftp:abc", connection.storageId)
    }
}
