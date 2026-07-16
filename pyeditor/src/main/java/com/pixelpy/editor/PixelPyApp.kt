package com.pixelpy.editor

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PixelPyApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val autosaveCoordinator by lazy {
        EditorAutosaveCoordinator(applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    }
}
