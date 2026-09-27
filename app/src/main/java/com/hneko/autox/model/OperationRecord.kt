package com.hneko.autox.model

import android.graphics.PointF
import com.google.gson.annotations.SerializedName

/**
 * 单个触摸动作（一次完整的 down -> move -> up）
 */
data class TouchAction(
    /** 起始坐标 */
    @SerializedName("start") val start: PointF,
    /** 结束坐标 */
    @SerializedName("end") val end: PointF,
    /** 轨迹中间点（用于滑动） */
    @SerializedName("path") val path: List<PointF> = emptyList(),
    /** 动作开始时间戳（相对录制开始，毫秒） */
    @SerializedName("startTime") val startTime: Long,
    /** 动作持续时长（毫秒） */
    @SerializedName("duration") val duration: Long,
    /** 动作类型：TAP / LONG_PRESS / SWIPE */
    @SerializedName("type") val type: ActionType
) {
    enum class ActionType { TAP, LONG_PRESS, SWIPE }
}

/**
 * 一条录制记录
 */
data class OperationRecord(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("createdAt") val createdAt: Long,
    /** 屏幕宽（录制时） */
    @SerializedName("screenWidth") val screenWidth: Int,
    /** 屏幕高（录制时） */
    @SerializedName("screenHeight") val screenHeight: Int,
    /** 动作序列 */
    @SerializedName("actions") val actions: List<TouchAction>,
    /** 总时长（毫秒） */
    @SerializedName("totalDuration") val totalDuration: Long
)
