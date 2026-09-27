package com.hneko.autox

import android.app.Application
import android.util.Log
import com.hneko.autox.util.LogCollector

class AutoXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启动日志收集（捕获本应用 logcat 输出，支持导出）
        LogCollector.start(this)
        Log.i("AutoXApp", "AutoX 应用启动")
    }
}
