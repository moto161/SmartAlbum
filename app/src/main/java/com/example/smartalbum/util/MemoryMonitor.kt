package com.example.smartalbum.util

import android.os.Handler
import android.os.Looper

/**
 * 内存监控工具
 * 定期记录内存使用情况，方便排查OOM
 */
object MemoryMonitor {
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private const val INTERVAL = 60000L  // 每分钟记录一次

    private val runnable = object : Runnable {
        override fun run() {
            logMemory()
            handler.postDelayed(this, INTERVAL)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        logMemory()
        handler.postDelayed(runnable, INTERVAL)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(runnable)
    }

    fun logMemory() {
        try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val usedMemory = totalMemory - freeMemory
            val usagePercent = (usedMemory * 100 / maxMemory).toInt()

            val level = when {
                usagePercent > 85 -> "CRITICAL"
                usagePercent > 70 -> "WARNING"
                else -> "OK"
            }

            LogUtil.i("Memory", "[$level] 已用: ${usedMemory}MB / ${maxMemory}MB (${usagePercent}%), 空闲: ${freeMemory}MB")

            // 内存超过85%时记录警告（不主动调用GC，由系统决定）
            if (usagePercent > 85) {
                LogUtil.w("Memory", "内存使用率过高，建议释放资源")
            }
        } catch (e: Exception) {
            LogUtil.e("MemoryMonitor", "异常", e)
        }
    }
}
