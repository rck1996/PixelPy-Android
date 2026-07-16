package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSessionStoreTest {
    @Test
    fun restoresOnlyExistingPathsInsideProjectsRoot() {
        val root = Files.createTempDirectory("pixelpy-session").toFile()
        val fallbackProject = root.resolve("Default").apply { mkdirs() }
        val fallbackFile = fallbackProject.resolve("main.py").apply { writeText("fallback") }
        val project = root.resolve("Second").apply { mkdirs() }
        val file = project.resolve("selected.py").apply { writeText("print('ok')") }

        val resolved = EditorSessionResolver.resolve(
            root,
            fallbackProject,
            fallbackFile,
            StoredEditorSession(projectId = "Second", fileId = "selected.py", tab = "Projects"),
        )

        assertEquals(project.canonicalFile, resolved.project)
        assertEquals(file.canonicalFile, resolved.file)
        assertEquals("Projects", resolved.tab)
        root.deleteRecursively()
    }

    @Test
    fun invalidOrMissingPathsFallBackWithoutCreatingFiles() {
        val root = Files.createTempDirectory("pixelpy-session-fallback").toFile()
        val fallbackProject = root.resolve("Default").apply { mkdirs() }
        val fallbackFile = fallbackProject.resolve("main.py").apply { writeText("fallback") }
        val outside = Files.createTempFile("outside", ".py").toFile()

        val resolved = EditorSessionResolver.resolve(
            root,
            fallbackProject,
            fallbackFile,
            StoredEditorSession(projectId = "..", fileId = outside.absolutePath),
        )

        assertEquals(fallbackProject.canonicalFile, resolved.project)
        assertEquals(fallbackFile.canonicalFile, resolved.file)
        assertEquals(0, root.walkTopDown().count { it.canonicalFile == outside.canonicalFile })
        outside.delete()
        root.deleteRecursively()
    }

    @Test
    fun selectionIsClampedAndOldVersionsUseSafeDefaults() {
        assertEquals(0 to 5, clampSelection(-4, 20, 5))
        val root = Files.createTempDirectory("pixelpy-session-version").toFile()
        val project = root.resolve("Default").apply { mkdirs() }
        val file = project.resolve("main.py").apply { writeText("12345") }

        val resolved = EditorSessionResolver.resolve(
            root,
            project,
            file,
            StoredEditorSession(version = 0, selectionStart = 4, selectionEnd = 4, tab = "Console"),
        )

        assertEquals("Editor", resolved.tab)
        assertEquals(0, resolved.selectionStart)
        root.deleteRecursively()
    }

    @Test
    fun emptyRestoredProjectFallsBackAsAConsistentPair() {
        val root = Files.createTempDirectory("pixelpy-empty-project").toFile()
        val fallbackProject = root.resolve("Default").apply { mkdirs() }
        val fallbackFile = fallbackProject.resolve("main.py").apply { writeText("fallback") }
        root.resolve("Empty").mkdirs()

        val resolved = EditorSessionResolver.resolve(
            root,
            fallbackProject,
            fallbackFile,
            StoredEditorSession(projectId = "Empty", fileId = "missing.py"),
        )

        assertEquals(fallbackProject.canonicalFile, resolved.project)
        assertEquals(fallbackFile.canonicalFile, resolved.file)
        root.deleteRecursively()
    }
}
