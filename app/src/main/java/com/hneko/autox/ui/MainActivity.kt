package com.hneko.autox.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hneko.autox.R
import com.hneko.autox.databinding.ActivityMainBinding
import com.hneko.autox.executor.OperationExecutor
import com.hneko.autox.model.ScheduleTask
import com.hneko.autox.scheduler.CalendarHelper
import com.hneko.autox.scheduler.ScheduleManager
import com.hneko.autox.service.FloatingControlService
import com.hneko.autox.store.RecordStore
import com.hneko.autox.util.LogCollector
import com.hneko.autox.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = OperationExecutor()
    private var replayJob: Job? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionStatus()
        }

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionStatus()
            Toast.makeText(this, "日历权限已更新", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        renderRecords()
        renderTasks()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---------------- 权限 ----------------

    private fun refreshPermissionStatus() {
        binding.tvOverlay.text = "悬浮窗权限：${state(PermissionHelper.hasOverlayPermission(this))}"
        binding.tvAccessibility.text = "无障碍服务：${state(PermissionHelper.isAccessibilityEnabled(this))}"
        binding.tvNotification.text = "通知权限：${state(PermissionHelper.hasNotificationPermission(this))}"
        binding.tvBattery.text = "忽略电池优化：${state(PermissionHelper.isIgnoringBatteryOptimizations(this))}"
        binding.tvShizuku.text = "Shizuku：${state(PermissionHelper.isShizukuPermissionGranted())}"
        binding.tvExactAlarm.text = "精确闹钟：${state(PermissionHelper.canScheduleExactAlarms(this))}"
    }

    private fun state(ok: Boolean) = if (ok) "✓ 已授权" else "✗ 未授权"

    private fun requestAllPermissions() {
        // 悬浮窗
        if (!PermissionHelper.hasOverlayPermission(this)) {
            PermissionHelper.requestOverlayPermission(this)
        }
        // 通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 忽略电池优化
        if (!PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            PermissionHelper.requestIgnoreBatteryOptimizations(this)
        }
        // 精确闹钟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !PermissionHelper.canScheduleExactAlarms(this)
        ) {
            PermissionHelper.openExactAlarmSettings(this)
        }
        // 无障碍
        if (!PermissionHelper.isAccessibilityEnabled(this)) {
            Toast.makeText(this, "请在系统设置中开启无障碍服务", Toast.LENGTH_LONG).show()
            PermissionHelper.openAccessibilitySettings(this)
        }
        // Shizuku
        if (!PermissionHelper.isShizukuPermissionGranted() && PermissionHelper.isShizukuAvailable()) {
            PermissionHelper.requestShizukuPermission()
        } else if (!PermissionHelper.isShizukuAvailable()) {
            Toast.makeText(this, "Shizuku 未运行，请先启动 Shizuku 应用", Toast.LENGTH_LONG).show()
        }
        // 日历
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
        refreshPermissionStatus()
    }

    // ---------------- 悬浮窗 ----------------

    private fun setupListeners() {
        binding.btnReqAll.setOnClickListener { requestAllPermissions() }
        binding.btnRefreshPerm.setOnClickListener { refreshPermissionStatus() }
        binding.btnShowFloat.setOnClickListener {
            if (PermissionHelper.hasOverlayPermission(this)) {
                FloatingControlService.start(this)
                Toast.makeText(this, "悬浮窗已显示", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                PermissionHelper.requestOverlayPermission(this)
            }
        }
        binding.btnHideFloat.setOnClickListener {
            FloatingControlService.stop(this)
            Toast.makeText(this, "悬浮窗已隐藏", Toast.LENGTH_SHORT).show()
        }
        binding.btnExportLog.setOnClickListener {
            val uri = LogCollector.exportToDownloads(this)
            if (uri != null) {
                Toast.makeText(this, "日志已导出到 Downloads 目录", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "日志导出失败，请查看 logcat", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnLogInfo.setOnClickListener {
            val info = LogCollector.getLogFileInfo()
            val recent = LogCollector.getRecentLogs().lineSequence().count()
            Toast.makeText(this, "日志文件: $info\n内存缓冲: $recent 行", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- 录制记录 ----------------

    private fun renderRecords() {
        val container = binding.recordsContainer
        container.removeAllViews()
        val records = RecordStore.get(this).getAllRecords()
        if (records.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无录制记录"
                setPadding(0, 16, 0, 16)
            })
            return
        }
        records.forEach { record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(this).apply {
                text = "${record.name}  ·  ${record.actions.size} 步  ·  ${record.totalDuration}ms"
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            actions.addView(Button(this).apply {
                text = "回放"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (replayJob?.isActive == true) {
                        Toast.makeText(this@MainActivity, "正在回放中", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    replayJob = scope.launch {
                        val ok = executor.replay(record)
                        Toast.makeText(
                            this@MainActivity,
                            "回放${if (ok) "完成" else "失败"}：${executor.activeExecutorName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
            actions.addView(Button(this).apply {
                text = "定时"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showScheduleDialog(record.id) }
            })
            actions.addView(Button(this).apply {
                text = "删除"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    RecordStore.get(this@MainActivity).deleteRecord(record.id)
                    renderRecords()
                }
            })
            row.addView(actions)
            container.addView(row)
        }
    }

    // ---------------- 定时任务 ----------------

    private fun showScheduleDialog(recordId: String) {
        val items = arrayOf("1分钟后执行一次", "5分钟后执行一次", "每5分钟重复", "创建日历事件触发")
        AlertDialog.Builder(this)
            .setTitle("选择定时方式")
            .setItems(items) { _, which ->
                val now = System.currentTimeMillis()
                val task = when (which) {
                    0 -> ScheduleTask(
                        id = UUID.randomUUID().toString(), recordId = recordId,
                        name = "一次:1分钟", type = ScheduleTask.ScheduleType.ONCE,
                        triggerAtMillis = now + 60_000
                    )
                    1 -> ScheduleTask(
                        id = UUID.randomUUID().toString(), recordId = recordId,
                        name = "一次:5分钟", type = ScheduleTask.ScheduleType.ONCE,
                        triggerAtMillis = now + 300_000
                    )
                    2 -> ScheduleTask(
                        id = UUID.randomUUID().toString(), recordId = recordId,
                        name = "重复:5分钟", type = ScheduleTask.ScheduleType.INTERVAL,
                        triggerAtMillis = now + 300_000, intervalMillis = 300_000
                    )
                    else -> {
                        // 日历事件触发：创建一个 1 分钟后的日历事件
                        val calId = CalendarHelper(this).createEvent(
                            "AutoX 回放", now + 60_000, now + 120_000
                        )
                        ScheduleTask(
                            id = UUID.randomUUID().toString(), recordId = recordId,
                            name = "日历事件", type = ScheduleTask.ScheduleType.CALENDAR,
                            triggerAtMillis = now + 60_000, calendarEventId = calId
                        )
                    }
                }
                val ok = ScheduleManager(this).schedule(task)
                Toast.makeText(
                    this,
                    if (ok) "定时任务已创建" else "创建失败：缺少精确闹钟权限",
                    Toast.LENGTH_SHORT
                ).show()
                renderTasks()
            }
            .show()
    }

    private fun renderTasks() {
        val container = binding.tasksContainer
        container.removeAllViews()
        val tasks = RecordStore.get(this).getAllTasks()
        if (tasks.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无定时任务"
                setPadding(0, 16, 0, 16)
            })
            return
        }
        tasks.forEach { task ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(TextView(this).apply {
                text = "${task.name} [${task.type}] ${if (task.enabled) "启用" else "已禁用"}"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
            row.addView(Button(this).apply {
                text = "取消"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    ScheduleManager(this@MainActivity).cancel(task.id)
                    renderTasks()
                }
            })
            container.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PermissionHelper.REQ_OVERLAY ||
            requestCode == PermissionHelper.REQ_BATTERY
        ) {
            refreshPermissionStatus()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionStatus()
    }
}
