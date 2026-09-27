package com.hneko.autox.scheduler

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log

/**
 * 日历操作助手：查询/创建日历事件
 * 创建事件后可将事件开始时间作为定时触发点
 */
class CalendarHelper(private val context: Context) {

    companion object {
        private const val TAG = "CalendarHelper"
    }

    data class CalendarEvent(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long
    )

    /** 查询指定时间段内的日历事件 */
    fun queryEvents(fromMillis: Long, toMillis: Long): List<CalendarEvent> {
        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(fromMillis.toString(), toMillis.toString())
        val result = mutableListOf<CalendarEvent>()

        runCatching {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        }.getOrNull()?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1) ?: ""
                val begin = cursor.getLong(2)
                val end = cursor.getLong(3)
                result.add(CalendarEvent(id, title, begin, end))
            }
        }.onFailure { Log.e(TAG, "查询日历失败", it) }
        return result
    }

    /**
     * 创建一个日历事件（用于关联回放任务）
     * @return 事件 ID，失败返回 -1
     */
    fun createEvent(title: String, beginMillis: Long, endMillis: Long): Long {
        val calId = getDefaultCalendarId() ?: run {
            Log.e(TAG, "没有可用的日历账户")
            return -1
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLong() ?: -1
        }.onFailure { Log.e(TAG, "创建日历事件失败", it) }.getOrDefault(-1)
    }

    private fun getDefaultCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    /** 根据事件 ID 获取事件开始时间 */
    fun getEventBegin(eventId: Long): Long {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return runCatching {
            context.contentResolver.query(uri, arrayOf(CalendarContract.Events.DTSTART), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
        }.getOrDefault(0L)
    }
}
