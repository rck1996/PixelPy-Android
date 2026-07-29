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
import java.io.File

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
        val error = automationWidgetState(sample(AutomationRunStatus.Error).copy(summary = "No se generó reporte.xlsx"))
        assertEquals(AutomationWidgetStatus.Error, error.status)
        assertEquals(R.drawable.automation_widget_status_error, error.status.backgroundRes)
        assertFalse(error.canOpen)
        assertEquals("ERROR · TOCA PARA VER", error.sectionLabel)
        assertEquals("No se generó reporte.xlsx", error.artifactName)
        val missing = automationWidgetState(null)
        assertEquals(AutomationWidgetStatus.Unavailable, missing.status)
        assertEquals(R.drawable.automation_widget_status_error, missing.status.backgroundRes)
        assertEquals("Automatización no disponible", missing.status.label)
    }


    @Test
    fun copiedDiagnosticIncludesOriginDurationAndGeneratedFiles() {
        val automation = sample(AutomationRunStatus.Error).copy(
            summary = "Resultado no actualizado",
            runHistory = listOf(
                AutomationRunRecord(
                    startedAtMillis = 1_000,
                    finishedAtMillis = 2_500,
                    durationMillis = 1_500,
                    origin = AutomationRunOrigin.Widget,
                    status = AutomationRunStatus.Error,
                    summary = "Resultado no actualizado",
                    generatedFiles = listOf("logs/run.txt"),
                )
            ),
        )

        val diagnostic = automationDiagnosticText(automation)

        assertTrue(diagnostic.contains("Desde el widget"))
        assertTrue(diagnostic.contains("1.5 s"))
        assertTrue(diagnostic.contains("logs/run.txt"))
        assertTrue(diagnostic.contains("Resultado no actualizado"))
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

    @Test
    fun publishedPreviewChoosesSafeInternalFormats() {
        assertEquals(PublishedPreviewKind.Json, previewKind(File("report.json")))
        assertEquals(PublishedPreviewKind.Csv, previewKind(File("report.csv")))
        assertEquals(PublishedPreviewKind.Image, previewKind(File("chart.png")))
        assertEquals(PublishedPreviewKind.External, previewKind(File("report.xlsx")))
        assertEquals("1.5 MB", humanFileSize(1_572_864))
    }

    @Test
    fun widgetShowsPublishedFileSizeAndSafeCopy() {
        val state = automationWidgetState(
            sample(AutomationRunStatus.Success).copy(
                publishedArtifactPath = "published/id/report.csv",
                publishedSizeBytes = 2_048,
            )
        )

        assertEquals("report.csv", state.artifactName)
        assertEquals("2 KB · copia segura", state.artifactMeta)
        assertTrue(state.canOpen)
    }

    private fun sample(status: AutomationRunStatus) = ScriptAutomation(
        name = "Reporte",
        projectPath = "Demo",
        scriptPath = "main.py",
        scheduleType = AutomationScheduleType.Daily,
        lastStatus = status,
    )
}
