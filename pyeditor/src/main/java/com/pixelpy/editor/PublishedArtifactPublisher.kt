package com.pixelpy.editor

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal data class PublishedArtifact(
    val relativePath: String,
    val updatedAtMillis: Long,
    val sizeBytes: Long,
    val mimeType: String,
)

internal class PublishedArtifactPublisher(private val filesDir: File) {
    fun publish(
        automation: ScriptAutomation,
        source: File,
        beforeReplace: () -> Unit = {},
    ): PublishedArtifact {
        UUID.fromString(automation.id)
        if (!source.isFile) {
            throw IllegalArgumentException("El archivo de resultado configurado no fue generado")
        }

        val publishedRoot = File(filesDir, "published").canonicalFile
        val destinationDir = File(publishedRoot, automation.id).canonicalFile
        if (!destinationDir.toPath().startsWith(publishedRoot.toPath())) {
            throw IllegalArgumentException("El identificador de automatización no es seguro")
        }
        Files.createDirectories(destinationDir.toPath())
        val destination = File(destinationDir, source.name).canonicalFile
        if (destination.parentFile != destinationDir) {
            throw IllegalArgumentException("El nombre del resultado no es seguro")
        }

        val temporary = Files.createTempFile(destinationDir.toPath(), ".pixelpy-publish-", ".tmp")
        try {
            Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            beforeReplace()
            try {
                Files.move(
                    temporary,
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            automation.publishedArtifactPath?.let { previousRelative ->
                runCatching { AutomationPathValidator.resolvePublished(filesDir, previousRelative) }
                    .getOrNull()
                    ?.takeIf { it.canonicalFile.parentFile == destinationDir && it.canonicalFile != destination }
                    ?.delete()
            }

            val now = System.currentTimeMillis()
            return PublishedArtifact(
                relativePath = destination.relativeTo(filesDir.canonicalFile).invariantSeparatorsPath,
                updatedAtMillis = now,
                sizeBytes = destination.length(),
                mimeType = mimeTypeForFile(destination),
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
internal fun mimeTypeForFile(file: File): String = when (file.extension.lowercase()) {
    "csv" -> "text/csv"
    "txt", "log", "md" -> "text/plain"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "pdf" -> "application/pdf"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "xls" -> "application/vnd.ms-excel"
    "zip" -> "application/zip"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}
