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
import java.util.Calendar
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = OperationExecutor()
    private var replayJob: Job? = null

    private val multiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionStatus()
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
        // 收集需要申请的运行时权限，一次性请求（避免多次请求冲突）
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasNotificationPermission(this)
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_CALENDAR)
            needed.add(Manifest.permission.WRITE_CALENDAR)
        }
        if (needed.isNotEmpty()) {
            multiPermissionLauncher.launch(needed.toTypedArray())
        }

        // 悬浮窗
        if (!PermissionHelper.hasOverlayPermission(this)) {
            PermissionHelper.requestOverlayPermission(this)
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
        val ctx = this
        val cal = Calendar.getInstance()
        // 初始时间设为 1 分钟后
        cal.add(Calendar.MINUTE, 1)

        // 类型选择
        val typeLabels = arrayOf("一次性执行", "间隔重复", "日历事件触发")
        var selectedType = 0

        // 日期/时间选择器
        val datePicker = android.widget.DatePicker(ctx).apply {
            init(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), null)
        }
        val timePicker = android.widget.TimePicker(ctx).apply {
            setIs24HourView(true)
            hour = cal.get(Calendar.HOUR_OF_DAY)
            minute = cal.get(Calendar.MINUTE)
        }

        // 间隔输入
        val intervalInput = android.widget.EditText(ctx).apply {
            hint = "重复间隔（分钟）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("5")
        }
        val intervalRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(ctx).apply { text = "间隔："; setPadding(0, 24, 8, 0) })
            addView(intervalInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply { text = " 分钟"; setPadding(8, 24, 0, 0) })
        }

        // 日历事件选择
        val calendarEvents = CalendarHelper(ctx).queryEvents(
            System.currentTimeMillis(),
            System.currentTimeMillis() + 30L * 24 * 3600 * 1000  // 未来 30 天
        )
        val eventLabels = if (calendarEvents.isEmpty()) {
            arrayOf("（暂无日历事件，请先在系统日历中创建）")
        } else {
            calendarEvents.map {
                val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it.begin))
                "$t  ${it.title}"
            }.toTypedArray()
        }
        var selectedEventIdx = 0
        val eventPicker = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, eventLabels)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    selectedEventIdx = pos
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

        // 根布局
        val root = android.widget.ScrollView(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(TextView(ctx).apply { text = "触发类型"; setPadding(0, 0, 0, 8) })
        }

        val typeGroup = android.widget.RadioGroup(ctx).apply {
            typeLabels.forEachIndexed { i, label ->
                addView(android.widget.RadioButton(ctx).apply {
                    text = label
                    id = i
                    if (i == 0) isChecked = true
                })
            }
            setOnCheckedChangeListener { _, checkedId ->
                selectedType = checkedId
                // 切换可见性
                intervalRow.visibility = if (checkedId == 1) android.view.View.VISIBLE else android.view.View.GONE
                eventPicker.visibility = if (checkedId == 2) android.view.View.VISIBLE else android.view.View.GONE
                datePicker.visibility = if (checkedId == 2) android.view.View.GONE else android.view.View.VISIBLE
                timePicker.visibility = if (checkedId == 2) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        content.addView(typeGroup)

        content.addView(datePicker)
        content.addView(timePicker)
        content.addView(intervalRow)
        content.addView(TextView(ctx).apply { text = "选择日历事件："; setPadding(0, 16, 0, 8) })
        content.addView(eventPicker)

        // 初始状态：只显示日期/时间选择
        intervalRow.visibility = android.view.View.GONE
        eventPicker.visibility = android.view.View.GONE

        root.addView(content)

        AlertDialog.Builder(ctx)
            .setTitle("设置定时触发")
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val task = when (selectedType) {
                    0 -> {
                        // 一次性
                        val trigger = buildTimeMillis(datePicker, timePicker)
                        if (trigger <= System.currentTimeMillis()) {
                            Toast.makeText(ctx, "触发时间必须晚于当前时间", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        ScheduleTask(
                            id = UUID.randomUUID().toString(), recordId = recordId,
                            name = "一次:${formatTime(trigger)}", type = ScheduleTask.ScheduleType.ONCE,
                            triggerAtMillis = trigger
                        )
                    }
                    1 -> {
                        // 间隔重复
                        val intervalMin = intervalInput.text.toString().toLongOrNull() ?: 0L
                        if (intervalMin <= 0) {
                            Toast.makeText(ctx, "请输入有效的间隔分钟数", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val trigger = buildTimeMillis(datePicker, timePicker)
                        if (trigger <= System.currentTimeMillis()) {
                            Toast.makeText(ctx, "首次触发时间必须晚于当前时间", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        ScheduleTask(
                            id = UUID.randomUUID().toString(), recordId = recordId,
                            name = "重复:${intervalMin}分钟", type = ScheduleTask.ScheduleType.INTERVAL,
                            triggerAtMillis = trigger, intervalMillis = intervalMin * 60_000
                        )
                    }
                    else -> {
                        // 日历事件
                        if (calendarEvents.isEmpty()) {
                            Toast.makeText(ctx, "没有可选的日历事件", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        val event = calendarEvents[selectedEventIdx]
                        ScheduleTask(
                            id = UUID.randomUUID().toString(), recordId = recordId,
                            name = "日历:${event.title}", type = ScheduleTask.ScheduleType.CALENDAR,
                            triggerAtMillis = event.begin, calendarEventId = event.id
                        )
                    }
                }
                val ok = ScheduleManager(ctx).schedule(task)
                Toast.makeText(
                    ctx,
                    if (ok) "定时任务已创建：${task.name}" else "创建失败：缺少精确闹钟权限",
                    Toast.LENGTH_LONG
                ).show()
                renderTasks()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildTimeMillis(dp: android.widget.DatePicker, tp: android.widget.TimePicker): Long {
        return Calendar.getInstance().apply {
            set(dp.year, dp.month, dp.dayOfMonth, tp.hour, tp.minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatTime(millis: Long): String {
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
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
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        tasks.forEach { task ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val typeText = when (task.type) {
                ScheduleTask.ScheduleType.ONCE -> "一次性"
                ScheduleTask.ScheduleType.INTERVAL -> "间隔 ${task.intervalMillis / 60_000} 分钟"
                ScheduleTask.ScheduleType.CALENDAR -> "日历事件"
            }
            val status = if (task.enabled) "✓ 启用" else "✗ 已禁用"
            row.addView(TextView(this).apply {
                text = "${task.name}  ·  $typeText  ·  $status"
            })
            row.addView(TextView(this).apply {
                text = "触发时间：${sdf.format(java.util.Date(task.triggerAtMillis))}"
                setTextColor(android.graphics.Color.GRAY)
                textSize = 12f
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = "取消"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    ScheduleManager(this@MainActivity).cancel(task.id)
                    renderTasks()
                }
            })
            actions.addView(Button(this).apply {
                text = "删除"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    RecordStore.get(this@MainActivity).deleteTask(task.id)
                    renderTasks()
                }
            })
            row.addView(actions)
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
