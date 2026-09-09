package com.zz.filemanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun homeRendersWithoutPhysicalDeviceDependency() {
        composeRule.onNodeWithText("ZZ File Manager").assertIsDisplayed()
    }

    @Test fun step3HomeDestinationsAndPrimaryControlsAreReachable() {
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
