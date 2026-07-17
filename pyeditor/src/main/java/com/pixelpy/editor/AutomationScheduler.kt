package com.pixelpy.editor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal interface AutomationWorkGateway {
    fun replaceScheduled(automation: ScriptAutomation, initialDelayMillis: Long)
    fun appendScheduled(automation: ScriptAutomation, initialDelayMillis: Long)
    fun enqueueImmediate(automation: ScriptAutomation)
    fun cancel(automationId: String)
}

internal class AutomationScheduler(
    private val repository: AutomationRepository,
    private val gateway: AutomationWorkGateway,
    private val projectsRoot: java.io.File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onChanged: (String) -> Unit = {},
) {
    private val immediateRequests = ConcurrentHashMap<String, Long>()

    fun save(automation: ScriptAutomation): ScriptAutomation {
        try {
            AutomationPathValidator.validate(projectsRoot, automation, requireExisting = true)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "${error.message} (proyecto='${automation.projectPath}', script='${automation.scriptPath}')",
                error,
            )
        }
        val next = AutomationScheduleCalculator.nextExecution(
            automation,
            ZonedDateTime.now().withNano(0),
        )?.toEpochMilli()
        val saved = repository.upsert(automation.copy(nextRunAtMillis = next))
        if (saved.enabled && next != null) {
            gateway.replaceScheduled(saved, max(0, next - nowMillis()))
        } else {
            gateway.cancel(saved.id)
        }
        onChanged(saved.id)
        return saved
    }

    fun setEnabled(id: String, enabled: Boolean): ScriptAutomation? {
        val current = repository.get(id) ?: return null
        if (!enabled) {
            gateway.cancel(id)
            val paused = repository.upsert(current.copy(enabled = false, nextRunAtMillis = null))
            onChanged(id)
            return paused
        }
        return save(current.copy(enabled = true, lastStatus = AutomationRunStatus.Pending))
    }

    fun runNow(id: String): Boolean {
        val current = repository.get(id)?.takeIf { it.enabled } ?: return false
        val now = nowMillis()
        val previous = immediateRequests.put(id, now)
        if (previous != null && now - previous < IMMEDIATE_DEBOUNCE_MILLIS) return false
        repository.upsert(
            current.copy(
                lastStatus = AutomationRunStatus.Pending,
                summary = "Ejecución solicitada; esperando las restricciones de Android.",
            )
        )
        gateway.enqueueImmediate(current)
        onChanged(id)
        return true
    }

    fun scheduleAfterRun(id: String, appendToCurrentChain: Boolean) {
        val current = repository.get(id)?.takeIf { it.enabled } ?: return
        if (current.scheduleType == AutomationScheduleType.Once) {
            repository.upsert(current.copy(nextRunAtMillis = null))
            onChanged(id)
            return
        }
        val next = AutomationScheduleCalculator.nextExecution(
            current,
            ZonedDateTime.now().plusSeconds(1),
        )?.toEpochMilli() ?: return
        val updated = repository.upsert(current.copy(nextRunAtMillis = next))
        val delay = max(0, next - nowMillis())
        if (appendToCurrentChain) gateway.appendScheduled(updated, delay)
        else gateway.replaceScheduled(updated, delay)
        onChanged(id)
    }

    fun delete(id: String): Boolean {
        gateway.cancel(id)
        immediateRequests.remove(id)
        val removed = repository.delete(id)
        onChanged(id)
        return removed
    }

    companion object {
        internal const val IMMEDIATE_DEBOUNCE_MILLIS = 1_500L
    }
}

internal class WorkManagerAutomationGateway(context: Context) : AutomationWorkGateway {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun replaceScheduled(automation: ScriptAutomation, initialDelayMillis: Long) {
        workManager.enqueueUniqueWork(
            scheduledName(automation.id),
            ExistingWorkPolicy.REPLACE,
            automation.request(initialDelayMillis, manual = false),
        )
    }

    override fun appendScheduled(automation: ScriptAutomation, initialDelayMillis: Long) {
        workManager.enqueueUniqueWork(
            scheduledName(automation.id),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            automation.request(initialDelayMillis, manual = false),
        )
    }

    override fun enqueueImmediate(automation: ScriptAutomation) {
        val request = automation.request(0, manual = true)
        workManager.enqueueUniqueWork(immediateName(automation.id), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(automationId: String) {
        workManager.cancelUniqueWork(scheduledName(automationId))
        workManager.cancelUniqueWork(immediateName(automationId))
        workManager.cancelAllWorkByTag(tag(automationId))
    }

    private fun ScriptAutomation.toWorkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
        .setRequiresCharging(requiresCharging)
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .build()

    private fun ScriptAutomation.request(initialDelayMillis: Long, manual: Boolean) =
        OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(
                workDataOf(
                    AutomationWorker.KEY_AUTOMATION_ID to id,
                    AutomationWorker.KEY_MANUAL_RUN to manual,
                )
            )
            .setConstraints(toWorkConstraints())
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(id))
            .build()

    companion object {
        internal fun scheduledName(id: String) = "pixelpy-automation-$id"
        internal fun immediateName(id: String) = "pixelpy-automation-now-$id"
        internal fun tag(id: String) = "pixelpy-automation-tag-$id"
    }
}
