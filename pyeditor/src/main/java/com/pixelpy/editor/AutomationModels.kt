package com.pixelpy.editor

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

internal const val AUTOMATION_STORE_VERSION = 1
internal const val MAX_AUTOMATION_TIMEOUT_SECONDS = 120
internal const val MAX_AUTOMATION_SUMMARY_LENGTH = 4_000

internal enum class AutomationScheduleType { Once, Daily, Weekly }
internal enum class AutomationRunStatus { Pending, Running, Success, Error }

internal data class ScriptAutomation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val projectPath: String,
    val scriptPath: String,
    val scheduleType: AutomationScheduleType,
    val onceAtMillis: Long? = null,
    val hour: Int = 8,
    val minute: Int = 0,
    val weeklyDays: Set<Int> = emptySet(),
    val requiresNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val timeoutSeconds: Int = MAX_AUTOMATION_TIMEOUT_SECONDS,
    val enabled: Boolean = true,
    val highlightedResultPath: String? = null,
    val lastStatus: AutomationRunStatus = AutomationRunStatus.Pending,
    val lastRunAtMillis: Long? = null,
    val nextRunAtMillis: Long? = null,
    val summary: String = "",
    val publishedArtifactPath: String? = null,
    val publishedAtMillis: Long? = null,
    val publishedSizeBytes: Long? = null,
    val publishedMimeType: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(projectPath.isNotBlank())
        require(scriptPath.endsWith(".py", ignoreCase = true))
        require(hour in 0..23)
        require(minute in 0..59)
        require(timeoutSeconds in 5..MAX_AUTOMATION_TIMEOUT_SECONDS)
        require(weeklyDays.all { it in 1..7 })
    }
}

internal object AutomationScheduleCalculator {
    fun nextExecution(
        automation: ScriptAutomation,
        now: ZonedDateTime = ZonedDateTime.now(),
        zoneId: ZoneId = now.zone,
    ): Instant? {
        if (!automation.enabled) return null
        val localNow = now.withZoneSameInstant(zoneId)
        return when (automation.scheduleType) {
            AutomationScheduleType.Once -> automation.onceAtMillis?.let { millis ->
                val requested = Instant.ofEpochMilli(millis)
                if (requested.isAfter(localNow.toInstant())) requested else localNow.toInstant()
            }
            AutomationScheduleType.Daily -> {
                var candidate = localNow.toLocalDate()
                    .atTime(automation.hour, automation.minute)
                    .atZone(zoneId)
                if (!candidate.isAfter(localNow)) candidate = candidate.plusDays(1)
                candidate.toInstant()
            }
            AutomationScheduleType.Weekly -> {
                val days = automation.weeklyDays.ifEmpty { setOf(localNow.dayOfWeek.value) }
                days.map { DayOfWeek.of(it) }
                    .map { day ->
                        var candidate = localNow
                            .with(TemporalAdjusters.nextOrSame(day))
                            .toLocalDate()
                            .atTime(automation.hour, automation.minute)
                            .atZone(zoneId)
                        if (!candidate.isAfter(localNow)) candidate = candidate.plusWeeks(1)
                        candidate
                    }
                    .minOrNull()
                    ?.toInstant()
            }
        }
    }
}

internal fun String.limitedAutomationSummary(): String =
    trim().takeLast(MAX_AUTOMATION_SUMMARY_LENGTH)
