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
                runHistory = history,
            )
        )

        val restored = AutomationRepository(filesDir).automations.value.single()

        assertEquals(MAX_AUTOMATION_HISTORY, restored.runHistory.size)
        assertEquals("run 3", restored.runHistory.first().summary)
        assertEquals(AutomationRunOrigin.Widget, restored.runHistory.last().origin)
        assertTrue(restored.runHistory.flatMap { it.generatedFiles }.none { it.startsWith('/') || it.contains(":\\") })
    }
}
