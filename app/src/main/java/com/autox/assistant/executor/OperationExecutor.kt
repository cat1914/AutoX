package com.autox.assistant.executor

import android.util.Log
import com.autox.assistant.model.OperationRecord
import com.autox.assistant.model.TouchAction
import kotlinx.coroutines.delay

/**
 * 操作执行调度器：优先 Shizuku，不可用时回退无障碍
 */
class OperationExecutor {

    companion object {
        private const val TAG = "OperationExecutor"
    }

    private val shizuku = ShizukuExecutor()
    private val accessibility = AccessibilityExecutor()

    /** 当前实际使用的执行器名称 */
    var activeExecutorName: String = "unknown"
        private set

    private fun selectExecutor(): TouchExecutor? {
        return when {
            shizuku.isAvailable() -> shizuku.also { activeExecutorName = it.name() }
            accessibility.isAvailable() -> accessibility.also { activeExecutorName = it.name() }
            else -> {
                activeExecutorName = "none"
                null
            }
        }
    }

    /**
     * 回放一条录制记录
     * @param record 录制记录
     * @param onAction 每执行完一个动作的回调（索引，总动作数，是否成功）
     * @return 是否全部执行成功
     */
    suspend fun replay(
        record: OperationRecord,
        onAction: ((index: Int, total: Int, success: Boolean) -> Unit)? = null
    ): Boolean {
        val executor = selectExecutor()
        if (executor == null) {
            Log.e(TAG, "没有可用的执行器（Shizuku 与无障碍均不可用）")
            onAction?.invoke(0, record.actions.size, false)
            return false
        }
        Log.i(TAG, "使用执行器: ${executor.name()}")

        var allOk = true
        val actions = record.actions
        for (i in actions.indices) {
            val action = actions[i]
            // 按照录制时的相对时间间隔等待
            val wait = if (i == 0) 0L else {
                (action.startTime - actions[i - 1].startTime - actions[i - 1].duration)
                    .coerceAtLeast(0L)
            }
            if (wait > 0) delay(wait)

            val ok = executor.execute(action)
            if (!ok) allOk = false
            onAction?.invoke(i + 1, actions.size, ok)
        }
        return allOk
    }

    /** 执行单个动作（供外部直接调用） */
    suspend fun executeSingle(action: TouchAction): Boolean {
        val executor = selectExecutor() ?: return false
        return executor.execute(action)
    }

    /** 检查是否有任一执行器可用 */
    fun hasAvailableExecutor(): Boolean = selectExecutor() != null
}
