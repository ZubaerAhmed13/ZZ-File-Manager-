package com.zz.filemanager.core.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Controlled CI interoperability against disposable protocol servers started by android.yml.
 * Outside certification CI these tests are skipped; CI sets STEP5_PROTOCOL_INTEROP_REQUIRED=1 and
 * separately enforces zero skipped tests, so missing/broken fixtures cannot be reported as green.
 */
class Step5DisposableProtocolInteropTest {
    private val settings = RemoteTransferSettings(
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 10_000,
        writeTimeoutMillis = 10_000,
        retryCount = 0,
    )

    @Test
    fun ftpClientPerformsRealSessionAgainstDisposableServer() {
        requireCertificationCi()
        val connection = NetworkConnection(
            protocol = RemoteProtocol.FTP,
            displayName = "CI FTP",
            host = requiredEnv("STEP5_FTP_HOST"),
            port = requiredEnv("STEP5_FTP_PORT").toInt(),
            username = requiredEnv("STEP5_PROTOCOL_USER"),
            authenticationType = RemoteAuthenticationType.PASSWORD,
            rootPath = "/",
        )
        openPassword(FtpRemoteFileSystemFactory(), connection).use { fs ->
            exercisePortableProtocol(fs, "/", "ftp")
        }
    }

    @Test
    fun ftpsClientPerformsRealTlsSessionAgainstDisposableServer() {
        requireCertificationCi()
        val connection = NetworkConnection(
            protocol = RemoteProtocol.FTPS,
            displayName = "CI FTPS",
            host = requiredEnv("STEP5_FTPS_HOST"),
            port = requiredEnv("STEP5_FTPS_PORT").toInt(),
            username = requiredEnv("STEP5_PROTOCOL_USER"),
            authenticationType = RemoteAuthenticationType.PASSWORD,
            rootPath = "/",
            tlsMode = RemoteTlsMode.EXPLICIT,
            certificatePolicy = RemoteCertificatePolicy.PINNED,
            certificateSha256 = requiredEnv("STEP5_FTPS_CERT_SHA256"),
        )
        openPassword(FtpRemoteFileSystemFactory(), connection).use { fs ->
            exercisePortableProtocol(fs, "/", "ftps")
        }
    }

    @Test
    fun sftpClientPerformsRealSessionAgainstDisposableServer() {
        requireCertificationCi()
        val base = NetworkConnection(
            protocol = RemoteProtocol.SFTP,
            displayName = "CI SFTP",
            host = requiredEnv("STEP5_SFTP_HOST"),
            port = requiredEnv("STEP5_SFTP_PORT").toInt(),
            username = requiredEnv("STEP5_PROTOCOL_USER"),
            authenticationType = RemoteAuthenticationType.PASSWORD,
            rootPath = "/upload",
        )

        val firstContact = try {
            openPassword(SftpRemoteFileSystemFactory(), base).use { }
            throw AssertionError("Unpinned SFTP first contact unexpectedly succeeded")
        } catch (required: RemoteAccessException.HostKeyTrustRequired) {
            required
        }
        assertTrue(firstContact.fingerprint.startsWith("SHA256:"))

        val pinned = base.copy(sshHostKeySha256 = firstContact.fingerprint)
        openPassword(SftpRemoteFileSystemFactory(), pinned).use { fs ->
            exercisePortableProtocol(fs, "/upload", "sftp", requireOffsetIo = true)
        }
    }

    @Test
    fun smbClientPerformsNegotiationOffsetIoRemoteCopyAndAtomicReplaceAgainstDisposableServer() {
        requireCertificationCi()
        val connection = NetworkConnection(
            protocol = RemoteProtocol.SMB,
            displayName = "CI SMB",
            host = requiredEnv("STEP5_SMB_HOST"),
            port = requiredEnv("STEP5_SMB_PORT").toInt(),
            username = requiredEnv("STEP5_PROTOCOL_USER"),
            authenticationType = RemoteAuthenticationType.PASSWORD,
            share = requiredEnv("STEP5_SMB_SHARE"),
            rootPath = "/",
        )
        openPassword(SmbRemoteFileSystemFactory(), connection).use { fs ->
            assertTrue(fs.capabilities.seekRead)
            assertTrue(fs.capabilities.seekWrite)
            assertTrue(fs.capabilities.serverSideCopy)
            assertTrue(fs.capabilities.atomicReplace)

            val dir = "/ci-smb-${System.nanoTime()}"
            val source = "$dir/source.bin"
            val copied = "$dir/copied.bin"
            val replacement = "$dir/replacement.bin"
            val renamed = "$dir/renamed.bin"
            fs.createDirectory(dir)
            try {
                val original = "0123456789".encodeToByteArray()
                fs.openOutput(source, 0L, truncate = true).use { it.write(original) }
                assertArrayEquals("56789".encodeToByteArray(), fs.openInput(source, 5L).use { it.readBytes() })

                fs.openOutput(source, 5L, truncate = false).use { it.write("ABCDE".encodeToByteArray()) }
                assertArrayEquals("01234ABCDE".encodeToByteArray(), fs.openInput(source).use { it.readBytes() })

                assertTrue("Disposable Samba server must exercise SMB server-side copy", fs.copyServerSide(source, copied))
                assertArrayEquals("01234ABCDE".encodeToByteArray(), fs.openInput(copied).use { it.readBytes() })

                fs.openOutput(replacement, 0L, truncate = true).use { it.write("replacement".encodeToByteArray()) }
                fs.rename(replacement, copied, replace = true)
                assertArrayEquals("replacement".encodeToByteArray(), fs.openInput(copied).use { it.readBytes() })

                fs.rename(source, renamed, replace = false)
                assertNotNull(fs.stat(renamed))
                val pages = mutableListOf<List<RemoteNode>>()
                kotlinx.coroutines.runBlocking { fs.listPages(dir, 1) { pages += it } }
                assertTrue(pages.size >= 2)
            } finally {
                runCatching { fs.delete(source, false) }
                runCatching { fs.delete(renamed, false) }
                runCatching { fs.delete(copied, false) }
                runCatching { fs.delete(replacement, false) }
                runCatching { fs.delete(dir, true) }
            }
        }
    }

    private fun exercisePortableProtocol(
        fs: RemoteFileSystem,
        root: String,
        label: String,
        requireOffsetIo: Boolean = false,
    ) {
        val dir = RemotePath.resolve(root, "ci-$label-${System.nanoTime()}")
        val source = RemotePath.resolve(dir, "source.txt")
        val renamed = RemotePath.resolve(dir, "renamed.txt")
        val payload = "hello-$label-real-protocol".encodeToByteArray()
        fs.createDirectory(dir)
        try {
            fs.openOutput(source, 0L, truncate = true).use { it.write(payload) }
            assertEquals(payload.size.toLong(), fs.stat(source)?.sizeBytes)

            val pages = mutableListOf<List<RemoteNode>>()
            kotlinx.coroutines.runBlocking { fs.listPages(dir, 1) { pages += it } }
            assertTrue(pages.flatten().any { it.name == "source.txt" })
            assertArrayEquals(payload, fs.openInput(source).use { it.readBytes() })

            if (requireOffsetIo) {
                val offset = 6L
                assertArrayEquals(payload.copyOfRange(offset.toInt(), payload.size), fs.openInput(source, offset).use { it.readBytes() })
                fs.openOutput(source, offset, truncate = false).use { it.write("OFFSET".encodeToByteArray()) }
                val changed = fs.openInput(source).use { it.readBytes() }
                assertArrayEquals(payload.copyOfRange(0, offset.toInt()) + "OFFSET".encodeToByteArray(), changed)
            }

            fs.rename(source, renamed, replace = false)
            assertNotNull(fs.stat(renamed))
            assertTrue(fs.stat(source) == null)
        } finally {
            runCatching { fs.delete(source, false) }
            runCatching { fs.delete(renamed, false) }
            runCatching { fs.delete(dir, true) }
        }
    }

    private fun openPassword(factory: RemoteFileSystemFactory, connection: NetworkConnection): RemoteFileSystem {
        val secrets = RemoteSessionSecrets(password = requiredEnv("STEP5_PROTOCOL_PASS").toCharArray())
        return try {
            factory.open(connection, secrets, settings)
        } finally {
            secrets.clear()
        }
    }

    private fun requireCertificationCi() {
        assumeTrue(
            "Disposable protocol interoperability runs in Step 5 certification CI",
            System.getenv("STEP5_PROTOCOL_INTEROP_REQUIRED") == "1",
        )
    }

    private fun requiredEnv(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is required when STEP5_PROTOCOL_INTEROP_REQUIRED=1")
}
