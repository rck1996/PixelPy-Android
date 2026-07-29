package com.pixelpy.editor

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionSessionStoreTest {
    @Test
    fun lineDiffKeepsRealAddedAndRemovedLines() {
        val diff = lineDiff("uno\ndos\ntres", "uno\nDOS\ntres\ncuatro")
        assertTrue(diff.any { it.kind == DiffKind.Removed && it.text == "dos" })
        assertTrue(diff.any { it.kind == DiffKind.Added && it.text == "DOS" })
        assertTrue(diff.any { it.kind == DiffKind.Added && it.text == "cuatro" })
    }
    @Test
    fun recordsCopiesPinsAndExportsExecution() {
        val filesDir = createTempDir(prefix = "pixelpy-session-")
        val generated = File(filesDir, "projects/Demo/report.csv").apply {
            parentFile!!.mkdirs()
            writeText("name,value\nA,1\n")
        }
        val store = ExecutionSessionStore(filesDir)
        val session = store.record(
            projectPath = "Demo",
            scriptName = "main.py",
            source = "print('ok')",
            output = "ok",
            success = true,
            startedAtMillis = 1_000,
            finishedAtMillis = 1_750,
            generatedFiles = listOf(generated),
        )

        generated.writeText("changed")
        assertEquals("name,value\nA,1\n", store.artifact(session, session.artifacts.single())!!.readText())
        assertTrue(store.setPinned(session.id, true)!!.pinned)

        val zip = store.export(session, File(filesDir, "session.zip"))
        ZipFile(zip).use {
            assertTrue(it.getEntry("main.py") != null)
            assertTrue(it.getEntry("salida.txt") != null)
            assertTrue(it.getEntry("resultados/report.csv") != null)
        }
    }

    @Test
    fun comparisonCountsAddedRemovedAndUnchangedLines() {
        val comparison = compareTextLines("a\nb\nc", "a\nc\nd")
        assertEquals(1, comparison.added)
        assertEquals(1, comparison.removed)
        assertEquals(2, comparison.unchanged)
        assertFalse(comparison.added == 0 && comparison.removed == 0)
    }
}
