package com.autox.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.autox.assistant.scheduler.ScheduleManager

/**
 * 开机自启接收器：恢复已注册的定时任务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.i(TAG, "开机自启，恢复定时任务")
                ScheduleManager(context).restoreAll()
            }
        }
    }
}
