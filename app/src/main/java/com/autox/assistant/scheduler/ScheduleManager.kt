package com.autox.assistant.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.autox.assistant.model.ScheduleTask
import com.autox.assistant.receiver.SchedulerReceiver
import com.autox.assistant.store.RecordStore

/**
 * 定时调度管理器：基于 AlarmManager 注册精确闹钟触发回放
 * 支持一次性、间隔重复、日历事件触发
 */
class ScheduleManager(private val context: Context) {

    companion object {
        private const val TAG = "ScheduleManager"
        const val EXTRA_TASK_ID = "task_id"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = RecordStore.get(context)

    /**
     * 注册一个定时任务
     * @return 是否注册成功（Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限）
     */
    fun schedule(task: ScheduleTask): Boolean {
        store.saveTask(task)
        val intent = Intent(context, SchedulerReceiver::class.java).apply {
            action = SchedulerReceiver.ACTION_TRIGGER
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val pi = PendingIntent.getBroadcast(
            context, task.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return when (task.type) {
            ScheduleTask.ScheduleType.ONCE -> scheduleExact(task.triggerAtMillis, pi)
            ScheduleTask.ScheduleType.INTERVAL -> {
                // 先注册下一次触发，触发后由接收器重新注册
                scheduleExact(task.triggerAtMillis, pi)
            }
            ScheduleTask.ScheduleType.CALENDAR -> {
                // 日历事件触发：以事件开始时间为准
                scheduleExact(task.triggerAtMillis, pi)
            }
        }
    }

    fun cancel(taskId: String) {
        val intent = Intent(context, SchedulerReceiver::class.java).apply {
            action = SchedulerReceiver.ACTION_TRIGGER
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val pi = PendingIntent.getBroadcast(
            context, taskId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
        val task = store.getTask(taskId)
        if (task != null) {
            store.saveTask(task.copy(enabled = false))
        }
        Log.i(TAG, "已取消定时任务: $taskId")
    }

    /**
     * 重新调度（用于间隔任务在触发后注册下一次，以及开机恢复）
     */
    fun rescheduleNext(task: ScheduleTask) {
        if (task.type != ScheduleTask.ScheduleType.INTERVAL || task.intervalMillis <= 0) return
        val next = System.currentTimeMillis() + task.intervalMillis
        val updated = task.copy(triggerAtMillis = next, enabled = true)
        schedule(updated)
        Log.i(TAG, "间隔任务下一次触发: $next")
    }

    /** 开机后恢复所有启用中的任务 */
    fun restoreAll() {
        store.getAllTasks().filter { it.enabled }.forEach { task ->
            // 已过期的一次性任务跳过
            if (task.type == ScheduleTask.ScheduleType.ONCE && task.triggerAtMillis < System.currentTimeMillis()) {
                return@forEach
            }
            schedule(task)
        }
        Log.i(TAG, "已恢复定时任务")
    }

    /** 检查是否拥有精确闹钟权限（Android 12+） */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun scheduleExact(triggerAtMillis: Long, pi: PendingIntent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pi
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
            Log.i(TAG, "已注册精确闹钟触发于 $triggerAtMillis")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少精确闹钟权限", e)
            false
        }
    }
}
