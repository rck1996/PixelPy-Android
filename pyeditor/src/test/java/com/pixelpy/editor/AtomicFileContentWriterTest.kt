package com.pixelpy.editor

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AtomicFileContentWriterTest {
    @Test
    fun successfulWriteReplacesOriginalContent() {
        val root = Files.createTempDirectory("pixelpy-atomic-success").toFile()
        val file = root.resolve("main.py").apply { writeText("old") }

        writeUtf8Atomically(file, "new content")

        assertEquals("new content", file.readText())
        root.deleteRecursively()
    }

    @Test
    fun failureBeforeReplacementKeepsOriginalContent() {
        val root = Files.createTempDirectory("pixelpy-atomic-failure").toFile()
        val file = root.resolve("main.py").apply { writeText("original") }

        try {
            writeUtf8Atomically(file, "incomplete") { throw IOException("injected failure") }
            fail("The injected failure should escape")
        } catch (_: IOException) {
            assertEquals("original", file.readText())
        }

        root.deleteRecursively()
    }

    @Test
    fun temporaryFilesAreRemovedAfterSuccessAndFailure() {
        val root = Files.createTempDirectory("pixelpy-atomic-cleanup").toFile()
        val file = root.resolve("main.py").apply { writeText("v0") }

        writeUtf8Atomically(file, "v1")
        runCatching { writeUtf8Atomically(file, "v2") { error("stop") } }

        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertEquals("v1", file.readText())
        root.deleteRecursively()
    }
}
