package com.pixelpy.editor

import java.io.File

internal data class ValidatedAutomationPaths(
    val project: File,
    val script: File,
    val highlightedResult: File?,
)

internal object AutomationPathValidator {
    fun validate(
        projectsRoot: File,
        automation: ScriptAutomation,
        requireExisting: Boolean = true,
    ): ValidatedAutomationPaths {
        val root = projectsRoot.canonicalFile
        val project = resolveInside(root, automation.projectPath, "proyecto")
        if (requireExisting && !project.isDirectory) {
            throw IllegalArgumentException("El proyecto de la automatización ya no existe")
        }

        val script = resolveInside(project, automation.scriptPath, "script")
        if (!script.name.endsWith(".py", ignoreCase = true)) {
            throw IllegalArgumentException("El script de la automatización debe ser un archivo .py")
        }
        if (requireExisting && !script.isFile) {
            throw IllegalArgumentException("El script de la automatización ya no existe")
        }

        val result = automation.highlightedResultPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { relative ->
                val candidate = resolveInside(project, relative, "archivo destacado")
                validateHighlightedResult(project, candidate)
                candidate
            }

        return ValidatedAutomationPaths(project, script, result)
    }

    fun resolvePublished(filesDir: File, relativePath: String): File =
        resolveInside(File(filesDir, "published").canonicalFile, relativePath.removePrefix("published/"), "resultado publicado")

    private fun resolveInside(root: File, relative: String, label: String): File {
        val clean = relative.trim().replace('\\', '/')
        if (clean.isBlank() || clean.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(clean)) {
            throw IllegalArgumentException("La ruta de $label debe ser relativa")
        }
        val segments = clean.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalArgumentException("La ruta de $label no es segura")
        }
        val candidate = File(root, segments.joinToString(File.separator)).canonicalFile
        if (candidate != root && !candidate.toPath().startsWith(root.toPath())) {
            throw IllegalArgumentException("La ruta de $label sale del almacenamiento permitido")
        }
        return candidate
    }

    private fun validateHighlightedResult(project: File, file: File) {
        if (!file.toPath().startsWith(project.canonicalFile.toPath())) {
            throw IllegalArgumentException("El archivo destacado debe estar dentro del proyecto")
        }
        val relativeParts = project.canonicalFile.toPath().relativize(file.toPath()).map { it.toString() }
        if (relativeParts.any { it == ".trash" || it == ".versions" }) {
            throw IllegalArgumentException("No se puede publicar contenido de .trash o .versions")
        }
        val name = file.name.lowercase()
        if (name.endsWith(".py")) {
            throw IllegalArgumentException("Un script .py no puede ser el resultado destacado")
        }
        if (name.startsWith(".pixelpy-") || name.endsWith(".tmp") || name.endsWith("~")) {
            throw IllegalArgumentException("No se puede publicar un archivo temporal")
        }
    }
}
