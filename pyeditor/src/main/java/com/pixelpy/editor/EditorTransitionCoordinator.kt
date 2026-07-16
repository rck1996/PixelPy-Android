package com.pixelpy.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class EditorTransitionCoordinator {
    private val transitionMutex = Mutex()

    fun launch(scope: CoroutineScope, transition: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transitionMutex.withLock { transition() }
        }
}
