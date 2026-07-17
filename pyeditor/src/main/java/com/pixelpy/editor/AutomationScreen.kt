package com.pixelpy.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AutomationInk = Color(0xFF191919)
private val AutomationPaper = Color(0xFFFFF8E7)
private val AutomationYellow = Color(0xFFFFD43B)
private val AutomationPink = Color(0xFFFF5DA2)
private val AutomationBlue = Color(0xFF79D8FF)
private val AutomationGreen = Color(0xFF7EE787)

@Composable
internal fun AutomationScreen(
    projectsRoot: File,
    repository: AutomationRepository,
    scheduler: AutomationScheduler,
    currentProject: File,
    currentScript: File,
    flushCurrent: suspend () -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val automations by repository.automations.collectAsState()
    var editor by remember { mutableStateOf<ScriptAutomation?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ScriptAutomation?>(null) }

    fun createDraft(): ScriptAutomation {
        val nextHour = ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        val canonicalRoot = projectsRoot.canonicalFile
        val safeProject = currentProject.canonicalFile.takeIf { project ->
            project != canonicalRoot &&
                project.isDirectory &&
                project.toPath().startsWith(canonicalRoot.toPath())
        } ?: canonicalRoot.listFiles { file -> file.isDirectory }?.sortedBy { it.name }?.firstOrNull()
        requireNotNull(safeProject) { "Crea un proyecto antes de añadir una automatización" }
        val safeScript = currentScript.canonicalFile.takeIf { script ->
            script.isFile && script.extension.equals("py", true) &&
                script.toPath().startsWith(safeProject.canonicalFile.toPath())
        } ?: safeProject.walkTopDown().firstOrNull { it.isFile && it.extension.equals("py", true) }
        requireNotNull(safeScript) { "El proyecto necesita al menos un script .py" }
        return ScriptAutomation(
            id = UUID.randomUUID().toString(),
            name = safeScript.nameWithoutExtension,
            projectPath = safeProject.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath,
            scriptPath = safeScript.canonicalFile.relativeTo(safeProject.canonicalFile).invariantSeparatorsPath,
            scheduleType = AutomationScheduleType.Daily,
            onceAtMillis = nextHour.toInstant().toEpochMilli(),
            hour = nextHour.hour,
            minute = nextHour.minute,
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("automation-screen").background(AutomationPaper).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("← PROYECTOS", fontWeight = FontWeight.Black) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { editor = createDraft(); editorVisible = true },
                    modifier = Modifier.testTag("automation-create"),
                ) { Text("＋ NUEVA", fontWeight = FontWeight.Black) }
            }
            Spacer(Modifier.height(12.dp))
            Text("AUTOMATIZACIONES", fontWeight = FontWeight.Black, fontSize = 28.sp)
            Text("Ejecuta scripts aunque PixelPy no esté abierto y conserva un resultado listo para compartir.")
            Spacer(Modifier.height(8.dp))
            Surface(color = AutomationYellow, modifier = Modifier.fillMaxWidth().border(2.dp, AutomationInk)) {
                Text(
                    "Los horarios son aproximados. Android puede retrasarlos por batería, Doze o restricciones del sistema.",
                    Modifier.padding(12.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
        if (automations.isEmpty()) {
            item {
                AutomationPanel(Color.White) {
                    Text("AÚN NO HAY AUTOMATIZACIONES", fontWeight = FontWeight.Black)
                    Text("Crea una para ejecutar el script actual ahora, diariamente o cada semana.")
                }
            }
        }
        items(automations, key = { it.id }) { automation ->
            AutomationCard(
                automation = automation,
                onEdit = { editor = automation; editorVisible = true },
                onToggle = { enabled -> scope.launch(Dispatchers.IO) { scheduler.setEnabled(automation.id, enabled) } },
                onRun = { scheduler.runNow(automation.id) },
                onDelete = { deleteCandidate = automation },
                onOpen = { openPublishedArtifact(context, automation.id) },
            )
        }
    }

    if (editorVisible && editor != null) {
        AutomationEditorDialog(
            initial = requireNotNull(editor),
            projectsRoot = projectsRoot,
            onDismiss = { editorVisible = false },
            onSave = { candidate ->
                scope.launch {
                    val selectedCurrent = runCatching {
                        val paths = AutomationPathValidator.validate(projectsRoot, candidate, requireExisting = true)
                        paths.project.canonicalFile == currentProject.canonicalFile &&
                            paths.script.canonicalFile == currentScript.canonicalFile
                    }.getOrDefault(false)
                    if (selectedCurrent && !flushCurrent()) return@launch
                    val result = withContext(Dispatchers.IO) { runCatching { scheduler.save(candidate) } }
                    withContext(Dispatchers.Main.immediate) {
                        result
                            .onSuccess { editorVisible = false }
                            .onFailure {
                                android.util.Log.e("PixelPyAutomation", "No se pudo guardar la automatización", it)
                                android.widget.Toast.makeText(context, it.message ?: "No se pudo guardar", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                }
            },
        )
    }

    deleteCandidate?.let { automation ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("¿Eliminar automatización?", fontWeight = FontWeight.Black) },
            text = { Text("Se cancelará su trabajo programado. El último archivo publicado no se abrirá desde el widget.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { scheduler.delete(automation.id) }
                    deleteCandidate = null
                }) { Text("ELIMINAR") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("CANCELAR") } },
        )
    }
}

@Composable
private fun AutomationCard(
    automation: ScriptAutomation,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val statusColor = when (automation.lastStatus) {
        AutomationRunStatus.Success -> AutomationGreen
        AutomationRunStatus.Error -> AutomationPink
        AutomationRunStatus.Running -> AutomationBlue
        AutomationRunStatus.Pending -> AutomationYellow
    }
    AutomationPanel(Color.White, Modifier.testTag("automation-${automation.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(statusColor).border(2.dp, AutomationInk).padding(horizontal = 9.dp, vertical = 6.dp)) {
                Text(automation.lastStatus.displayName(), fontWeight = FontWeight.Black, fontSize = 10.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(automation.name, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("${automation.projectPath}/${automation.scriptPath}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Switch(checked = automation.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        Text(scheduleDescription(automation), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(nextDescription(automation), fontSize = 12.sp)
        if (automation.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(automation.summary, maxLines = 3, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onRun, enabled = automation.enabled, modifier = Modifier.testTag("automation-run-${automation.id}")) { Text("EJECUTAR AHORA") }
            OutlinedButton(onClick = onOpen, enabled = automation.publishedArtifactPath != null) { Text("ABRIR RESULTADO") }
            OutlinedButton(onClick = onEdit) { Text("EDITAR") }
            TextButton(onClick = onDelete) { Text("ELIMINAR") }
        }
    }
}

@Composable
private fun AutomationPanel(
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth().border(3.dp, AutomationInk), color = color) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun AutomationEditorDialog(
    initial: ScriptAutomation,
    projectsRoot: File,
    onDismiss: () -> Unit,
    onSave: (ScriptAutomation) -> Unit,
) {
    val projects = remember { projectsRoot.listFiles { it.isDirectory }?.sortedBy { it.name } ?: emptyList() }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var projectPath by remember(initial.id) { mutableStateOf(initial.projectPath) }
    var scriptPath by remember(initial.id) { mutableStateOf(initial.scriptPath) }
    var type by remember(initial.id) { mutableStateOf(initial.scheduleType) }
    val initialOnce = remember(initial.id) {
        Instant.ofEpochMilli(initial.onceAtMillis ?: System.currentTimeMillis() + 3_600_000)
            .atZone(ZoneId.systemDefault())
    }
    var dateText by remember(initial.id) { mutableStateOf(initialOnce.toLocalDate().toString()) }
    var timeText by remember(initial.id) { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var weeklyDays by remember(initial.id) { mutableStateOf(initial.weeklyDays.ifEmpty { setOf(1) }) }
    var network by remember(initial.id) { mutableStateOf(initial.requiresNetwork) }
    var charging by remember(initial.id) { mutableStateOf(initial.requiresCharging) }
    var battery by remember(initial.id) { mutableStateOf(initial.requiresBatteryNotLow) }
    var timeout by remember(initial.id) { mutableStateOf(initial.timeoutSeconds.toString()) }
    var resultPath by remember(initial.id) { mutableStateOf(initial.highlightedResultPath.orEmpty()) }
    var error by remember { mutableStateOf("") }
    val selectedProject = projects.firstOrNull {
        runCatching { it.canonicalFile.relativeTo(projectsRoot.canonicalFile).invariantSeparatorsPath == projectPath }.getOrDefault(false)
    }
    val scripts = selectedProject?.walkTopDown()?.filter { it.isFile && it.extension.equals("py", true) }?.toList().orEmpty()
    val resultCandidates = selectedProject?.walkTopDown()?.filter { file ->
        file.isFile && !file.extension.equals("py", true) && !file.invariantSeparatorsPath.contains("/.trash/") && !file.invariantSeparatorsPath.contains("/.versions/")
    }?.take(8)?.toList().orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (repositoryStyleIsNew(initial)) "NUEVA AUTOMATIZACIÓN" else "EDITAR AUTOMATIZACIÓN", fontWeight = FontWeight.Black) },
        text = {
            Column(Modifier.heightIn(max = 610.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("automation-name"), label = { Text("Nombre") }, singleLine = true)
                Text("PROYECTO", fontWeight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    projects.forEach { project ->
                        val relative = project.canonicalFile.relativeTo(projectsRoot.canonicalFile).invariantSeparatorsPath
                        FilterChip(selected = projectPath == relative, onClick = {
                            projectPath = relative
                            scriptPath = project.walkTopDown().firstOrNull { it.isFile && it.extension == "py" }
                                ?.canonicalFile?.relativeTo(project.canonicalFile)?.invariantSeparatorsPath.orEmpty()
                        }, label = { Text(project.name) })
                    }
                }
                Text("SCRIPT", fontWeight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scripts.forEach { script ->
                        val relative = script.canonicalFile.relativeTo(requireNotNull(selectedProject).canonicalFile).invariantSeparatorsPath
                        FilterChip(selected = scriptPath == relative, onClick = { scriptPath = relative }, label = { Text(relative) })
                    }
                }
                Text("FRECUENCIA", fontWeight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AutomationScheduleType.entries.forEach { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option.displayName()) })
                    }
                }
                if (type == AutomationScheduleType.Once) {
                    OutlinedTextField(dateText, { dateText = it }, Modifier.fillMaxWidth(), label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true)
                }
                OutlinedTextField(timeText, { timeText = it }, Modifier.fillMaxWidth(), label = { Text("Hora aproximada (HH:mm)") }, singleLine = true)
                if (type == AutomationScheduleType.Weekly) {
                    Text("DÍAS", fontWeight = FontWeight.Black)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("L", "M", "X", "J", "V", "S", "D").forEachIndexed { index, label ->
                            val day = index + 1
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(checked = day in weeklyDays, onCheckedChange = { checked ->
                                    weeklyDays = if (checked) weeklyDays + day else weeklyDays - day
                                })
                                Text(label, fontSize = 10.sp)
                            }
                        }
                    }
                }
                HorizontalDivider()
                AutomationSwitch("Red requerida", network) { network = it }
                AutomationSwitch("Solo mientras carga", charging) { charging = it }
                AutomationSwitch("Esperar si la batería está baja", battery) { battery = it }
                OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Timeout (5–120 segundos)") }, singleLine = true)
                OutlinedTextField(resultPath, { resultPath = it }, Modifier.fillMaxWidth(), label = { Text("Resultado destacado opcional") }, supportingText = { Text("Ruta relativa, por ejemplo reporte.xlsx") }, singleLine = true)
                if (resultCandidates.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        resultCandidates.forEach { file ->
                            val relative = file.canonicalFile.relativeTo(requireNotNull(selectedProject).canonicalFile).invariantSeparatorsPath
                            FilterChip(selected = resultPath == relative, onClick = { resultPath = relative }, label = { Text(relative) })
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    require(name.isNotBlank()) { "Escribe un nombre" }
                    require(projectPath.isNotBlank() && scriptPath.isNotBlank()) { "Elige proyecto y script" }
                    val time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("H:mm"))
                    val once = if (type == AutomationScheduleType.Once) {
                        LocalDate.parse(dateText).atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } else initial.onceAtMillis
                    val limit = timeout.toIntOrNull() ?: MAX_AUTOMATION_TIMEOUT_SECONDS
                    require(limit in 5..MAX_AUTOMATION_TIMEOUT_SECONDS) { "El timeout debe estar entre 5 y 120 segundos" }
                    require(type != AutomationScheduleType.Weekly || weeklyDays.isNotEmpty()) { "Selecciona al menos un día" }
                    initial.copy(
                        name = name.trim(),
                        projectPath = projectPath,
                        scriptPath = scriptPath,
                        scheduleType = type,
                        onceAtMillis = once,
                        hour = time.hour,
                        minute = time.minute,
                        weeklyDays = if (type == AutomationScheduleType.Weekly) weeklyDays else emptySet(),
                        requiresNetwork = network,
                        requiresCharging = charging,
                        requiresBatteryNotLow = battery,
                        timeoutSeconds = limit,
                        highlightedResultPath = resultPath.trim().takeIf(String::isNotBlank),
                    )
                }.onSuccess(onSave).onFailure { error = it.message ?: "Datos inválidos" }
            }, modifier = Modifier.testTag("automation-save")) { Text("GUARDAR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } },
    )
}

@Composable
private fun AutomationSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChecked)
    }
}

private fun repositoryStyleIsNew(automation: ScriptAutomation): Boolean =
    automation.lastRunAtMillis == null && automation.nextRunAtMillis == null && automation.summary.isBlank()

private fun AutomationScheduleType.displayName(): String = when (this) {
    AutomationScheduleType.Once -> "UNA VEZ"
    AutomationScheduleType.Daily -> "DIARIA"
    AutomationScheduleType.Weekly -> "SEMANAL"
}

private fun AutomationRunStatus.displayName(): String = when (this) {
    AutomationRunStatus.Pending -> "PENDIENTE"
    AutomationRunStatus.Running -> "EJECUTANDO"
    AutomationRunStatus.Success -> "CORRECTO"
    AutomationRunStatus.Error -> "ERROR"
}

private fun scheduleDescription(automation: ScriptAutomation): String = when (automation.scheduleType) {
    AutomationScheduleType.Once -> "Una vez"
    AutomationScheduleType.Daily -> "Cada día · %02d:%02d".format(automation.hour, automation.minute)
    AutomationScheduleType.Weekly -> "Semanal · ${automation.weeklyDays.sorted().joinToString { dayShort(it) }} · %02d:%02d".format(automation.hour, automation.minute)
}

private fun nextDescription(automation: ScriptAutomation): String = when {
    !automation.enabled -> "Automatización pausada"
    automation.nextRunAtMillis == null -> "Sin próxima ejecución"
    else -> "Próxima ejecución aproximada: " + Instant.ofEpochMilli(automation.nextRunAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd-MM-yyyy, HH:mm"))
}

private fun dayShort(day: Int): String = listOf("L", "M", "X", "J", "V", "S", "D").getOrElse(day - 1) { "?" }
