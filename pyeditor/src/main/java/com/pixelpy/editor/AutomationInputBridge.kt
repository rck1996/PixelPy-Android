package com.pixelpy.editor

import androidx.annotation.Keep

internal const val AUTOMATION_INPUT_ERROR = "Las automatizaciones no pueden solicitar input()"

@Keep
class AutomationInputBridge(private val stopped: () -> Boolean) {
    @Volatile private var cancelled = false

    @Keep
    fun request(prompt: String): String = throw IllegalStateException(AUTOMATION_INPUT_ERROR)

    @Keep
    fun submit(answer: String) = Unit

    @Keep
    fun cancel() {
        cancelled = true
    }

    @Keep
    fun isCancelled(): Boolean = cancelled || stopped()
}
