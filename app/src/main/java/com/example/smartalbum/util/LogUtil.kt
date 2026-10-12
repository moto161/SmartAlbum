package com.example.smartalbum.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object LogUtil {

    /**
     * 敏感信息脱敏
     * 对日志中的经纬度、设备ID、文件路径等敏感信息进行脱敏处理
     */
    private fun sanitize(message: String): String {
        var result = message
        // 经纬度脱敏：保留前2位小数（约1公里精度）
        result = result.replace(Regex("""(latitude|longitude|lat|lng)[=:\s]+(-?\d+\.\d{2})\d+"""), "$1=$2***")
        // 设备ID脱敏：保留前4位和后4位
        val deviceIdPattern = """(device_id|deviceId|android_id)[=:\s]+([A-Za-z0-9]{4})[A-Za-z0-9]+([A-Za-z0-9]{4})"""
        val deviceIdRegex = Regex(deviceIdPattern)
        result = result.replace(deviceIdRegex, "$1=$2***$3")
        // 文件路径脱敏：只保留文件名
        result = result.replace(Regex("""/storage/emulated/\d+/[^\s]+/([^/\s]+)"""), "/storage/***$1")
        result = result.replace(Regex("""/sdcard/[^\s]+/([^/\s]+)"""), "/sdcard/***$1")
        return result
    }


    enum class Level {
        DEBUG, INFO, WARN, ERROR, CRASH
    }

    data class LogEntry(
        val time: String,
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private val logs = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private const val MAX_LOGS = 2000
    private const val RETENTION_DAYS = 1L // 只保留1天

    // 2026-10-10 性能优化：日志写盘 debounce
    // 之前每写一条日志都 new Thread 全量重写文件，2000 条日志刷 200 次 = 磁盘被写 200 次 × N 字节。
    // 改成：日志来了先入内存，postDelayed 500ms 后才真正写盘；
    // 500ms 内又来了新日志就 removeCallbacks 再 postDelayed，合并成一次写。
    private const val WRITE_DEBOUNCE_MS = 500L

    private var context: Context? = null
    private var logFile: File? = null

    // 单线程 HandlerThread，串行处理写盘，避免每条日志 new Thread 的开销
    private var writeHandlerThread: HandlerThread? = null
    private var writeHandler: Handler? = null

    /**
     * 初始化日志工具（在Application或MainActivity中调用）
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        logFile = File(context?.filesDir, "app_logs.txt")
        // 启动单线程 HandlerThread 用于串行写盘
        if (writeHandlerThread == null) {
            writeHandlerThread = HandlerThread("LogWriter").also { it.start() }
            writeHandler = Handler(writeHandlerThread!!.looper)
        }
        // 从文件加载历史日志
        loadLogsFromFile()
        // 清理超过1天的日志
        cleanOldLogs()
    }

    fun d(tag: String, message: String) {
        addLog(Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        addLog(Level.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        addLog(Level.WARN, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            message + "\n" + throwable.stackTraceToString()
        } else {
            message
        }
        addLog(Level.ERROR, tag, fullMessage)
    }

    fun crash(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            message + "\n" + throwable.stackTraceToString()
        } else {
            message
        }
        addLog(Level.CRASH, tag, fullMessage)
    }

    private fun addLog(level: Level, tag: String, message: String) {
        synchronized(logs) {
            val now = System.currentTimeMillis()
            val sanitizedMessage = sanitize(message)
            val entry = LogEntry(
                time = dateFormat.format(Date(now)),
                timestamp = now,
                level = level,
                tag = tag,
                message = sanitizedMessage
            )
            logs.add(entry)
            
            // 同时输出到logcat
            when (level) {
                Level.DEBUG -> android.util.Log.d(tag, sanitizedMessage)
                Level.INFO -> android.util.Log.i(tag, sanitizedMessage)
                Level.WARN -> android.util.Log.w(tag, sanitizedMessage)
                Level.ERROR -> android.util.Log.e(tag, sanitizedMessage)
                Level.CRASH -> android.util.Log.e(tag, "CRASH: $sanitizedMessage")
            }
            
            // 清理超过1天的日志
            cleanOldLogs()
            
            // 限制最大数量
            while (logs.size > MAX_LOGS) {
                logs.removeAt(0)
            }
            
            // 异步保存到文件
            saveLogsToFileAsync()
        }
    }

    /**
     * 清理超过1天的日志
     */
    private fun cleanOldLogs() {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val iterator = logs.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().timestamp < cutoffTime) {
                iterator.remove()
            } else {
                break // 日志是按时间顺序添加的，后面的都是新的
            }
        }
    }

    /**
     * 从文件加载历史日志
     */
    private fun loadLogsFromFile() {
        try {
            logFile?.let { file ->
                if (!file.exists()) return
                val lines = file.readLines()
                val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        // 格式：timestamp|level|tag|message
                        val parts = line.split("|", limit = 4)
                        if (parts.size >= 4) {
                            val timestamp = parts[0].toLong()
                            if (timestamp >= cutoffTime) {
                                val level = Level.valueOf(parts[1])
                                val tag = parts[2]
                                val message = parts[3].replace("\\n", "\n")
                                logs.add(LogEntry(
                                    time = dateFormat.format(Date(timestamp)),
                                    timestamp = timestamp,
                                    level = level,
                                    tag = tag,
                                    message = message
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        LogUtil.d("LogUtil", "跳过解析失败的行: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LogUtil", "加载日志文件失败: ${e.message}")
        }
    }

    /**
     * 异步保存日志到文件（debounce 500ms）
     *
     * 2026-10-10 性能优化：
     * 之前每写一条日志都 new Thread 全量重写文件，磁盘写放大严重。
     * 现在用单线程 Handler + postDelayed：日志来了先 removeCallbacks 再 postDelayed，
     * 500ms 内没新日志才真正写一次，期间所有日志合并写。
     */
    private fun saveLogsToFileAsync() {
        val handler = writeHandler ?: return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            try {
                logFile?.let { file ->
                    val sb = StringBuilder()
                    synchronized(logs) {
                        for (entry in logs) {
                            sb.append(entry.timestamp)
                            sb.append("|")
                            sb.append(entry.level.name)
                            sb.append("|")
                            sb.append(entry.tag)
                            sb.append("|")
                            sb.append(entry.message.replace("\n", "\\n"))
                            sb.append("\n")
                        }
                    }
                    file.writeText(sb.toString())
                }
            } catch (e: Exception) {
                android.util.Log.e("LogUtil", "保存日志文件失败: ${e.message}")
            }
        }, WRITE_DEBOUNCE_MS)
    }

    fun getAllLogs(): List<LogEntry> {
        synchronized(logs) {
            return logs.toList()
        }
    }

    fun getLogsAsString(): String {
        synchronized(logs) {
            if (logs.isEmpty()) return "暂无日志记录"
            return logs.joinToString("\n\n") { entry ->
                "[${entry.time}] [${entry.level.name}] [${entry.tag}]\n${entry.message}"
            }
        }
    }

    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
        }
        // 取消 pending 的写盘任务，否则刚清空又被异步写回
        writeHandler?.removeCallbacksAndMessages(null)
        logFile?.delete()
    }
}
