package com.autox.assistant.executor

import com.autox.assistant.model.TouchAction

/**
 * 触摸执行器统一接口
 */
interface TouchExecutor {
    /** 是否可用 */
    fun isAvailable(): Boolean

    /** 执行名称（用于日志/展示） */
    fun name(): String

    /**
     * 执行一个触摸动作
     * @return 是否执行成功
     */
    suspend fun execute(action: TouchAction): Boolean
}
