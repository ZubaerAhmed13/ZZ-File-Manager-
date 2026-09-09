package com.zz.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.filemanager.core.analyzer.StorageAnalyzer
import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.step4.Step4OpenCodec
import com.zz.filemanager.core.step4.Step4WriteJournal
import com.zz.filemanager.core.step4.Step4WritePhase
import com.zz.filemanager.core.step4.Step4WriteTransaction
import com.zz.filemanager.core.storage.LocalStorageProvider
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Step4CertificationInstrumentationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testRoot: File
        get() = File(context.cacheDir, "step4-certification").apply { mkdirs() }

    @After
    fun cleanup() {
        testRoot.deleteRecursively()
    }

    @Test
    fun textViewerEditorOpensAndTransactionallySaves() {
        val file = File(testRoot, "notes.txt").apply { writeText("hello\nworld") }
        launchFile(file, FileEntryType.TEXT, "text/plain").use {
            composeRule.onNodeWithText("Edit").assertIsDisplayed().performClick()
            composeRule.onNode(hasText("hello\nworld") and hasSetTextAction())
                .performTextReplacement("changed\ncontent")
            composeRule.onNodeWithContentDescription("Save").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                file.exists() && runCatching { file.readText() == "changed\ncontent" }.getOrDefault(false)
            }
            assertEquals("changed\ncontent", file.readText())
        }
    }

    @Test
    fun archiveViewerNavigatesGeneratedZipHierarchy() {
        val file = File(testRoot, "fixture.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("folder/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("folder/a.txt")); zip.write("archive-data".toByteArray()); zip.closeEntry()
        }
        launchFile(file, FileEntryType.ARCHIVE, "application/zip").use {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasText("folder")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("folder").performClick()
            composeRule.onNodeWithText("a.txt").assertExists()
            composeRule.onNodeWithText("Extract all").assertExists()
        }
    }

    @Test
    fun imageViewerRendersGeneratedImage() {
        val file = File(testRoot, "fixture.png")
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        } finally {
            bitmap.recycle()
        }
        launchFile(file, FileEntryType.IMAGE, "image/png").use {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(hasText("16 × 12", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("fixture.png").assertExists()
            composeRule.onNodeWithText("Previous").assertExists()
            composeRule.onNodeWithText("Next").assertExists()
        }
    }

    @Test
    fun videoPlayerScreenInitializesAndMalformedMediaDoesNotCrashActivity() {
        val file = File(testRoot, "broken.mp4").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        launchFile(file, FileEntryType.VIDEO, "video/mp4").use {
            composeRule.onNodeWithText("broken.mp4").assertExists()
            composeRule.onNodeWithText("Fullscreen").assertExists()
        }
    }

    @Test
    fun audioPlayerScreenInitializes() {
        val file = File(testRoot, "audio.mp3").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        launchFile(file, FileEntryType.AUDIO, "audio/mpeg").use {
            composeRule.onNodeWithText("audio.mp3").assertExists()
        }
    }

    @Test
    fun apkDetailPassivelyInspectsTheTestedApplicationApk() {
        val apk = File(context.applicationInfo.sourceDir)
        launchFile(apk, FileEntryType.APK, "application/vnd.android.package-archive", rootOverride = apk.parentFile).use {
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodes(hasText(context.packageName)).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.packageName).assertExists()
            composeRule.onNodeWithText("Install with Android Package Installer").assertExists()
        }
    }

    @Test
    fun appsToolNavigatesWithoutBroadPackageVisibilityPermission() {
        launchTool(Step4ToolsActivity.MODE_APPS).use {
            composeRule.onNodeWithText("Installed Apps").assertIsDisplayed()
            composeRule.onNodeWithText("Search apps").assertExists()
        }
    }

    @Test
    fun analyzerToolScreenInitializes() {
        launchTool(Step4ToolsActivity.MODE_ANALYZER).use {
            composeRule.onNodeWithText("Analyze Storage").assertIsDisplayed()
            composeRule.onNodeWithText("Scan").assertExists()
            composeRule.onNodeWithText("Find duplicates").assertExists()
        }
    }

    @Test
    fun archiveCreationToolScreenInitializes() {
        launchTool(Step4ToolsActivity.MODE_ARCHIVE_CREATE).use {
            composeRule.onNodeWithText("Create Archive").assertIsDisplayed()
            composeRule.onNodeWithText("Choose source folder").assertExists()
            composeRule.onNodeWithText("Choose destination").assertExists()
        }
    }

    @Test
    fun analyzerCancellationIsRealOnApi35Instrumentation() = runBlocking {
        val provider = SlowLogicalProvider()
        val analyzer = StorageAnalyzer(Registry(provider))
        val job = launch(Dispatchers.Default) { analyzer.scan(provider.root) }
        while (provider.calls == 0) yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun step4WriteJournalSurvivesNewJournalInstance() {
        val journal = Step4WriteJournal(context)
        val parent = BrowserLocation("test", "root", "Root", "root", "root", "test", true, true)
        val id = UUID.randomUUID().toString()
        journal.put(Step4WriteTransaction(id, parent, "final.txt", ".zzstage-$id", ".zzbackup-$id", 123L, Step4WritePhase.COMMITTING))
        try {
            val restored = Step4WriteJournal(context).list().single { it.id == id }
            assertEquals(Step4WritePhase.COMMITTING, restored.phase)
            assertEquals(123L, restored.expectedBytes)
            assertEquals("final.txt", restored.finalName)
        } finally {
            journal.remove(id)
        }
    }

    private fun launchTool(mode: String): ActivityScenario<Step4ToolsActivity> =
        ActivityScenario.launch(Step4ToolsActivity.intent(context, mode))

    private fun launchFile(
        file: File,
        type: FileEntryType,
        mime: String,
        rootOverride: File? = null,
    ): ActivityScenario<Step4FileActivity> {
        val root = (rootOverride ?: testRoot).canonicalFile
        val canonical = file.canonicalFile
        val parent = BrowserLocation(
            providerId = LocalStorageProvider.ID,
            id = "local:${root.path}",
            displayName = root.name.ifBlank { "Test root" },
            reference = root.path,
            rootReference = root.path,
            storageId = "step4-test",
            readable = true,
            writable = root.canWrite(),
        )
        val entry = FileEntry(
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
            storageId = "step4-test",
            thumbnailKey = null,
        )
        val request = Step4OpenCodec.request(entry, parent)
        val intent = android.content.Intent(context, Step4FileActivity::class.java).setData(Uri.parse(request.uri))
        return ActivityScenario.launch(intent)
    }

    private class Registry(private val provider: StorageProvider) : StorageProviderRegistry {
        override fun providerFor(providerId: String): StorageProvider = provider
        override fun writableProviderFor(providerId: String): WritableStorageProvider? = null
    }

    private class SlowLogicalProvider : StorageProvider {
        override val id: String = "slow"
        val root = BrowserLocation(id, "root", "Root", "root", "root", "slow", true, false)
        @Volatile var calls: Int = 0

        override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
            calls++
            repeat(100) { yield() }
            return (0 until 1_000).map { index ->
                FileEntry(
                    id = "f$index",
                    reference = FileReference(id, "f$index"),
                    name = "f$index.bin",
                    extension = "bin",
                    mimeType = "application/octet-stream",
                    type = FileEntryType.GENERIC,
                    sizeBytes = 1L,
                    modifiedAtMillis = null,
                    createdAtMillis = null,
                    isHidden = false,
                    isReadable = true,
                    isWritable = false,
                    childCount = null,
                    storageId = "slow",
                    thumbnailKey = null,
                )
            }
        }

        override suspend fun getMetadata(item: FileReference): FileEntry? = null
        override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(byteArrayOf(1))
        override suspend fun exists(item: FileReference): Boolean = true
        override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
        override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
    }
}
