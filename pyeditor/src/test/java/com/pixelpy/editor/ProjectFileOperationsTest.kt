package com.pixelpy.editor

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileOperationsTest {
    @Test
    fun immediateExportContainsLatestEditBeforeDebounce() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-export-latest").toFile()
        val project = root.resolve("active").apply { mkdirs() }
        val current = project.resolve("main.py").apply { writeText("old") }
        val output = root.resolve("active.zip")
        val coordinator = EditorAutosaveCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMillis = 60_000,
        )
        coordinator.registerFile(current, "old")
        coordinator.onEdit(current, "latest edit")

        val exported = exportProjectToZip(
            project,
            project,
            current,
            output,
            flushCurrent = { file ->
                coordinator.flushPendingSave(file)
                coordinator.status(file) != SaveStatus.Error
            },
        )

        assertTrue(exported)
        ZipFile(output).use { zip ->
            val content = zip.getInputStream(zip.getEntry("main.py")).bufferedReader().readText()
            assertEquals("latest edit", content)
        }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun exportingInactiveProjectDoesNotFlushActiveEditor() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-export-inactive").toFile()
        val active = root.resolve("active").apply { mkdirs() }
        val current = active.resolve("main.py").apply { writeText("pending") }
        val other = root.resolve("other").apply { mkdirs() }
        other.resolve("main.py").writeText("other")
        var flushes = 0

        val exported = exportProjectToZip(
            other,
            active,
            current,
            root.resolve("other.zip"),
            flushCurrent = { flushes++; true },
        )

        assertTrue(exported)
        assertEquals(0, flushes)
        root.deleteRecursively()
        Unit
    }
    @Test
    fun currentFilePhysicalReadWaitsForSuccessfulFlush() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-share-flush").toFile()
        val current = root.resolve("main.py").apply { writeText("source") }
        var flushFinished = false

        val ready = ensureFileReadyForPhysicalRead(current, current) {
            kotlinx.coroutines.delay(20)
            flushFinished = true
            true
        }

        assertTrue(ready)
        assertTrue(flushFinished)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun failedFlushBlocksCurrentFilePhysicalRead() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-share-error").toFile()
        val current = root.resolve("main.py").apply { writeText("source") }

        val ready = ensureFileReadyForPhysicalRead(current, current) { false }

        assertEquals(false, ready)
        root.deleteRecursively()
        Unit
    }

}
