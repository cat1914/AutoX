package com.autox.assistant.recorder

import android.graphics.PointF
import android.util.Log
import com.autox.assistant.model.TouchAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

/**
 * 通过 Shizuku 执行 getevent 命令录制触摸轨迹
 * 适用于 API 30（onMotionEvent 不可用）的设备
 *
 * 解析 getevent -lt 输出，提取触摸坐标序列
 */
class ShizukuRecorder {

    companion object {
        private const val TAG = "ShizukuRecorder"
        private const val TAP_MOVE_THRESHOLD = 20f
        private const val LONG_PRESS_MS = 400L
    }

    interface Callback {
        fun onActionCaptured(action: TouchAction)
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var callback: Callback? = null

    // 输入设备坐标范围（用于映射到屏幕坐标）
    private var inputMinX = 0
    private var inputMaxX = 1
    private var inputMinY = 0
    private var inputMaxY = 1
    private var screenWidth = 1080
    private var screenHeight = 1920

    @Volatile
    var isRecording = false
        private set

    fun start(screenW: Int, screenH: Int, cb: Callback): Boolean {
        if (!isShizukuReady()) {
            Log.w(TAG, "Shizuku 不可用")
            return false
        }
        screenWidth = screenW
        screenHeight = screenH
        callback = cb

        // 探测触摸设备的坐标范围
        probeInputDevice()

        scope = CoroutineScope(Dispatchers.IO + Job())
        isRecording = true
        job = scope?.launch { runGetevent() }
        Log.i(TAG, "Shizuku 录制已启动")
        return true
    }

    fun stop() {
        isRecording = false
        job?.cancel()
        scope?.cancel()
        scope = null
        job = null
        callback = null
        Log.i(TAG, "Shizuku 录制已停止")
    }

    private fun isShizukuReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 探测触摸输入设备的 ABS 坐标范围 */
    private suspend fun probeInputDevice() = withContext(Dispatchers.IO) {
        runCatching {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", "getevent -lp"), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var inAbsSection = false
            var line = reader.readLine()
            while (line != null) {
                if (line.contains("0035") || line.contains("ABS_MT_POSITION_X")) {
                    val match = Regex("min\\s+(\\d+).*?max\\s+(\\d+)").find(line)
                    if (match != null) {
                        inputMinX = match.groupValues[1].toInt()
                        inputMaxX = match.groupValues[2].toInt()
                    }
                }
                if (line.contains("0036") || line.contains("ABS_MT_POSITION_Y")) {
                    val match = Regex("min\\s+(\\d+).*?max\\s+(\\d+)").find(line)
                    if (match != null) {
                        inputMinY = match.groupValues[1].toInt()
                        inputMaxY = match.groupValues[2].toInt()
                    }
                }
                line = reader.readLine()
            }
            process.waitFor()
            Log.i(TAG, "输入设备范围 X=[$inputMinX,$inputMaxX] Y=[$inputMinY,$inputMaxY]")
        }.onFailure { Log.e(TAG, "探测输入设备失败", it) }
    }

    private suspend fun runGetevent() {
        val process = runCatching {
            Shizuku.newProcess(arrayOf("sh", "-c", "getevent -lt"), null, null)
        }.getOrElse {
            Log.e(TAG, "启动 getevent 失败", it)
            return
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var down = false
        var downTime = 0L
        var recordStart = System.currentTimeMillis()
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        val path = mutableListOf<PointF>()
        val xRegex = Regex("ABS_MT_POSITION_X\\s+([0-9a-fA-F]+)")
        val yRegex = Regex("ABS_MT_POSITION_Y\\s+([0-9a-fA-F]+)")
        val btnDownRegex = Regex("BTN_TOUCH\\s+DOWN")
        val btnUpRegex = Regex("BTN_TOUCH\\s+UP")

        try {
            while (isRecording && scope?.isActive == true) {
                val line = reader.readLine() ?: break
                btnDownRegex.find(line)?.let {
                    down = true
                    downTime = System.currentTimeMillis()
                    path.clear()
                }
                btnUpRegex.find(line)?.let {
                    if (down) {
                        down = false
                        val now = System.currentTimeMillis()
                        val duration = (now - downTime).coerceAtLeast(16L)
                        val startTime = (downTime - recordStart).coerceAtLeast(0L)
                        val start = PointF(downX, downY)
                        val end = PointF(lastX, lastY)
                        val dx = abs(end.x - start.x)
                        val dy = abs(end.y - start.y)
                        val type = when {
                            dx > TAP_MOVE_THRESHOLD || dy > TAP_MOVE_THRESHOLD -> TouchAction.ActionType.SWIPE
                            duration >= LONG_PRESS_MS -> TouchAction.ActionType.LONG_PRESS
                            else -> TouchAction.ActionType.TAP
                        }
                        val action = TouchAction(start, end, path.toList(), startTime, duration, type)
                        callback?.onActionCaptured(action)
                    }
                }
                xRegex.find(line)?.let {
                    val raw = it.groupValues[1].toLong(16)
                    lastX = mapX(raw)
                    if (down && path.isEmpty()) downX = lastX
                    if (down) path.add(PointF(lastX, lastY))
                }
                yRegex.find(line)?.let {
                    val raw = it.groupValues[1].toLong(16)
                    lastY = mapY(raw)
                    if (down && path.isEmpty()) downY = lastY
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getevent 读取异常", e)
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun mapX(raw: Long): Float {
        if (inputMaxX <= inputMinX) return raw.toFloat()
        return (raw - inputMinX).toFloat() / (inputMaxX - inputMinX) * screenWidth
    }

    private fun mapY(raw: Long): Float {
        if (inputMaxY <= inputMinY) return raw.toFloat()
        return (raw - inputMinY).toFloat() / (inputMaxY - inputMinY) * screenHeight
    }
}
