package com.zz.filemanager.core.remote

import java.security.MessageDigest

/** Exact SHA-256 leaf-certificate pin comparison shared by FTPS and its certification tests. */
internal class CertificatePinPolicy(expectedSha256: String) {
    private val expected = normalize(expectedSha256)

    fun matches(encodedCertificate: ByteArray): Boolean = sha256(encodedCertificate) == expected

    companion object {
        internal fun normalize(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()
        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
