package com.hneko.autox.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hneko.autox.model.TouchAction
import com.hneko.autox.service.AutoAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 通过无障碍服务的 dispatchGesture 执行点击/滑动
 * 作为 Shizuku 不可用时的兜底通道
 */
class AccessibilityExecutor : TouchExecutor {

    companion object {
        private const val TAG = "AccessibilityExecutor"
    }

    override fun isAvailable(): Boolean {
        return AutoAccessibilityService.instance != null
    }

    override fun name(): String = "Accessibility"

    override suspend fun execute(action: TouchAction): Boolean {
        val service = AutoAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "无障碍服务未连接")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture 需要 Android 7.0+")
            return false
        }
        val path = Path().apply {
            moveTo(action.start.x, action.start.y)
            action.path.forEach { lineTo(it.x, it.y) }
            lineTo(action.end.x, action.end.y)
        }
        val duration = action.duration.coerceIn(16L, 5000L)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = runCatching {
                service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
            }.getOrDefault(false)
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }
}
