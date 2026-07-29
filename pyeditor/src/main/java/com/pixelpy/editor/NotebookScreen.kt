package com.pixelpy.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NotebookInk = Color(0xFF202124)
private val NotebookPaper = Color(0xFFFFF7E6)
private val NotebookYellow = Color(0xFFFFD83D)
private val NotebookBlue = Color(0xFF74B9FF)
private val NotebookGreen = Color(0xFF7DE39B)
private val NotebookPink = Color(0xFFFF8FB8)

@Composable
internal fun NotebookScreen(
    project: File,
    onBack: () -> Unit,
    onArtifactsChanged: () -> Unit,
) {
    val store = remember(project) { NotebookStore(project) }
    var notebooks by remember(project) { mutableStateOf(store.list()) }
    var notebook by remember(project) { mutableStateOf(store.getOrCreate()) }
    var selector by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    var runningIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    fun persist(next: PixelNotebook) {
        notebook = store.save(next)
        notebooks = store.list()
    }

    fun updateCell(index: Int, transform: (NotebookCell) -> NotebookCell) {
        persist(notebook.copy(cells = notebook.cells.toMutableList().also {
            it[index] = transform(it[index])
        }))
    }

    fun execute(index: Int, runAll: Boolean) {
        if (runningIndex >= 0) return
        val target = if (runAll) {
            notebook.cells.indexOfLast { it.type == NotebookCellType.Code }
        } else index
        if (target < 0 || notebook.cells[target].type != NotebookCellType.Code) return
        runningIndex = target
        updateCell(target) { it.copy(status = NotebookCellStatus.Running, output = "") }
        val snapshot = notebook
        val codeIndexes = snapshot.cells.mapIndexedNotNull { cellIndex, cell ->
            cellIndex.takeIf { cell.type == NotebookCellType.Code && cellIndex <= target }
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    PythonRuntimeCoordinator.runExclusive {
                        val value = Python.getInstance().getModule("runner").callAttr(
                            "notebook_run",
                            codeIndexes.map { snapshot.cells[it].source },
                            project.absolutePath,
                            120,
                        )
                        NotebookRuntimeResult(
                            ok = value.callAttr("get", "ok").toBoolean(),
                            cellResults = value.callAttr("get", "results").asList().map { cellResult ->
                                NotebookCellResult(
                                    ok = cellResult.callAttr("get", "ok").toBoolean(),
                                    output = cellResult.callAttr("get", "output").toString(),
                                    durationMillis = cellResult.callAttr("get", "duration_ms").toLong(),
                                )
                            },
                            files = value.callAttr("get", "files").asList().map { File(it.toString()) },
                        )
                    }
                }
            }
            result.onSuccess { execution ->
                val resultByCell = codeIndexes.zip(execution.cellResults).toMap()
                val cells = notebook.cells.mapIndexed { cellIndex, cell ->
                    val cellResult = resultByCell[cellIndex]
                    when {
                        cellResult != null -> cell.copy(
                            output = cellResult.output.ifBlank { "✓ Celda terminada sin salida." },
                            status = if (cellResult.ok) NotebookCellStatus.Success else NotebookCellStatus.Error,
                            durationMillis = cellResult.durationMillis,
                        )
                        cellIndex == target && !execution.ok ->
                            cell.copy(status = NotebookCellStatus.Error, output = "La ejecución se detuvo en una celda anterior.")
                        else -> cell
                    }
                }
                persist(notebook.copy(cells = cells))
                if (execution.files.isNotEmpty()) onArtifactsChanged()
            }.onFailure { error ->
                updateCell(target) {
                    it.copy(
                        status = NotebookCellStatus.Error,
                        output = "ERROR DEL NOTEBOOK\n${error::class.simpleName}: ${error.message}",
                    )
                }
            }
            runningIndex = -1
        }
    }

    Column(Modifier.fillMaxSize().background(NotebookPaper).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(3.dp, NotebookInk),
            ) { Text("← VOLVER", color = NotebookInk, fontWeight = FontWeight.Black) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { selector = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(3.dp, NotebookInk),
                ) { Text(notebook.name.uppercase(), color = NotebookInk, fontWeight = FontWeight.Black) }
                DropdownMenu(expanded = selector, onDismissRequest = { selector = false }) {
                    notebooks.forEach { item ->
                        DropdownMenuItem(text = { Text(item.name) }, onClick = {
                            notebook = item
                            selector = false
                        })
                    }
                    DropdownMenuItem(text = { Text("＋ NUEVO NOTEBOOK") }, onClick = {
                        selector = false
                        newDialog = true
                    })
                }
            }
            Spacer(Modifier.width(8.dp))
            NotebookButton("▶ TODO", NotebookGreen, enabled = runningIndex < 0) {
                execute(notebook.cells.lastIndex, true)
            }
        }
        Text(
            "Celdas locales · variables compartidas en orden · ${project.name}",
            Modifier.padding(vertical = 9.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(notebook.cells, key = { _, cell -> cell.id }) { index, cell ->
                NotebookCellCard(
                    index = index,
                    cell = cell,
                    running = runningIndex == index,
                    canDelete = notebook.cells.size > 1,
                    onSource = { source -> updateCell(index) { it.copy(source = source, status = NotebookCellStatus.Idle) } },
                    onType = {
                        updateCell(index) {
                            it.copy(
                                type = if (it.type == NotebookCellType.Code) NotebookCellType.Markdown else NotebookCellType.Code,
                                output = "",
                                status = NotebookCellStatus.Idle,
                            )
                        }
                    },
                    onRun = { execute(index, false) },
                    onMove = { direction ->
                        val destination = index + direction
                        if (destination in notebook.cells.indices) {
                            val changed = notebook.cells.toMutableList()
                            val moving = changed.removeAt(index)
                            changed.add(destination, moving)
                            persist(notebook.copy(cells = changed))
                        }
                    },
                    onDelete = {
                        persist(notebook.copy(cells = notebook.cells.filterIndexed { cellIndex, _ -> cellIndex != index }))
                    },
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotebookButton("＋ CÓDIGO", NotebookBlue, Modifier.weight(1f)) {
                        persist(notebook.copy(cells = notebook.cells + NotebookCell()))
                    }
                    NotebookButton("＋ MARKDOWN", NotebookPink, Modifier.weight(1f)) {
                        persist(notebook.copy(cells = notebook.cells + NotebookCell(type = NotebookCellType.Markdown)))
                    }
                }
            }
        }
    }

    if (newDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDialog = false },
            shape = RoundedCornerShape(0.dp),
            containerColor = NotebookYellow,
            title = { Text("NUEVO NOTEBOOK", fontWeight = FontWeight.Black) },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                NotebookButton("CREAR", NotebookGreen, enabled = name.isNotBlank()) {
                    notebook = store.create(name)
                    notebooks = store.list()
                    newDialog = false
                }
            },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("CANCELAR") } },
        )
    }
}

@Composable
private fun NotebookCellCard(
    index: Int,
    cell: NotebookCell,
    running: Boolean,
    canDelete: Boolean,
    onSource: (String) -> Unit,
    onType: () -> Unit,
    onRun: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val accent = if (cell.type == NotebookCellType.Code) NotebookBlue else NotebookPink
    var markdownPreview by remember(cell.id) { mutableStateOf(cell.type == NotebookCellType.Markdown) }
    Surface(
        color = Color.White,
        border = BorderStroke(3.dp, NotebookInk),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(accent).border(2.dp, NotebookInk), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (cell.type == NotebookCellType.Code) "PYTHON" else "MARKDOWN",
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Black,
                )
                TextButton(onClick = onType) { Text("CAMBIAR", fontSize = 10.sp, fontWeight = FontWeight.Black) }
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Outlined.ArrowUpward, "Subir") }
                IconButton(onClick = { onMove(1) }) { Icon(Icons.Outlined.ArrowDownward, "Bajar") }
                IconButton(onClick = onDelete, enabled = canDelete) { Icon(Icons.Outlined.Delete, "Eliminar") }
            }
            if (cell.type == NotebookCellType.Code) {
                OutlinedTextField(
                    value = cell.source,
                    onValueChange = onSource,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    placeholder = { Text("Escribe Python…") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NotebookButton(if (running) "EJECUTANDO…" else "▶ CELDA", NotebookGreen, enabled = !running, onClick = onRun)
                    Spacer(Modifier.width(10.dp))
                    val state = when (cell.status) {
                        NotebookCellStatus.Idle -> "SIN EJECUTAR"
                        NotebookCellStatus.Running -> "EJECUTANDO"
                        NotebookCellStatus.Success -> "CORRECTO"
                        NotebookCellStatus.Error -> "ERROR"
                    }
                    Text(
                        state + (cell.durationMillis?.let { " · ${it} ms" } ?: ""),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                if (cell.output.isNotBlank()) {
                    Text(
                        cell.output,
                        Modifier.fillMaxWidth().background(NotebookInk).padding(10.dp),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotebookButton("VISTA", if (markdownPreview) NotebookYellow else Color.White, Modifier.weight(1f)) { markdownPreview = true }
                    NotebookButton("EDITAR", if (!markdownPreview) NotebookYellow else Color.White, Modifier.weight(1f)) { markdownPreview = false }
                }
                if (markdownPreview) {
                    NotebookMarkdownPreview(cell.source)
                } else {
                    OutlinedTextField(
                        value = cell.source,
                        onValueChange = onSource,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        placeholder = { Text("Escribe Markdown…") },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookMarkdownPreview(source: String) {
    val blocks = remember(source) { parseNotebookMarkdown(source) }
    Column(
        Modifier.fillMaxWidth().background(NotebookPaper).border(2.dp, NotebookInk).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (blocks.isEmpty()) Text("Sin contenido Markdown.", color = Color.Gray)
        var orderedItem = 0
        blocks.forEach { block ->
            when (block) {
                is NotebookMarkdownBlock.Heading -> Text(
                    markdownInline(block.text),
                    fontSize = (25 - block.level * 2).coerceAtLeast(15).sp,
                    fontWeight = FontWeight.Black,
                )
                is NotebookMarkdownBlock.Paragraph -> Text(markdownInline(block.text), fontSize = 15.sp)
                is NotebookMarkdownBlock.Bullet -> Row {
                    if (block.numbered) orderedItem++
                    Text(if (block.numbered) "$orderedItem." else "•", fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Text(markdownInline(block.text), Modifier.weight(1f), fontSize = 15.sp)
                }
                is NotebookMarkdownBlock.Quote -> Text(
                    markdownInline(block.text),
                    Modifier.fillMaxWidth().border(2.dp, NotebookBlue).padding(10.dp),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
                is NotebookMarkdownBlock.Code -> Text(
                    block.text,
                    Modifier.fillMaxWidth().background(NotebookInk).padding(10.dp),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private fun markdownInline(text: String) = buildAnnotatedString {
    val token = Regex("(\\*\\*.+?\\*\\*|`.+?`|\\*[^*]+?\\*)")
    var cursor = 0
    token.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val value = match.value
        when {
            value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(value.removeSurrounding("**")) }
            value.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFE0E0E0))) { append(value.removeSurrounding("`")) }
            else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(value.removeSurrounding("*")) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun NotebookButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.border(2.dp, NotebookInk),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = NotebookInk),
        shape = RoundedCornerShape(0.dp),
    ) {
        Text(label, fontWeight = FontWeight.Black, fontSize = 11.sp)
    }
}

private data class NotebookRuntimeResult(
    val ok: Boolean,
    val cellResults: List<NotebookCellResult>,
    val files: List<File>,
)

private data class NotebookCellResult(
    val ok: Boolean,
    val output: String,
    val durationMillis: Long,
)
