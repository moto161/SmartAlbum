package com.example.smartalbum.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 崩溃处理类
 * 捕获APP崩溃异常，记录完整堆栈到文件
 */
class CrashHandler(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 记录完整崩溃信息到日志
            val stackTrace = buildString {
                append("=== APP崩溃 ===\n")
                append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                append("线程: ${thread.name}\n")
                append("异常类型: ${throwable.javaClass.name}\n")
                append("错误信息: ${throwable.message}\n")
                append("=== 完整堆栈 ===\n")
                throwable.stackTrace.forEach { append("    at $it\n") }
                // 记录cause链
                var cause = throwable.cause
                while (cause != null) {
                    append("=== Caused by: ${cause.javaClass.name}: ${cause.message} ===\n")
                    cause.stackTrace.forEach { append("    at $it\n") }
                    cause = cause.cause
                }
                append("=== 内存信息 ===\n")
                val runtime = Runtime.getRuntime()
                append("最大内存: ${runtime.maxMemory() / 1024 / 1024}MB\n")
                append("已用内存: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024}MB\n")
                append("可用内存: ${runtime.freeMemory() / 1024 / 1024}MB\n")
            }

            LogUtil.crash("CrashHandler", stackTrace, throwable)

            // 同时写入独立的崩溃文件（方便查看）
            try {
                val crashDir = File(context.filesDir, "crashes")
                if (!crashDir.exists()) crashDir.mkdirs()
                val crashFile = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
                PrintWriter(FileWriter(crashFile)).use { it.write(stackTrace) }
            } catch (e: Exception) {
                LogUtil.e("CrashHandler", "异常", e)
            }
        } catch (e: Exception) {
            LogUtil.e("CrashHandler", "异常", e)
        }

        // 等待1秒，让日志写入
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            LogUtil.e("CrashHandler", "异常", e)
        }

        // 调用默认的崩溃处理
        defaultHandler?.uncaughtException(thread, throwable)

        // 退出APP
        exitProcess(1)
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
            LogUtil.i("CrashHandler", "崩溃处理器已初始化（增强版：记录完整堆栈）")
        }
    }
}
