package com.pixelpy.editor

import android.app.PendingIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationBridgeAndWidgetTest {
    @Test
    fun inputFailsImmediatelyWithClearMessage() {
        val error = assertThrows(IllegalStateException::class.java) {
            AutomationInputBridge { false }.request("Nombre: ")
        }
        assertEquals(AUTOMATION_INPUT_ERROR, error.message)
    }

    @Test
    fun automationUsesTheSameRuntimeExclusionGate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            PythonRuntimeCoordinator.runExclusive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var secondEntered = false
        val second = async {
            PythonRuntimeCoordinator.runExclusive { secondEntered = true }
        }
        delay(50)
        assertFalse(secondEntered)
        release.complete(Unit)
        first.await(); second.await()
        assertTrue(secondEntered)
    }

    @Test
    fun widgetStatesCoverSuccessErrorAndMissingAutomation() {
        val success = automationWidgetState(sample(AutomationRunStatus.Success).copy(publishedArtifactPath = "published/id/report.xlsx"))
        assertEquals(AutomationWidgetStatus.Success, success.status)
        assertEquals(R.drawable.automation_widget_status_success, success.status.backgroundRes)
        assertTrue(success.canOpen)
        val error = automationWidgetState(sample(AutomationRunStatus.Error))
        assertEquals(AutomationWidgetStatus.Error, error.status)
        assertEquals(R.drawable.automation_widget_status_error, error.status.backgroundRes)
        assertFalse(error.canOpen)
        val missing = automationWidgetState(null)
        assertEquals(AutomationWidgetStatus.Unavailable, missing.status)
        assertEquals(R.drawable.automation_widget_status_error, missing.status.backgroundRes)
        assertEquals("Automatización no disponible", missing.status.label)
    }

    @Test
    fun automationWithoutHighlightedResultKeepsRunAvailableAndOpenDisabled() {
        val automation = sample(AutomationRunStatus.Pending)
        val state = automationWidgetState(automation)

        assertFalse(state.canOpen)
        assertTrue(state.canRun)
        assertEquals(AUTOMATION_WITHOUT_RESULT_NOTICE, automationWidgetConfigurationNotice(automation))
    }

    @Test
    fun widgetPendingIntentsAreImmutable() {
        assertTrue(widgetPendingIntentFlags() and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(widgetPendingIntentFlags() and PendingIntent.FLAG_MUTABLE == 0)
    }

    private fun sample(status: AutomationRunStatus) = ScriptAutomation(
        name = "Reporte",
        projectPath = "Demo",
        scriptPath = "main.py",
        scheduleType = AutomationScheduleType.Daily,
        lastStatus = status,
    )
}
