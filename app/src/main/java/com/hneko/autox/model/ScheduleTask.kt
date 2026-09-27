package com.hneko.autox.model

import com.google.gson.annotations.SerializedName

/**
 * 定时任务（按时间或日历触发回放）
 */
data class ScheduleTask(
    @SerializedName("id") val id: String,
    @SerializedName("recordId") val recordId: String,
    @SerializedName("name") val name: String,
    /** 触发类型 */
    @SerializedName("type") val type: ScheduleType,
    /** 一次性触发的时间戳（毫秒），type == ONCE 时有效 */
    @SerializedName("triggerAtMillis") val triggerAtMillis: Long,
    /** 重复间隔（毫秒），type == INTERVAL 时有效 */
    @SerializedName("intervalMillis") val intervalMillis: Long = 0,
    /** 日历事件 ID，type == CALENDAR 时有效 */
    @SerializedName("calendarEventId") val calendarEventId: Long = -1,
    /** 是否启用 */
    @SerializedName("enabled") val enabled: Boolean = true
) {
    enum class ScheduleType { ONCE, INTERVAL, CALENDAR }
}
