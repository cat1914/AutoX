package com.hneko.autox.executor

import android.util.Log
import com.hneko.autox.model.TouchAction
import com.hneko.autox.util.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 Shizuku 执行 shell input 命令来模拟点击/滑动
 * 这是最简单可靠的 Shizuku 注入方式（shell 用户身份）
 */
class ShizukuExecutor : TouchExecutor {

    companion object {
        private const val TAG = "ShizukuExecutor"
    }

    override fun isAvailable(): Boolean = ShizukuShell.isReady()

    override fun name(): String = "Shizuku"

    override suspend fun execute(action: TouchAction): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku 不可用")
            return@withContext false
        }
        val cmd = buildCommand(action) ?: return@withContext false
        val output = ShizukuShell.execForOutput(cmd)
        Log.d(TAG, "执行: $cmd -> ${output?.trim()}")
        output != null
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
