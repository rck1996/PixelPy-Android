package com.pixelpy.editor

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.chaquo.python.Python
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {
    @Test
    fun activityAndPythonRuntimeWorkOnEmulator() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.text("PIXELPY")), 5_000))

            val applicationContext = instrumentation.targetContext.applicationContext
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
}
