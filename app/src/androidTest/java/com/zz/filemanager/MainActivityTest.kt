package com.zz.filemanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun homeRendersWithoutPhysicalDeviceDependency() {
        composeRule.onNodeWithText("ZZ File Manager").assertIsDisplayed()
    }
}
