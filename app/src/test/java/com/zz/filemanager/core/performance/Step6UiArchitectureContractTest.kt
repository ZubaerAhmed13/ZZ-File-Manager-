package com.zz.filemanager.core.performance

import com.zz.filemanager.core.model.ThumbnailMode
import com.zz.filemanager.core.model.ViewMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Step6UiArchitectureContractTest {
    @Test fun professionalViewModesAndThumbnailPolicyAreComplete() {
        assertEquals(setOf("LIST", "COMPACT_LIST", "GRID", "THUMBNAIL_GRID", "DETAILED_LIST"), ViewMode.entries.map { it.name }.toSet())
        assertEquals(setOf("SHOW", "ICON_ONLY"), ThumbnailMode.entries.map { it.name }.toSet())
    }
    @Test fun composeRenderingDoesNotStartStorageScansOrNetworkCalls() {
        val ui = projectFile("src/main/java/com/zz/filemanager/feature").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(ui.contains("GlobalScope"))
        assertFalse(Regex("@Composable[\\s\\S]{0,400}storageAnalyzer\\.scan\\(").containsMatchIn(ui))
        assertFalse(Regex("@Composable[\\s\\S]{0,400}findDuplicates\\(").containsMatchIn(ui))
    }
    @Test fun baselineProfileCoversPrimaryJourneys() {
        val profile = projectFile("src/main/baseline-prof.txt").readText()
        listOf("MainActivity", "HomeScreen", "BrowserScreen", "ThumbnailRepository").forEach { assertTrue(profile.contains(it)) }
    }
    private fun projectFile(relative: String): File = listOf(File(relative), File("app/$relative")).firstOrNull { it.exists() } ?: error("Missing project path: $relative")
}
