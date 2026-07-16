package com.pixelpy.editor

import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorAutosaveCoordinatorTest {
    @Test
    fun filesNeverReceiveEachOthersContentAndFlushKeepsLatestVersion() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-autosave").toFile()
        val fileA = root.resolve("a.py").apply { writeText("A0") }
        val fileB = root.resolve("b.py").apply { writeText("B0") }
        val coordinator = EditorAutosaveCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMillis = 100,
        )
        coordinator.registerFile(fileA, "A0")
        coordinator.registerFile(fileB, "B0")

        coordinator.onEdit(fileA, "A1")
        coordinator.onEdit(fileA, "A2")
        coordinator.onEdit(fileB, "B1")
        coordinator.flushPendingSave()

        assertEquals("A2", fileA.readText())
        assertEquals("B1", fileB.readText())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun unchangedContentDoesNotWriteAgain() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-autosave-unchanged").toFile()
        val file = root.resolve("main.py").apply { writeText("same") }
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = EditorAutosaveCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMillis = 10,
            writer = FileContentWriter { target, content ->
                writes += content
                target.writeText(content)
            },
        )
        coordinator.registerFile(file, "same")

        coordinator.onEdit(file, "same")
        coordinator.flushPendingSave(file)
        coordinator.onEdit(file, "changed")
        coordinator.flushPendingSave(file)
        coordinator.onEdit(file, "changed")
        coordinator.flushPendingSave(file)

        assertEquals(listOf("changed"), writes)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun debouncedOlderVersionCannotOverwriteNewerVersion() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-autosave-version").toFile()
        val file = root.resolve("main.py").apply { writeText("v0") }
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = EditorAutosaveCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMillis = 40,
            writer = FileContentWriter { target, content ->
                writes += content
                target.writeText(content)
            },
        )
        coordinator.registerFile(file, "v0")

        coordinator.onEdit(file, "v1")
        delay(10)
        coordinator.onEdit(file, "v2")
        coordinator.flushPendingSave(file)

        assertEquals("v2", file.readText())
        assertEquals(listOf("v2"), writes)
        root.deleteRecursively()
        Unit
    }
    @Test
    fun saveErrorBlocksTransitionUntilSuccessfulRetry() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-autosave-retry").toFile()
        val file = root.resolve("main.py").apply { writeText("old") }
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val coordinator = EditorAutosaveCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMillis = 60_000,
            writer = FileContentWriter { target, content ->
                if (attempts.incrementAndGet() == 1) error("injected write failure")
                writeUtf8Atomically(target, content)
            },
        )
        coordinator.registerFile(file, "old")
        coordinator.onEdit(file, "new")
        var transitionApplied = false

        coordinator.flushPendingSave(file)
        if (coordinator.status(file) != SaveStatus.Error) transitionApplied = true

        assertEquals(false, transitionApplied)
        assertEquals(SaveStatus.Error, coordinator.status(file))
        assertEquals("old", file.readText())

        coordinator.flushPendingSave(file)
        if (coordinator.status(file) != SaveStatus.Error) transitionApplied = true

        assertEquals(true, transitionApplied)
        assertEquals(SaveStatus.Saved, coordinator.status(file))
        assertEquals("new", file.readText())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun inFlightOldVersionCannotFinallyReplaceNewVersion() = runBlocking {
        val root = Files.createTempDirectory("pixelpy-autosave-inflight").toFile()
        val file = root.resolve("main.py").apply { writeText("v0") }
        val oldWriteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseOldWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        val coordinator = EditorAutosaveCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            debounceMillis = 0,
            writer = FileContentWriter { target, content ->
                if (content == "v1") {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        oldWriteStarted.complete(Unit)
                        releaseOldWrite.await()
                        writeUtf8Atomically(target, content)
                    }
                } else {
                    writeUtf8Atomically(target, content)
                }
            },
        )
        coordinator.registerFile(file, "v0")

        coordinator.onEdit(file, "v1")
        oldWriteStarted.await()
        coordinator.onEdit(file, "v2")
        releaseOldWrite.complete(Unit)
        coordinator.flushPendingSave(file)

        assertEquals("v2", file.readText())
        assertEquals(SaveStatus.Saved, coordinator.status(file))
        root.deleteRecursively()
        Unit
    }

}
