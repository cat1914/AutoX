package com.hneko.autox.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志收集器：
 * 1. 通过 logcat 捕获本应用日志（按 PID 过滤）
 * 2. 维护内存环形缓冲区（最近 N 行）
 * 3. 同时写入滚动日志文件，避免崩溃丢失
 * 4. 支持导出为单个日志文件
 */
object LogCollector {

    private const val TAG = "LogCollector"
    private const val MAX_BUFFER_LINES = 2000
    private const val MAX_LOG_FILE_SIZE = 2 * 1024 * 1024L  // 2MB

    private val buffer = ConcurrentLinkedDeque<String>()
    private val running = AtomicBoolean(false)
    private var collectorThread: Thread? = null
    private var logFile: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 启动日志收集
     */
    fun start(context: Context) {
        if (running.getAndSet(true)) return

        // 准备日志文件（应用外部文件目录，无需存储权限）
        val logDir = File(context.getExternalFilesDir(null), "logs")
        if (!logDir.exists()) logDir.mkdirs()
        logFile = File(logDir, "autox.log")

        collectorThread = Thread({
            runCatching { captureLoop() }
                .onFailure { Log.e(TAG, "日志收集线程异常", it) }
        }, "LogCollector").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "日志收集已启动，日志文件: ${logFile?.absolutePath}")
    }

    fun stop() {
        running.set(false)
        collectorThread?.interrupt()
        collectorThread = null
    }

    private fun captureLoop() {
        val pid = android.os.Process.myPid()
        val cmd = arrayOf("logcat", "-v", "time", "--pid=$pid")

        var process: Process? = null
        var reader: BufferedReader? = null
        try {
            process = Runtime.getRuntime().exec(cmd)
            reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (running.get()) {
                line = reader.readLine() ?: break
                if (line.isNotEmpty()) {
                    appendLine(line)
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "logcat 读取失败", e)
        } finally {
            runCatching { reader?.close() }
            runCatching { process?.destroy() }
        }
    }

    private fun appendLine(line: String) {
        // 内存环形缓冲
        buffer.addLast(line)
        while (buffer.size > MAX_BUFFER_LINES) {
            buffer.pollFirst()
        }
        // 写入文件（滚动）
        runCatching {
            logFile?.let { f ->
                if (f.exists() && f.length() > MAX_LOG_FILE_SIZE) {
                    // 滚动：重命名为 .1，创建新文件
                    val old = File(f.parentFile, "autox.log.1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                f.appendText("$line\n")
            }
        }
    }

    /**
     * 获取内存中的日志（最近的）
     */
    fun getRecentLogs(): String {
        return buffer.joinToString("\n")
    }

    /**
     * 导出日志到公共 Downloads 目录（使用 MediaStore，无需存储权限）
     * @return 导出的文件 Uri，失败返回 null
     */
    fun exportToDownloads(context: Context): Uri? {
        return runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "autox_logs_$timestamp.txt"

            val sb = StringBuilder()
            sb.append("===== AutoX Log Export =====\n")
            sb.append("Export time: ${dateFormat.format(Date())}\n")
            sb.append("PID: ${android.os.Process.myPid()}\n")
            sb.append("Package: ${context.packageName}\n")
            sb.append("=============================\n\n")

            sb.append("----- Recent logs (memory buffer) -----\n")
            sb.append(getRecentLogs())
            sb.append("\n\n")

            logFile?.let { f ->
                if (f.exists()) {
                    sb.append("----- Full log file -----\n")
                    sb.append(f.readText())
                }
                val rolled = File(f.parentFile, "autox.log.1")
                if (rolled.exists()) {
                    sb.append("\n----- Previous log file -----\n")
                    sb.append(rolled.readText())
                }
            }

            val content = sb.toString()

            // Android 10+ 使用 MediaStore 写入 Downloads，无需存储权限
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert 返回 null")

            resolver.openOutputStream(uri)?.use { os: OutputStream ->
                os.write(content.toByteArray())
            } ?: throw IllegalStateException("无法打开输出流")

            Log.i(TAG, "日志已导出至 Downloads: $fileName (${content.length} chars)")
            uri
        }.onFailure { Log.e(TAG, "导出日志失败", it) }.getOrNull()
    }

    /**
     * 获取日志文件大小信息
     */
    fun getLogFileInfo(): String {
        val f = logFile ?: return "未初始化"
        val size = if (f.exists()) f.length() else 0
        return "${f.absolutePath} (${size / 1024} KB)"
    }
}
