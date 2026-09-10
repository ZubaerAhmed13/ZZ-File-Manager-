package com.zz.filemanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.zz.filemanager.core.preferences.PreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class MainActivityTest {
    private val resetLaunchStateRule = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            runBlocking { PreferencesRepository(context).clearLastLocation() }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(resetLaunchStateRule)
        .around(composeRule)

    @Test fun homeRendersWithoutPhysicalDeviceDependency() {
        composeRule.onNodeWithText("ZZ File Manager").assertIsDisplayed()
    }

    @Test fun step3HomeDestinationsAndPrimaryControlsAreReachable() {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText("Search files and folders")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Search files and folders").performClick()
        composeRule.onNodeWithText("Any size").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Favorites").performScrollTo().performClick()
        composeRule.onNodeWithText("No favorites yet.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Recent").performScrollTo().performClick()
        composeRule.onNodeWithText("Recent files").assertIsDisplayed()
        composeRule.onNodeWithText("Activity history").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Recycle Bin").performScrollTo().performClick()
        composeRule.onNodeWithText("Recycle Bin").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
    }
}
