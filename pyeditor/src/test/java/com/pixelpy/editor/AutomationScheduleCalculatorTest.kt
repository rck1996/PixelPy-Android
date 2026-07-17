package com.pixelpy.editor

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutomationScheduleCalculatorTest {
    private val zone = ZoneId.of("America/Santiago")

    @Test
    fun futureOneTimeKeepsRequestedInstant() {
        val now = ZonedDateTime.of(2026, 7, 17, 7, 0, 0, 0, zone)
        val requested = now.plusHours(2).toInstant()
        val automation = sample(AutomationScheduleType.Once).copy(onceAtMillis = requested.toEpochMilli())
        assertEquals(requested, AutomationScheduleCalculator.nextExecution(automation, now, zone))
    }

    @Test
    fun pastOneTimeBecomesImmediatelyEligible() {
        val now = ZonedDateTime.of(2026, 7, 17, 9, 0, 0, 0, zone)
        val automation = sample(AutomationScheduleType.Once).copy(onceAtMillis = now.minusDays(1).toInstant().toEpochMilli())
        assertEquals(now.toInstant(), AutomationScheduleCalculator.nextExecution(automation, now, zone))
    }

    @Test
    fun dailyMovesToTomorrowAfterLocalTimePassed() {
        val now = ZonedDateTime.of(2026, 7, 17, 9, 0, 0, 0, zone)
        val next = AutomationScheduleCalculator.nextExecution(sample(AutomationScheduleType.Daily), now, zone)
        assertEquals(LocalDateTime.of(2026, 7, 18, 8, 0), next!!.atZone(zone).toLocalDateTime())
    }

    @Test
    fun weeklySelectsNextConfiguredDay() {
        val friday = ZonedDateTime.of(2026, 7, 17, 9, 0, 0, 0, zone)
        val automation = sample(AutomationScheduleType.Weekly).copy(weeklyDays = setOf(1, 3))
        val next = AutomationScheduleCalculator.nextExecution(automation, friday, zone)
        assertEquals(1, next!!.atZone(zone).dayOfWeek.value)
        assertEquals(8, next.atZone(zone).hour)
    }

    @Test
    fun dailyUsesTheNewLocalZone() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val nowInChile = ZonedDateTime.of(2026, 7, 17, 22, 0, 0, 0, zone)
        val next = requireNotNull(AutomationScheduleCalculator.nextExecution(sample(AutomationScheduleType.Daily), nowInChile, tokyo))
        assertEquals(8, next.atZone(tokyo).hour)
    }

    private fun sample(type: AutomationScheduleType) = ScriptAutomation(
        name = "Reporte",
        projectPath = "Proyecto",
        scriptPath = "main.py",
        scheduleType = type,
        hour = 8,
        minute = 0,
    )
}
