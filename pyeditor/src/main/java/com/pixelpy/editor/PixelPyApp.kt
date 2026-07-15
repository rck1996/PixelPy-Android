package com.pixelpy.editor

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class PixelPyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    }
}
