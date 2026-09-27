package com.hneko.autox.util

import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method

/**
 * Shizuku shell 命令执行工具
 *
 * Shizuku 13.x 中 Shizuku.newProcess 不是公开 API，
 * 通过反射调用以 shell 身份执行命令。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    private var newProcessMethod: Method? = null

    init {
        runCatching {
            newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                File::class.java
            ).apply { isAccessible = true }
        }.onFailure { Log.e(TAG, "反射 newProcess 失败", it) }
    }

    fun isReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false) && newProcessMethod != null
    }

    /**
     * 以 shell 身份执行命令，返回 Process（失败返回 null）
     */
    fun exec(cmd: String): Process? {
        val method = newProcessMethod ?: run {
            Log.e(TAG, "newProcess 方法不可用")
            return null
        }
        return runCatching {
            method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        }.onFailure { Log.e(TAG, "执行命令失败: $cmd", it) }.getOrNull()
    }

    /**
     * 执行命令并获取输出文本
     */
    fun execForOutput(cmd: String): String? {
        val process = exec(cmd) ?: return null
        return runCatching {
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        }.getOrNull()
    }
}
