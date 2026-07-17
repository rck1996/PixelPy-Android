package com.pixelpy.editor

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val EXTRA_AUTOMATION_ID = "com.pixelpy.editor.extra.AUTOMATION_ID"

internal data class AutomationWidgetState(
    val name: String,
    val status: String,
    val updated: String,
    val artifactName: String,
    val canOpen: Boolean,
)

internal fun automationWidgetState(automation: ScriptAutomation?): AutomationWidgetState {
    if (automation == null) {
        return AutomationWidgetState(
            name = "PIXELPY",
            status = "Automatización no disponible",
            updated = "Abre PixelPy para configurarla",
            artifactName = "Sin resultado",
            canOpen = false,
        )
    }
    val status = when {
        !automation.enabled -> "PAUSADA"
        automation.lastStatus == AutomationRunStatus.Pending -> "PENDIENTE"
        automation.lastStatus == AutomationRunStatus.Running -> "EJECUTANDO"
        automation.lastStatus == AutomationRunStatus.Success -> "CORRECTO"
        else -> "ERROR"
    }
    val updated = automation.publishedAtMillis?.let {
        "Actualizado ${formatWidgetTime(it)}"
    } ?: automation.lastRunAtMillis?.let {
        "Ejecutado ${formatWidgetTime(it)}"
    } ?: "Todavía no se ejecutó"
    return AutomationWidgetState(
        name = automation.name,
        status = status,
        updated = updated,
        artifactName = automation.publishedArtifactPath?.substringAfterLast('/') ?: "Sin resultado publicado",
        canOpen = automation.publishedArtifactPath != null,
    )
}

private fun formatWidgetTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd-MM HH:mm"))

internal object AutomationWidgetPreferences {
    private const val PREFS = "automation_widgets_v1"
    private fun key(widgetId: Int) = "widget_$widgetId"

    fun bind(context: Context, widgetId: Int, automationId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(widgetId), automationId).apply()
    }

    fun automationId(context: Context, widgetId: Int): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(widgetId), null)

    fun remove(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(widgetId)).apply()
    }
}

internal fun widgetPendingIntentFlags(): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

internal fun widgetStatusBackground(status: String): Int = when (status) {
    "CORRECTO" -> R.drawable.automation_widget_status_success
    "ERROR", "AutomatizaciÃ³n no disponible" -> R.drawable.automation_widget_status_error
    "EJECUTANDO" -> R.drawable.automation_widget_status_running
    "PAUSADA" -> R.drawable.automation_widget_status_paused
    else -> R.drawable.automation_widget_status_pending
}

class AutomationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { AutomationWidgetPreferences.remove(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val automationId = intent.getStringExtra(EXTRA_AUTOMATION_ID) ?: return
        when (intent.action) {
            ACTION_RUN -> {
                val app = context.applicationContext as? PixelPyApp ?: return
                if (!app.automationScheduler.runNow(automationId)) {
                    Toast.makeText(context, "La ejecución ya está solicitada o la automatización está pausada", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_OPEN -> openPublishedArtifact(context, automationId)
        }
    }

    companion object {
        internal const val ACTION_RUN = "com.pixelpy.editor.action.RUN_AUTOMATION"
        internal const val ACTION_OPEN = "com.pixelpy.editor.action.OPEN_AUTOMATION_RESULT"

        internal fun updateForAutomation(context: Context, automationId: String) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, AutomationWidgetProvider::class.java))
                .filter { AutomationWidgetPreferences.automationId(context, it) == automationId }
                .forEach { updateWidget(context, manager, it) }
        }

        internal fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, AutomationWidgetProvider::class.java))
                .forEach { updateWidget(context, manager, it) }
        }

        internal fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val app = context.applicationContext as? PixelPyApp
            val id = AutomationWidgetPreferences.automationId(context, widgetId)
            val automation = id?.let { app?.automationRepository?.get(it) }
            val state = automationWidgetState(automation)
            val views = RemoteViews(context.packageName, R.layout.automation_widget).apply {
                setTextViewText(R.id.widget_name, state.name)
                setTextViewText(R.id.widget_status, state.status)
                setTextViewText(R.id.widget_updated, state.updated)
                setTextViewText(R.id.widget_artifact, state.artifactName)
                setInt(R.id.widget_status, "setBackgroundResource", widgetStatusBackground(state.status))
                setTextColor(R.id.widget_open, if (state.canOpen) 0xFF191919.toInt() else 0xFF9A9489.toInt())
                if (id != null) {
                    setOnClickPendingIntent(R.id.widget_open, actionIntent(context, widgetId, id, ACTION_OPEN, 1))
                    setOnClickPendingIntent(R.id.widget_run, actionIntent(context, widgetId, id, ACTION_RUN, 2))
                }
                setBoolean(R.id.widget_open, "setEnabled", state.canOpen)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun actionIntent(
            context: Context,
            widgetId: Int,
            automationId: String,
            action: String,
            actionCode: Int,
        ): PendingIntent {
            val intent = Intent(context, AutomationWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_AUTOMATION_ID, automationId)
            }
            return PendingIntent.getBroadcast(context, widgetId * 10 + actionCode, intent, widgetPendingIntentFlags())
        }
    }
}

internal fun openPublishedArtifact(context: Context, automationId: String) {
    val app = context.applicationContext as? PixelPyApp ?: return
    val automation = app.automationRepository.get(automationId)
    val relative = automation?.publishedArtifactPath
    val file = relative?.let {
        runCatching { AutomationPathValidator.resolvePublished(context.filesDir, it) }.getOrNull()
    }
    if (automation == null || file == null || !file.isFile) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_AUTOMATION_ID, automationId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        return
    }

    val uri = FileProvider.getUriForFile(context, "com.pixelpy.editor.files", file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, automation.publishedMimeType ?: mimeTypeForFile(file))
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(view, "Abrir ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure {
        Toast.makeText(context, "No hay una app compatible para abrir ${file.name}", Toast.LENGTH_LONG).show()
    }
}

class AutomationWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as PixelPyApp
        setContent {
            WidgetAutomationPicker(app.automationRepository) { automationId ->
                AutomationWidgetPreferences.bind(this, widgetId, automationId)
                AutomationWidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), widgetId)
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                )
                finish()
            }
        }
    }
}

@Composable
private fun WidgetAutomationPicker(
    repository: AutomationRepository,
    onSelected: (String) -> Unit,
) {
    val automations by repository.automations.collectAsState()
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(Color(0xFFFFF8E7)).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("CONFIGURAR WIDGET", fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text("Elige la automatización cuyo último resultado quieres tener en tu pantalla principal.")
            if (automations.isEmpty()) {
                Text("Aún no hay automatizaciones. Créala primero desde Proyectos → Automatizaciones.")
            }
            automations.forEach { automation ->
                Surface(
                    modifier = Modifier.fillMaxWidth().border(3.dp, Color(0xFF191919)).clickable { onSelected(automation.id) },
                    color = Color.White,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(automation.name, fontWeight = FontWeight.Black)
                        Text("${automation.projectPath}/${automation.scriptPath}", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
