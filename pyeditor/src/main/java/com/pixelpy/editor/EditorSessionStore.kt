package com.pixelpy.editor

import android.content.Context
import java.io.File

internal data class StoredEditorSession(
    val version: Int = CURRENT_SESSION_VERSION,
    val projectId: String? = null,
    val fileId: String? = null,
    val tab: String? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
)

internal data class ResolvedEditorSession(
    val project: File,
    val file: File,
    val tab: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

internal const val CURRENT_SESSION_VERSION = 1

internal class EditorSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("pixelpy_session", Context.MODE_PRIVATE)

    fun load(): StoredEditorSession = StoredEditorSession(
        version = preferences.getInt("version", CURRENT_SESSION_VERSION),
        projectId = preferences.getString("project_id", null),
        fileId = preferences.getString("file_id", null),
        tab = preferences.getString("tab", null),
        selectionStart = preferences.getInt("selection_start", 0),
        selectionEnd = preferences.getInt("selection_end", 0),
    )

    fun save(
        projectsRoot: File,
        project: File,
        file: File,
        tab: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val projectId = relativeIdentifier(projectsRoot, project) ?: return
        val fileId = relativeIdentifier(project, file) ?: return
        preferences.edit()
            .putInt("version", CURRENT_SESSION_VERSION)
            .putString("project_id", projectId)
            .putString("file_id", fileId)
            .putString("tab", tab)
            .putInt("selection_start", selectionStart)
            .putInt("selection_end", selectionEnd)
            .apply()
    }

    private fun relativeIdentifier(parent: File, child: File): String? {
        val root = parent.canonicalFile
        val candidate = child.canonicalFile
        if (!candidate.isInside(root)) return null
        return candidate.relativeTo(root).invariantSeparatorsPath
    }
}

internal object EditorSessionResolver {
    fun resolve(
        projectsRoot: File,
        fallbackProject: File,
        fallbackFile: File,
        stored: StoredEditorSession,
    ): ResolvedEditorSession {
        if (stored.version != CURRENT_SESSION_VERSION) {
            return fallback(fallbackProject, fallbackFile)
        }

        val root = projectsRoot.canonicalFile
        val restoredProject = stored.projectId
            ?.let { safeChild(root, it) }
            ?.takeIf { it.isDirectory && it.parentFile?.canonicalFile == root }
        val candidateProject = restoredProject ?: fallbackProject.canonicalFile
        val restoredFile = stored.fileId
            ?.let { safeChild(candidateProject, it) }
            ?.takeIf { it.isFile && it.extension == "py" && it.isInside(candidateProject) }
        val candidateFile = restoredFile
            ?: candidateProject.listFiles { candidate -> candidate.isFile && candidate.extension == "py" }
                ?.sortedBy { it.name }
                ?.firstOrNull()
        val project = if (candidateFile == null) fallbackProject.canonicalFile else candidateProject
        val file = candidateFile ?: fallbackFile.canonicalFile
        val contentLength = runCatching { file.readText().length }.getOrDefault(0)
        val selection = clampSelection(stored.selectionStart, stored.selectionEnd, contentLength)
        val tab = stored.tab?.takeIf { candidate ->
            candidate in setOf("Projects", "Editor", "Repl", "Console")
        } ?: "Editor"
        return ResolvedEditorSession(project, file, tab, selection.first, selection.second)
    }

    private fun fallback(project: File, file: File) = ResolvedEditorSession(
        project.canonicalFile,
        file.canonicalFile,
        "Editor",
        0,
        0,
    )

    private fun safeChild(parent: File, identifier: String): File? = runCatching {
        File(parent, identifier).canonicalFile.takeIf { it.isInside(parent.canonicalFile) }
    }.getOrNull()
}

internal fun clampSelection(start: Int, end: Int, contentLength: Int): Pair<Int, Int> {
    val safeLength = contentLength.coerceAtLeast(0)
    return start.coerceIn(0, safeLength) to end.coerceIn(0, safeLength)
}

private fun File.isInside(parent: File): Boolean {
    val parentPath = parent.canonicalFile.path
    val path = canonicalFile.path
    return path == parentPath || path.startsWith(parentPath + File.separator)
}
