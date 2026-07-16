package com.pixelpy.editor

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class SaveStatus { Editing, Saving, Saved, Error }

internal fun interface FileContentWriter {
    suspend fun write(file: File, content: String)
}

internal class EditorAutosaveCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 650,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writer: FileContentWriter = AtomicFileContentWriter,
) {
    private data class PendingSave(
        val file: File,
        val content: String,
        val version: Long,
    )

    private val stateLock = Any()
    private val nextVersion = AtomicLong(0)
    private val pending = mutableMapOf<String, PendingSave>()
    private val confirmed = mutableMapOf<String, String>()
    private val jobs = mutableMapOf<String, Job>()
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, SaveStatus>>(emptyMap())
    val states: StateFlow<Map<String, SaveStatus>> = _states

    fun registerFile(file: File, diskContent: String) {
        val path = file.safePath()
        synchronized(stateLock) {
            if (pending[path] == null) {
                confirmed[path] = diskContent
                updateStatusLocked(path, SaveStatus.Saved)
            }
        }
    }

    fun onEdit(file: File, content: String): Long {
        val path = file.safePath()
        synchronized(stateLock) {
            val existing = pending[path]
            if (existing?.content == content || (existing == null && confirmed[path] == content)) {
                if (existing == null) updateStatusLocked(path, SaveStatus.Saved)
                return existing?.version ?: nextVersion.get()
            }

            val version = nextVersion.incrementAndGet()
            pending[path] = PendingSave(file.canonicalFile, content, version)
            updateStatusLocked(path, SaveStatus.Editing)
            jobs.remove(path)?.cancel()
            jobs[path] = scope.launch {
                delay(debounceMillis)
                saveLatest(path)
            }
            return version
        }
    }

    suspend fun flushPendingSave(file: File? = null) {
        val paths = synchronized(stateLock) {
            if (file == null) pending.keys.toList() else listOf(file.safePath())
        }
        paths.forEach { path ->
            while (true) {
                val job = synchronized(stateLock) { jobs.remove(path) }
                job?.cancelAndJoin()
                saveLatest(path)
                val retryNewerVersion = synchronized(stateLock) {
                    pending[path] != null && _states.value[path] != SaveStatus.Error
                }
                if (!retryNewerVersion) break
            }
        }
    }

    fun flushAsync(file: File? = null): Job = scope.launch {
        flushPendingSave(file)
    }

    fun status(file: File): SaveStatus =
        states.value[file.safePath()] ?: SaveStatus.Saved

    fun latestContent(file: File): String? = synchronized(stateLock) {
        pending[file.safePath()]?.content
    }

    private suspend fun saveLatest(path: String) {
        fileLocks.getOrPut(path) { Mutex() }.withLock {
            val snapshot = synchronized(stateLock) { pending[path] } ?: return
            val unchanged = synchronized(stateLock) { confirmed[path] == snapshot.content }
            if (unchanged) {
                synchronized(stateLock) {
                    if (pending[path]?.version == snapshot.version) pending.remove(path)
                    updateStatusLocked(path, SaveStatus.Saved)
                }
                return
            }

            synchronized(stateLock) { updateStatusLocked(path, SaveStatus.Saving) }
            try {
                withContext(ioDispatcher) { writer.write(snapshot.file, snapshot.content) }
                synchronized(stateLock) {
                    val latest = pending[path]
                    if (latest?.version == snapshot.version) {
                        confirmed[path] = snapshot.content
                        pending.remove(path)
                        jobs.remove(path)
                        updateStatusLocked(path, SaveStatus.Saved)
                    } else {
                        updateStatusLocked(path, SaveStatus.Editing)
                    }
                }
            } catch (_: Exception) {
                synchronized(stateLock) { updateStatusLocked(path, SaveStatus.Error) }
            }
        }
    }

    private fun updateStatusLocked(path: String, status: SaveStatus) {
        _states.value = _states.value.toMutableMap().apply { put(path, status) }
    }

    private fun File.safePath(): String = canonicalFile.path
}
