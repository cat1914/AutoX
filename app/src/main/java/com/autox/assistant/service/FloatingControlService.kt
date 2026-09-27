package com.autox.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autox.assistant.R
import com.autox.assistant.executor.OperationExecutor
import com.autox.assistant.recorder.RecordingManager
import com.autox.assistant.store.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗控制服务：
 * - 提供可拖拽的悬浮按钮，展开后显示「录制/停止/回放」控件
 * - 作为前台服务保活
 */
class FloatingControlService : Service() {

    companion object {
        private const val TAG = "FloatingControl"
        private const val NOTI_CHANNEL_ID = "autox_floating"
        private const val NOTI_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatingControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var panelView: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var recordingManager: RecordingManager
    private lateinit var executor: OperationExecutor

    private var replayJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        recordingManager = RecordingManager(this)
        executor = OperationExecutor()

        startForeground(NOTI_ID, buildNotification())
        addFloatingView()
        Log.i(TAG, "悬浮窗服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingView()
        serviceScope.cancel()
        Log.i(TAG, "悬浮窗服务已销毁")
    }

    // ---------------- 通知 ----------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTI_CHANNEL_ID, "悬浮窗控制", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AutoX 悬浮窗录制/回放控制"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTI_CHANNEL_ID)
            .setContentTitle("AutoX")
            .setContentText("悬浮窗控制运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ---------------- 悬浮窗 ----------------

    private fun addFloatingView() {
        if (floatingView != null) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        val btn = TextView(this).apply {
            text = "AutoX"
            textSize = 14f
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xCC2196F3.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTime = 0L

        btn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(btn, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (System.currentTimeMillis() - downTime < 300) {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = btn
        runCatching { windowManager.addView(btn, params) }
            .onFailure { Log.e(TAG, "添加悬浮窗失败", it) }
    }

    private fun removeFloatingView() {
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
        removePanel()
    }

    // ---------------- 控制面板 ----------------

    private fun togglePanel() {
        if (panelView != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xF0FFFFFF.toInt())
        }

        val recordBtn = makePanelButton("开始录制")
        val stopBtn = makePanelButton("停止录制")
        val replayBtn = makePanelButton("回放最新")
        val closeBtn = makePanelButton("关闭悬浮窗")

        recordBtn.setOnClickListener {
            if (recordingManager.start()) {
                Toast.makeText(this, "开始录制", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "录制启动失败：请开启无障碍或 Shizuku", Toast.LENGTH_LONG).show()
            }
        }
        stopBtn.setOnClickListener {
            val record = recordingManager.stop()
            if (record != null) {
                Toast.makeText(this, "已保存：${record.name}（${record.actions.size} 步）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "未录制到动作", Toast.LENGTH_SHORT).show()
            }
        }
        replayBtn.setOnClickListener {
            replayLatest()
        }
        closeBtn.setOnClickListener {
            removePanel()
            stopSelf()
        }

        panel.addView(recordBtn)
        panel.addView(stopBtn)
        panel.addView(replayBtn)
        panel.addView(closeBtn)

        panelView = panel
        runCatching { windowManager.addView(panel, params) }
    }

    private fun makePanelButton(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(32, 20, 32, 20)
            setBackgroundColor(0xFF2196F3.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
            layoutParams = lp
        }
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
    }

    // ---------------- 回放 ----------------

    private fun replayLatest() {
        val records = RecordStore.get(this).getAllRecords()
        if (records.isEmpty()) {
            Toast.makeText(this, "没有可回放的记录", Toast.LENGTH_SHORT).show()
            return
        }
        val record = records.first()
        if (replayJob?.isActive == true) {
            Toast.makeText(this, "正在回放中", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "开始回放：${record.name}", Toast.LENGTH_SHORT).show()
        replayJob = serviceScope.launch {
            val ok = executor.replay(record)
            Toast.makeText(
                this@FloatingControlService,
                "回放${if (ok) "完成" else "失败"}：${executor.activeExecutorName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
