package com.pixelpy.editor

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal data class ExecutionSession(
    val id: String,
    val projectPath: String,
    val scriptName: String,
    val source: String,
    val output: String,
    val success: Boolean,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val artifacts: List<String>,
    val pinned: Boolean = false,
)

internal class ExecutionSessionStore(filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val root = File(filesDir, "execution-sessions").apply { mkdirs() }

    @Synchronized
    fun record(
        projectPath: String,
        scriptName: String,
        source: String,
        output: String,
        success: Boolean,
        startedAtMillis: Long,
        finishedAtMillis: Long,
        generatedFiles: List<File>,
    ): ExecutionSession {
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        val artifactsDir = File(directory, "artifacts").apply { mkdirs() }
        val artifacts = generatedFiles.filter(File::isFile).mapNotNull { sourceFile ->
            runCatching {
                val destination = uniqueDestination(artifactsDir, sourceFile.name)
                sourceFile.inputStream().use { input ->
                    writeBytesAtomically(destination, input.readBytes())
                }
                destination.relativeTo(directory).invariantSeparatorsPath
            }.getOrNull()
        }
        val session = ExecutionSession(
            id = id,
            projectPath = projectPath,
            scriptName = scriptName,
            source = source,
            output = output.take(100_000),
            success = success,
            startedAtMillis = startedAtMillis,
            durationMillis = (finishedAtMillis - startedAtMillis).coerceAtLeast(0),
            artifacts = artifacts,
        )
        writeSession(directory, session)
        cleanup()
        return session
    }

    @Synchronized
    fun list(): List<ExecutionSession> = root.listFiles(File::isDirectory).orEmpty()
        .mapNotNull { directory -> readSession(directory) }
        .sortedByDescending(ExecutionSession::startedAtMillis)

    @Synchronized
    fun get(id: String): ExecutionSession? = safeDirectory(id)?.let(::readSession)

    @Synchronized
    fun setPinned(id: String, pinned: Boolean): ExecutionSession? {
        val directory = safeDirectory(id) ?: return null
        val updated = readSession(directory)?.copy(pinned = pinned) ?: return null
        writeSession(directory, updated)
        return updated
    }

    @Synchronized
    fun delete(id: String): Boolean = safeDirectory(id)?.deleteRecursively() == true

    fun artifact(session: ExecutionSession, relativePath: String): File? {
        val directory = safeDirectory(session.id) ?: return null
        val file = File(directory, relativePath).canonicalFile
        return file.takeIf { it.isFile && it.toPath().startsWith(directory.canonicalFile.toPath()) }
    }

    fun export(session: ExecutionSession, destination: File): File {
        val directory = safeDirectory(session.id) ?: error("La ejecución ya no existe")
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(session.scriptName))
                zip.write(session.source.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("salida.txt"))
                zip.write(session.output.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                session.artifacts.forEach { relative ->
                    artifact(session, relative)?.let { file ->
                        zip.putNextEntry(ZipEntry("resultados/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            replaceAtomically(temporary, destination)
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun cleanup(maxUnpinned: Int = 20) {
        list().filterNot(ExecutionSession::pinned).drop(maxUnpinned).forEach { delete(it.id) }
    }

    private fun safeDirectory(id: String): File? {
        if (runCatching { UUID.fromString(id) }.isFailure) return null
        val directory = File(root, id).canonicalFile
        return directory.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) && it.isDirectory }
    }

    private fun writeSession(directory: File, session: ExecutionSession) {
        val json = JSONObject()
            .put("version", 1)
            .put("id", session.id)
            .put("projectPath", session.projectPath)
            .put("scriptName", session.scriptName)
            .put("source", session.source)
            .put("output", session.output)
            .put("success", session.success)
            .put("startedAtMillis", session.startedAtMillis)
            .put("durationMillis", session.durationMillis)
            .put("artifacts", JSONArray(session.artifacts))
            .put("pinned", session.pinned)
        writeUtf8Atomically(File(directory, "session.json"), json.toString())
    }

    private fun readSession(directory: File): ExecutionSession? = runCatching {
        val json = JSONObject(File(directory, "session.json").readText(Charsets.UTF_8))
        ExecutionSession(
            id = json.getString("id"),
            projectPath = json.getString("projectPath"),
            scriptName = json.getString("scriptName"),
            source = json.getString("source"),
            output = json.getString("output"),
            success = json.getBoolean("success"),
            startedAtMillis = json.getLong("startedAtMillis"),
            durationMillis = json.getLong("durationMillis"),
            artifacts = json.getJSONArray("artifacts").let { array ->
                (0 until array.length()).map(array::getString)
            },
            pinned = json.optBoolean("pinned"),
        )
    }.getOrNull()

    private fun uniqueDestination(directory: File, requestedName: String): File {
        val safe = requestedName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "resultado" }
        var destination = File(directory, safe)
        var number = 2
        while (destination.exists()) {
            destination = File(directory, "${destination.nameWithoutExtension}_$number.${destination.extension}")
            number++
        }
        return destination
    }
}

internal data class TextComparison(val added: Int, val removed: Int, val unchanged: Int)
internal enum class DiffKind { Added, Removed, Unchanged }
internal data class DiffLine(val kind: DiffKind, val text: String)

internal fun compareTextLines(previous: String, current: String): TextComparison {
    val oldCounts = previous.lines().groupingBy(String::trimEnd).eachCount().toMutableMap()
    var added = 0
    var unchanged = 0
    current.lines().map(String::trimEnd).forEach { line ->
        val remaining = oldCounts[line] ?: 0
        if (remaining > 0) {
            unchanged++
            oldCounts[line] = remaining - 1
        } else added++
    }
    return TextComparison(added, oldCounts.values.sum(), unchanged)
}

internal fun lineDiff(previous: String, current: String): List<DiffLine> {
    val old = previous.lines()
    val fresh = current.lines()
    val matrix = Array(old.size + 1) { IntArray(fresh.size + 1) }
    for (i in old.indices.reversed()) for (j in fresh.indices.reversed()) {
        matrix[i][j] = if (old[i] == fresh[j]) matrix[i + 1][j + 1] + 1
        else maxOf(matrix[i + 1][j], matrix[i][j + 1])
    }
    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < old.size || j < fresh.size) {
        when {
            i < old.size && j < fresh.size && old[i] == fresh[j] -> { result += DiffLine(DiffKind.Unchanged, old[i]); i++; j++ }
            j < fresh.size && (i == old.size || matrix[i][j + 1] >= matrix[i + 1][j]) -> { result += DiffLine(DiffKind.Added, fresh[j]); j++ }
            else -> { result += DiffLine(DiffKind.Removed, old[i]); i++ }
        }
    }
    return result
}

private fun writeBytesAtomically(destination: File, content: ByteArray) {
    val parent = requireNotNull(destination.parentFile)
    parent.mkdirs()
    val temporary = Files.createTempFile(parent.toPath(), ".pixelpy-session-", ".tmp")
    try {
        Files.write(temporary, content)
        replaceAtomically(temporary.toFile(), destination)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun replaceAtomically(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
