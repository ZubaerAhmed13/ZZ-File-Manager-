package com.zz.filemanager.core.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class Step6StaticSecurityAuditTest {
    @Test fun releaseManifestHasOnlyTheLauncherExportedAndDisablesCredentialBackup() {
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val android = "http://schemas.android.com/apk/res/android"
        val application = document.getElementsByTagName("application").item(0)
        assertEquals("false", application.attributes.getNamedItemNS(android, "allowBackup").nodeValue)
        val exported = mutableListOf<Pair<String, String>>()
        listOf("activity", "service", "receiver", "provider").forEach { tag ->
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) { val node = nodes.item(i); if (node.attributes.getNamedItemNS(android, "exported")?.nodeValue == "true") exported += tag to node.attributes.getNamedItemNS(android, "name").nodeValue }
        }
        assertEquals(listOf("activity" to ".MainActivity"), exported)
    }
    @Test fun releaseDoesNotOptIntoGlobalCleartextOrWriteUriSharing() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val app = projectFile("src/main/java/com/zz/filemanager/ZZFileManagerApp.kt").readText()
        val browser = projectFile("src/main/java/com/zz/filemanager/feature/browser/BrowserScreen.kt").readText()
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\"")); assertFalse(app.contains("FLAG_GRANT_WRITE_URI_PERMISSION")); assertFalse(browser.contains("FLAG_GRANT_WRITE_URI_PERMISSION"))
    }
    @Test fun fileProviderIsPrivateAndEveryGrantIsTemporary() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("androidx.core.content.FileProvider")); assertTrue(manifest.contains("android:exported=\"false\"")); assertTrue(manifest.contains("android:grantUriPermissions=\"true\""))
    }
    private fun projectFile(relative: String): File = listOf(File(relative), File("app/$relative")).firstOrNull(File::isFile) ?: error("Missing project file: $relative")
}
