package com.pixelpy.editor

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

private val Ink = Color(0xFF191919)
private val Paper = Color(0xFFFFF8E7)
private val Yellow = Color(0xFFFFD43B)
private val Pink = Color(0xFFFF5DA2)
private val Blue = Color(0xFF79D8FF)
private val Green = Color(0xFF7EE787)
internal enum class Tab { Projects, Editor, Repl, Console }
private data class RunRecord(val file: String, val ok: Boolean, val preview: String, val time: Long)
private data class CodeSymbol(val kind: String, val name: String, val line: Int)
private data class ReplEntry(val command: String, val output: String, val ok: Boolean)
private data class RuntimeResult(val ok: Boolean, val output: String, val files: List<File>, val errorLine: Int?)

class InputBridge(private val onPrompt: (String) -> Unit) {
    private val answers = ArrayBlockingQueue<String>(1)
    @Volatile private var cancelled = false
    fun request(prompt: String): String { onPrompt(prompt); return answers.take() }
    fun submit(answer: String) { answers.offer(answer) }
    fun cancel() { cancelled = true; answers.offer("") }
    fun isCancelled() = cancelled
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixelPy() }
    }
}

@Composable fun PixelPy() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val transitions = remember { EditorTransitionCoordinator() }
    val projectsRoot = remember { File(context.filesDir, "projects").apply { mkdirs() } }
    val sessionStore = remember { EditorSessionStore(context.applicationContext) }
    val autosave = remember {
        (context.applicationContext as PixelPyApp).autosaveCoordinator
    }
    val initialSession = remember {
        val fallbackProject = prepareProjects(projectsRoot)
        val fallbackFiles = seed(fallbackProject)
        EditorSessionResolver.resolve(
            projectsRoot,
            fallbackProject,
            fallbackFiles.first(),
            sessionStore.load(),
        )
    }
    val initialContent = remember {
        autosave.latestContent(initialSession.file) ?: initialSession.file.readText()
    }
    var dir by remember { mutableStateOf(initialSession.project) }
    var files by remember {
        mutableStateOf(
            initialSession.project.listFiles { file -> file.isFile && file.extension == "py" }
                ?.sortedBy { it.name }
                ?.ifEmpty { listOf(initialSession.file) }
                ?: listOf(initialSession.file)
        )
    }
    var current by remember { mutableStateOf(initialSession.file) }
    var code by remember {
        mutableStateOf(
            TextFieldValue(
                initialContent,
                selection = TextRange(initialSession.selectionStart, initialSession.selectionEnd),
            )
        )
    }
    val selectionReference = remember { AtomicReference(code.selection) }
    var lastRunFile by remember { mutableStateOf(current.name) }
    var lastRunProject by remember { mutableStateOf(dir.absolutePath) }
    var lastRunSource by remember { mutableStateOf(code.text) }
    var output by remember { mutableStateOf("Pulsa EJECUTAR para ver el resultado.") }
    var generated by remember { mutableStateOf<List<File>>(emptyList()) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var success by remember { mutableStateOf<Boolean?>(null) }
    var running by remember { mutableStateOf(false) }
    val runtimeBusy by PythonRuntimeCoordinator.busy.collectAsState()
    var lastErrorLine by remember { mutableStateOf<Int?>(null) }
    var tab by remember { mutableStateOf(Tab.valueOf(initialSession.tab)) }
    var newDialog by remember { mutableStateOf(false) }
    var newProjectDialog by remember { mutableStateOf(false) }
    var resourceVersion by remember { mutableIntStateOf(0) }
    var projectVersion by remember { mutableIntStateOf(0) }
    var replEntries by remember { mutableStateOf<List<ReplEntry>>(emptyList()) }
    var inputBridge by remember { mutableStateOf<InputBridge?>(null) }
    var pendingPrompt by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(loadHistory(context)) }
    val saveStates by autosave.states.collectAsState()
    val saveStatus = saveStates[runCatching { current.canonicalPath }.getOrDefault(current.path)]
        ?: SaveStatus.Saved
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        autosave.registerFile(current, initialContent)
    }

    fun persistSession(
        project: File = dir,
        file: File = current,
        activeTab: Tab = tab,
        selection: TextRange = selectionReference.get(),
    ) {
        sessionStore.save(
            projectsRoot,
            project,
            file,
            activeTab.name,
            selection.start,
            selection.end,
        )
    }

    suspend fun flushPendingSave(file: File = current): Boolean {
        autosave.flushPendingSave(file)
        val saved = autosave.status(file) != SaveStatus.Error
        if (!saved) {
            android.widget.Toast.makeText(
                context,
                "No se pudo guardar ${file.name}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        return saved
    }

    fun transition(block: suspend () -> Unit) {
        transitions.launch(scope, block)
    }

    suspend fun openNow(
        file: File,
        destination: Tab = Tab.Editor,
        selection: TextRange = TextRange.Zero,
    ) {
        val opened = file.canonicalFile
        val source = withContext(Dispatchers.IO) {
            autosave.latestContent(opened) ?: opened.readText()
        }
        autosave.registerFile(opened, source)
        current = opened
        code = TextFieldValue(
            source,
            selection = TextRange(
                selection.start.coerceIn(0, source.length),
                selection.end.coerceIn(0, source.length),
            ),
        )
        selectionReference.set(code.selection)
        tab = destination
    }

    suspend fun switchProjectNow(project: File, destination: Tab = Tab.Projects) {
        val openedProject = project.canonicalFile
        val projectFiles = withContext(Dispatchers.IO) {
            openedProject.listFiles { file -> file.isFile && file.extension == "py" }
                ?.sortedBy { it.name }
                ?.ifEmpty {
                    val main = File(openedProject, "main.py")
                    writeUtf8Atomically(main, "print(\"Nuevo proyecto PixelPy\")\n")
                    listOf(main)
                }
                ?: emptyList()
        }
        if (projectFiles.isNotEmpty()) {
            dir = openedProject
            files = projectFiles
            openNow(projectFiles.first(), destination)
        }
    }

    fun open(file: File) {
        transition {
            if (file.canonicalFile == current.canonicalFile) {
                tab = Tab.Editor
                return@transition
            }
            if (!flushPendingSave(current)) return@transition
            openNow(file)
        }
    }

    fun switchProject(project: File) {
        transition {
            if (project.canonicalFile == dir.canonicalFile) return@transition
            if (!flushPendingSave(current)) return@transition
            switchProjectNow(project)
        }
    }

    LaunchedEffect(dir.path, current.path, tab, code.selection) {
        delay(650)
        persistSession()
    }

    val latestProject by rememberUpdatedState(dir)
    val latestFile by rememberUpdatedState(current)
    val latestTab by rememberUpdatedState(tab)
    val latestBridge by rememberUpdatedState(inputBridge)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                sessionStore.save(
                    projectsRoot,
                    latestProject,
                    latestFile,
                    latestTab.name,
                    selectionReference.get().start,
                    selectionReference.get().end,
                )
                autosave.flushAsync(latestFile)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestBridge?.cancel()
            sessionStore.save(
                projectsRoot,
                latestProject,
                latestFile,
                latestTab.name,
                selectionReference.get().start,
                selectionReference.get().end,
            )
            autosave.flushAsync(latestFile)
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = exportFile
        exportFile = null
        if (uri != null && source != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        source.inputStream().use { it.copyTo(outputStream) }
                    }
                }
            }.onSuccess {
                android.widget.Toast.makeText(context, "Archivo guardado", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "No se pudo guardar", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transition {
            if (!flushPendingSave(current)) return@transition
            runCatching {
                val target = withContext(Dispatchers.IO) {
                    val requested = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                        ?.takeIf { it.endsWith(".py") } ?: "importado.py"
                    var imported = File(dir, requested)
                    var number = 2
                    while (imported.exists()) {
                        imported = File(dir, requested.removeSuffix(".py") + "_$number.py")
                        number++
                    }
                    val importedSource = context.contentResolver.openInputStream(uri)!!.use { inputStream ->
                        inputStream.bufferedReader(Charsets.UTF_8).readText()
                    }
                    writeUtf8Atomically(imported, importedSource)
                    imported
                }
                files = dir.listFiles { file -> file.extension == "py" }!!.sortedBy { it.name }
                openNow(target)
            }.onFailure {
                android.widget.Toast.makeText(context, "No se pudo importar", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val resourceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val requested = contentName(context, uri).ifBlank { "recurso" }
                    var target = File(dir, requested)
                    var number = 2
                    while (target.exists()) {
                        target = File(dir, requested.substringBeforeLast('.', requested) + "_$number" + requested.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" })
                        number++
                    }
                    context.contentResolver.openInputStream(uri)!!.use { inputStream ->
                        target.outputStream().use { inputStream.copyTo(it) }
                    }
                    target
                }
            }.onSuccess { target ->
                resourceVersion++
                android.widget.Toast.makeText(context, "${target.name} añadido al proyecto", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "No se pudo importar el recurso", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val projectImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transition {
            if (!flushPendingSave(current)) return@transition
            runCatching {
                val target = withContext(Dispatchers.IO) {
                    val base = contentName(context, uri).removeSuffix(".zip").ifBlank { "Proyecto importado" }
                    var imported = File(projectsRoot, base)
                    var number = 2
                    while (imported.exists()) { imported = File(projectsRoot, "$base $number"); number++ }
                    imported.mkdirs()
                    context.contentResolver.openInputStream(uri)!!.use { unzipProject(it, imported) }
                    if (imported.listFiles().isNullOrEmpty()) throw IllegalArgumentException("ZIP vacío")
                    imported
                }
                projectVersion++
                switchProjectNow(target)
            }.onFailure {
                android.widget.Toast.makeText(context, "No se pudo importar el proyecto ZIP", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun executeSnapshot(
        executedFile: String,
        executedSource: String,
        executedProject: String,
        debug: Boolean,
        bridge: InputBridge,
    ) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    PythonRuntimeCoordinator.runExclusive {
                        val module = Python.getInstance().getModule("runner")
                        val issues = module.callAttr("analyze", executedSource, executedFile).asList().map { it.toString() }
                        val errors = issues.filter { it.startsWith("ERROR|") }
                        if (errors.isNotEmpty()) {
                            val line = errors.first().split('|', limit = 3).getOrNull(1)?.toIntOrNull()
                            RuntimeResult(false, "ANÁLISIS PREVIO\n\n" + issues.joinToString("\n") { issue -> val parts = issue.split('|', limit = 3); "${parts[0]} · línea ${parts[1]}: ${parts[2]}" }, emptyList(), line)
                        } else {
                            val value = module.callAttr("execute", executedSource, "", executedProject, bridge, 120, debug, executedFile)
                            val warnings = issues.joinToString("\n") { issue -> val parts = issue.split('|', limit = 3); "⚠ Línea ${parts[1]}: ${parts[2]}" }
                            val trace = value.callAttr("get", "trace").asList().joinToString("\n") { it.toString() }
                            RuntimeResult(value.callAttr("get", "ok").toBoolean(), (if (warnings.isBlank()) "" else "$warnings\n\n") + value.callAttr("get", "output").toString() + if (trace.isBlank()) "" else "\n\nDEPURACIÓN · LÍNEAS Y VARIABLES\n$trace", value.callAttr("get", "files").asList().map { File(it.toString()) }, value.callAttr("get", "error_line").toInt().takeIf { it > 0 })
                        }
                    }
                }
                success = result.ok; output = result.output.ifBlank { "✓ Programa terminado sin salida." }; generated = result.files; lastErrorLine = result.errorLine; if (result.files.isNotEmpty()) resourceVersion++
                history = (listOf(RunRecord(executedFile, result.ok, output.take(700), System.currentTimeMillis())) + history).take(20)
                saveHistory(context, history)
            } catch (error: Exception) {
                success = false; lastErrorLine = null; output = "ERROR DEL RUNTIME\n${error::class.simpleName}: ${error.message}"
            } finally {
                running = false
                inputBridge = null
            }
        }
    }

    fun runCode(debug: Boolean = false) {
        if (running || runtimeBusy) {
            android.widget.Toast.makeText(context, "El runtime Python está ocupado", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        transition {
            if (running || runtimeBusy) return@transition
            val executionFile = current.canonicalFile
            if (!flushPendingSave(executionFile)) return@transition
            if (running || runtimeBusy) return@transition
            val executedSource = withContext(Dispatchers.IO) {
                backupFile(executionFile)
                executionFile.readText()
            }
            val executedFile = executionFile.name
            val executedProject = requireNotNull(executionFile.parentFile).absolutePath
            running = true
            tab = Tab.Console
            lastRunFile = executedFile; lastRunProject = executedProject; lastRunSource = executedSource
            val bridge = InputBridge { question -> scope.launch(Dispatchers.Main) { pendingPrompt = question.ifBlank { "Python solicita un valor" } } }
            inputBridge = bridge
            executeSnapshot(executedFile, executedSource, executedProject, debug, bridge)
        }
    }
    fun stopCode() { inputBridge?.cancel(); pendingPrompt = null; output = "Deteniendo ejecución…" }

    fun shareNow(file: File) {
        val uri = FileProvider.getUriForFile(context, "com.pixelpy.editor.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir ${file.name}"))
    }

    fun shareFile(file: File) {
        transition {
            if (!ensureFileReadyForPhysicalRead(file, current, ::flushPendingSave)) return@transition
            shareNow(file)
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, background = Paper, surface = Paper)) {
        Scaffold(modifier = Modifier.testTag("pixelpy-root"), containerColor = Paper, topBar = {
            Row(Modifier.fillMaxWidth().background(Yellow).statusBarsPadding().height(68.dp).border(3.dp, Ink).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.pixelpy_brand_mark), "PixelPy", Modifier.size(38.dp), tint = Color.Unspecified)
                Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("PIXELPY", fontWeight = FontWeight.Black, fontSize = 21.sp); Text(current.name, Modifier.testTag("current-file-name"), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (tab == Tab.Editor || running) {
                    if (!running) { BrutalButton("DEBUG", Blue) { runCode(true) }; Spacer(Modifier.width(6.dp)) }
                    BrutalButton(if (running) "■ DETENER" else "▶ EJECUTAR", if (running) Pink else Green, Modifier.testTag("run-button"), onClick = { if (running) stopCode() else runCode(false) })
                } else {
                    Surface(color = Paper, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp)) {
                        Text(when (tab) { Tab.Projects -> "PROYECTOS"; Tab.Repl -> "REPL"; Tab.Console -> "CONSOLA"; Tab.Editor -> "EDITOR" }, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontWeight = FontWeight.Black, fontSize = 10.sp)
                    }
                }
            }
        }, bottomBar = {
            Row(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().height(68.dp)) {
                Nav(Tab.Projects, tab, "PROYECTOS", Icons.Outlined.Folder) { tab = it }
                Nav(Tab.Editor, tab, "EDITOR", Icons.Outlined.Code) { tab = it }
                Nav(Tab.Repl, tab, "REPL", Icons.Outlined.DataObject) { tab = it }
                Nav(Tab.Console, tab, "CONSOLA", Icons.Outlined.Terminal) { tab = it }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    Tab.Projects -> Projects(remember(projectVersion) { projectsRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList() }, dir, files, remember(dir, resourceVersion) { dir.listFiles { f -> f.isFile && f.extension != "py" }?.sortedBy { it.name } ?: emptyList() }, current, onProject = ::switchProject, onNewProject = { newProjectDialog = true }, onImportProject = { projectImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, onExportProject = { project ->
                        transition {
                            val zip = File(context.cacheDir, project.name + ".zip")
                            if (!exportProjectToZip(project, dir, current, zip, ::flushPendingSave)) return@transition
                            exportFile = zip
                            saveLauncher.launch(zip.name)
                        }
                    }, onOpen = ::open, onNew = { newDialog = true }, onImport = { importLauncher.launch(arrayOf("text/x-python", "text/plain", "*/*")) }, onImportResource = { resourceLauncher.launch(arrayOf("*/*")) }, onDelete = { file ->
                        transition {
                            if (file.canonicalFile == current.canonicalFile && !flushPendingSave(current)) return@transition
                            if (files.size > 1) {
                                val remaining = withContext(Dispatchers.IO) {
                                    moveToTrash(file)
                                    dir.listFiles { candidate -> candidate.extension == "py" }
                                        ?.sortedBy { it.name } ?: emptyList()
                                }
                                files = remaining
                                if (file.canonicalFile == current.canonicalFile && remaining.isNotEmpty()) {
                                    openNow(remaining.first())
                                }
                            }
                        }
                    }, onDeleteResource = { file -> if (moveToTrash(file)) resourceVersion++ }, onTrashChanged = { files = dir.listFiles { f -> f.extension == "py" }?.sortedBy { it.name } ?: emptyList(); resourceVersion++ }, onRename = { file, raw ->
                        transition {
                            val renamingCurrent = file.canonicalFile == current.canonicalFile
                            if (renamingCurrent && !flushPendingSave(current)) return@transition
                            val renamed = File(dir, raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").removeSuffix(".py") + ".py")
                            val renamedFiles = withContext(Dispatchers.IO) {
                                if (!renamed.exists() && file.renameTo(renamed)) {
                                    dir.listFiles { candidate -> candidate.extension == "py" }!!.sortedBy { it.name }
                                } else null
                            }
                            if (renamedFiles != null) {
                                files = renamedFiles
                                if (renamingCurrent) {
                                    current = renamed
                                    autosave.registerFile(renamed, code.text)
                                }
                            } else android.widget.Toast.makeText(context, "No se pudo renombrar", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, onDuplicate = { file ->
                        transition {
                            if (!ensureFileReadyForPhysicalRead(file, current, ::flushPendingSave)) return@transition
                            files = withContext(Dispatchers.IO) {
                                var copy = File(dir, file.nameWithoutExtension + "_copia.py")
                                var number = 2
                                while (copy.exists()) {
                                    copy = File(dir, file.nameWithoutExtension + "_copia$number.py")
                                    number++
                                }
                                file.copyTo(copy)
                                dir.listFiles { candidate -> candidate.extension == "py" }!!.sortedBy { it.name }
                            }
                        }
                    }, onShare = ::shareFile)
                    Tab.Editor -> Editor(files, current, code, saveStatus, onOpen = ::open, onCode = { next ->
                        val textChanged = next.text != code.text
                        selectionReference.set(next.selection)
                        code = next
                        if (textChanged) autosave.onEdit(current, next.text)
                    })
                    Tab.Repl -> ReplScreen(dir, replEntries, runtimeBusy) { replEntries = it }
                    Tab.Console -> Console(lastRunFile, lastRunSource, output, generated, history, success, running, lastErrorLine, onBack = { tab = Tab.Editor }, onGoToLine = { line ->
                        transition {
                            if (!flushPendingSave(current)) return@transition
                            val target = File(lastRunProject, lastRunFile).canonicalFile
                            val loaded: Triple<File, List<File>, TextFieldValue>? = withContext(Dispatchers.IO) {
                                if (!target.exists()) null else {
                                    val targetProject = requireNotNull(target.parentFile)
                                    Triple(
                                        targetProject,
                                        targetProject.listFiles { file -> file.extension == "py" }
                                            ?.sortedBy { it.name } ?: emptyList(),
                                        editorValueAtLine(target, line),
                                    )
                                }
                            }
                            if (loaded != null) {
                                val (targetProject, targetFiles, value) = loaded
                                if (targetProject.canonicalFile != dir.canonicalFile) {
                                    dir = targetProject
                                    files = targetFiles
                                }
                                autosave.registerFile(target, value.text)
                                current = target
                                code = value
                                selectionReference.set(value.selection)
                                tab = Tab.Editor
                            } else {
                                android.widget.Toast.makeText(context, "El archivo ejecutado ya no existe", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, onRun = ::runCode, onSave = { exportFile = it; saveLauncher.launch(it.name) }, onShare = ::shareFile)
                }
            }
        }
    }
    if (newDialog) NewFileDialog(onDismiss = { newDialog = false }) { raw ->
        transition {
            if (!flushPendingSave(current)) return@transition
            val name = raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "script" } + ".py"
            val file = withContext(Dispatchers.IO) {
                File(dir, name).also { target ->
                    if (!target.exists()) writeUtf8Atomically(target, "# $name\nprint(\"Hola desde PixelPy\")\n")
                }
            }
            files = dir.listFiles { candidate -> candidate.extension == "py" }!!.sortedBy { it.name }
            openNow(file)
            newDialog = false
        }
    }
    if (newProjectDialog) NewProjectDialog(onDismiss = { newProjectDialog = false }) { raw ->
        transition {
            if (!flushPendingSave(current)) return@transition
            val (project, main) = withContext(Dispatchers.IO) {
                val safe = raw.trim().replace(Regex("[^a-zA-Z0-9 _-]"), "_").ifBlank { "Nuevo proyecto" }
                var created = File(projectsRoot, safe)
                var number = 2
                while (created.exists()) { created = File(projectsRoot, "$safe $number"); number++ }
                created.mkdirs()
                val entry = File(created, "main.py")
                writeUtf8Atomically(entry, "# ${created.name}\nprint(\"Hola desde ${created.name}\")\n")
                created to entry
            }
            dir = project
            files = listOf(main)
            openNow(main, Tab.Projects)
            projectVersion++
            newProjectDialog = false
        }
    }
    pendingPrompt?.let { question ->
        InputDialog(question, onSubmit = { answer -> pendingPrompt = null; inputBridge?.submit(answer) })
    }
}

@Composable private fun RowScope.Nav(value: Tab, selected: Tab, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (Tab) -> Unit) {
    val active = value == selected
    Column(Modifier.weight(1f).fillMaxHeight().testTag("nav-${value.name.lowercase()}").clickable { onClick(value) }.background(if (active) Yellow else Ink), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = if (active) Ink else Color.White)
        Text(label, color = if (active) Ink else Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

@Composable private fun Projects(projects: List<File>, selectedProject: File, files: List<File>, resources: List<File>, current: File, onProject: (File) -> Unit, onNewProject: () -> Unit, onImportProject: () -> Unit, onExportProject: (File) -> Unit, onOpen: (File) -> Unit, onNew: () -> Unit, onImport: () -> Unit, onImportResource: () -> Unit, onDelete: (File) -> Unit, onDeleteResource: (File) -> Unit, onTrashChanged: () -> Unit, onRename: (File, String) -> Unit, onDuplicate: (File) -> Unit, onShare: (File) -> Unit) {
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var packages by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var projectActions by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("PROYECTOS", fontWeight = FontWeight.Black, fontSize = 30.sp); Text("Tus archivos, módulos y recursos locales.", fontWeight = FontWeight.Medium); Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { projects.forEach { project -> Surface(onClick = { onProject(project) }, modifier = Modifier.testTag("project-${project.name}"), color = if (project == selectedProject) Yellow else Color.White, border = BorderStroke(3.dp, Ink), shape = RoundedCornerShape(0.dp)) { Text("▣ ${project.name}", Modifier.padding(12.dp, 9.dp), fontWeight = FontWeight.Black) } }; Surface(onClick = onNewProject, color = Pink, border = BorderStroke(3.dp, Ink), shape = RoundedCornerShape(0.dp)) { Text("＋ PROYECTO", Modifier.padding(12.dp, 9.dp), fontWeight = FontWeight.Black) } }; Spacer(Modifier.height(12.dp)); Text(selectedProject.name.uppercase(), Modifier.testTag("current-project-name"), fontWeight = FontWeight.Black, fontSize = 18.sp); Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { BrutalButton("＋ ARCHIVO", Pink, Modifier.weight(1f), onClick = onNew); Box(Modifier.weight(1f)) { BrutalButton("MÁS ACCIONES", Blue, Modifier.fillMaxWidth()) { projectActions = true }; DropdownMenu(expanded = projectActions, onDismissRequest = { projectActions = false }) { DropdownMenuItem(text = { Text("Importar archivo .py") }, onClick = { projectActions = false; onImport() }, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) }); DropdownMenuItem(text = { Text("Añadir recurso") }, onClick = { projectActions = false; onImportResource() }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }); DropdownMenuItem(text = { Text("Importar proyecto ZIP") }, onClick = { projectActions = false; onImportProject() }, leadingIcon = { Icon(Icons.Outlined.FolderZip, null) }); DropdownMenuItem(text = { Text("Exportar proyecto ZIP") }, onClick = { projectActions = false; onExportProject(selectedProject) }, leadingIcon = { Icon(Icons.Outlined.Archive, null) }); DropdownMenuItem(text = { Text("Librerías incluidas") }, onClick = { projectActions = false; packages = true }, leadingIcon = { Icon(Icons.Outlined.Extension, null) }); DropdownMenuItem(text = { Text("Papelera") }, onClick = { projectActions = false; trash = true }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }) } } } }
        items(files, key = { it.path }) { file ->
            BrutalCard(if (file == current) Blue else Color.White) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Yellow).border(2.dp, Ink), contentAlignment = Alignment.Center) { Text(".PY", fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(file.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("${file.readLines().size} líneas · guardado local", fontSize = 12.sp) }
                    IconButton(onClick = { onShare(file) }) { Icon(Icons.Outlined.Share, "Compartir") }
                    IconButton(onClick = { onDelete(file) }, enabled = files.size > 1) { Icon(Icons.Outlined.Delete, "Eliminar") }
                }
                Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BrutalButton(if (file == current) "ABIERTO" else "ABRIR", if (file == current) Green else Yellow, Modifier.weight(1f).testTag("project-open-${file.name}"), onClick = { onOpen(file) }); IconButton(onClick = { renameTarget = file }, Modifier.border(2.dp, Ink)) { Icon(Icons.Outlined.Edit, "Renombrar") }; IconButton(onClick = { onDuplicate(file) }, Modifier.border(2.dp, Ink)) { Icon(Icons.Outlined.ContentCopy, "Duplicar") } }
            }
        }
        item { Spacer(Modifier.height(4.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("RECURSOS", fontWeight = FontWeight.Black, fontSize = 22.sp); Text("CSV, JSON, Excel, imágenes, TXT o ZIP", fontSize = 12.sp) }; BrutalButton("＋ AÑADIR", Blue, onClick = onImportResource) } }
        if (resources.isEmpty()) item { Surface(color = Color.White, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxWidth()) { Text("Este proyecto todavía no tiene archivos de datos.", Modifier.padding(14.dp)) } }
        items(resources, key = { it.path }) { file ->
            BrutalCard(Color.White) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Green).border(2.dp, Ink), contentAlignment = Alignment.Center) { Text(file.extension.uppercase().take(4).ifBlank { "FILE" }, fontWeight = FontWeight.Black, fontSize = 10.sp) }
                    Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(file.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Text(formatBytes(file.length()) + " · ruta: ${file.name}", fontSize = 11.sp) }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(file.name)); android.widget.Toast.makeText(context, "Ruta copiada", android.widget.Toast.LENGTH_SHORT).show() }) { Icon(Icons.Outlined.ContentCopy, "Copiar ruta") }
                    IconButton(onClick = { onShare(file) }) { Icon(Icons.Outlined.Share, "Compartir") }
                    IconButton(onClick = { onDeleteResource(file) }) { Icon(Icons.Outlined.Delete, "Eliminar") }
                }
            }
        }
    }
    renameTarget?.let { file -> RenameDialog(file.nameWithoutExtension, onDismiss = { renameTarget = null }) { onRename(file, it); renameTarget = null } }
    if (packages) PackagesDialog { packages = false }
    if (trash) TrashDialog(selectedProject, onDismiss = { trash = false }) { onTrashChanged() }
}

@Composable private fun Editor(files: List<File>, current: File, code: TextFieldValue, saveStatus: SaveStatus, onOpen: (File) -> Unit, onCode: (TextFieldValue) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var undo by remember { mutableStateOf<List<TextFieldValue>>(emptyList()) }
    var redo by remember { mutableStateOf<List<TextFieldValue>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var fontSize by rememberSaveable { mutableFloatStateOf(context.getSharedPreferences("pixelpy", 0).getFloat("font_size", 15f)) }
    var showOutline by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var moreTools by remember { mutableStateOf(false) }
    LaunchedEffect(fontSize) { context.getSharedPreferences("pixelpy", 0).edit().putFloat("font_size", fontSize).apply() }
    val completionPrefix = code.text.substring(0, code.selection.min).takeLastWhile { it.isLetterOrDigit() || it == '_' }
    val completions = if (completionPrefix.length >= 2) completionItems(code.text).filter { it.startsWith(completionPrefix, ignoreCase = true) && !it.equals(completionPrefix, true) }.take(5) else emptyList()
    fun change(next: TextFieldValue) { if (next.text != code.text) { undo = (undo + code).takeLast(80); redo = emptyList() }; onCode(next) }
    fun insert(value: String) {
        val start = code.selection.min; val end = code.selection.max
        change(TextFieldValue(code.text.replaceRange(start, end, value), TextRange(start + value.length)))
    }
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEach { file -> Surface(onClick = { onOpen(file) }, modifier = Modifier.testTag("editor-file-${file.name}"), color = if (file == current) Yellow else Color.White, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp)) { Text((if (file == current) "● " else "") + file.name, Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp) } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column { Text("EDITOR", fontWeight = FontWeight.Black, fontSize = 18.sp); val statusText = when (saveStatus) { SaveStatus.Editing -> "● EDITANDO"; SaveStatus.Saving -> "● GUARDANDO"; SaveStatus.Saved -> "● GUARDADO"; SaveStatus.Error -> "● ERROR AL GUARDAR" }; Text(statusText, Modifier.testTag("save-status"), color = if (saveStatus == SaveStatus.Error) Color(0xFFC62828) else Color(0xFF16853B), fontWeight = FontWeight.Black, fontSize = 9.sp) }; Spacer(Modifier.weight(1f)); IconButton(onClick = { searching = !searching }) { Icon(Icons.Outlined.Search, "Buscar") }; IconButton(onClick = { if (undo.isNotEmpty()) { redo = redo + code; val previous = undo.last(); undo = undo.dropLast(1); onCode(previous) } }, enabled = undo.isNotEmpty()) { Icon(Icons.Outlined.Undo, "Deshacer") }; IconButton(onClick = { if (redo.isNotEmpty()) { undo = undo + code; val next = redo.last(); redo = redo.dropLast(1); onCode(next) } }, enabled = redo.isNotEmpty()) { Icon(Icons.Outlined.Redo, "Rehacer") }; Box { IconButton(onClick = { moreTools = true }) { Icon(Icons.Outlined.MoreVert, "Más herramientas") }; DropdownMenu(moreTools, { moreTools = false }) { DropdownMenuItem({ Text("Estructura del código") }, { moreTools = false; showOutline = true }, leadingIcon = { Icon(Icons.Outlined.AccountTree, null) }); DropdownMenuItem({ Text("Versiones anteriores") }, { moreTools = false; showVersions = true }, leadingIcon = { Icon(Icons.Outlined.Restore, null) }); DropdownMenuItem({ Text("Reducir texto") }, { moreTools = false; fontSize = (fontSize - 1).coerceAtLeast(11f) }, leadingIcon = { Text("A−", fontWeight = FontWeight.Black) }); DropdownMenuItem({ Text("Aumentar texto") }, { moreTools = false; fontSize = (fontSize + 1).coerceAtMost(24f) }, leadingIcon = { Text("A+", fontWeight = FontWeight.Black) }) } } }
        if (searching) {
            Column(Modifier.fillMaxWidth().background(Yellow).border(2.dp, Ink).padding(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Buscar") }, singleLine = true)
                    BrutalButton("SIG.", Blue, enabled = query.isNotEmpty()) { val start = if (code.selection.max < code.text.length) code.selection.max else 0; val found = code.text.indexOf(query, start, ignoreCase = true).let { if (it < 0) code.text.indexOf(query, 0, ignoreCase = true) else it }; if (found >= 0) onCode(code.copy(selection = TextRange(found, found + query.length))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), label = { Text("Reemplazar por") }, singleLine = true)
                    BrutalButton("UNO", Green, enabled = query.isNotEmpty()) { if (code.selection.min != code.selection.max && code.text.substring(code.selection.min, code.selection.max).equals(query, true)) { val start = code.selection.min; change(TextFieldValue(code.text.replaceRange(code.selection.min, code.selection.max, replacement), TextRange(start + replacement.length))) } }
                    BrutalButton("TODO", Pink, enabled = query.isNotEmpty()) { change(TextFieldValue(code.text.replace(query, replacement, ignoreCase = true))) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().padding(end = 5.dp, bottom = 5.dp)) {
            Box(Modifier.matchParentSize().offset(5.dp, 5.dp).background(Ink))
            Row(Modifier.fillMaxSize().background(Color(0xFF20232A)).border(3.dp, Ink).padding(10.dp)) {
                val lines = code.text.count { it == '\n' } + 1
                Text((1..lines).joinToString("\n"), color = Color(0xFF7D8590), fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Spacer(Modifier.width(10.dp)); Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF444C56))); Spacer(Modifier.width(10.dp))
                BasicTextField(value = code, onValueChange = { raw -> change(autoIndent(code, raw)) }, modifier = Modifier.testTag("editor-input").fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp), cursorBrush = SolidColor(Yellow), visualTransformation = PythonHighlight)
            }
        }
        if (completions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                completions.forEach { suggestion -> Surface(onClick = { val end = code.selection.min; val start = end - completionPrefix.length; change(TextFieldValue(code.text.replaceRange(start, end, suggestion), TextRange(start + suggestion.length))) }, color = Pink, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp)) { Text(suggestion, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) } }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("TAB" to "    ", "(" to "()", "[" to "[]", "{" to "{}", ":" to ":", "'" to "''", "\"" to "\"\"", "=" to " = ", "#" to "# ").forEach { (label, value) ->
                Surface(onClick = { insertPair(code, value, ::change) }, color = Blue, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp)) { Text(label, Modifier.padding(horizontal = 13.dp, vertical = 8.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black) }
            }
        }
    }
    if (showOutline) CodeOutlineDialog(code.text, onDismiss = { showOutline = false }) { line -> val position = code.text.lineSequence().take(line - 1).sumOf { it.length + 1 }; onCode(code.copy(selection = TextRange(position.coerceAtMost(code.text.length)))); showOutline = false }
    if (showVersions) VersionsDialog(current, onDismiss = { showVersions = false }) { version -> onCode(TextFieldValue(version.readText())); showVersions = false; android.widget.Toast.makeText(context, "Versión restaurada y guardada", android.widget.Toast.LENGTH_SHORT).show() }
}

@Composable private fun ReplScreen(project: File, entries: List<ReplEntry>, runtimeBusy: Boolean, onEntries: (List<ReplEntry>) -> Unit) {
    var command by rememberSaveable(project.path) { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun evaluate() {
        val source = command.trim(); if (source.isEmpty() || running || runtimeBusy) return
        running = true; command = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    PythonRuntimeCoordinator.runExclusive {
                        val value = Python.getInstance().getModule("runner").callAttr("repl_eval", source, project.absolutePath)
                        value.callAttr("get", "ok").toBoolean() to value.callAttr("get", "output").toString()
                    }
                }
                onEntries((entries + ReplEntry(source, result.second.ifBlank { "✓" }, result.first)).takeLast(100))
            } catch (error: Exception) {
                onEntries((entries + ReplEntry(source, "Runtime: ${error.message}", false)).takeLast(100))
            } finally {
                running = false
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(14.dp)) {
        Text("PYTHON REPL", fontWeight = FontWeight.Black, fontSize = 27.sp)
        Text(if (runtimeBusy) "Runtime Python ocupado…" else "Sesión activa · ${project.name}", color = if (runtimeBusy) Pink else Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Ink).border(3.dp, Ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) item { Text("Prueba expresiones sin crear un archivo.\n\n>>> 2 ** 10\n>>> datos = [3, 1, 2]\n>>> sorted(datos)", color = Color(0xFF9DA7B3), fontFamily = FontFamily.Monospace) }
            items(entries) { entry -> Column { Text(">>> ${entry.command}", color = Yellow, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Text(entry.output, color = if (entry.ok) Color.White else Pink, fontFamily = FontFamily.Monospace) } }
        }
        Spacer(Modifier.height(10.dp)); Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(command, { command = it }, Modifier.weight(1f), label = { Text(">>> comando") }, enabled = !runtimeBusy, textStyle = TextStyle(fontFamily = FontFamily.Monospace), maxLines = 4)
            BrutalButton(if (runtimeBusy) "…" else "EJECUTAR", Green, enabled = !runtimeBusy, onClick = ::evaluate)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onEntries(emptyList()) }, enabled = entries.isNotEmpty()) { Text("LIMPIAR", color = Ink, fontWeight = FontWeight.Black) } }
    }
}

@Composable private fun Console(fileName: String, code: String, output: String, generated: List<File>, history: List<RunRecord>, success: Boolean?, running: Boolean, errorLine: Int?, onBack: () -> Unit, onGoToLine: (Int) -> Unit, onRun: () -> Unit, onSave: (File) -> Unit, onShare: (File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val report = "PIXELPY LOG\n\nCÓDIGO:\n$code\n\nSALIDA:\n$output"
    val explanation = explainError(output)
    var showHistory by remember { mutableStateOf(false) }
    var consolePage by remember { mutableStateOf("SALIDA") }
    val debugMarker = "DEPURACIÓN · LÍNEAS Y VARIABLES"
    val standardOutput = output.substringBefore(debugMarker).trim()
    val debugOutput = output.substringAfter(debugMarker, "").trim()
    val visibleOutput = when (consolePage) { "VARIABLES" -> debugOutput.ifBlank { "Ejecuta con DEBUG para inspeccionar líneas y variables." }; "ERROR" -> if (success == false) output else "No hay errores en la última ejecución."; else -> standardOutput.ifBlank { "✓ Programa terminado sin salida." } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("CONSOLA", fontWeight = FontWeight.Black, fontSize = 28.sp); Spacer(Modifier.weight(1f)); IconButton(onClick = { showHistory = true }) { Icon(Icons.Outlined.History, "Historial") }; Surface(color = when { running -> Yellow; success == true -> Green; success == false -> Pink; else -> Blue }, shape = RoundedCornerShape(0.dp), border = BorderStroke(2.dp, Ink)) { Text(if (running) "EJECUTANDO" else if (success == false) "ERROR" else "LISTO", Modifier.padding(8.dp, 4.dp), fontWeight = FontWeight.Black) } }
        Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf("SALIDA" to Green, "VARIABLES" to Blue, "ERROR" to Pink).forEach { (label, color) -> Surface(onClick = { consolePage = label }, color = if (consolePage == label) color else Color.White, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp)) { Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Black, fontSize = 10.sp) } } }
        Spacer(Modifier.height(8.dp)); BrutalCard(Ink, Modifier.weight(1f)) { Text("$ python $fileName", color = Green, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(visibleOutput, color = Color.White, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("console-output").verticalScroll(rememberScrollState())) }
        if (explanation != null) { Spacer(Modifier.height(10.dp)); BrutalCard(Yellow) { Text("QUÉ SIGNIFICA", fontWeight = FontWeight.Black, fontSize = 11.sp); Text(explanation, fontWeight = FontWeight.Medium) } }
        Spacer(Modifier.height(12.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrutalButton("COPIAR LOG", Pink, Modifier.weight(1f)) { clipboard.setText(AnnotatedString(report)); android.widget.Toast.makeText(context, "Log copiado", android.widget.Toast.LENGTH_SHORT).show() }
            BrutalButton("COMPARTIR", Green, Modifier.weight(1f)) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Compartir log")) }
        }
        if (errorLine != null) { Spacer(Modifier.height(10.dp)); BrutalButton("IR A LÍNEA $errorLine", Pink, Modifier.fillMaxWidth()) { onGoToLine(errorLine) } }
        generated.forEach { file ->
            Spacer(Modifier.height(10.dp)); BrutalCard(Blue) {
                Text("ARCHIVO GENERADO", fontWeight = FontWeight.Black, fontSize = 11.sp)
                Text(file.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { BrutalButton("GUARDAR", Yellow, Modifier.weight(1f)) { onSave(file) }; BrutalButton("COMPARTIR", Green, Modifier.weight(1f)) { onShare(file) } }
            }
        }
        Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { BrutalButton("← EDITAR", Blue, Modifier.weight(1f), onClick = onBack); BrutalButton("↻ REPETIR", Yellow, Modifier.weight(1f), enabled = !running, onClick = onRun) }
    }
    if (showHistory) HistoryDialog(history) { showHistory = false }
}

@Composable private fun BrutalCard(color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth().padding(end = 6.dp, bottom = 6.dp)) { Box(Modifier.matchParentSize().offset(6.dp, 6.dp).background(Ink)); Column(Modifier.fillMaxWidth().background(color).border(3.dp, Ink).padding(14.dp), content = content) }
}

@Composable private fun BrutalButton(label: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 42.dp), enabled = enabled, shape = RoundedCornerShape(0.dp), border = BorderStroke(2.dp, Ink), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink, disabledContainerColor = Color.LightGray)) { Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp) }
}

@Composable private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Yellow, shape = RoundedCornerShape(0.dp), title = { Text("NUEVO ARCHIVO", fontWeight = FontWeight.Black) }, text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre sin .py") }, singleLine = true) }, confirmButton = { BrutalButton("CREAR", Green, enabled = name.isNotBlank()) { onCreate(name) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR", color = Ink, fontWeight = FontWeight.Black) } })
}

@Composable private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Yellow, shape = RoundedCornerShape(0.dp), title = { Text("RENOMBRAR", fontWeight = FontWeight.Black) }, text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre del archivo") }, singleLine = true) }, confirmButton = { BrutalButton("GUARDAR", Green, enabled = name.isNotBlank()) { onRename(name) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR", color = Ink, fontWeight = FontWeight.Black) } })
}

@Composable private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Pink, shape = RoundedCornerShape(0.dp), title = { Text("NUEVO PROYECTO", fontWeight = FontWeight.Black) }, text = { Column { Text("Creará una carpeta independiente para sus módulos y archivos."); Spacer(Modifier.height(10.dp)); OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) } }, confirmButton = { BrutalButton("CREAR PROYECTO", Green, enabled = name.isNotBlank()) { onCreate(name) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR", color = Ink, fontWeight = FontWeight.Black) } })
}

@Composable private fun PackagesDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Paper, shape = RoundedCornerShape(0.dp), title = { Text("LIBRERÍAS", fontWeight = FontWeight.Black) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PackageRow("requests", "2.34.2", "APIs, descargas y HTTP")
        PackageRow("beautifulsoup4", "4.15.0", "HTML y scraping")
        PackageRow("openpyxl", "3.1.5", "Leer y crear Excel")
        PackageRow("defusedxml", "0.7.1", "XML más seguro")
        Text("También está disponible toda la biblioteca estándar de Python 3.13.", fontSize = 12.sp)
    } }, confirmButton = { BrutalButton("ENTENDIDO", Yellow, onClick = onDismiss) })
}

@Composable private fun PackageRow(name: String, version: String, description: String) {
    Row(Modifier.fillMaxWidth().background(Color.White).border(2.dp, Ink).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(38.dp).background(Blue).border(2.dp, Ink), contentAlignment = Alignment.Center) { Text("PY", fontWeight = FontWeight.Black) }; Spacer(Modifier.width(10.dp)); Column { Text("$name  $version", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Text(description, fontSize = 12.sp) } }
}

@Composable private fun HistoryDialog(history: List<RunRecord>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Paper, shape = RoundedCornerShape(0.dp), title = { Text("HISTORIAL", fontWeight = FontWeight.Black) }, text = {
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (history.isEmpty()) item { Text("Todavía no hay ejecuciones guardadas.") }
            items(history) { run ->
                Column(Modifier.fillMaxWidth().background(if (run.ok) Green else Pink).border(2.dp, Ink).padding(10.dp)) {
                    Row { Text(if (run.ok) "✓ " else "✕ ", fontWeight = FontWeight.Black); Text(run.file, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(run.time)), fontSize = 11.sp) }
                    Text(run.preview.lineSequence().firstOrNull().orEmpty().ifBlank { "Sin salida" }, maxLines = 2, fontSize = 12.sp)
                }
            }
        }
    }, confirmButton = { BrutalButton("CERRAR", Yellow, onClick = onDismiss) })
}

@Composable private fun CodeOutlineDialog(source: String, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val symbols = remember(source) { codeSymbols(source) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Paper, shape = RoundedCornerShape(0.dp), title = { Text("ESTRUCTURA", fontWeight = FontWeight.Black) }, text = { LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (symbols.isEmpty()) item { Text("No se encontraron funciones ni clases.") }
        items(symbols) { symbol -> Surface(onClick = { onGo(symbol.line) }, color = if (symbol.kind == "class") Pink else Blue, border = BorderStroke(2.dp, Ink), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp)) { Text(if (symbol.kind == "class") "C" else "ƒ", fontWeight = FontWeight.Black); Spacer(Modifier.width(10.dp)); Text(symbol.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("L${symbol.line}", fontSize = 11.sp) } } }
    } }, confirmButton = { BrutalButton("CERRAR", Yellow, onClick = onDismiss) })
}

@Composable private fun TrashDialog(project: File, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val trash = remember(project, refresh) { File(project, ".trash").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Paper, shape = RoundedCornerShape(0.dp), title = { Text("PAPELERA", fontWeight = FontWeight.Black) }, text = { LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trash.isEmpty()) item { Text("La papelera está vacía.") }
        items(trash, key = { it.path }) { file -> Row(Modifier.fillMaxWidth().background(Color.White).border(2.dp, Ink).padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(file.name.substringAfter("__"), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); BrutalButton("RESTAURAR", Green) { restoreTrash(file, project); refresh++; onChanged() } } }
    } }, confirmButton = { BrutalButton("CERRAR", Yellow, onClick = onDismiss) })
}

@Composable private fun VersionsDialog(file: File, onDismiss: () -> Unit, onRestore: (File) -> Unit) {
    val versions = remember(file) { File(file.parentFile, ".versions/${file.name}").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Paper, shape = RoundedCornerShape(0.dp), title = { Text("VERSIONES", fontWeight = FontWeight.Black) }, text = { LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (versions.isEmpty()) item { Text("Se creará una versión automática antes de cada ejecución.") }
        items(versions, key = { it.path }) { version -> Row(Modifier.fillMaxWidth().background(Blue).border(2.dp, Ink).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(java.text.SimpleDateFormat("dd MMM · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(version.lastModified())), fontWeight = FontWeight.Bold); Text("${version.readLines().size} líneas", fontSize = 11.sp) }; BrutalButton("RESTAURAR", Green) { onRestore(version) } } }
    } }, confirmButton = { BrutalButton("CERRAR", Yellow, onClick = onDismiss) })
}

@Composable private fun InputDialog(prompt: String, onSubmit: (String) -> Unit) {
    var answer by remember(prompt) { mutableStateOf("") }
    AlertDialog(onDismissRequest = {}, containerColor = Blue, shape = RoundedCornerShape(0.dp), title = { Text("INPUT()", fontWeight = FontWeight.Black) }, text = { Column { Text(prompt, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); OutlinedTextField(answer, { answer = it }, label = { Text("Tu respuesta") }, singleLine = true) } }, confirmButton = { BrutalButton("ENVIAR ↵", Green) { onSubmit(answer) } })
}

private object PythonHighlight : VisualTransformation {
    private val words = setOf("and","as","assert","async","await","break","class","continue","def","del","elif","else","except","False","finally","for","from","global","if","import","in","is","lambda","None","nonlocal","not","or","pass","raise","return","True","try","while","with","yield","print","input","range","len")
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        Regex("#[^\\n]*").findAll(text.text).forEach { builder.addStyle(SpanStyle(color = Color(0xFF8B949E)), it.range.first, it.range.last + 1) }
        Regex("(?:\\\"[^\\\"\\n]*\\\"|'[^'\\n]*')").findAll(text.text).forEach { builder.addStyle(SpanStyle(color = Color(0xFFA5D6FF)), it.range.first, it.range.last + 1) }
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b").findAll(text.text).filter { it.value in words }.forEach { builder.addStyle(SpanStyle(color = Color(0xFFFF7AB2), fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1) }
        Regex("\\b\\d+(?:\\.\\d+)?\\b").findAll(text.text).forEach { builder.addStyle(SpanStyle(color = Color(0xFFFFD43B)), it.range.first, it.range.last + 1) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private fun autoIndent(old: TextFieldValue, next: TextFieldValue): TextFieldValue {
    if (next.text.length != old.text.length + 1 || next.selection.start < 1 || next.text[next.selection.start - 1] != '\n') return next
    val previousLine = old.text.substring(0, old.selection.min).substringAfterLast('\n')
    val base = previousLine.takeWhile { it == ' ' || it == '\t' }
    val indent = base + if (previousLine.trimEnd().endsWith(':')) "    " else ""
    if (indent.isEmpty()) return next
    val cursor = next.selection.start
    return TextFieldValue(next.text.substring(0, cursor) + indent + next.text.substring(cursor), TextRange(cursor + indent.length))
}

private fun insertPair(current: TextFieldValue, value: String, onChange: (TextFieldValue) -> Unit) {
    val start = current.selection.min; val end = current.selection.max
    val selected = current.text.substring(start, end)
    val paired = value.length == 2 && value in listOf("()", "[]", "{}", "''", "\"\"")
    val inserted = if (paired && selected.isNotEmpty()) "${value.first()}$selected${value.last()}" else value
    val cursor = if (paired && selected.isEmpty()) start + 1 else start + inserted.length
    onChange(TextFieldValue(current.text.replaceRange(start, end, inserted), TextRange(cursor)))
}

private fun completionItems(source: String): List<String> {
    val keywords = listOf("and","as","assert","async","await","break","class","continue","def","elif","else","except","False","finally","for","from","if","import","in","is","lambda","None","not","or","pass","raise","return","True","try","while","with","yield","print","input","range","len","list","dict","set","str","int","float","enumerate","zip","open","super")
    val names = Regex("\\b(?:def|class)\\s+([A-Za-z_]\\w*)|\\b([A-Za-z_]\\w*)\\s*=").findAll(source).flatMap { match -> match.groupValues.drop(1).filter { it.isNotBlank() }.asSequence() }.toList()
    return (names + keywords).distinct()
}

private fun codeSymbols(source: String): List<CodeSymbol> = source.lineSequence().mapIndexedNotNull { index, line ->
    Regex("^\\s*(def|class)\\s+([A-Za-z_]\\w*)").find(line)?.let { CodeSymbol(it.groupValues[1], it.groupValues[2], index + 1) }
}.toList()

private fun seed(dir: File): List<File> {
    if (dir.listFiles().isNullOrEmpty()) {
        writeUtf8Atomically(File(dir, "hola_mundo.py"), "# Tu primer programa\nnombre = input(\"¿Cómo te llamas? \" )\nprint(f\"¡Hola, {nombre}!\")\n")
        writeUtf8Atomically(File(dir, "lista_creativa.py"), "ideas = [\"dibujar\", \"caminar\", \"crear una app\"]\n\nfor numero, idea in enumerate(ideas, 1):\n    print(f\"{numero}. {idea}\")\n")
    }
    File(dir, "input_interactivo.py").let { file -> if (!file.exists()) writeUtf8Atomically(file, "nombre = input(\"¿Cómo te llamas? \" )\nedad = input(\"¿Cuántos años tienes? \" )\nprint(f\"Hola {nombre}, tienes {edad} años.\")\n") }
    File(dir, "librerias_demo.py").let { file -> if (!file.exists()) writeUtf8Atomically(file, "import requests\nfrom bs4 import BeautifulSoup\nfrom openpyxl import Workbook\n\nrespuesta = requests.get(\"https://example.com\", timeout=15)\nsoup = BeautifulSoup(respuesta.text, \"html.parser\")\n\nlibro = Workbook()\nhoja = libro.active\nhoja.append([\"Título web\", soup.title.string])\nlibro.save(\"demo_librerias.xlsx\")\n\nprint(\"OK:\", soup.title.string)\nprint(\"Excel generado con openpyxl\")\n") }
    return dir.listFiles { file -> file.extension == "py" }!!.sortedBy { it.name }
}

private fun prepareProjects(root: File): File {
    val looseFiles = root.listFiles { file -> file.isFile }?.toList().orEmpty()
    var principal = File(root, "Proyecto principal")
    if (looseFiles.isNotEmpty() || root.listFiles { file -> file.isDirectory }.isNullOrEmpty()) principal.mkdirs()
    looseFiles.forEach { source ->
        var target = File(principal, source.name); var number = 2
        while (target.exists()) { target = File(principal, source.nameWithoutExtension + "_migrado$number" + if (source.extension.isNotEmpty()) ".${source.extension}" else ""); number++ }
        if (!source.renameTo(target)) runCatching { source.copyTo(target); source.delete() }
    }
    return root.listFiles { file -> file.isDirectory }?.firstOrNull { it.name == "Proyecto principal" }
        ?: root.listFiles { file -> file.isDirectory }?.sortedBy { it.name }?.firstOrNull()
        ?: principal.apply { mkdirs() }
}

private fun backupFile(file: File) {
    if (!file.exists()) return
    val folder = File(file.parentFile, ".versions/${file.name}").apply { mkdirs() }
    val latest = folder.listFiles()?.maxByOrNull { it.lastModified() }
    if (latest != null && runCatching { latest.readText() == file.readText() }.getOrDefault(false)) return
    file.copyTo(File(folder, "${System.currentTimeMillis()}.py"), overwrite = false)
    folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
}

private fun moveToTrash(file: File): Boolean {
    val folder = File(file.parentFile, ".trash").apply { mkdirs() }
    return file.renameTo(File(folder, "${System.currentTimeMillis()}__${file.name}"))
}

private fun restoreTrash(file: File, project: File) {
    val original = file.name.substringAfter("__"); var target = File(project, original); var number = 2
    while (target.exists()) { target = File(project, original.substringBeforeLast('.', original) + "_restaurado$number" + original.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }); number++ }
    file.renameTo(target)
}

private fun unzipProject(input: java.io.InputStream, target: File) {
    var total = 0L
    ZipInputStream(input.buffered()).use { zip -> var entry = zip.nextEntry; while (entry != null) {
        val output = File(target, entry.name); if (!output.canonicalPath.startsWith(target.canonicalPath + File.separator)) throw SecurityException("Ruta ZIP inválida")
        if (entry.isDirectory) output.mkdirs() else { output.parentFile?.mkdirs(); output.outputStream().use { stream -> val buffer = ByteArray(8192); var read = zip.read(buffer); while (read > 0) { total += read; if (total > 100L * 1024 * 1024) throw IllegalArgumentException("ZIP demasiado grande"); stream.write(buffer, 0, read); read = zip.read(buffer) } } }
        zip.closeEntry(); entry = zip.nextEntry
    } }
}

private fun mime(file: File) = when (file.extension.lowercase()) {
    "py" -> "text/x-python"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "txt", "log" -> "text/plain"
    else -> "application/octet-stream"
}

private fun contentName(context: android.content.Context, uri: android.net.Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) ?: "" }
    return uri.lastPathSegment?.substringAfterLast('/') ?: ""
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun explainError(output: String): String? = when {
    "SyntaxError" in output -> "Python no pudo entender la estructura del código. Revisa paréntesis, comillas y los dos puntos cerca de la línea indicada."
    "IndentationError" in output || "TabError" in output -> "La sangría no coincide. Alinea el bloque usando cuatro espacios y evita mezclar tabulaciones con espacios."
    "NameError" in output -> "Se usó un nombre que Python todavía no conoce. Comprueba su escritura y que la variable se defina antes de utilizarla."
    "TypeError" in output -> "La operación recibió un tipo de dato incompatible. Revisa si estás mezclando texto, números, listas u otros valores."
    "IndexError" in output -> "Se intentó acceder a una posición que no existe dentro de una lista o secuencia."
    "KeyError" in output -> "El diccionario no contiene la clave solicitada. Puedes comprobarla con `clave in diccionario` o usar `.get()`."
    "ModuleNotFoundError" in output -> "Ese módulo no está incluido en PixelPy. Los módulos de la biblioteca estándar funcionan; los paquetes externos se añadirán desde el gestor de paquetes."
    "PermissionError" in output -> "Android bloqueó esa ruta. Genera el archivo en el proyecto y usa GUARDAR o COMPARTIR desde PixelPy."
    "URLError" in output || "No address associated with hostname" in output -> "No se pudo alcanzar el servidor. Comprueba Internet, la dirección y que el servicio esté disponible."
    "Traceback" in output -> "Python detuvo la ejecución por un error. La última línea indica el tipo y el mensaje; usa IR A LÍNEA para corregirlo."
    else -> null
}

private fun loadHistory(context: android.content.Context): List<RunRecord> = runCatching {
    val raw = context.getSharedPreferences("pixelpy", 0).getString("run_history", "[]") ?: "[]"
    val array = JSONArray(raw)
    (0 until array.length()).map { index -> val item = array.getJSONObject(index); RunRecord(item.getString("file"), item.getBoolean("ok"), item.getString("preview"), item.getLong("time")) }
}.getOrDefault(emptyList())

private fun saveHistory(context: android.content.Context, history: List<RunRecord>) {
    val array = JSONArray(); history.forEach { run -> array.put(JSONObject().put("file", run.file).put("ok", run.ok).put("preview", run.preview).put("time", run.time)) }
    context.getSharedPreferences("pixelpy", 0).edit().putString("run_history", array.toString()).apply()
}
