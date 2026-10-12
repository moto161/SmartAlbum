package com.example.smartalbum

import com.example.smartalbum.data.PhotoScanManager.PREFS_NAME

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.smartalbum.databinding.ActivityMainBinding
import com.example.smartalbum.util.CrashHandler
import com.example.smartalbum.util.LogUtil
import com.example.smartalbum.util.MemoryMonitor
import com.example.smartalbum.ui.list.LocationListFragment
import com.example.smartalbum.ui.map.MapFragment2D
import android.content.SharedPreferences
import com.example.smartalbum.ui.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mapFragment2D = MapFragment2D()
    
    private lateinit var sharedPreferences: SharedPreferences
    private val locationListFragment = LocationListFragment()
    private val settingsFragment = SettingsFragment()
    private var currentFragment: Fragment? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val photoGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        if (photoGranted) {
            // 权限已授予，Fragment会自动加载数据
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startTime = System.currentTimeMillis()
        LogUtil.init(this)
        CrashHandler.init(this)
        // 确保从数据库加载照片缓存（进程被杀后直接恢复MainActivity也能加载）
        com.example.smartalbum.data.PhotoScanManager.initFromCache(this)
        LogUtil.i("MainActivity", "APP启动，onCreate")
        super.onCreate(savedInstanceState)

        // 非必要组件异步初始化，不阻塞启动
        Thread {
            try {
                MemoryMonitor.start()
                com.example.smartalbum.util.SearchHistoryUtil.init(this)
                initAmapPrivacy()
                LogUtil.i("MainActivity", "异步初始化完成，耗时: ${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                LogUtil.e("MainActivity", "异步初始化失败: ${e.message}")
            }
        }.start()
        
        // 屏幕旋转时恢复Fragment状态
        if (savedInstanceState != null) {
            currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? Fragment
            LogUtil.i("MainActivity", "恢复Fragment状态: ${currentFragment?.javaClass?.simpleName}")
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 注册主题变化监听
        com.example.smartalbum.util.ThemeManager.addOnThemeChangedListener(themeChangedListener)

        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 设置底部导航
        setupBottomNavigation()

        // 默认显示地图
        switchToMap()

        // 检查权限，加载照片数据
        if (hasRequiredPermissions()) {
            // 有权限，Fragment会自动加载照片数据
        } else {
            requestPermissions()
        }
    }

    /**
     * 设置底部导航栏点击事件
     */
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            LogUtil.i(
                "MainActivity",
                "底部导航点击: ${item.itemId}, currentFragment=${currentFragment?.javaClass?.simpleName}"
            )
            val target = when (item.itemId) {
                R.id.nav_map -> mapFragment2D
                R.id.nav_locations -> locationListFragment
                R.id.nav_settings -> settingsFragment
                else -> null
            }
            if (target != null && currentFragment !== target) {
                switchFragment(target)
            } else if (currentFragment === target) {
                LogUtil.i("MainActivity", "已经是目标Fragment，无需切换")
            }
            true
        }
    }

    /**
     * 切换Fragment
     */
    private fun switchFragment(target: Fragment) {
        try {
            val transaction = supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            // 隐藏当前Fragment
            if (currentFragment != null) {
                transaction.hide(currentFragment!!)
                LogUtil.i("MainActivity", "隐藏: ${currentFragment!!.javaClass.simpleName}")
            }
            // 显示目标Fragment
            if (target.isAdded) {
                transaction.show(target)
            } else {
                transaction.add(R.id.fragment_container, target, target.javaClass.simpleName)
            }
            transaction.commitNowAllowingStateLoss()
            currentFragment = target
            LogUtil.i("MainActivity", "显示成功: ${target.javaClass.simpleName}")

            // 添加淡入动画
            target.view?.let { view ->
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        } catch (e: Exception) {
            LogUtil.e("MainActivity", "Fragment切换失败: ${e.message}")
            LogUtil.e("MainActivity", "异常", e)
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    // 主题变化回调
    private val themeChangedListener = com.example.smartalbum.util.ThemeManager.OnThemeChangedListener {
        applyTheme()
    }

    fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            binding.root.setBackgroundColor(theme.getBackgroundColor())
            binding.bottomNavigation.setBackgroundColor(theme.getBottomNavColor())
            val states = arrayOf(
                intArrayOf(android.R.attr.state_selected),
                intArrayOf(-android.R.attr.state_selected)
            )
            val colors = intArrayOf(theme.getBottomNavSelectedColor(), theme.getBottomNavUnselectedColor())
            binding.bottomNavigation.itemIconTintList = android.content.res.ColorStateList(states, colors)
            binding.bottomNavigation.itemTextColor = android.content.res.ColorStateList(states, colors)
            applyStatusBarTheme()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "应用主题失败: ${e.message}")
        }
    }

    /**
     * 状态栏跟随主题配色。
     * 2026-10-04：此前完全没设置状态栏颜色，一直是系统默认的蓝色 ——
     * 在暗夜主题下顶部一条亮蓝，非常不协调（用户反馈主题"看着不舒服"的一部分）。
     * 同时要切换图标明暗：浅色状态栏配深色图标，深色状态栏配浅色图标。
     */
    private fun applyStatusBarTheme() {
        val theme = com.example.smartalbum.util.ThemeManager
        window.statusBarColor = theme.getBackgroundColor()
        val lightBar = !theme.isDarkTheme()
        val insets = if (lightBar) {
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        } else {
            0
        }
        // 兼容 API 30 以下（平板是 Android 8.1，必须走老 API）
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = if (lightBar) {
                window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        } else {
            window.insetsController?.setSystemBarsAppearance(
                insets,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        }
    }

    /**
     * 返回键处理：非首页先回首页，首页弹出退出确认
     */
    override fun onBackPressed() {
        LogUtil.i("MainActivity", "onBackPressed, currentFragment=${currentFragment?.javaClass?.simpleName}")
        
        // 如果不是地图页面，先返回地图页面
        val isMapPage = currentFragment is MapFragment2D
        if (!isMapPage) {
            switchToMap()
            binding.bottomNavigation.selectedItemId = R.id.nav_map
            return
        }

        // 首页时，弹出退出确认对话框
        AlertDialog.Builder(this)
            .setTitle("退出确认")
            .setMessage("确定要退出智能相册吗？")
            .setPositiveButton("确定") { _, _ ->
                LogUtil.i("MainActivity", "用户确认退出")
                super.onBackPressed()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 用反射调用高德地图SDK的隐私合规方法
     * 兼容不同版本的SDK
     */
    private fun initAmapPrivacy() {
        initMapPrivacy()
        initLocationPrivacy()
        initSearchPrivacy()
    }

    /**
     * 初始化地图SDK隐私合规
     */
    private fun initMapPrivacy() {
        try {
            val mapsInitializerClass = Class.forName("com.amap.api.maps2d.MapsInitializer")
            invokePrivacyMethod(mapsInitializerClass, "updatePrivacyShow", this, true, true)
            invokePrivacyMethod(mapsInitializerClass, "updatePrivacyAgree", this, true)
        } catch (e: ClassNotFoundException) {
            LogUtil.d("MainActivity", "地图SDK未找到: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e("MainActivity", "异常", e)
        }
    }

    /**
     * 初始化定位SDK隐私合规
     */
    private fun initLocationPrivacy() {
        try {
            val locationClientClass = Class.forName("com.amap.api.location.AMapLocationClient")
            invokePrivacyMethod(locationClientClass, "updatePrivacyShow", this, true, true)
            invokePrivacyMethod(locationClientClass, "updatePrivacyAgree", this, true)
        } catch (e: ClassNotFoundException) {
            LogUtil.d("MainActivity", "定位SDK未找到: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e("MainActivity", "异常", e)
        }
    }

    /**
     * 初始化搜索SDK隐私合规
     */
    private fun initSearchPrivacy() {
        // 搜索SDK可能有两种类名
        val possibleClassNames = listOf(
            "com.amap.api.services.core.ServiceSettings",
            "com.amap.api.services.core.AMapPrivacyChecker"
        )

        for (className in possibleClassNames) {
            try {
                val clazz = Class.forName(className)
                invokePrivacyMethod(clazz, "updatePrivacyShow", this, true, true)
                invokePrivacyMethod(clazz, "updatePrivacyAgree", this, true)
            } catch (e: ClassNotFoundException) {
            LogUtil.d("MainActivity", "该类名不存在，尝试下一个: ${e.message}")
        } catch (e: Exception) {
                LogUtil.e("MainActivity", "异常", e)
            }
        }
    }

    /**
     * 通用的隐私合规方法调用工具
     */
    private fun invokePrivacyMethod(clazz: Class<*>, methodName: String, vararg args: Any) {
        try {
            val paramTypes = args.map { it.javaClass }.toTypedArray()
            val method = clazz.getMethod(methodName, *paramTypes)
            method.invoke(null, *args)
        } catch (e: NoSuchMethodException) {
            LogUtil.d("MainActivity", "旧版本SDK没有这个方法，跳过: ${e.message}")
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(getRequiredPermissions())
    }

    fun switchToMap() {
        if (currentFragment !== mapFragment2D) {
            switchFragment(mapFragment2D)
        }
    }


    override fun onDestroy() {
        com.example.smartalbum.util.ThemeManager.removeOnThemeChangedListener(themeChangedListener)
        // 2026-10-13：MemoryMonitor 之前在 onCreate 里 start 后无人 stop，
        // 进程存活期间每分钟写一条内存日志。这里停掉；
        // 旋屏销毁后 onCreate 会重新 start（start 幂等），不影响功能。
        com.example.smartalbum.util.MemoryMonitor.stop()
        super.onDestroy()
    }
}