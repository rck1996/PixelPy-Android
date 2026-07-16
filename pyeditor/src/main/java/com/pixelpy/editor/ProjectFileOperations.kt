package com.pixelpy.editor

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ensureFileReadyForPhysicalRead(
    file: File,
    currentFile: File,
    flushCurrent: suspend (File) -> Boolean,
): Boolean =
    file.canonicalFile != currentFile.canonicalFile || flushCurrent(currentFile)

internal suspend fun exportProjectToZip(
    project: File,
    activeProject: File,
    currentFile: File,
    output: File,
    flushCurrent: suspend (File) -> Boolean,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean {
    if (project.canonicalFile == activeProject.canonicalFile && !flushCurrent(currentFile)) {
        return false
    }
    withContext(ioDispatcher) { zipProject(project, output) }
    return true
}

internal fun zipProject(project: File, output: File) {
    ZipOutputStream(output.outputStream().buffered()).use { zip ->
        project.walkTopDown()
            .filter { it.isFile && !it.relativeTo(project).path.startsWith(".") }
            .forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(project).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        zip.putNextEntry(ZipEntry("requirements.txt"))
        zip.write(
            ("requests==2.34.2\nbeautifulsoup4==4.15.0\n" +
                "openpyxl==3.1.5\ndefusedxml==0.7.1\n").toByteArray(),
        )
        zip.closeEntry()
    }
}
