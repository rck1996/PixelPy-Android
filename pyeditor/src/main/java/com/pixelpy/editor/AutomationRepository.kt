package com.pixelpy.editor

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal class AutomationRepository(filesDir: File) {
    private val lock = Any()
    private val storeFile = File(filesDir, "automations/automations-v1.json")
    private val _automations = MutableStateFlow(loadFromDisk())
    val automations: StateFlow<List<ScriptAutomation>> = _automations.asStateFlow()

    fun get(id: String): ScriptAutomation? = synchronized(lock) {
        _automations.value.firstOrNull { it.id == id }
    }

    fun upsert(automation: ScriptAutomation): ScriptAutomation = synchronized(lock) {
        val normalized = automation.copy(
            name = automation.name.trim(),
            projectPath = automation.projectPath.trim().replace('\\', '/'),
            scriptPath = automation.scriptPath.trim().replace('\\', '/'),
            highlightedResultPath = automation.highlightedResultPath
                ?.trim()
                ?.replace('\\', '/')
                ?.takeIf { it.isNotBlank() },
            timeoutSeconds = automation.timeoutSeconds.coerceIn(5, MAX_AUTOMATION_TIMEOUT_SECONDS),
            summary = automation.summary.limitedAutomationSummary(),
        )
        val next = _automations.value.toMutableList()
        val index = next.indexOfFirst { it.id == normalized.id }
        if (index >= 0) next[index] = normalized else next += normalized
        persistLocked(next.sortedBy { it.name.lowercase() })
        normalized
    }

    fun update(id: String, transform: (ScriptAutomation) -> ScriptAutomation): ScriptAutomation? =
        synchronized(lock) {
            val current = _automations.value.firstOrNull { it.id == id } ?: return@synchronized null
            upsertLocked(transform(current))
        }

    fun delete(id: String): Boolean = synchronized(lock) {
        val next = _automations.value.filterNot { it.id == id }
        if (next.size == _automations.value.size) return@synchronized false
        persistLocked(next)
        true
    }

    private fun upsertLocked(automation: ScriptAutomation): ScriptAutomation {
        val next = _automations.value.toMutableList()
        val index = next.indexOfFirst { it.id == automation.id }
        if (index >= 0) next[index] = automation else next += automation
        persistLocked(next.sortedBy { it.name.lowercase() })
        return automation
    }

    private fun persistLocked(items: List<ScriptAutomation>) {
        val root = JSONObject()
            .put("version", AUTOMATION_STORE_VERSION)
            .put("automations", JSONArray().apply { items.forEach { put(it.toJson()) } })
        writeUtf8Atomically(storeFile, root.toString())
        _automations.value = items
    }

    private fun loadFromDisk(): List<ScriptAutomation> = runCatching {
        if (!storeFile.isFile) return@runCatching emptyList()
        val root = JSONObject(storeFile.readText(Charsets.UTF_8))
        if (root.optInt("version", -1) != AUTOMATION_STORE_VERSION) return@runCatching emptyList()
        val array = root.optJSONArray("automations") ?: return@runCatching emptyList()
        buildList {
            repeat(array.length()) { index ->
                runCatching { array.getJSONObject(index).toAutomation() }.getOrNull()?.let(::add)
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())
}

private fun ScriptAutomation.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("projectPath", projectPath)
    .put("scriptPath", scriptPath)
    .put("scheduleType", scheduleType.name)
    .putNullable("onceAtMillis", onceAtMillis)
    .put("hour", hour)
    .put("minute", minute)
    .put("weeklyDays", JSONArray(weeklyDays.sorted()))
    .put("requiresNetwork", requiresNetwork)
    .put("requiresCharging", requiresCharging)
    .put("requiresBatteryNotLow", requiresBatteryNotLow)
    .put("timeoutSeconds", timeoutSeconds)
    .put("enabled", enabled)
    .putNullable("highlightedResultPath", highlightedResultPath)
    .put("lastStatus", lastStatus.name)
    .putNullable("lastRunAtMillis", lastRunAtMillis)
    .putNullable("nextRunAtMillis", nextRunAtMillis)
    .put("summary", summary)
    .putNullable("publishedArtifactPath", publishedArtifactPath)
    .putNullable("publishedAtMillis", publishedAtMillis)
    .putNullable("publishedSizeBytes", publishedSizeBytes)
    .putNullable("publishedMimeType", publishedMimeType)

private fun JSONObject.toAutomation(): ScriptAutomation {
    val days = optJSONArray("weeklyDays") ?: JSONArray()
    return ScriptAutomation(
        id = getString("id"),
        name = getString("name"),
        projectPath = getString("projectPath"),
        scriptPath = getString("scriptPath"),
        scheduleType = enumValueOf(optString("scheduleType", AutomationScheduleType.Once.name)),
        onceAtMillis = nullableLong("onceAtMillis"),
        hour = optInt("hour", 8),
        minute = optInt("minute", 0),
        weeklyDays = buildSet { repeat(days.length()) { add(days.getInt(it)) } },
        requiresNetwork = optBoolean("requiresNetwork", false),
        requiresCharging = optBoolean("requiresCharging", false),
        requiresBatteryNotLow = optBoolean("requiresBatteryNotLow", true),
        timeoutSeconds = optInt("timeoutSeconds", MAX_AUTOMATION_TIMEOUT_SECONDS),
        enabled = optBoolean("enabled", true),
        highlightedResultPath = nullableString("highlightedResultPath"),
        lastStatus = runCatching {
            enumValueOf<AutomationRunStatus>(optString("lastStatus", AutomationRunStatus.Pending.name))
        }.getOrDefault(AutomationRunStatus.Pending),
        lastRunAtMillis = nullableLong("lastRunAtMillis"),
        nextRunAtMillis = nullableLong("nextRunAtMillis"),
        summary = optString("summary", "").limitedAutomationSummary(),
        publishedArtifactPath = nullableString("publishedArtifactPath"),
        publishedAtMillis = nullableLong("publishedAtMillis"),
        publishedSizeBytes = nullableLong("publishedSizeBytes"),
        publishedMimeType = nullableString("publishedMimeType"),
    )
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
