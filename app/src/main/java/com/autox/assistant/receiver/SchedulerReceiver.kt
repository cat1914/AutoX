package com.autox.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.autox.assistant.executor.OperationExecutor
import com.autox.assistant.model.ScheduleTask
import com.autox.assistant.scheduler.ScheduleManager
import com.autox.assistant.store.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 定时触发广播接收器：闹钟到期后执行对应记录的回放
 */
class SchedulerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER = "com.autox.assistant.ACTION_TRIGGER_REPLAY"
        private const val TAG = "SchedulerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return
        val taskId = intent.getStringExtra(ScheduleManager.EXTRA_TASK_ID) ?: return
        Log.i(TAG, "定时触发: $taskId")

        val task = RecordStore.get(context).getTask(taskId) ?: run {
            Log.w(TAG, "任务不存在: $taskId")
            return
        }
        val record = RecordStore.get(context).getRecord(task.recordId) ?: run {
            Log.w(TAG, "记录不存在: ${task.recordId}")
            return
        }

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        scope.launch {
            val executor = OperationExecutor()
            val ok = executor.replay(record)
            Log.i(TAG, "定时回放完成: $taskId success=$ok")
        }

        // 间隔任务：注册下一次触发
        if (task.type == ScheduleTask.ScheduleType.INTERVAL) {
            ScheduleManager(context).rescheduleNext(task)
        } else {
            // 一次性/日历任务触发后禁用
            RecordStore.get(context).saveTask(task.copy(enabled = false))
        }
    }
}
