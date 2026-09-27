package com.hneko.autox.store

import android.content.Context
import android.content.SharedPreferences
import com.hneko.autox.model.OperationRecord
import com.hneko.autox.model.ScheduleTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 录制记录与定时任务的本地持久化（基于 SharedPreferences + Gson）
 */
class RecordStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("autox_store", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_RECORDS = "records"
        private const val KEY_TASKS = "tasks"
        private const val KEY_LAST_RECORD_ID = "last_record_id"

        @Volatile
        private var instance: RecordStore? = null

        fun get(context: Context): RecordStore {
            return instance ?: synchronized(this) {
                instance ?: RecordStore(context.applicationContext).also { instance = it }
            }
        }
    }

    // ---------- 录制记录 ----------

    fun getAllRecords(): List<OperationRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<OperationRecord>>(json, object : TypeToken<List<OperationRecord>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun getRecord(id: String): OperationRecord? {
        return getAllRecords().firstOrNull { it.id == id }
    }

    fun saveRecord(record: OperationRecord) {
        val list = getAllRecords().toMutableList().apply {
            removeAll { it.id == record.id }
            add(0, record)
        }
        prefs.edit().putString(KEY_RECORDS, gson.toJson(list)).apply()
    }

    fun deleteRecord(id: String) {
        val list = getAllRecords().filterNot { it.id == id }
        prefs.edit().putString(KEY_RECORDS, gson.toJson(list)).apply()
    }

    fun nextRecordId(): String {
        val n = prefs.getInt(KEY_LAST_RECORD_ID, 0) + 1
        prefs.edit().putInt(KEY_LAST_RECORD_ID, n).apply()
        return "R$n"
    }

    // ---------- 定时任务 ----------

    fun getAllTasks(): List<ScheduleTask> {
        val json = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<ScheduleTask>>(json, object : TypeToken<List<ScheduleTask>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun getTask(id: String): ScheduleTask? {
        return getAllTasks().firstOrNull { it.id == id }
    }

    fun saveTask(task: ScheduleTask) {
        val list = getAllTasks().toMutableList().apply {
            removeAll { it.id == task.id }
            add(task)
        }
        prefs.edit().putString(KEY_TASKS, gson.toJson(list)).apply()
    }

    fun deleteTask(id: String) {
        val list = getAllTasks().filterNot { it.id == id }
        prefs.edit().putString(KEY_TASKS, gson.toJson(list)).apply()
    }
}
