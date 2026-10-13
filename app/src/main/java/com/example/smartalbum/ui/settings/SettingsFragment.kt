package com.example.smartalbum.ui.settings

import com.example.smartalbum.data.PhotoScanManager.PREFS_NAME

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.smartalbum.util.ActivityAnimationUtil

import com.example.smartalbum.R

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.smartalbum.cache.PhotoCacheManager
import com.example.smartalbum.db.PhotoDatabaseHelper
import com.example.smartalbum.data.PhotoRepository
import com.example.smartalbum.databinding.FragmentSettingsBinding
import com.example.smartalbum.util.LogUtil
import com.example.smartalbum.ui.admin.AdminActivity
import android.content.Intent
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    @Inject
    lateinit var repository: PhotoRepository
    private lateinit var cacheManager: PhotoCacheManager
    @Inject lateinit var dbHelper: PhotoDatabaseHelper

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { exportCacheToUri(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importCacheFromUri(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            // 根布局背景
            binding.root.setBackgroundColor(theme.getBackgroundColor())
            // Toolbar
            binding.toolbar.setBackgroundColor(theme.getAccentColor())
            binding.toolbar.setTitleTextColor(theme.getButtonTextColor())
            // ⚠️ 2026-10-04 修正：MaterialButton 不能用 setBackgroundColor() 改底色。
            // 它内部用 backgroundTintList 控制着色，直接 setBackgroundColor 会
            // 把 tint 状态打乱，导致文字与底色几乎同色（实测「导出缓存」1.21:1、
            // 「清除照片缓存」1.00:1，文字完全看不见）。必须改 backgroundTintList。
            binding.btnExport.backgroundTintList =
                android.content.res.ColorStateList.valueOf(theme.getAccentColor())
            binding.btnExport.setTextColor(theme.getButtonTextColor())
            binding.btnImport.backgroundTintList =
                android.content.res.ColorStateList.valueOf(theme.getAccentColor())
            binding.btnImport.setTextColor(theme.getButtonTextColor())
            // 危险操作（清除缓存）用专门的危险色，与导出/导入的主题色区分开，
            // 避免用户在清新白主题下分不清哪个按钮会丢数据（2026-10-04 实测 bug）
            binding.btnClearCache.backgroundTintList =
                android.content.res.ColorStateList.valueOf(theme.getDangerColor())
            binding.btnClearCache.setTextColor(theme.getButtonTextColor())
            // 日志按钮：查看=强调色，清空=中性色（用卡片色，弱化）
            binding.btnViewLogs.backgroundTintList =
                android.content.res.ColorStateList.valueOf(theme.getAccentColor())
            binding.btnViewLogs.setTextColor(theme.getButtonTextColor())
            binding.btnClearLogs.backgroundTintList =
                android.content.res.ColorStateList.valueOf(theme.getCardColor())
            binding.btnClearLogs.setTextColor(theme.getTextColor())
            // 卡片背景（遍历所有子View中的CardView）
            applyThemeToViewGroup(binding.root)
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "应用主题失败: ${e.message}")
        }
    }

    // 主题变化回调：延迟到下一帧执行，避免与对话框动画冲突
    private val themeChangedListener = com.example.smartalbum.util.ThemeManager.OnThemeChangedListener {
        view?.post {
            applyTheme()
        }
    }

    private fun applyThemeToViewGroup(view: android.view.View) {
        val theme = com.example.smartalbum.util.ThemeManager
        // 2026-10-04 修正：MaterialButton 内部还包着一层 FrameLayout/TextView 子树，
        // 光在 TextView 分支里 return 挡不住子节点被刷色。
        // 这里在入口就整棵跳过按钮子树，让 applyTheme() 设定的按钮配色完全保留。
        if (view is com.google.android.material.button.MaterialButton) return
        when (view) {
            is com.google.android.material.card.MaterialCardView -> {
                view.setCardBackgroundColor(theme.getCardColor())
                // 2026-10-04 重排版新增：卡片改用「1dp 描边」代替阴影来区分层次，
                // 但描边色如果不管就会一直是浅灰色，在暗夜主题下会非常突兀。
                view.strokeColor = theme.getDividerColor()
            }
            is android.widget.ImageView -> {
                // 2026-10-04 重排版新增：每行左侧加了图标。
                // 图标必须用「浅色变体」——暗夜主题下按钮色 #3D6E9E 作图标只有 2.87:1，
                // 在深色卡片上几乎看不见（2.87 < WCAG 图标标准 3.0）。
                // ⚠️ Android 的 ColorStateList 没有 setTintColor 方法（只有 setTint/setTintList），
                //    直接调 tint.setTintColor(...) 会编译不过，用 ColorStateList.valueOf 构造。
                if (view.contentDescription == null) {
                    view.imageTintList =
                        android.content.res.ColorStateList.valueOf(
                            theme.getAccentOnSurfaceColor()
                        )
                }
            }
            is android.widget.TextView -> {
                // 2026-10-04 修正（重要）：
                // ① MaterialButton 本身就是 TextView 的子类，会走进这个分支。
                //    按钮文字 14sp < 15sp → 被判成"说明文字" → 刷成灰色，
                //    覆盖掉 applyTheme() 里刚设好的白字，
                //    导致「清除照片缓存」红底配灰字（实测仅 1.21:1，几乎看不见）。
                //    所以这里必须跳过所有按钮，让它们保留 applyTheme() 设定的颜色。
                if (view is com.google.android.material.button.MaterialButton) return
                // ② 原来靠「文字长度 1..9 就当主色、否则当次色」来猜主/次层级，
                //    重排后会把加粗的分组小标题（「照片显示」4 个字）误判成灰色。
                //    改为按 textSize 判断：≥15sp 视为标题（主色），否则为说明文字（次色）。
                val textLen = view.text?.length ?: 0
                if (textLen == 0) {
                    return  // 空 TextView 不动
                }
                val sizeSp = view.textSize / view.resources.displayMetrics.scaledDensity
                view.setTextColor(
                    if (sizeSp >= 15f) theme.getTextColor() else theme.getSecondaryTextColor()
                )
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyThemeToViewGroup(view.getChildAt(i))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cacheManager = PhotoCacheManager(requireContext())
        // 注册主题变化监听
        com.example.smartalbum.util.ThemeManager.addOnThemeChangedListener(themeChangedListener)


        binding.btnExport.setOnClickListener {
            exportCache()
        }

        binding.btnImport.setOnClickListener {
            importCache()
        }

        binding.btnViewLogs.setOnClickListener {
            viewLogs()
        }

        binding.btnClearLogs.setOnClickListener {
            clearLogs()
        }

        binding.btnClearCache.setOnClickListener {
            clearPhotoCache()
        }

        // 显示全部照片开关
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.switchShowAllPhotos.isChecked = prefs.getBoolean("show_all_photos", false)
        binding.switchShowAllPhotos.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_all_photos", isChecked).apply()
            // 2026-10-10：开关切换只改 SharedPreferences，不清数据库/不清缓存/不触发重扫。
            // 内存里已分好"有位置/无位置"两份，下次进位置页时 buildLocationGroups 自动决定要不要拼无位置分组。
            if (isChecked) {
                Toast.makeText(requireContext(), "已开启显示全部照片，将同时显示无定位照片", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "已关闭，将只显示带定位的照片", Toast.LENGTH_SHORT).show()
            }
        }

        // 无位置照片时间范围选择
        val timeOptions = arrayOf("最近1个月", "最近3个月", "最近半年", "最近1年", "全部")
        val timeValues = intArrayOf(1, 3, 6, 12, 0)
        val currentTimeMonths = prefs.getInt("no_location_time_months", 6)
        var currentIndex = timeValues.indexOf(currentTimeMonths).let { if (it >= 0) it else 2 }
        binding.tvNoLocationTime.text = timeOptions[currentIndex]

        // 主题切换
        val themeOptions = arrayOf("清新白", "温暖奶油", "暗夜极简")
        val currentTheme = com.example.smartalbum.util.ThemeManager.getCurrentTheme()
        binding.tvCurrentTheme.text = themeOptions[currentTheme.id]

        binding.cardTheme.setOnClickListener {
            // 每次打开弹窗都重新读取当前主题，避免闭包捕获旧值
            val nowTheme = com.example.smartalbum.util.ThemeManager.getCurrentTheme()
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("选择界面主题")
                .setSingleChoiceItems(themeOptions, nowTheme.id) { dialog, which ->
                    val theme = com.example.smartalbum.util.ThemeManager.Theme.entries.find { it.id == which }
                        ?: com.example.smartalbum.util.ThemeManager.Theme.LIGHT
                    binding.tvCurrentTheme.text = themeOptions[which]
                    dialog.dismiss()
                    // 延迟到下一帧执行，避免与对话框dismiss动画冲突
                    // ThemeManager.setTheme会自动通知所有监听者（包括MainActivity和其他Fragment）
                    view?.post {
                        com.example.smartalbum.util.ThemeManager.setTheme(theme)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 开源版公开入口：轨迹管理（原隐藏激活码后门，现改为设置页直接进入）
        binding.cardTrailAdmin.setOnClickListener {
            val intent = android.content.Intent(requireContext(),
                com.example.smartalbum.ui.admin.AdminActivity::class.java)
            ActivityAnimationUtil.startActivityWithAnimation(requireContext(), intent)
        }

        binding.cardNoLocationTime.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("选择无位置照片时间范围")
                .setSingleChoiceItems(timeOptions, currentIndex) { dialog, which ->
                    val selectedMonths = timeValues[which]
                    prefs.edit().putInt("no_location_time_months", selectedMonths).apply()
                    binding.tvNoLocationTime.text = timeOptions[which]
                    currentIndex = which  // 更新当前选中项，下次打开对话框显示正确
                    // 2026-10-10：不再 clearCache 重扫——无位置缓存已单独存内存，下次 getNoLocationPhotos 时按新 months 现算
                    dialog.dismiss()
                    Toast.makeText(requireContext(), "已设置为${timeOptions[which]}，下次进入位置页生效", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 动态设置版本号
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvAppVersion.text = "版本 ${packageInfo.versionName}"
        } catch (e: Exception) {
            LogUtil.d("SettingsFragment", "获取版本号失败，使用默认值: ${e.message}")
            binding.tvAppVersion.text = "版本 3.2.2"
        }

        // 点击版本号检查更新
        binding.tvAppVersion.setOnClickListener {
            android.widget.Toast.makeText(requireContext(), "正在检查更新...", android.widget.Toast.LENGTH_SHORT).show()
            com.example.smartalbum.util.AppUpdateManager.checkUpdate(requireContext()) { updateInfo ->
                activity?.runOnUiThread {
                    if (updateInfo != null) {
                        com.example.smartalbum.util.AppUpdateManager.showUpdateDialog(requireContext(), updateInfo)
                    } else {
                        android.widget.Toast.makeText(requireContext(), "当前已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 隐藏功能：连续点击"关于"分组标题7次，弹出激活码输入框
        // 开源版：移除隐藏的管理员激活入口。如需 AdminActivity 管理面板，
        // 可自行在设置页加一个按钮显式跳转，或通过 adb am start 启动。
    }

    private fun viewLogs() {
        // 合并运行日志和崩溃日志
        val runLogs = LogUtil.getLogsAsString()
        val crashLogs = loadCrashLogs()
        val allLogs = buildString {
            if (crashLogs.isNotEmpty()) {
                append("========== 崩溃日志 ==========\n")
                append(crashLogs)
                append("\n\n")
            }
            if (runLogs.isNotEmpty()) {
                append("========== 运行日志 ==========\n")
                append(runLogs)
            }
        }

        if (allLogs.isEmpty()) {
            Toast.makeText(requireContext(), "暂无日志", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_logs, null)
        val logsTextView = dialogView.findViewById<android.widget.TextView>(R.id.tv_logs)
        val btnCopy = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_copy)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share)
        val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close)

        logsTextView.text = allLogs

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("APP日志", allLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        btnShare?.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "智能相册APP日志")
                putExtra(Intent.EXTRA_TEXT, allLogs)
            }
            startActivity(Intent.createChooser(shareIntent, "分享日志"))
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun loadCrashLogs(): String {
        return try {
            val crashDir = java.io.File(requireContext().filesDir, "crashes")
            if (!crashDir.exists()) return ""
            val files = crashDir.listFiles()
            ?.filter { it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return ""
            if (files.isEmpty()) return ""
            // 只显示最近3条崩溃日志
            files.take(3).joinToString("\n\n") { it.readText() }
        } catch (e: Exception) {
            LogUtil.e("SettingsFragment", "读取崩溃日志失败", e)
            ""
        }
    }

    private fun clearLogs() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认清空")
            .setMessage("确定要清空所有日志吗？")
            .setPositiveButton("确定") { _, _ ->
                LogUtil.clearLogs()
                Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearPhotoCache() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认清除")
            .setMessage("确定要清除所有照片缓存吗？清除后需要重新扫描照片，可能需要一些时间。")
            .setPositiveButton("确定清除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            // 清除数据库缓存
                            dbHelper.clear()
                            // 清除全局扫描管理器缓存
                            com.example.smartalbum.data.PhotoScanManager.clearCache()
                        }
                        Toast.makeText(requireContext(), "缓存已清除，请重启APP重新扫描", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        LogUtil.e("SettingsFragment", "异常", e)
                        Toast.makeText(requireContext(), "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCache() {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
        val fileName = "photo_cache_${sdf.format(java.util.Date())}.json"
        exportLauncher.launch(fileName)
    }

    private fun exportCacheToUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val externalCache = cacheManager.loadFromExternalStorage()
                    val rootJson = JSONObject()
                    rootJson.put("version", 1)
                    rootJson.put("export_time", System.currentTimeMillis())

                    val photosArray = org.json.JSONArray()
                    externalCache?.photos?.values?.forEach { entry ->
                        val pJson = JSONObject()
                        pJson.put("display_name", entry.displayName)
                        pJson.put("date_taken", entry.dateTaken)
                        pJson.put("latitude", entry.latitude)
                        pJson.put("longitude", entry.longitude)
                        photosArray.put(pJson)
                    }
                    rootJson.put("photos", photosArray)

                    val locationNamesJson = JSONObject()
                    dbHelper.getAllLocationNames().forEach { (key, name) ->
                        locationNamesJson.put(key, name)
                    }
                    rootJson.put("location_names", locationNamesJson)

                    requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
                Toast.makeText(requireContext(), "缓存导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                LogUtil.e("SettingsFragment", "异常", e)
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importCache() {
        importLauncher.launch(arrayOf("application/json", "*/*"))
    }

    private fun importCacheFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 2026-10-10 修复：之前解析完 photosMap/locationNamesMap 后直接丢弃，
                // 相当于没导入。现在直接把 JSON 原文写回外部存储的 photo_cache.json，
                // 然后 forceRefresh 让内存和数据库立刻用上。
                val ok = withContext(Dispatchers.IO) {
                    val jsonString = requireContext().contentResolver
                        .openInputStream(uri)?.use { inputStream ->
                            inputStream.bufferedReader(Charsets.UTF_8).readText()
                        } ?: return@withContext false
                    cacheManager.importExternalCacheJson(jsonString)
                }

                if (ok) {
                    // 立即用新缓存重建内存
                    withContext(Dispatchers.IO) {
                        com.example.smartalbum.data.PhotoScanManager
                            .forceRefresh(requireContext())
                    }
                    Toast.makeText(requireContext(), "缓存导入成功，已重新扫描", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "导入失败：文件格式不是 photo_cache.json", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                LogUtil.e("SettingsFragment", "异常", e)
                Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        com.example.smartalbum.util.ThemeManager.removeOnThemeChangedListener(themeChangedListener)
        super.onDestroyView()
        _binding = null
    }

}


