package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishedArtifactPublisherTest {
    @Test
    fun publishesAndSecondPublicationReplacesFirst() {
        val filesDir = Files.createTempDirectory("pixelpy-published").toFile()
        val source = filesDir.resolve("projects/Demo/report.txt").apply { parentFile.mkdirs(); writeText("first") }
        val publisher = PublishedArtifactPublisher(filesDir)
        val first = publisher.publish(automation(), source)
        assertEquals("first", filesDir.resolve(first.relativePath).readText())
        source.writeText("second")
        val second = publisher.publish(automation().copy(publishedArtifactPath = first.relativePath), source)
        assertEquals("second", filesDir.resolve(second.relativePath).readText())
        assertEquals("text/plain", second.mimeType)
    }

    @Test
    fun failedReplacementPreservesPublishedCopyAndDeletesTemporary() {
        val filesDir = Files.createTempDirectory("pixelpy-published").toFile()
        val source = filesDir.resolve("projects/Demo/report.txt").apply { parentFile.mkdirs(); writeText("stable") }
        val publisher = PublishedArtifactPublisher(filesDir)
        val first = publisher.publish(automation(), source)
        source.writeText("broken update")
        assertThrows(IllegalStateException::class.java) {
            publisher.publish(automation().copy(publishedArtifactPath = first.relativePath), source) {
                error("simulated failure")
            }
        }
        assertEquals("stable", filesDir.resolve(first.relativePath).readText())
        assertFalse(filesDir.resolve("published/${automation().id}").listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun missingSourceDoesNotTouchPreviousResult() {
        val filesDir = Files.createTempDirectory("pixelpy-published").toFile()
        val source = filesDir.resolve("projects/Demo/report.txt").apply { parentFile.mkdirs(); writeText("stable") }
        val publisher = PublishedArtifactPublisher(filesDir)
        val first = publisher.publish(automation(), source)
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publish(automation().copy(publishedArtifactPath = first.relativePath), source.resolveSibling("missing.txt"))
        }
        assertTrue(filesDir.resolve(first.relativePath).isFile)
        assertEquals("stable", filesDir.resolve(first.relativePath).readText())
    }

    private fun automation() = ScriptAutomation(
        id = "a7be4f60-d575-4ef1-85da-6b4e9c9ca444",
        name = "Reporte",
        projectPath = "Demo",
        scriptPath = "main.py",
        scheduleType = AutomationScheduleType.Daily,
        highlightedResultPath = "report.txt",
    )
}
