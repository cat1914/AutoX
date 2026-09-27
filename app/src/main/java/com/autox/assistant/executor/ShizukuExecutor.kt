package com.autox.assistant.executor

import android.util.Log
import com.autox.assistant.model.TouchAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 通过 Shizuku 执行 shell input 命令来模拟点击/滑动
 * 这是最简单可靠的 Shizuku 注入方式（shell 用户身份）
 */
class ShizukuExecutor : TouchExecutor {

    companion object {
        private const val TAG = "ShizukuExecutor"
    }

    override fun isAvailable(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override fun name(): String = "Shizuku"

    override suspend fun execute(action: TouchAction): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku 不可用")
            return@withContext false
        }
        val cmd = buildCommand(action) ?: return@withContext false
        runCatching {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val code = process.waitFor()
            if (code != 0) {
                Log.e(TAG, "命令执行失败 code=$code output=$output")
            }
            code == 0
        }.onFailure { Log.e(TAG, "执行异常", it) }.getOrDefault(false)
    }

    private fun buildCommand(action: TouchAction): String? {
        return when (action.type) {
            TouchAction.ActionType.TAP,
            TouchAction.ActionType.LONG_PRESS -> {
                val d = action.duration.coerceAtLeast(50)
                // input tap 不支持长按，用 swipe 同坐标模拟长按
                "input swipe ${action.start.x.toInt()} ${action.start.y.toInt()} " +
                    "${action.end.x.toInt()} ${action.end.y.toInt()} $d"
            }
            TouchAction.ActionType.SWIPE -> {
                val d = action.duration.coerceIn(50, 5000)
                "input swipe ${action.start.x.toInt()} ${action.start.y.toInt()} " +
                    "${action.end.x.toInt()} ${action.end.y.toInt()} $d"
            }
        }
    }
}
