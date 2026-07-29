package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceOperationsTest {
    @Test fun `lists empty folders and ignores private folders`() {
        val project = Files.createTempDirectory("resources").toFile()
        val empty = project.resolve("datos/vacios").apply { mkdirs() }
        project.resolve(".trash").mkdirs()

        val paths = resourceFolders(project).map { resourceRelativePath(project, it) }

        assertTrue("datos" in paths)
        assertTrue(resourceRelativePath(project, empty) in paths)
        assertFalse(paths.any { it.startsWith(".trash") })
    }

    @Test fun `moves nested resource and returns relative path change`() {
        val project = Files.createTempDirectory("resources").toFile()
        val source = project.resolve("result.csv").apply { writeText("latest") }

        val changes = moveResources(project, listOf(source), "datos/2026")

        assertEquals(mapOf("result.csv" to "datos/2026/result.csv"), changes)
        assertEquals("latest", project.resolve("datos/2026/result.csv").readText())
    }

    @Test fun `rename preserves extension and rejects traversal`() {
        val project = Files.createTempDirectory("resources").toFile()
        val source = project.resolve("result.csv").apply { writeText("data") }

        assertEquals(
            "result.csv" to "informe.csv",
            renameResource(project, source, "informe"),
        )
        assertTrue(runCatching {
            safeResourceDestination(project, "../outside", "bad.txt")
        }.isFailure)
        assertTrue(runCatching { createResourceFolder(project, ".private") }.isFailure)
    }

    @Test fun `resource move updates only matching automation result`() {
        val filesDir = Files.createTempDirectory("automation-resources").toFile()
        val repository = AutomationRepository(filesDir)
        val matching = repository.upsert(automation("Proyecto", "result.csv"))
        val unrelated = repository.upsert(automation("Otro", "result.csv"))

        assertEquals(
            1,
            repository.updateHighlightedResourcePath(
                "Proyecto",
                "result.csv",
                "datos/result.csv",
            ),
        )

        assertEquals("datos/result.csv", repository.get(matching.id)?.highlightedResultPath)
        assertEquals("result.csv", repository.get(unrelated.id)?.highlightedResultPath)
    }

    private fun automation(project: String, result: String) = ScriptAutomation(
        name = "$project automation",
        projectPath = project,
        scriptPath = "main.py",
        scheduleType = AutomationScheduleType.Daily,
        highlightedResultPath = result,
    )
}
