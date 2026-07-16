package com.pixelpy.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide gate for every access to the embedded Python runtime. */
internal object PythonRuntimeCoordinator {
    internal val mutex = Mutex()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun <T> runExclusive(block: suspend () -> T): T = mutex.withLock {
        _busy.value = true
        try {
            block()
        } finally {
            _busy.value = false
        }
    }
}
