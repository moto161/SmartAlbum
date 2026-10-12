package com.example.smartalbum.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * 主题管理器 - 三套UI主题快速切换
 * 主题1：清新白（Apple风） 主题2：温暖奶油（Pinterest风） 主题3：暗夜极简（Linear风）
 */
object ThemeManager {

    enum class Theme(val id: Int, val displayName: String) {
        LIGHT(0, "清新白"),
        CREAM(1, "温暖奶油"),
        DARK(2, "暗夜极简")
    }

    // 主题变化回调接口
    fun interface OnThemeChangedListener {
        fun onThemeChanged(theme: Theme)
    }

    private const val PREFS_NAME = "app_settings"
    private const val KEY_THEME = "ui_theme"

    private var currentTheme: Theme = Theme.LIGHT
    private var prefs: SharedPreferences? = null
    private val listeners = mutableListOf<OnThemeChangedListener>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs?.getInt(KEY_THEME, Theme.LIGHT.id) ?: Theme.LIGHT.id
        currentTheme = Theme.entries.find { it.id == savedId } ?: Theme.LIGHT
    }

    fun getCurrentTheme(): Theme = currentTheme

    fun setTheme(theme: Theme) {
        if (currentTheme == theme) return
        currentTheme = theme
        prefs?.edit()?.putInt(KEY_THEME, theme.id)?.apply()
        // 通知所有监听者
        listeners.forEach { it.onThemeChanged(theme) }
    }

    fun addOnThemeChangedListener(listener: OnThemeChangedListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeOnThemeChangedListener(listener: OnThemeChangedListener) {
        listeners.remove(listener)
    }

    // 背景色
    fun getBackgroundColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#FFFFFF")
        // 2026-10-04 调整：奶油主题原用 #FBFBF9（灰白），观感发冷发脏。
        // 改为真正的暖米色 #FAF6EF，符合"奶油"这个名字。
        Theme.CREAM -> Color.parseColor("#FAF6EF")
        // 2026-10-04 调整：暗夜主题原用 #010102（近纯黑），与卡片 #0F1011
        // 的亮度差只有 0.0048，几乎看不出卡片边界，整屏发闷。
        // 改为深炭灰 #16181A，卡片再提亮到 #22252A，层次分明又不刺眼。
        Theme.DARK -> Color.parseColor("#16181A")
    }

    // 卡片/表面色
    fun getCardColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#F5F5F7")
        Theme.CREAM -> Color.parseColor("#FFFFFF")
        Theme.DARK -> Color.parseColor("#22252A")
    }

    // 主文字色
    fun getTextColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#1D1D1F")
        Theme.CREAM -> Color.parseColor("#2E2620")
        Theme.DARK -> Color.parseColor("#F2F4F5")
    }

    // 次要文字色
    fun getSecondaryTextColor(): Int = when (currentTheme) {
        // 2026-10-04 调整：#86868B 在 #F5F5F7 卡片上只有 3.33:1，
        // 小字号（11-12sp）偏淡难读，加深到 #6E6E73（约 5.0:1）。
        Theme.LIGHT -> Color.parseColor("#6E6E73")
        Theme.CREAM -> Color.parseColor("#7A6A5C")
        Theme.DARK -> Color.parseColor("#9AA0A6")
    }

    // 强调色（按钮、链接、选中态）
    fun getAccentColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#0066CC")
        // 2026-10-04 调整：原 #E60023 是正红（饱和度100%），与"奶油"语义完全冲突，
        // 且大面积按钮刺眼。改为暖陶土色 #A85B2A（饱和度60%）。
        // 注意：初版选的 #C97B4A 白字对比度只有 3.27:1（不达标），
        // 用 Python 按色相区间扫描求解出这个值，白字 5.01:1 达标。
        Theme.CREAM -> Color.parseColor("#A85B2A")
        // 2026-10-04 调整：原 #5E6AD2 蓝紫在近纯黑背景上对比生硬。
        // 改为柔和蓝 #3D6E9E（饱和度44%），深底上柔和不刺眼，白字 5.35:1 达标。
        // 同理：初版选的 #7FB3D3 白字只有 2.26:1，已求解替换。
        Theme.DARK -> Color.parseColor("#3D6E9E")
    }

    // 分割线色
    fun getDividerColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#D2D2D7")
        Theme.CREAM -> Color.parseColor("#E8DFD2")
        Theme.DARK -> Color.parseColor("#33373C")
    }

    // 底部导航栏背景色
    fun getBottomNavColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#FFFFFF")
        Theme.CREAM -> Color.parseColor("#FAF6EF")
        Theme.DARK -> Color.parseColor("#1C1F22")
    }

    // 底部导航选中图标色
    fun getBottomNavSelectedColor(): Int = getAccentColor()

    // 底部导航未选中图标色
    fun getBottomNavUnselectedColor(): Int = getSecondaryTextColor()

    // 按钮文字色
    fun getButtonTextColor(): Int = Color.parseColor("#FFFFFF")

    /**
     * 危险操作色（清除缓存、删除等会丢数据的操作）。
     * 2026-10-04 新增：此前危险色硬编码在 layout 的 backgroundTint 里，
     * 而普通按钮用 getAccentColor()，两者在部分主题下会同色，
     * 用户无法从颜色区分「安全操作」和「会删数据的操作」。
     *
     * 2026-10-04 二次修正：原 #F44336 配白字只有 3.68:1（三个主题都一样），
     * 按钮文字看不清 → 改为 #C62828，白字 5.62:1 达标，仍是明确的红色警示。
     */
    fun getDangerColor(): Int = Color.parseColor("#C62828")

    /**
     * 强调色的"浅色变体"：专用于**图标/选中态**等需要在大面积卡片上保持可见的元素。
     * 2026-10-04 新增：暗夜主题下 getAccentColor()(#3D6E9E) 作图标只有 2.87:1，太暗看不见；
     * 但同一色相作按钮底配白字是 5.35:1（达标）。
     * → 说明按钮底和图标需要不同明度的同色相，不能共用一个值。
     */
    fun getAccentOnSurfaceColor(): Int = when (currentTheme) {
        Theme.LIGHT -> Color.parseColor("#0066CC")
        Theme.CREAM -> Color.parseColor("#A85B2A")
        Theme.DARK -> Color.parseColor("#7FB4D8")
    }

    // 是否深色主题
    fun isDarkTheme(): Boolean = currentTheme == Theme.DARK
}
