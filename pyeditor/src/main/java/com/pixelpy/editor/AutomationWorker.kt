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
                    val value = Python.getInstance().getModule("runner").callAttr(
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
                }
            }

            if (!execution.ok) {
                markError(repository, id, execution.summary())
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
                } else {
                    val artifact = paths.highlightedResult?.let { resultFile ->
                        withContext(Dispatchers.IO) {
                            PublishedArtifactPublisher(applicationContext.filesDir)
                                .publish(automation, resultFile)
                        }
                    }
                    markSuccess(repository, id, execution.output, artifact)
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
            }
            throw cancelled
        } catch (error: Exception) {
            markError(repository, id, error.message ?: error::class.java.simpleName)
            app.automationScheduler.scheduleAfterRun(id, appendToCurrentChain = !manualRun)
            Result.success()
        } finally {
            bridge.cancel()
            AutomationWidgetProvider.updateForAutomation(applicationContext, id)
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
    }
}
