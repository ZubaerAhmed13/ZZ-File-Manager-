package com.zz.filemanager.core.remote

import java.security.KeyPairGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step5RemoteSecurityCertificationTest {
    @Test
    fun sftpHostKeyChangeBlocksConnection() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val firstKey = generator.generateKeyPair().public
        val secondKey = generator.generateKeyPair().public

        val firstContact = PinnedSha256HostKeyVerifier(null)
        assertFalse(firstContact.verify("example.test", 22, firstKey))
        val trustRequired = firstContact.failure()
        assertTrue(trustRequired is RemoteAccessException.HostKeyTrustRequired)
        val trustedFingerprint = (trustRequired as RemoteAccessException.HostKeyTrustRequired).fingerprint

        val returningConnection = PinnedSha256HostKeyVerifier(trustedFingerprint)
        assertFalse(returningConnection.verify("example.test", 22, secondKey))
        assertTrue(returningConnection.failure() is RemoteAccessException.ServerIdentityChanged)
    }

    @Test
    fun ftpsCertificateChangeBlocksConnection() {
        val trustedLeafDer = "trusted-leaf-certificate-der".encodeToByteArray()
        val changedLeafDer = "different-leaf-certificate-der".encodeToByteArray()
        val trustedPin = CertificatePinPolicy.sha256(trustedLeafDer)
        val policy = CertificatePinPolicy(trustedPin)

        assertTrue(policy.matches(trustedLeafDer))
        assertFalse(policy.matches(changedLeafDer))
    }
}
