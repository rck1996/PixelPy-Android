package com.pixelpy.editor

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PixelPyApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val projectsRoot by lazy {
        java.io.File(filesDir, "projects").apply { mkdirs() }
    }
    internal val autosaveCoordinator by lazy {
        EditorAutosaveCoordinator(applicationScope)
    }
    internal val automationRepository by lazy {
        AutomationRepository(filesDir)
    }
    internal val automationScheduler by lazy {
        AutomationScheduler(
            repository = automationRepository,
            gateway = WorkManagerAutomationGateway(this),
            projectsRoot = projectsRoot,
            onChanged = { AutomationWidgetProvider.updateForAutomation(this, it) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    }
}
