package com.autox.assistant

import android.app.Application
import android.util.Log

class AutoXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("AutoXApp", "AutoX 应用启动")
    }
}
