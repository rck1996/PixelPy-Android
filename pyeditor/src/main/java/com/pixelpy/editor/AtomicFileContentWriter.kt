package com.pixelpy.editor

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal object AtomicFileContentWriter : FileContentWriter {
    override suspend fun write(file: File, content: String) {
        writeUtf8Atomically(file, content)
    }
}

internal fun writeUtf8Atomically(
    file: File,
    content: String,
    beforeReplace: () -> Unit = {},
) {
    val target = file.canonicalFile.toPath()
    val parent = requireNotNull(target.parent) { "El archivo debe tener un directorio padre" }
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".pixelpy-${file.name}-", ".tmp")

    try {
        val bytes = ByteBuffer.wrap(content.toByteArray(StandardCharsets.UTF_8))
        FileChannel.open(
            temporary,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }

        beforeReplace()
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
