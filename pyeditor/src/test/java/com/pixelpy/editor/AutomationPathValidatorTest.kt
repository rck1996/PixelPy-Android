package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationPathValidatorTest {
    @Test
    fun acceptsExistingProjectScriptAndResult() {
        val root = Files.createTempDirectory("pixelpy-paths").toFile()
        val project = root.resolve("Demo").apply { mkdirs() }
        project.resolve("main.py").writeText("print('ok')")
        project.resolve("result.xlsx").writeText("data")
        val paths = AutomationPathValidator.validate(root, automation(result = "result.xlsx"))
        assertEquals(project.resolve("result.xlsx").canonicalFile, paths.highlightedResult)
    }

    @Test
    fun deletedProjectAndScriptAreReported() {
        val root = Files.createTempDirectory("pixelpy-paths").toFile()
        val missingProject = assertThrows(IllegalArgumentException::class.java) {
            AutomationPathValidator.validate(root, automation())
        }
        assertEquals("El proyecto de la automatización ya no existe", missingProject.message)
        root.resolve("Demo").mkdirs()
        val missingScript = assertThrows(IllegalArgumentException::class.java) {
            AutomationPathValidator.validate(root, automation())
        }
        assertEquals("El script de la automatización ya no existe", missingScript.message)
    }

    @Test
    fun traversalAndForbiddenHighlightedFilesAreRejected() {
        val root = Files.createTempDirectory("pixelpy-paths").toFile()
        val project = root.resolve("Demo").apply { mkdirs() }
        project.resolve("main.py").writeText("pass")
        assertThrows(IllegalArgumentException::class.java) {
            AutomationPathValidator.validate(root, automation(script = "../outside.py"), requireExisting = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationPathValidator.validate(root, automation(result = "../outside.xlsx"), requireExisting = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationPathValidator.validate(root, automation(result = ".versions/old.xlsx"), requireExisting = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomationPathValidator.validate(root, automation(result = "copy.py"), requireExisting = false)
        }
    }

    private fun automation(script: String = "main.py", result: String? = null) = ScriptAutomation(
        name = "Demo",
        projectPath = "Demo",
        scriptPath = script,
        scheduleType = AutomationScheduleType.Daily,
        highlightedResultPath = result,
    )
}
