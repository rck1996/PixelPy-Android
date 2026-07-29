package com.pixelpy.editor

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val ResultInk = Color(0xFF191919)
private val ResultPaper = Color(0xFFFFF8E7)
private val ResultYellow = Color(0xFFFFD43B)
private val ResultBlue = Color(0xFF79D8FF)
private const val MAX_PREVIEW_BYTES = 1_000_000

internal enum class PublishedPreviewKind { Text, Json, Csv, Image, External }
internal const val EXTRA_PROJECT_RESULT_PATH = "com.pixelpy.editor.extra.PROJECT_RESULT_PATH"

internal data class CsvPreviewData(val headers: List<String>, val rows: List<List<String>>)

internal sealed interface JsonPreviewNode {
    data class ObjectNode(val values: List<Pair<String, JsonPreviewNode>>) : JsonPreviewNode
    data class ArrayNode(val values: List<JsonPreviewNode>) : JsonPreviewNode
    data class ValueNode(val value: String, val type: JsonValueType) : JsonPreviewNode
}

internal enum class JsonValueType { String, Number, Boolean, Null }

internal fun previewKind(file: File): PublishedPreviewKind = when (file.extension.lowercase()) {
    "txt", "log", "md", "xml" -> PublishedPreviewKind.Text
    "json" -> PublishedPreviewKind.Json
    "csv" -> PublishedPreviewKind.Csv
    "png", "jpg", "jpeg", "webp" -> PublishedPreviewKind.Image
    else -> PublishedPreviewKind.External
}

internal fun formatPublishedText(file: File, kind: PublishedPreviewKind): String {
    if (file.length() > MAX_PREVIEW_BYTES) {
        return "La vista previa está limitada a 1 MB. Usa ABRIR EN OTRA APP para ver el archivo completo."
    }
    val raw = file.readText(Charsets.UTF_8)
    return when (kind) {
        PublishedPreviewKind.Json -> runCatching {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
        }.getOrElse { raw }
        PublishedPreviewKind.Csv -> raw.lineSequence().take(200).joinToString("\n")
        else -> raw
    }
}

internal fun parseCsvPreview(raw: String, maxRows: Int = 200): CsvPreviewData {
    val records = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val value = StringBuilder()
    var quoted = false
    var index = 0
    while (index < raw.length && records.size <= maxRows) {
        val char = raw[index]
        when {
            char == '"' && quoted && raw.getOrNull(index + 1) == '"' -> {
                value.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                row += value.toString()
                value.clear()
            }
            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && raw.getOrNull(index + 1) == '\n') index++
                row += value.toString()
                value.clear()
                if (row.any(String::isNotEmpty)) records += row
                row = mutableListOf()
            }
            else -> value.append(char)
        }
        index++
    }
    if (value.isNotEmpty() || row.isNotEmpty()) {
        row += value.toString()
        if (row.any(String::isNotEmpty)) records += row
    }
    val headers = records.firstOrNull().orEmpty()
    return CsvPreviewData(headers, records.drop(1).take(maxRows))
}

internal fun parseJsonPreview(raw: String): JsonPreviewNode {
    val trimmed = raw.trim()
    val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
    return jsonPreviewNode(root)
}

private fun jsonPreviewNode(value: Any?): JsonPreviewNode = when (value) {
    is JSONObject -> JsonPreviewNode.ObjectNode(
        value.keys().asSequence().map { key -> key to jsonPreviewNode(value.get(key)) }.toList()
    )
    is JSONArray -> JsonPreviewNode.ArrayNode(
        (0 until value.length()).map { index -> jsonPreviewNode(value.get(index)) }
    )
    JSONObject.NULL, null -> JsonPreviewNode.ValueNode("null", JsonValueType.Null)
    is Boolean -> JsonPreviewNode.ValueNode(value.toString(), JsonValueType.Boolean)
    is Number -> JsonPreviewNode.ValueNode(value.toString(), JsonValueType.Number)
    else -> JsonPreviewNode.ValueNode(value.toString(), JsonValueType.String)
}

class PublishedResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val automationId = intent.getStringExtra(EXTRA_AUTOMATION_ID)
        val projectResultPath = intent.getStringExtra(EXTRA_PROJECT_RESULT_PATH)
        val app = application as PixelPyApp
        val automation = automationId?.let(app.automationRepository::get)
        val file = when {
            automation?.publishedArtifactPath != null -> runCatching {
                AutomationPathValidator.resolvePublished(filesDir, automation.publishedArtifactPath)
            }.getOrNull()
            projectResultPath != null -> runCatching {
                AutomationPathValidator.resolveProjectResult(filesDir, projectResultPath)
            }.getOrNull()
            else -> null
        }?.takeIf(File::isFile)

        setContent {
            MaterialTheme(lightColorScheme(primary = ResultInk, background = ResultPaper, surface = ResultPaper)) {
                PublishedResultScreen(
                    automationName = automation?.name ?: "Ejecución actual",
                    file = file,
                    mimeType = automation?.publishedMimeType,
                    onBack = ::finish,
                    onExternal = { target -> openExternal(target, automation?.publishedMimeType) },
                    onShare = { target -> share(target, automation?.publishedMimeType) },
                )
            }
        }
    }

    private fun uriFor(file: File) =
        FileProvider.getUriForFile(this, "com.pixelpy.editor.files", file)

    private fun openExternal(file: File, mimeType: String?) {
        val uri = uriFor(file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: mimeTypeForFile(file))
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(view, "Abrir ${file.name}")) }
            .onFailure {
                Toast.makeText(this, "No hay otra app compatible con este archivo", Toast.LENGTH_LONG).show()
            }
    }

    private fun share(file: File, mimeType: String?) {
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: mimeTypeForFile(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Compartir ${file.name}"))
    }
}

@Composable
private fun PublishedResultScreen(
    automationName: String,
    file: File?,
    mimeType: String?,
    onBack: () -> Unit,
    onExternal: (File) -> Unit,
    onShare: (File) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ResultPaper)) {
        Row(
            Modifier.fillMaxWidth().background(ResultYellow).statusBarsPadding().border(3.dp, ResultInk).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onBack,
                color = ResultInk,
                contentColor = ResultYellow,
                border = BorderStroke(2.dp, ResultInk),
                modifier = Modifier.height(48.dp),
            ) {
                Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("← VOLVER", fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("RESULTADO PUBLICADO", fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(automationName, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1)
            }
        }

        if (file == null) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Surface(color = Color.White, border = BorderStroke(3.dp, ResultInk)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RESULTADO NO DISPONIBLE", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Ejecuta nuevamente la automatización para publicar una copia válida.")
                    }
                }
            }
            return
        }

        val kind = remember(file.path, file.lastModified()) { previewKind(file) }
        Column(
            Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = ResultBlue, border = BorderStroke(2.dp, ResultInk)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(file.name, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text("${humanFileSize(file.length())} · ${mimeType ?: mimeTypeForFile(file)}", fontSize = 11.sp)
                }
            }
            Surface(Modifier.fillMaxWidth(), color = Color.White, border = BorderStroke(3.dp, ResultInk)) {
                PublishedPreview(file, kind)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onShare(file) }, modifier = Modifier.weight(1f)) {
                    Text("COMPARTIR", fontWeight = FontWeight.Black)
                }
                OutlinedButton(onClick = { onExternal(file) }, modifier = Modifier.weight(1f)) {
                    Text("OTRA APP", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun PublishedPreview(file: File, kind: PublishedPreviewKind) {
    when (kind) {
        PublishedPreviewKind.Json -> JsonPreview(file)
        PublishedPreviewKind.Csv -> CsvPreview(file)
        PublishedPreviewKind.Image -> {
            val bitmap = remember(file.path, file.lastModified()) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxWidth().height(360.dp).padding(10.dp),
                    contentScale = ContentScale.Fit,
                )
            } else PreviewMessage("No se pudo decodificar la imagen.")
        }
        PublishedPreviewKind.External ->
            PreviewMessage("Este formato necesita una aplicación compatible. Puedes compartirlo o abrirlo externamente.")
        PublishedPreviewKind.Text -> {
            var text by remember(file.path, file.lastModified()) { mutableStateOf("Preparando vista previa…") }
            LaunchedEffect(file.path, file.lastModified()) {
                text = withContext(Dispatchers.IO) {
                    runCatching { formatPublishedText(file, kind) }
                        .getOrElse { "No se pudo leer la vista previa: ${it.message}" }
                }
            }
            Text(
                text,
                Modifier.padding(14.dp).horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun CsvPreview(file: File) {
    var data by remember(file.path, file.lastModified()) { mutableStateOf<CsvPreviewData?>(null) }
    var error by remember(file.path, file.lastModified()) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path, file.lastModified()) {
        withContext(Dispatchers.IO) {
            if (file.length() > MAX_PREVIEW_BYTES) {
                error = "La tabla está limitada a 1 MB. Usa OTRA APP para ver el archivo completo."
            } else {
                runCatching { parseCsvPreview(file.readText(Charsets.UTF_8)) }
                    .onSuccess { data = it }
                    .onFailure { error = "No se pudo leer el CSV: ${it.message}" }
            }
        }
    }
    val table = data
    if (table == null) {
        PreviewMessage(error ?: "Preparando tabla…")
        return
    }
    if (table.headers.isEmpty()) {
        PreviewMessage("El CSV no contiene filas.")
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            "${table.rows.size} filas · desliza horizontalmente",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            CsvRow(table.headers, header = true)
            table.rows.forEachIndexed { index, row -> CsvRow(row, shaded = index % 2 == 1) }
        }
    }
}

@Composable
private fun CsvRow(values: List<String>, header: Boolean = false, shaded: Boolean = false) {
    Row(Modifier.background(if (header) ResultYellow else if (shaded) Color(0xFFF1F4F6) else Color.White)) {
        values.forEach { value ->
            Text(
                value.ifEmpty { " " },
                Modifier.width(144.dp).border(1.dp, ResultInk).padding(9.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = if (header) FontWeight.Black else FontWeight.Normal,
                fontSize = 11.sp,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun JsonPreview(file: File) {
    var node by remember(file.path, file.lastModified()) { mutableStateOf<JsonPreviewNode?>(null) }
    var error by remember(file.path, file.lastModified()) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path, file.lastModified()) {
        withContext(Dispatchers.IO) {
            if (file.length() > MAX_PREVIEW_BYTES) {
                error = "El árbol JSON está limitado a 1 MB. Usa OTRA APP para ver el archivo completo."
            } else {
                runCatching { parseJsonPreview(file.readText(Charsets.UTF_8)) }
                    .onSuccess { node = it }
                    .onFailure { error = "JSON inválido: ${it.message}" }
            }
        }
    }
    val root = node
    if (root == null) {
        PreviewMessage(error ?: "Construyendo árbol JSON…")
        return
    }
    Column(Modifier.fillMaxWidth().padding(10.dp)) {
        Text("Toca {…} o […] para plegar cada sección", fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        JsonNodeRow(label = null, node = root, depth = 0, path = "root")
    }
}

@Composable
private fun JsonNodeRow(
    label: String?,
    node: JsonPreviewNode,
    depth: Int,
    path: String,
) {
    val left = (depth * 14).dp
    when (node) {
        is JsonPreviewNode.ObjectNode -> {
            var expanded by remember(path) { mutableStateOf(depth < 2) }
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = left, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▼" else "▶", fontWeight = FontWeight.Black, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                if (label != null) Text("\"$label\": ", color = Color(0xFF8A3FFC), fontFamily = FontFamily.Monospace)
                Text(if (expanded) "{ ${node.values.size} }" else "{…}", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            if (expanded) node.values.forEach { (key, child) ->
                JsonNodeRow(key, child, depth + 1, "$path.$key")
            }
        }
        is JsonPreviewNode.ArrayNode -> {
            var expanded by remember(path) { mutableStateOf(depth < 2) }
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = left, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▼" else "▶", fontWeight = FontWeight.Black, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                if (label != null) Text("\"$label\": ", color = Color(0xFF8A3FFC), fontFamily = FontFamily.Monospace)
                Text(if (expanded) "[ ${node.values.size} ]" else "[…]", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
            if (expanded) node.values.forEachIndexed { index, child ->
                JsonNodeRow("[$index]", child, depth + 1, "$path[$index]")
            }
        }
        is JsonPreviewNode.ValueNode -> {
            val color = when (node.type) {
                JsonValueType.String -> Color(0xFF16853B)
                JsonValueType.Number -> Color(0xFF0066CC)
                JsonValueType.Boolean -> Color(0xFFD04A00)
                JsonValueType.Null -> Color(0xFF77716A)
            }
            Row(Modifier.fillMaxWidth().padding(start = left, top = 3.dp, bottom = 3.dp)) {
                if (label != null) Text("\"$label\": ", color = Color(0xFF8A3FFC), fontFamily = FontFamily.Monospace)
                Text(
                    if (node.type == JsonValueType.String) "\"${node.value}\"" else node.value,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun PreviewMessage(message: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("VISTA PREVIA", fontWeight = FontWeight.Black)
        Spacer(Modifier.height(6.dp))
        Text(message)
    }
}

internal fun humanFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    else -> String.format("%.1f MB", bytes / 1_048_576.0)
}
