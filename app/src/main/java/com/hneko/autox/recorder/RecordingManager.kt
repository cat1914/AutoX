package com.hneko.autox.recorder

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.hneko.autox.model.OperationRecord
import com.hneko.autox.model.TouchAction
import com.hneko.autox.service.AutoAccessibilityService
import com.hneko.autox.store.RecordStore
import java.util.UUID

/**
 * 录制管理器：统一封装录制流程
 * - API 31+：优先使用无障碍 onMotionEvent
 * - API 30：使用 Shizuku getevent
 * 录制完成后自动保存为 OperationRecord
 */
class RecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingManager"
    }

    private val shizukuRecorder = ShizukuRecorder()
    private val actions = mutableListOf<TouchAction>()
    private var recordStartTime: Long = 0L
    private var usingAccessibility = false

    @Volatile
    var isRecording = false
        private set

    var onStateChanged: ((recording: Boolean) -> Unit)? = null

    fun start(): Boolean {
        val size = getScreenSize()
        recordStartTime = System.currentTimeMillis()
        actions.clear()

        // 优先使用无障碍录制（API 31+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && AutoAccessibilityService.instance != null) {
            AutoAccessibilityService.instance?.let { svc ->
                svc.recordingCallback = object : AutoAccessibilityService.RecordingCallback {
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
                return true
            }
        }

        // 兜底：Shizuku getevent 录制
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

        Log.e(TAG, "没有可用的录制方式（无障碍未开启且 Shizuku 不可用）")
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

        if (usingAccessibility) {
            AutoAccessibilityService.instance?.stopRecording()
            AutoAccessibilityService.instance?.recordingCallback = null
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
        if (usingAccessibility) {
            AutoAccessibilityService.instance?.stopRecording()
            AutoAccessibilityService.instance?.recordingCallback = null
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
