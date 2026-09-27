package com.hneko.autox.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PointF
import android.view.MotionEvent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.hneko.autox.model.TouchAction
import kotlin.math.abs

/**
 * 无障碍服务：
 * 1. 持有单例实例，供 AccessibilityExecutor 调用 dispatchGesture
 * 2. 通过 onMotionEvent（API 31+）录制用户触摸轨迹
 */
class AutoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoAccessibility"
        private const val TAP_MOVE_THRESHOLD = 20f  // 超过此位移判定为滑动
        private const val LONG_PRESS_MS = 400L

        @Volatile
        var instance: AutoAccessibilityService? = null
            private set

        /** 是否正在录制 */
        @Volatile
        var isRecording: Boolean = false
            private set

        /** 录制回调 */
        var recordingCallback: RecordingCallback? = null

        interface RecordingCallback {
            fun onActionCaptured(action: TouchAction)
            fun onRecordingStopped()
        }
    }

    // 录制状态
    private var recordStartTime: Long = 0L
    private var downTime: Long = 0L
    private var downPoint: PointF? = null
    private val pathPoints = mutableListOf<PointF>()
    private var lastEventTime: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 录制不需要处理 UI 事件
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRecording = false
        Log.i(TAG, "无障碍服务已销毁")
    }

    // ---------------- 录制控制 ----------------

    fun startRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "onMotionEvent 录制需要 Android 12+，当前设备请使用 Shizuku getevent 录制")
        }
        isRecording = true
        recordStartTime = System.currentTimeMillis()
        downPoint = null
        pathPoints.clear()
        Log.i(TAG, "开始录制")
    }

    fun stopRecording() {
        isRecording = false
        recordingCallback?.onRecordingStopped()
        Log.i(TAG, "停止录制")
    }

    // ---------------- 触摸事件捕获（API 31+） ----------------

    override fun onMotionEvent(event: MotionEvent) {
        super.onMotionEvent(event)
        if (!isRecording) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downPoint = PointF(event.x, event.y)
                pathPoints.clear()
                pathPoints.add(PointF(event.x, event.y))
                lastEventTime = downTime
            }
            MotionEvent.ACTION_MOVE -> {
                pathPoints.add(PointF(event.x, event.y))
                lastEventTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val start = downPoint ?: return
                val end = PointF(event.x, event.y)
                val now = System.currentTimeMillis()
                val duration = (now - downTime).coerceAtLeast(16L)
                val startTime = (downTime - recordStartTime).coerceAtLeast(0L)

                val dx = abs(end.x - start.x)
                val dy = abs(end.y - start.y)
                val isSwipe = dx > TAP_MOVE_THRESHOLD || dy > TAP_MOVE_THRESHOLD
                val type = when {
                    isSwipe -> TouchAction.ActionType.SWIPE
                    duration >= LONG_PRESS_MS -> TouchAction.ActionType.LONG_PRESS
                    else -> TouchAction.ActionType.TAP
                }

                val action = TouchAction(
                    start = start,
                    end = end,
                    path = if (isSwipe) pathPoints.toList() else emptyList(),
                    startTime = startTime,
                    duration = duration,
                    type = type
                )
                recordingCallback?.onActionCaptured(action)
                downPoint = null
                pathPoints.clear()
            }
        }
    }
}
