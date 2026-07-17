package com.pixelpy.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun cleanUp() {
        val app = composeRule.activity.application as PixelPyApp
        app.automationRepository.automations.value
            .filter { it.name == "AVD AUTOMATION" }
            .forEach { app.automationScheduler.delete(it.id) }
    }

    @Test
    fun createsAutomationFromProjectsScreen() {
        composeRule.onNodeWithTag("nav-projects").performClick()
        composeRule.onNodeWithTag("open-automations").performClick()
        composeRule.onNodeWithTag("automation-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("automation-create").performClick()
        composeRule.onNodeWithTag("automation-name").performTextReplacement("AVD AUTOMATION")
        composeRule.onNodeWithTag("automation-save").performClick()
        composeRule.waitUntil(10_000) {
            (composeRule.activity.application as PixelPyApp)
                .automationRepository.automations.value.any { it.name == "AVD AUTOMATION" }
        }
        composeRule.onNodeWithText("AVD AUTOMATION").assertIsDisplayed()
    }
}
