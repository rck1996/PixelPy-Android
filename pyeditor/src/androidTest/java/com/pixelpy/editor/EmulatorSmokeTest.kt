package com.pixelpy.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityAndPythonRuntimeWorkOnEmulator() {
        composeRule.onNodeWithText("PIXELPY").assertIsDisplayed()

        val applicationContext = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        assertTrue(applicationContext is PixelPyApp)
        assertTrue(Python.isStarted())
        val python = Python.getInstance()
        val runner = python.getModule("runner")
        val workDir = File(applicationContext.cacheDir, "emulator-smoke")
        val result = runner.callAttr(
            "execute",
            "print(\"PixelPy emulator OK\")",
            "",
            workDir.absolutePath,
            null,
            30,
            false,
            "emulator_smoke.py",
        )

        assertTrue(result.callAttr("get", "ok").toBoolean())
        assertTrue(
            result.callAttr("get", "output").toString()
                .contains("PixelPy emulator OK")
        )
    }
}
