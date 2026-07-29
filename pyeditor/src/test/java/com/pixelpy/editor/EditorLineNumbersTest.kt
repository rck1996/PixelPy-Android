package com.pixelpy.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLineNumbersTest {
    @Test
    fun lineNumbersMatchEmptyTrailingAndMultilineSources() {
        assertEquals("1", lineNumberText(""))
        assertEquals("1\n2", lineNumberText("print('ok')\n"))
        assertEquals("1\n2\n3", lineNumberText("a = 1\nb = 2\nprint(a + b)"))
    }
}
