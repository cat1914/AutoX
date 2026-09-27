package com.hneko.autox.recorder

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.hneko.autox.model.OperationRecord
import com.hneko.autox.model.TouchAction
import com.hneko.autox.service.AutoAccessibilityService
import com.hneko.autox.store.RecordStore
import com.hneko.autox.util.ShizukuShell

/**
 * 录制管理器：统一封装录制流程
 * 录制策略（按优先级）：
 * 1. Shizuku getevent（最可靠，跨 ROM 兼容）
 * 2. 无障碍 onMotionEvent（API 31+，部分国产 ROM 可能不回调）
 * 录制完成后自动保存为 OperationRecord
 */
class RecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingManager"
        /** 无障碍录制无动作超时（毫秒），超时后自动回退到 Shizuku */
        private const val A11Y_FALLBACK_TIMEOUT_MS = 3000L
    }

    private val shizukuRecorder = ShizukuRecorder()
    private val actions = mutableListOf<TouchAction>()
    private val handler = Handler(Looper.getMainLooper())
    private var recordStartTime: Long = 0L
    private var usingAccessibility = false

    @Volatile
    var isRecording = false
        private set

    var onStateChanged: ((recording: Boolean) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    fun start(): Boolean {
        val size = getScreenSize()
        recordStartTime = System.currentTimeMillis()
        actions.clear()

        // 优先使用 Shizuku getevent（最可靠）
        if (ShizukuShell.isReady()) {
            val started = shizukuRecorder.start(size.x, size.y, object : ShizukuRecorder.Callback {
                override fun onActionCaptured(action: TouchAction) {
                    actions.add(action)
                }
            })
            if (started) {
                usingAccessibility = false
                isRecording = true
                onStateChanged?.invoke(true)
                Log.i(TAG, "使用 Shizuku getevent 录制")
                return true
            }
        }

        // 回退：无障碍 onMotionEvent（API 31+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && AutoAccessibilityService.instance != null) {
            AutoAccessibilityService.instance?.let { svc ->
                AutoAccessibilityService.recordingCallback = object : AutoAccessibilityService.RecordingCallback {
                    override fun onActionCaptured(action: TouchAction) {
                        actions.add(action)
                    }
                    override fun onRecordingStopped() {}
                }
                svc.startRecording()
                usingAccessibility = true
                isRecording = true
                onStateChanged?.invoke(true)
                Log.i(TAG, "使用无障碍录制")

                // 超时 watchdog：若无障碍未捕获到任何动作，提示用户并尝试回退
                handler.postDelayed({
                    if (isRecording && usingAccessibility && actions.isEmpty()) {
                        Log.w(TAG, "无障碍录制 ${A11Y_FALLBACK_TIMEOUT_MS}ms 内未捕获动作，尝试回退到 Shizuku")
                        if (ShizukuShell.isReady()) {
                            // 切换到 Shizuku
                            svc.stopRecording()
                            AutoAccessibilityService.recordingCallback = null
                            val started = shizukuRecorder.start(size.x, size.y, object : ShizukuRecorder.Callback {
                                override fun onActionCaptured(action: TouchAction) {
                                    actions.add(action)
                                }
                            })
                            if (started) {
                                usingAccessibility = false
                                Log.i(TAG, "已回退到 Shizuku getevent 录制")
                            } else {
                                onError?.invoke("无障碍录制未捕获到触摸事件，且 Shizuku 不可用。请检查无障碍服务权限或启动 Shizuku。")
                            }
                        } else {
                            onError?.invoke("无障碍录制未捕获到触摸事件。部分国产 ROM 可能不支持无障碍录制，请尝试启动 Shizuku 后重试。")
                        }
                    }
                }, A11Y_FALLBACK_TIMEOUT_MS)

                return true
            }
        }

        Log.e(TAG, "没有可用的录制方式（无障碍未开启且 Shizuku 不可用）")
        onError?.invoke("无法开始录制：请先开启无障碍服务或启动 Shizuku。")
        return false
    }

    /**
     * 停止录制并保存
     * @param name 记录名称，null 则自动生成
     * @return 保存的记录，null 表示无动作未保存
     */
    fun stop(name: String? = null): OperationRecord? {
        if (!isRecording) return null
        isRecording = false
        handler.removeCallbacksAndMessages(null)

        if (usingAccessibility) {
            AutoAccessibilityService.instance?.stopRecording()
            AutoAccessibilityService.recordingCallback = null
        } else {
            shizukuRecorder.stop()
        }

        onStateChanged?.invoke(false)

        if (actions.isEmpty()) {
            Log.w(TAG, "录制无动作，未保存")
            return null
        }

        val size = getScreenSize()
        val totalDuration = if (actions.isEmpty()) 0L else
            actions.last().let { it.startTime + it.duration }
        val record = OperationRecord(
            id = RecordStore.get(context).nextRecordId(),
            name = name ?: "录制 ${System.currentTimeMillis()}",
            createdAt = System.currentTimeMillis(),
            screenWidth = size.x,
            screenHeight = size.y,
            actions = actions.toList(),
            totalDuration = totalDuration
        )
        RecordStore.get(context).saveRecord(record)
        Log.i(TAG, "录制已保存: ${record.id}, 动作数=${actions.size}")
        return record
    }

    fun cancel() {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacksAndMessages(null)
        if (usingAccessibility) {
            AutoAccessibilityService.instance?.stopRecording()
            AutoAccessibilityService.recordingCallback = null
        } else {
            shizukuRecorder.stop()
        }
        actions.clear()
        onStateChanged?.invoke(false)
    }

    private fun getScreenSize(): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }
}
