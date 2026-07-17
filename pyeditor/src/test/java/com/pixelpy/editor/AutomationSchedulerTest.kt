package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSchedulerTest {
    @Test
    fun editingScheduleReplacesExistingWork() {
        val fixture = fixture()
        val saved = fixture.scheduler.save(fixture.automation)
        fixture.scheduler.save(saved.copy(hour = 9))
        assertEquals(2, fixture.gateway.replacements.size)
        assertEquals(saved.id, fixture.gateway.replacements.last().first.id)
        assertEquals(9, fixture.gateway.replacements.last().first.hour)
    }

    @Test
    fun pauseAndDeleteCancelAllWork() {
        val fixture = fixture()
        val saved = fixture.scheduler.save(fixture.automation)
        val paused = fixture.scheduler.setEnabled(saved.id, false)
        assertFalse(requireNotNull(paused).enabled)
        assertNull(paused.nextRunAtMillis)
        assertTrue(saved.id in fixture.gateway.cancelled)
        assertTrue(fixture.scheduler.delete(saved.id))
        assertTrue(fixture.repository.get(saved.id) == null)
    }

    @Test
    fun rapidRunNowRequestsAreDeduplicated() {
        var now = 10_000L
        val fixture = fixture { now }
        val saved = fixture.scheduler.save(fixture.automation)
        assertTrue(fixture.scheduler.runNow(saved.id))
        assertFalse(fixture.scheduler.runNow(saved.id))
        now += AutomationScheduler.IMMEDIATE_DEBOUNCE_MILLIS
        assertTrue(fixture.scheduler.runNow(saved.id))
        assertEquals(2, fixture.gateway.immediate.size)
    }

    private fun fixture(clock: () -> Long = { System.currentTimeMillis() }): Fixture {
        val filesDir = Files.createTempDirectory("pixelpy-scheduler").toFile()
        val project = filesDir.resolve("projects/Demo").apply { mkdirs() }
        project.resolve("main.py").writeText("print('ok')")
        val repository = AutomationRepository(filesDir)
        val gateway = FakeGateway()
        val scheduler = AutomationScheduler(repository, gateway, filesDir.resolve("projects"), clock)
        return Fixture(repository, gateway, scheduler, ScriptAutomation(
            name = "Demo",
            projectPath = "Demo",
            scriptPath = "main.py",
            scheduleType = AutomationScheduleType.Daily,
        ))
    }

    private data class Fixture(
        val repository: AutomationRepository,
        val gateway: FakeGateway,
        val scheduler: AutomationScheduler,
        val automation: ScriptAutomation,
    )

    private class FakeGateway : AutomationWorkGateway {
        val replacements = mutableListOf<Pair<ScriptAutomation, Long>>()
        val immediate = mutableListOf<ScriptAutomation>()
        val cancelled = mutableListOf<String>()
        override fun replaceScheduled(automation: ScriptAutomation, initialDelayMillis: Long) {
            replacements += automation to initialDelayMillis
        }
        override fun appendScheduled(automation: ScriptAutomation, initialDelayMillis: Long) {
            replacements += automation to initialDelayMillis
        }
        override fun enqueueImmediate(automation: ScriptAutomation) { immediate += automation }
        override fun cancel(automationId: String) { cancelled += automationId }
    }
}
