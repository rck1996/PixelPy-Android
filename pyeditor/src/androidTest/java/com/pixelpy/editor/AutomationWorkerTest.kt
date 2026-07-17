package com.pixelpy.editor

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationWorkerTest {
    private lateinit var context: Context
    private lateinit var app: PixelPyApp
    private val createdIds = mutableListOf<String>()
    private val createdProjects = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as PixelPyApp
    }

    @After
    fun cleanUp() {
        createdIds.forEach { app.automationScheduler.delete(it) }
        createdProjects.forEach { it.deleteRecursively() }
    }

    @Test
    fun workerPublishesOpensReplacesAndPreservesLastGoodResult() = runBlocking {
        val id = UUID.randomUUID().toString().also(createdIds::add)
        val project = File(app.projectsRoot, "Automation-$id").apply { mkdirs() }.also(createdProjects::add)
        val script = File(project, "main.py")
        script.writeText("from pathlib import Path\nPath('result.txt').write_text('first', encoding='utf-8')\nprint('first run')\n")
        app.automationRepository.upsert(automation(id, project.name, result = "result.txt"))

        assertTrue(runWorker(id) is ListenableWorker.Result.Success)
        val first = requireNotNull(app.automationRepository.get(id))
        val published = AutomationPathValidator.resolvePublished(context.filesDir, requireNotNull(first.publishedArtifactPath))
        assertEquals("first", published.readText())
        val uri = FileProvider.getUriForFile(context, "com.pixelpy.editor.files", published)
        assertEquals("content", uri.scheme)
        assertEquals("first", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })

        script.writeText("from pathlib import Path\nPath('result.txt').write_text('second', encoding='utf-8')\nprint('second run')\n")
        assertTrue(runWorker(id) is ListenableWorker.Result.Success)
        assertEquals("second", published.readText())

        script.writeText("raise RuntimeError('forced failure')\n")
        assertTrue(runWorker(id) is ListenableWorker.Result.Success)
        val failed = requireNotNull(app.automationRepository.get(id))
        assertEquals(AutomationRunStatus.Error, failed.lastStatus)
        assertEquals("second", published.readText())
    }

    @Test
    fun successfulRunWithoutUpdatingConfiguredResultPreservesLastPublication() = runBlocking {
        val id = UUID.randomUUID().toString().also(createdIds::add)
        val project = File(app.projectsRoot, "Automation-$id").apply { mkdirs() }.also(createdProjects::add)
        val script = File(project, "main.py")
        val result = File(project, "result.txt")
        script.writeText(
            "from pathlib import Path\n" +
                "Path('result.txt').write_text('first', encoding='utf-8')\n" +
                "print('first run')\n"
        )
        app.automationRepository.upsert(automation(id, project.name, result = "result.txt"))

        assertTrue(runWorker(id) is ListenableWorker.Result.Success)
        val first = requireNotNull(app.automationRepository.get(id))
        val firstPublishedAt = requireNotNull(first.publishedAtMillis)
        val published = AutomationPathValidator.resolvePublished(
            context.filesDir,
            requireNotNull(first.publishedArtifactPath),
        )
        assertEquals("first", published.readText())

        script.writeText("print('ok')\n")
        assertTrue(runWorker(id) is ListenableWorker.Result.Success)

        val stale = requireNotNull(app.automationRepository.get(id))
        assertEquals(AutomationRunStatus.Error, stale.lastStatus)
        assertEquals("first", result.readText())
        assertEquals("first", published.readText())
        assertEquals(first.publishedArtifactPath, stale.publishedArtifactPath)
        assertEquals(firstPublishedAt, stale.publishedAtMillis)
        assertEquals(first.publishedSizeBytes, stale.publishedSizeBytes)
        assertEquals(first.publishedMimeType, stale.publishedMimeType)
        assertTrue(stale.summary.contains(AUTOMATION_RESULT_NOT_UPDATED_ERROR))
    }

    @Test
    fun inputFailsImmediatelyWithoutInteraction() = runBlocking {
        val id = UUID.randomUUID().toString().also(createdIds::add)
        val project = File(app.projectsRoot, "Automation-$id").apply { mkdirs() }.also(createdProjects::add)
        File(project, "main.py").writeText("value = input('Nombre: ')\nprint(value)\n")
        app.automationRepository.upsert(automation(id, project.name))
        assertTrue(runWorker(id) is ListenableWorker.Result.Success)
        val stored = requireNotNull(app.automationRepository.get(id))
        assertEquals(AutomationRunStatus.Error, stored.lastStatus)
        assertTrue(stored.summary.contains(AUTOMATION_INPUT_ERROR))
    }

    @Test
    fun workerWaitsForManualRuntimeExecution() = runBlocking {
        val id = UUID.randomUUID().toString().also(createdIds::add)
        val project = File(app.projectsRoot, "Automation-$id").apply { mkdirs() }.also(createdProjects::add)
        File(project, "main.py").writeText("print('after mutex')\n")
        app.automationRepository.upsert(automation(id, project.name))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manual = async {
            PythonRuntimeCoordinator.runExclusive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val worker = async { runWorker(id) }
        delay(200)
        assertFalse(worker.isCompleted)
        release.complete(Unit)
        manual.await()
        assertTrue(worker.await() is ListenableWorker.Result.Success)
        assertEquals(AutomationRunStatus.Success, requireNotNull(app.automationRepository.get(id)).lastStatus)
    }

    private suspend fun runWorker(id: String): ListenableWorker.Result =
        TestListenableWorkerBuilder<AutomationWorker>(context)
            .setInputData(workDataOf(AutomationWorker.KEY_AUTOMATION_ID to id))
            .build()
            .doWork()

    private fun automation(id: String, project: String, result: String? = null) = ScriptAutomation(
        id = id,
        name = "AVD $id",
        projectPath = project,
        scriptPath = "main.py",
        scheduleType = AutomationScheduleType.Daily,
        highlightedResultPath = result,
    )
}
