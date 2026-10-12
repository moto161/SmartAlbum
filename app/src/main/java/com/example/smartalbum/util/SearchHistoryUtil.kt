package com.example.smartalbum.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 搜索历史记录管理工具
 * 使用SharedPreferences保存最近搜索记录
 * 不依赖Gson，使用简单的字符串分隔符
 */
object SearchHistoryUtil {
    private const val PREFS_NAME = "search_history"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_HISTORY_SIZE = 10
    private const val SEPARATOR = "|||"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getHistory(): List<String> {
        if (!::prefs.isInitialized) return emptyList()
        val saved = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        if (saved.isBlank()) return emptyList()
        return saved.split(SEPARATOR).filter { it.isNotBlank() }
    }

    fun addHistory(keyword: String) {
        if (!::prefs.isInitialized || keyword.isBlank()) return

        val history = getHistory().toMutableList()
        // 移除已存在的相同关键词
        history.remove(keyword)
        // 添加到开头
        history.add(0, keyword)
        // 限制最大数量
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.size - 1)
        }
        // 保存
        prefs.edit().putString(KEY_HISTORY, history.joinToString(SEPARATOR)).apply()
    }

    fun clearHistory() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun getMaxHistorySize(): Int = MAX_HISTORY_SIZE
}
