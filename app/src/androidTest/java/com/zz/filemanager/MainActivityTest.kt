package com.zz.filemanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.filemanager.core.preferences.PreferencesRepository
import com.zz.filemanager.core.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Assert.assertTrue

class MainActivityTest {
    private val resetLaunchStateRule = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            runBlocking { PreferencesRepository(context).clearLastLocation(); PreferencesRepository(context).setTheme(ThemeMode.LIGHT) }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(resetLaunchStateRule)
        .around(composeRule)

    @Test fun homeRendersWithoutPhysicalDeviceDependency() {
        waitForHome()
    }

    @Test fun step3HomeDestinationsAndPrimaryControlsAreReachable() {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("Main Storage")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithText("Any size").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithTag("home-quick-Favorites").performScrollTo().performClick()
        composeRule.onNodeWithText("No favorites yet.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithTag("home-quick-New Files").performScrollTo().performClick()
        composeRule.onNodeWithText("Recent files").assertIsDisplayed()
        composeRule.onNodeWithText("Activity history").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithTag("home-quick-Recycle Bin").performScrollTo().performClick()
        composeRule.onNodeWithText("Recycle Bin").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
    }

    @Test fun step6DenseHomeLightGoldenStateIsNonBlank() {
        waitForHome(); composeRule.onNodeWithTag("home-quick-Storage Analysis").assertIsDisplayed(); composeRule.onNodeWithTag("home-quick-Images").assertIsDisplayed(); assertRenderedSurfaceIsNonBlank()
    }

    @Test fun step6NavigationDrawerGoldenStateIsNonBlank() {
        composeRule.onNodeWithContentDescription("Navigation drawer").performClick()
        composeRule.onNodeWithText("STORAGE").assertIsDisplayed(); composeRule.onNodeWithText("CATEGORIES").assertIsDisplayed(); composeRule.onNodeWithText("ACCOUNTS / REMOTE").assertIsDisplayed(); assertRenderedSurfaceIsNonBlank()
    }

    @Test fun step6DenseHomeDarkGoldenStateIsNonBlank() {
        composeRule.onNodeWithContentDescription("Settings").performClick(); composeRule.onNodeWithText("Dark").performClick(); composeRule.onNodeWithContentDescription("Back").performClick(); waitForHome(); assertRenderedSurfaceIsNonBlank()
    }

    @Test fun step6LargeFontKeepsPrimaryActionsReachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.executeShellCommand("settings put system font_scale 1.3").close(); composeRule.activityRule.scenario.recreate(); waitForHome()
            composeRule.onNodeWithContentDescription("Navigation drawer").assertIsDisplayed(); composeRule.onNodeWithContentDescription("Search").assertIsDisplayed(); composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        } finally { automation.executeShellCommand("settings put system font_scale 1.0").close() }
    }

    private fun waitForHome() = composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Main Storage").fetchSemanticsNodes().isNotEmpty() }
    private fun assertRenderedSurfaceIsNonBlank() {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap(); val colors = linkedSetOf<Long>()
        for (x in 0 until pixels.width step (pixels.width / 12).coerceAtLeast(1)) for (y in 0 until pixels.height step (pixels.height / 20).coerceAtLeast(1)) colors += pixels[x, y].value.toLong()
        assertTrue("Rendered surface should contain multiple visual regions", colors.size > 3)
    }
}
