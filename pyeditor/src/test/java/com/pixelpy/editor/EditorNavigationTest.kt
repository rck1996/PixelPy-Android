package com.pixelpy.editor

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditorNavigationTest {
    @Test
    fun goToLineReadsCurrentFileInsteadOfLastRunSnapshot() {
        val file = Files.createTempFile("pixelpy-navigation", ".py").toFile()
        try {
            val lastRunSource = "old = True\nraise ValueError()"
            val editedSource = "header = 'new'\nold = False\nraise ValueError()"
            file.writeText(editedSource)

            val value = editorValueAtLine(file, 3)

            assertEquals(editedSource, value.text)
            assertEquals(editedSource.indexOf("raise"), value.selection.start)
            assertFalse(value.text == lastRunSource)
        } finally {
            file.delete()
        }
    }
}
