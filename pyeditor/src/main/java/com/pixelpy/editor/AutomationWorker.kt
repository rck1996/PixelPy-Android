package com.pixelpy.editor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import java.io.File
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val AUTOMATION_RESULT_NOT_UPDATED_ERROR =
    "El script terminó correctamente, pero no generó ni actualizó el archivo de resultado configurado."

class AutomationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_AUTOMATION_ID) ?: return Result.failure()
        val app = applicationContext as? PixelPyApp ?: return Result.failure()
        val repository = app.automationRepository
        val automation = repository.get(id) ?: return Result.success()
        if (!automation.enabled) return Result.success()
        val manualRun = inputData.getBoolean(KEY_MANUAL_RUN, false)
        val origin = runCatching {
            enumValueOf<AutomationRunOrigin>(inputData.getString(KEY_RUN_ORIGIN).orEmpty())
        }.getOrDefault(if (manualRun) AutomationRunOrigin.App else AutomationRunOrigin.Scheduled)

        val startedAt = System.currentTimeMillis()
        repository.update(id) {
            it.copy(
                lastStatus = AutomationRunStatus.Running,
                lastRunAtMillis = startedAt,
                summary = "Ejecutando…",
            )
        }
        AutomationWidgetProvider.updateForAutomation(applicationContext, id)

        val bridge = AutomationInputBridge { isStopped }
        return try {
            val paths = withContext(Dispatchers.IO) {
                AutomationPathValidator.validate(app.projectsRoot, automation, requireExisting = true)
            }
            val source = withContext(Dispatchers.IO) { paths.script.readText(Charsets.UTF_8) }
            val execution = withContext(Dispatchers.Default) {
                PythonRuntimeCoordinator.runExclusive {
                    val python = Python.getInstance()
                    val environment = requireNotNull(python.getModule("os").get("environ"))
                    val previous = automation.parameters.mapValues { (key, _) ->
                        if (environment.callAttr("__contains__", key).toBoolean()) environment.callAttr("get", key).toString() else null
                    }
                    try {
                        automation.parameters.forEach { (key, value) -> environment.callAttr("__setitem__", key, value) }
                        val value = python.getModule("runner").callAttr(
                            "execute",
                            source,
                            "",
                            paths.project.absolutePath,
                            bridge,
                            automation.timeoutSeconds,
                            false,
                            paths.script.name,
                        )
                        PythonAutomationResult(
                            ok = value.callAttr("get", "ok").toBoolean(),
                            output = value.callAttr("get", "output").toString(),
                            errorType = value.callAttr("get", "error_type").toString(),
                            errorMessage = value.callAttr("get", "error_message").toString(),
                            files = value.callAttr("get", "files").asList().map { it.toString() },
                        )
                    } finally {
                        previous.forEach { (key, value) ->
                            if (value == null) environment.callAttr("pop", key, null)
                            else environment.callAttr("__setitem__", key, value)
                        }
                    }
                }
            }
            val generatedFiles = withContext(Dispatchers.IO) {
                val projectRoot = paths.project.canonicalFile.toPath()
                execution.files.mapNotNull { path ->
                    runCatching { File(path).canonicalFile.toPath() }.getOrNull()
                        ?.takeIf { it.startsWith(projectRoot) }
                        ?.let { projectRoot.relativize(it).toString().replace('\\', '/') }
                }.distinct().take(MAX_AUTOMATION_GENERATED_FILES)
            }

            if (!execution.ok) {
                val summary = execution.summary()
                markError(repository, id, summary)
                appendHistory(repository, id, startedAt, origin, AutomationRunStatus.Error, summary, generatedFiles, false)
                app.automationScheduler.scheduleAfterRun(id, appendToCurrentChain = !manualRun)
                Result.success()
            } else {
                val resultWasUpdated = paths.highlightedResult?.let { resultFile ->
                    withContext(Dispatchers.IO) {
                        val configuredResult = resultFile.canonicalFile
                        execution.files.any { generatedPath ->
                            runCatching { File(generatedPath).canonicalFile == configuredResult }
                                .getOrDefault(false)
                        }
                    }
                } ?: true

                if (!resultWasUpdated) {
                    markError(repository, id, AUTOMATION_RESULT_NOT_UPDATED_ERROR)
                    appendHistory(repository, id, startedAt, origin, AutomationRunStatus.Error, AUTOMATION_RESULT_NOT_UPDATED_ERROR, generatedFiles, false)
                } else {
                    val artifact = paths.highlightedResult?.let { resultFile ->
                        withContext(Dispatchers.IO) {
                            PublishedArtifactPublisher(applicationContext.filesDir)
                                .publish(automation, resultFile)
                        }
                    }
                    val summary = execution.output.ifBlank { "Ejecución completada correctamente." }
                    markSuccess(repository, id, execution.output, artifact)
                    appendHistory(repository, id, startedAt, origin, AutomationRunStatus.Success, summary, generatedFiles, artifact != null)
                }
                app.automationScheduler.scheduleAfterRun(id, appendToCurrentChain = !manualRun)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            bridge.cancel()
            withContext(NonCancellable) {
                repository.update(id) { current ->
                    if (current.enabled) current.copy(
                        lastStatus = AutomationRunStatus.Error,
                        summary = "Ejecución cancelada por Android o por el usuario.",
                    ) else current
                }
                AutomationWidgetProvider.updateForAutomation(applicationContext, id)
                appendHistory(repository, id, startedAt, origin, AutomationRunStatus.Error, "Ejecución cancelada por Android o por el usuario.", emptyList(), false)
            }
            throw cancelled
        } catch (error: Exception) {
            markError(repository, id, error.message ?: error::class.java.simpleName)
            app.automationScheduler.scheduleAfterRun(id, appendToCurrentChain = !manualRun)
            appendHistory(repository, id, startedAt, origin, AutomationRunStatus.Error, error.message ?: error::class.java.simpleName, emptyList(), false)
            Result.success()
        } finally {
            bridge.cancel()
            AutomationWidgetProvider.updateForAutomation(applicationContext, id)
            repository.get(id)?.takeIf { it.lastStatus != AutomationRunStatus.Running }?.let {
                AutomationNotifications.show(applicationContext, it)
            }
        }
    }

    private fun markSuccess(
        repository: AutomationRepository,
        id: String,
        output: String,
        artifact: PublishedArtifact?,
    ) {
        repository.update(id) { current ->
            current.copy(
                lastStatus = AutomationRunStatus.Success,
                summary = output.ifBlank { "Ejecución completada correctamente." }.limitedAutomationSummary(),
                publishedArtifactPath = artifact?.relativePath ?: current.publishedArtifactPath,
                publishedAtMillis = artifact?.updatedAtMillis ?: current.publishedAtMillis,
                publishedSizeBytes = artifact?.sizeBytes ?: current.publishedSizeBytes,
                publishedMimeType = artifact?.mimeType ?: current.publishedMimeType,
            )
        }
    }

    private fun markError(repository: AutomationRepository, id: String, message: String) {
        repository.update(id) { current ->
            current.copy(
                lastStatus = AutomationRunStatus.Error,
                summary = message.ifBlank { "La automatización terminó con un error." }.limitedAutomationSummary(),
            )
        }
    }

    private fun appendHistory(
        repository: AutomationRepository,
        id: String,
        startedAt: Long,
        origin: AutomationRunOrigin,
        status: AutomationRunStatus,
        summary: String,
        generatedFiles: List<String>,
        resultPublished: Boolean,
    ) {
        val finishedAt = System.currentTimeMillis()
        val record = AutomationRunRecord(
            startedAtMillis = startedAt,
            finishedAtMillis = finishedAt,
            durationMillis = (finishedAt - startedAt).coerceAtLeast(0L),
            origin = origin,
            status = status,
            summary = summary.ifBlank { status.name }.limitedAutomationSummary(),
            generatedFiles = generatedFiles.take(MAX_AUTOMATION_GENERATED_FILES),
            resultPublished = resultPublished,
        )
        repository.update(id) { it.copy(runHistory = (it.runHistory + record).takeLast(MAX_AUTOMATION_HISTORY)) }
    }

    private data class PythonAutomationResult(
        val ok: Boolean,
        val output: String,
        val errorType: String,
        val errorMessage: String,
        val files: List<String>,
    ) {
        fun summary(): String = sequenceOf(output, listOf(errorType, errorMessage).filter { it.isNotBlank() }.joinToString(": "))
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .limitedAutomationSummary()
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"
        const val KEY_MANUAL_RUN = "manual_run"
        const val KEY_RUN_ORIGIN = "run_origin"
    }
}
