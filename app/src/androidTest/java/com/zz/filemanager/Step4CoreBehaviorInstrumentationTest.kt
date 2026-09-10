package com.zz.filemanager

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.filemanager.app.AppContainer
import com.zz.filemanager.core.apk.ApkBackupMode
import com.zz.filemanager.core.archive.ArchiveCreateRequest
import com.zz.filemanager.core.archive.ArchiveExtractRequest
import com.zz.filemanager.core.archive.ArchiveFailure
import com.zz.filemanager.core.archive.ArchiveFormat
import com.zz.filemanager.core.archive.ArchiveSource
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.step4.SafeWriteResult
import com.zz.filemanager.core.storage.LocalStorageProvider
import com.zz.filemanager.core.text.LineEnding
import com.zz.filemanager.core.text.TextEncoding
import com.zz.filemanager.core.text.TextFailure
import com.zz.filemanager.core.text.TextOpenMode
import com.zz.filemanager.core.text.TextSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step4CoreBehaviorInstrumentationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val container: AppContainer by lazy {
        (context.applicationContext as ZZFileManagerApplication).container
    }

    private val root: File
        get() = File(context.cacheDir, "step4-core-cert").apply { mkdirs() }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun textEncodingsLineEndingsSearchAndLargeModeAreHandledWithoutWholeHugeBuffer() = runBlocking {
        val utf8 = File(root, "utf8.txt").apply { writeText("alpha alpha\nalpha", StandardCharsets.UTF_8) }
        val utf8Bom = File(root, "utf8-bom.txt").apply {
            writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "bom\r\nline".toByteArray(StandardCharsets.UTF_8))
        }
        val utf16Le = File(root, "utf16le.txt").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "left\r\nright".toByteArray(StandardCharsets.UTF_16LE))
        }
        val utf16Be = File(root, "utf16be.txt").apply {
            writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "big\nendian".toByteArray(StandardCharsets.UTF_16BE))
        }
        val parent = localLocation(root)

        val normal = container.textFileEngine.open(TextSource(entry(utf8, FileEntryType.TEXT, "text/plain"), parent))
        assertEquals(TextEncoding.UTF8, normal.encoding)
        assertEquals(LineEnding.LF, normal.lineEnding)
        assertEquals(3, container.textFileEngine.search(normal, "alpha").size)

        val bom = container.textFileEngine.open(TextSource(entry(utf8Bom, FileEntryType.TEXT, "text/plain"), parent))
        assertEquals(TextEncoding.UTF8_BOM, bom.encoding)
        assertEquals(LineEnding.CRLF, bom.lineEnding)
        assertEquals("bom\r\nline", bom.text)

        val le = container.textFileEngine.open(TextSource(entry(utf16Le, FileEntryType.TEXT, "text/plain"), parent))
        assertEquals(TextEncoding.UTF16_LE, le.encoding)
        assertEquals(LineEnding.CRLF, le.lineEnding)
        assertEquals("left\r\nright", le.text)

        val be = container.textFileEngine.open(TextSource(entry(utf16Be, FileEntryType.TEXT, "text/plain"), parent))
        assertEquals(TextEncoding.UTF16_BE, be.encoding)
        assertEquals(LineEnding.LF, be.lineEnding)
        assertEquals("big\nendian", be.text)

        val logicalTenGiB = entry(utf8, FileEntryType.TEXT, "text/plain").copy(sizeBytes = 10L * 1024L * 1024L * 1024L)
        val large = container.textFileEngine.open(TextSource(logicalTenGiB, parent))
        assertEquals(TextOpenMode.LARGE_READ_ONLY, large.mode)
        assertTrue(large.text.length < 1_000_000)
        assertEquals(1L, large.windowStartLine)
    }

    @Test
    fun textExternalModificationIsDetectedBeforeOverwrite() = runBlocking {
        val file = File(root, "conflict.txt").apply { writeText("original") }
        val parent = localLocation(root)
        val source = TextSource(entry(file, FileEntryType.TEXT, "text/plain"), parent)
        val opened = container.textFileEngine.open(source)

        file.writeText("externally changed and longer")
        var conflict = false
        try {
            container.textFileEngine.save(opened, "my edit")
        } catch (_: TextFailure.ExternalModification) {
            conflict = true
        }
        assertTrue(conflict)
        assertEquals("externally changed and longer", file.readText())
    }

    @Test
    fun failedStagedReplaceLeavesOriginalUntouchedAndCleansUncommittedStage() = runBlocking {
        val original = File(root, "safe.txt").apply { writeText("keep-me") }
        val parent = localLocation(root)
        val failure = object : InputStream() {
            private val bytes = "new-data-that-never-finishes".toByteArray()
            private var position = 0
            override fun read(): Int {
                if (position >= 5) throw IOException("simulated source failure")
                return bytes[position++].toInt() and 0xff
            }
        }

        var failed = false
        try {
            container.safeOutputWriter.write(
                parent = parent,
                requestedName = original.name,
                mimeType = "text/plain",
                input = failure,
                expectedBytes = 25L,
                collisionPolicy = CollisionPolicy.REPLACE,
            )
        } catch (_: Throwable) {
            failed = true
        }
        assertTrue(failed)
        assertEquals("keep-me", original.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".zzstage-") })
    }

    @Test
    fun encryptedZipCreateListExtractWrongPasswordAndReplaceCollisionAreReal() = runBlocking {
        val sourceFile = File(root, "report.txt").apply { writeText("restored-data") }
        val parent = localLocation(root)
        val source = ArchiveSource(entry(sourceFile, FileEntryType.TEXT, "text/plain"), parent)
        val password = "correct horse".toCharArray()

        val archiveEntry = container.archiveManager.create(
            ArchiveCreateRequest(
                sources = listOf(source),
                destination = parent,
                archiveName = "secure.zip",
                format = ArchiveFormat.ZIP,
                password = password,
            ),
        )
        val archiveSource = ArchiveSource(archiveEntry, parent)
        val listing = container.archiveManager.list(archiveSource, password)
        assertEquals(ArchiveFormat.ZIP, listing.format)
        assertTrue(listing.encrypted)
        assertTrue(listing.entries.any { it.path == "report.txt" && it.encrypted })

        val wrongDir = File(root, "wrong").apply { mkdirs() }
        var wrongPassword = false
        try {
            container.archiveManager.extract(
                ArchiveExtractRequest(
                    source = archiveSource,
                    destination = localLocation(wrongDir, root),
                    password = "wrong".toCharArray(),
                ),
            )
        } catch (_: ArchiveFailure.BadPassword) {
            wrongPassword = true
        }
        assertTrue(wrongPassword)
        assertFalse(File(wrongDir, "report.txt").exists())

        val destinationDir = File(root, "extract").apply { mkdirs() }
        File(destinationDir, "report.txt").writeText("old")
        container.archiveManager.extract(
            ArchiveExtractRequest(
                source = archiveSource,
                destination = localLocation(destinationDir, root),
                password = password,
                collisionPolicy = CollisionPolicy.REPLACE,
            ),
        )
        assertEquals("restored-data", File(destinationDir, "report.txt").readText())
    }

    @Test
    fun malformedApkIsRejectedAndOwnApkCertificateMetadataAndStreamingBackupWork() = runBlocking {
        val malformed = File(root, "broken.apk").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        var rejected = false
        try {
            container.apkManager.inspect(entry(malformed, FileEntryType.APK, "application/vnd.android.package-archive"))
        } catch (_: Throwable) {
            rejected = true
        }
        assertTrue(rejected)

        val selfApk = File(context.applicationInfo.sourceDir)
        val metadata = container.apkManager.inspect(
            entry(selfApk, FileEntryType.APK, "application/vnd.android.package-archive", storageId = "self"),
        )
        assertEquals(context.packageName, metadata.packageName)
        assertTrue(metadata.certificates.isNotEmpty())
        assertTrue(metadata.certificates.all { it.sha256.length == 64 })

        val installed = container.apkManager.installedApps().firstOrNull { it.packageName == context.packageName }
        assertNotNull(installed)
        val backupDir = File(root, "apk-backup").apply { mkdirs() }
        val result = container.apkManager.backup(installed!!, localLocation(backupDir, root), ApkBackupMode.BASE_ONLY)
        assertTrue(result.exportedNames.isNotEmpty())
        assertTrue(result.exportedNames.all { it.endsWith(".apk") })
        assertTrue(result.exportedNames.all { File(backupDir, it).isFile && File(backupDir, it).length() > 0L })
    }


    @Test
    fun freshGeneratedWriteUsesHiddenJournaledStageUntilCommit() = runBlocking {
        val parent = localLocation(root)
        val journal = com.zz.filemanager.core.step4.Step4WriteJournal(context)
        journal.list().forEach { journal.remove(it.id) }
        var finalVisibleDuringProducer = true
        var stageVisibleDuringProducer = false
        var stagingJournalVisible = false
        val payload = "fresh-transaction".toByteArray(StandardCharsets.UTF_8)

        val result = container.safeOutputWriter.writeGenerated(
            parent = parent,
            requestedName = "fresh.bin",
            mimeType = "application/octet-stream",
            expectedBytes = payload.size.toLong(),
            collisionPolicy = CollisionPolicy.KEEP_BOTH,
        ) { output ->
            finalVisibleDuringProducer = File(root, "fresh.bin").exists()
            stageVisibleDuringProducer = root.listFiles().orEmpty().any { it.name.startsWith(".zzstage-") }
            stagingJournalVisible = journal.list().any {
                it.finalName == "fresh.bin" && it.phase == com.zz.filemanager.core.step4.Step4WritePhase.STAGING
            }
            output.write(payload)
        }

        assertFalse(finalVisibleDuringProducer)
        assertTrue(stageVisibleDuringProducer)
        assertTrue(stagingJournalVisible)
        assertTrue(result is SafeWriteResult.Written)
        assertEquals("fresh-transaction", File(root, "fresh.bin").readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".zzstage-") })
        assertTrue(journal.list().none { it.finalName == "fresh.bin" })
    }

    @Test
    fun startupReconcileRemovesProcessKilledFreshStageWithoutCreatingFinal() = runBlocking {
        val parent = localLocation(root)
        val journal = com.zz.filemanager.core.step4.Step4WriteJournal(context)
        journal.list().forEach { journal.remove(it.id) }
        val id = "fresh-process-death"
        val staged = File(root, ".zzstage-$id").apply { writeText("partial-visible-only-as-hidden-stage") }
        journal.put(
            com.zz.filemanager.core.step4.Step4WriteTransaction(
                id = id,
                parent = parent,
                finalName = "movie.mkv",
                stagedName = staged.name,
                backupName = null,
                expectedBytes = 20L * 1024L * 1024L * 1024L,
                phase = com.zz.filemanager.core.step4.Step4WritePhase.STAGING,
                replacesExisting = false,
            ),
        )

        val unresolved = container.safeOutputWriter.reconcile()

        assertTrue(unresolved.isEmpty())
        assertFalse(staged.exists())
        assertFalse(File(root, "movie.mkv").exists())
        assertTrue(journal.list().none { it.id == id })
    }

    @Test
    fun recoveryRejectsSameSizedUnrelatedFinalAndPreservesBackup() = runBlocking {
        val parent = localLocation(root)
        val journal = com.zz.filemanager.core.step4.Step4WriteJournal(context)
        journal.list().forEach { journal.remove(it.id) }
        val id = "same-size-proof"
        val final = File(root, "document.bin").apply { writeBytes("BBBB".toByteArray()) }
        val backup = File(root, ".zzbackup-$id").apply { writeBytes("GOOD".toByteArray()) }
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("AAAA".toByteArray())
            .joinToString("") { "%02X".format(it) }

        journal.put(
            com.zz.filemanager.core.step4.Step4WriteTransaction(
                id = id,
                parent = parent,
                finalName = final.name,
                stagedName = ".zzstage-gone",
                backupName = backup.name,
                expectedBytes = 4L,
                phase = com.zz.filemanager.core.step4.Step4WritePhase.COMMITTING,
                replacesExisting = true,
                stagedMutationIdentity = null,
                contentSha256 = expectedDigest,
            ),
        )

        val unresolved = container.safeOutputWriter.reconcile()

        assertTrue(id in unresolved)
        assertTrue(backup.exists())
        assertEquals("GOOD", backup.readText())
        assertEquals("BBBB", final.readText())
        assertTrue(journal.list().any { it.id == id })
        journal.remove(id)
    }

    @Test
    fun generatedOutputFailureNeverExposesRequestedFinalName() = runBlocking {
        val parent = localLocation(root)
        var failed = false
        try {
            container.safeOutputWriter.writeGenerated(
                parent = parent,
                requestedName = "Backup.zip",
                mimeType = "application/zip",
                collisionPolicy = CollisionPolicy.KEEP_BOTH,
            ) { output ->
                output.write("partial archive".toByteArray())
                throw IOException("simulated archive producer failure")
            }
        } catch (_: IOException) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(File(root, "Backup.zip").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".zzstage-") })
        assertTrue(com.zz.filemanager.core.step4.Step4WriteJournal(context).list().none { it.finalName == "Backup.zip" })
    }

    @Test
    fun interruptedCompleteSplitBackupLeavesNoVisiblePartialBackupFolder() = runBlocking {
        val destinationDir = File(root, "split-interrupted").apply { mkdirs() }
        val base = File(root, "source-base.apk").apply { writeBytes(ByteArray(4096) { 7 }) }
        val missingSplit = File(root, "missing-split.apk")
        val app = com.zz.filemanager.core.apk.InstalledAppInfo(
            label = "Interrupted Set",
            packageName = "test.interrupted",
            versionName = "1",
            versionCode = 42L,
            firstInstallTime = null,
            lastUpdateTime = null,
            baseApkPath = base.absolutePath,
            splitApkPaths = listOf(missingSplit.absolutePath),
            baseApkSizeBytes = base.length(),
            isSystemApp = false,
            icon = null,
        )

        var failed = false
        try {
            container.apkManager.backup(
                app,
                localLocation(destinationDir, root),
                ApkBackupMode.COMPLETE_SPLITS,
            )
        } catch (_: Throwable) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(File(destinationDir, "Interrupted Set-42-apks").exists())
        assertTrue(destinationDir.listFiles().orEmpty().none { it.name.startsWith(".zzapkbackup-") })
        assertTrue(com.zz.filemanager.core.step4.Step4WriteJournal(context).list().none {
            it.finalName == "Interrupted Set-42-apks"
        })
    }

    @Test
    fun completeSplitBackupFinalizesWholeSetWithVerifiedManifest() = runBlocking {
        val destinationDir = File(root, "split-complete").apply { mkdirs() }
        val base = File(root, "complete-base.apk").apply { writeBytes(ByteArray(4096) { 1 }) }
        val split = File(root, "split_config.en.apk").apply { writeBytes(ByteArray(2048) { 2 }) }
        val app = com.zz.filemanager.core.apk.InstalledAppInfo(
            label = "CompleteSet",
            packageName = "test.complete",
            versionName = "7",
            versionCode = 7L,
            firstInstallTime = null,
            lastUpdateTime = null,
            baseApkPath = base.absolutePath,
            splitApkPaths = listOf(split.absolutePath),
            baseApkSizeBytes = base.length(),
            isSystemApp = false,
            icon = null,
        )

        val result = container.apkManager.backup(
            app,
            localLocation(destinationDir, root),
            ApkBackupMode.COMPLETE_SPLITS,
        )

        val folder = File(destinationDir, "CompleteSet-7-apks")
        assertTrue(folder.isDirectory)
        assertEquals(ApkBackupMode.COMPLETE_SPLITS, result.mode)
        assertTrue(result.reinstallableSetPreserved)
        assertTrue(File(folder, "base.apk").isFile)
        assertTrue(File(folder, "split_config.en.apk").isFile)
        val manifest = File(folder, "zz-apk-backup-manifest.json")
        assertTrue(manifest.isFile)
        assertTrue(manifest.readText().contains("\"complete\":true"))
        assertTrue(destinationDir.listFiles().orEmpty().none { it.name.startsWith(".zzapkbackup-") })
    }

    @Test
    fun malformedMediaMetadataFailsGracefullyWithoutThrowing() = runBlocking {
        val video = File(root, "bad.mp4").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
        val audio = File(root, "bad.mp3").apply { writeBytes(byteArrayOf(5, 4, 3, 2)) }
        val videoMeta = container.mediaInspector.video(entry(video, FileEntryType.VIDEO, "video/mp4"))
        val audioMeta = container.mediaInspector.audio(entry(audio, FileEntryType.AUDIO, "audio/mpeg"))
        assertEquals("video/mp4", videoMeta.videoMime)
        assertEquals("audio/mpeg", audioMeta.mimeType)
    }

    private fun localLocation(directory: File, rootOverride: File = directory): BrowserLocation {
        val rootCanonical = rootOverride.canonicalFile
        val directoryCanonical = directory.canonicalFile
        return BrowserLocation(
            providerId = LocalStorageProvider.ID,
            id = "local:${directoryCanonical.path}",
            displayName = directoryCanonical.name.ifBlank { "Test" },
            reference = directoryCanonical.path,
            rootReference = rootCanonical.path,
            storageId = "step4-core-test",
            readable = true,
            writable = true,
        )
    }

    private fun entry(
        file: File,
        type: FileEntryType,
        mime: String,
        storageId: String = "step4-core-test",
    ): FileEntry {
        val canonical = file.canonicalFile
        return FileEntry(
            id = "local:${canonical.path}",
            reference = FileReference(LocalStorageProvider.ID, "local:${canonical.path}", path = canonical.path),
            name = canonical.name,
            extension = canonical.extension.takeIf { it.isNotBlank() },
            mimeType = mime,
            type = type,
            sizeBytes = canonical.length(),
            modifiedAtMillis = canonical.lastModified().takeIf { it > 0L },
            createdAtMillis = null,
            isHidden = false,
            isReadable = canonical.canRead(),
            isWritable = canonical.canWrite(),
            childCount = null,
            storageId = storageId,
            thumbnailKey = null,
        )
    }
}
