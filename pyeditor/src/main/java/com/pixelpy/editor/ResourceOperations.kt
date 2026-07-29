package com.pixelpy.editor

import java.io.File

internal fun resourceFolders(project: File): List<File> =
    project.walkTopDown()
        .filter(File::isDirectory)
        .filter { folder ->
            folder != project &&
                folder.relativeTo(project).invariantSeparatorsPath
                    .split('/')
                    .none { it.startsWith(".") }
        }
        .sortedBy { it.relativeTo(project).invariantSeparatorsPath.lowercase() }
        .toList()

internal fun resourceRelativePath(project: File, file: File): String {
    val root = project.canonicalFile.toPath()
    val candidate = file.canonicalFile.toPath()
    require(candidate.startsWith(root) && candidate != root) {
        "El recurso debe quedar dentro del proyecto"
    }
    return root.relativize(candidate).toString().replace('\\', '/')
}

internal fun safeResourceDestination(project: File, relativeFolder: String, fileName: String): File {
    require(
        fileName.isNotBlank() &&
            fileName != "." &&
            fileName != ".." &&
            '/' !in fileName &&
            '\\' !in fileName &&
            !fileName.startsWith(".")
    ) {
        "Nombre de recurso inválido"
    }
    val folder = relativeFolder.replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)
    require(folder.none { it == "." || it == ".." || it.startsWith(".") }) {
        "Carpeta de recurso inválida"
    }
    val destination = folder.fold(project.canonicalFile) { parent, part -> File(parent, part) }
        .canonicalFile
    require(destination.toPath().startsWith(project.canonicalFile.toPath())) {
        "La carpeta debe quedar dentro del proyecto"
    }
    return File(destination, fileName).canonicalFile.also {
        require(it.toPath().startsWith(project.canonicalFile.toPath())) {
            "El recurso debe quedar dentro del proyecto"
        }
    }
}

internal fun createResourceFolder(project: File, relativeFolder: String): File {
    val parts = relativeFolder.replace('\\', '/').split('/').filter(String::isNotBlank)
    require(parts.isNotEmpty() && parts.none { it == "." || it == ".." || it.startsWith(".") }) {
        "Nombre de carpeta inválido"
    }
    val folder = parts.fold(project.canonicalFile) { parent, part -> File(parent, part) }.canonicalFile
    require(folder.toPath().startsWith(project.canonicalFile.toPath())) {
        "La carpeta debe quedar dentro del proyecto"
    }
    require(folder.mkdirs()) { "La carpeta ya existe o no se pudo crear" }
    return folder
}

internal fun renameResource(project: File, source: File, rawName: String): Pair<String, String> {
    val oldPath = resourceRelativePath(project, source)
    val requested = rawName.trim()
    require(requested.isNotBlank() && '/' !in requested && '\\' !in requested) {
        "Usa solo un nombre, sin carpetas"
    }
    val extension = source.extension
    val name = if (extension.isNotBlank() && !requested.endsWith(".$extension", true)) {
        "$requested.$extension"
    } else requested
    val target = safeResourceDestination(
        project,
        requireNotNull(source.parentFile).relativeTo(project).invariantSeparatorsPath
            .takeUnless { it == "." }.orEmpty(),
        name,
    )
    require(!target.exists()) { "Ya existe $name" }
    require(source.renameTo(target)) { "No se pudo renombrar el recurso" }
    return oldPath to resourceRelativePath(project, target)
}

internal fun moveResources(
    project: File,
    sources: List<File>,
    relativeFolder: String,
): Map<String, String> {
    val targets = sources.associateWith {
        safeResourceDestination(project, relativeFolder, it.name)
    }
    targets.forEach { (source, target) ->
        require(source.canonicalFile != target) { "${source.name} ya está en esa carpeta" }
        require(!target.exists()) { "Ya existe ${source.name} en esa carpeta" }
    }
    targets.values.firstOrNull()?.parentFile?.mkdirs()
    val moved = linkedMapOf<String, String>()
    try {
        targets.forEach { (source, target) ->
            val oldPath = resourceRelativePath(project, source)
            require(source.renameTo(target)) { "No se pudo mover ${source.name}" }
            moved[oldPath] = resourceRelativePath(project, target)
        }
    } catch (error: Throwable) {
        moved.forEach { (oldPath, newPath) ->
            val movedFile = File(project, newPath)
            val original = File(project, oldPath)
            original.parentFile?.mkdirs()
            movedFile.renameTo(original)
        }
        throw error
    }
    return moved
}
