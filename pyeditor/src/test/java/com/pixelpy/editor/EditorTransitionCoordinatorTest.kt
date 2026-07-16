package com.pixelpy.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorTransitionCoordinatorTest {
    @Test
    fun rapidFileRequestsFinishOnLastSelectedFile() = runBlocking {
        val coordinator = EditorTransitionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        var selected = "A.py"

        val openB = coordinator.launch(this) {
            releaseFirst.await()
            selected = "B.py"
        }
        val openC = coordinator.launch(this) { selected = "C.py" }
        releaseFirst.complete(Unit)
        joinAll(openB, openC)

        assertEquals("C.py", selected)
    }

    @Test
    fun rapidProjectRequestsFinishOnLastSelectedProject() = runBlocking {
        val coordinator = EditorTransitionCoordinator()
        val releaseFirst = CompletableDeferred<Unit>()
        var project = "Project A"

        val openB = coordinator.launch(this) {
            releaseFirst.await()
            project = "Project B"
        }
        val openC = coordinator.launch(this) { project = "Project C" }
        releaseFirst.complete(Unit)
        joinAll(openB, openC)

        assertEquals("Project C", project)
    }

    @Test
    fun executionCapturesFileAfterQueuedNavigation() = runBlocking {
        val coordinator = EditorTransitionCoordinator()
        val releaseNavigation = CompletableDeferred<Unit>()
        var current = "A.py"
        var executed: String? = null

        val navigation = coordinator.launch(this) {
            releaseNavigation.await()
            current = "B.py"
        }
        val execution = coordinator.launch(this) { executed = current }
        releaseNavigation.complete(Unit)
        joinAll(navigation, execution)

        assertEquals("B.py", executed)
    }
}
