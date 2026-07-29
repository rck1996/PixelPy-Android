package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRepositoryHistoryTest {
    @Test
    fun historyIsPersistedAndLimitedWithoutAbsolutePaths() {
        val filesDir = Files.createTempDirectory("pixelpy-history").toFile()
        val repository = AutomationRepository(filesDir)
        val history = (1..12).map { index ->
            AutomationRunRecord(
                startedAtMillis = index * 1_000L,
                finishedAtMillis = index * 1_000L + 250,
                durationMillis = 250,
                origin = AutomationRunOrigin.Widget,
                status = AutomationRunStatus.Success,
                summary = "run $index",
                generatedFiles = listOf("results/$index.txt"),
                resultPublished = true,
            )
        }
        repository.upsert(
            ScriptAutomation(
                name = "Reporte",
                projectPath = "Demo",
                scriptPath = "main.py",
                scheduleType = AutomationScheduleType.Daily,
                parameters = mapOf("CIUDAD" to "Santiago", "LIMITE" to "25"),
                parameterDefinitions = listOf(
                    AutomationParameter("TOKEN", AutomationParameterType.Secret, "abc"),
                    AutomationParameter("LIMITE", AutomationParameterType.Number, "25"),
                ),
                runHistory = history,
            )
        )

        val restored = AutomationRepository(filesDir).automations.value.single()

        assertEquals(MAX_AUTOMATION_HISTORY, restored.runHistory.size)
        assertEquals("run 3", restored.runHistory.first().summary)
        assertEquals(AutomationRunOrigin.Widget, restored.runHistory.last().origin)
        assertEquals("Santiago", restored.parameters["CIUDAD"])
        assertEquals(AutomationParameterType.Secret, restored.parameterDefinitions.first().type)
        assertEquals("25", restored.resolvedParameters()["LIMITE"])
        assertTrue(restored.runHistory.flatMap { it.generatedFiles }.none { it.startsWith('/') || it.contains(":\\") })
    }
}
