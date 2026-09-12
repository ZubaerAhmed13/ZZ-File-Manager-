from pathlib import Path

path = Path("app/src/androidTest/java/com/zz/filemanager/Step4CertificationInstrumentationTest.kt")
text = path.read_text()
old = '''        launchFile(file, FileEntryType.IMAGE, "image/png").use {
            composeRule.waitUntil(10_000) {
                runCatching { composeRule.onNodeWithText("16 × 12", substring = true).fetchSemanticsNode() }.isSuccess
            }
            composeRule.onNodeWithContentDescription("fixture.png").assertIsDisplayed()
            composeRule.onNodeWithText("Previous").assertIsDisplayed()
            composeRule.onNodeWithText("Next").assertIsDisplayed()
        }
'''
new = '''        launchFile(file, FileEntryType.IMAGE, "image/png").use { scenario ->
            composeRule.waitUntil(10_000) {
                runCatching { composeRule.onNodeWithText("16 × 12", substring = true).fetchSemanticsNode() }.isSuccess
            }
            // Emulator launcher/system work can briefly steal foreground focus even after the
            // image has decoded. Restore the owned scenario, then require the actual image node
            // to become visibly rendered; this keeps the test visual rather than weakening it to
            // semantics-node existence.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            composeRule.waitUntil(10_000) {
                runCatching {
                    composeRule.onNodeWithContentDescription("fixture.png").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            composeRule.onNodeWithContentDescription("fixture.png").assertIsDisplayed()
            composeRule.onNodeWithText("Previous").assertIsDisplayed()
            composeRule.onNodeWithText("Next").assertIsDisplayed()
        }
'''
count = text.count(old)
assert count == 1, f"expected one image test block, found {count}"
path.write_text(text.replace(old, new))
print("patched image viewer instrumentation test")
