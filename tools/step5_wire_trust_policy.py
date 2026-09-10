from pathlib import Path

sftp = Path("app/src/main/java/com/zz/filemanager/core/remote/SftpRemoteFileSystem.kt")
text = sftp.read_text()
old = "private class PinnedSha256HostKeyVerifier(private val expectedRaw: String?) : HostKeyVerifier {"
new = "internal class PinnedSha256HostKeyVerifier(private val expectedRaw: String?) : HostKeyVerifier {"
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)
sftp.write_text(text)

ftp = Path("app/src/main/java/com/zz/filemanager/core/remote/FtpRemoteFileSystem.kt")
text = ftp.read_text()
old = '''private class PinnedCertificateTrustManager(expectedSha256: String) : X509TrustManager {\n    private val expected = normalizeFingerprint(expectedSha256)\n\n    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit\n\n    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {\n        val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")\n        certificate.checkValidity()\n        val actual = sha256(certificate.encoded)\n        if (actual != expected) throw CertificateException("Server certificate fingerprint changed")\n    }\n\n    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()\n\n    companion object {\n        private fun normalizeFingerprint(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()\n        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }\n    }\n}'''
new = '''private class PinnedCertificateTrustManager(expectedSha256: String) : X509TrustManager {\n    private val pin = CertificatePinPolicy(expectedSha256)\n\n    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit\n\n    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {\n        val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")\n        certificate.checkValidity()\n        if (!pin.matches(certificate.encoded)) throw CertificateException("Server certificate fingerprint changed")\n    }\n\n    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()\n}'''
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)
ftp.write_text(text)
print("wired trust policies")
