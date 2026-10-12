package com.example.smartalbum
import com.example.smartalbum.util.LogUtil
import com.example.smartalbum.util.ActivityAnimationUtil

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.smartalbum.data.PhotoScanManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isFinished = false
    private var isPreloadComplete = false
    private val minDisplayTime = 500L  // 最短展示0.5秒，预加载完成即可跳转
    private var startTime = 0L
    private var progressBar: android.widget.ProgressBar? = null
    private var progressText: android.widget.TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 立即从本地缓存加载照片数据，不阻塞UI
        com.example.smartalbum.data.PhotoScanManager.initFromCache(this)
        setContentView(R.layout.activity_splash)
        // 应用主题背景色
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            window.decorView.setBackgroundColor(theme.getBackgroundColor())
        } catch (e: Exception) {
            android.util.Log.e("Splash", "应用主题失败: ${e.message}")
        }
        startTime = System.currentTimeMillis()

        // 全屏显示
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setupAnimations()
        setupVersionText()
        setupSkipButton()
        startPreload()

        // 最短展示时间到后，如果预加载已完成则立即跳转
        handler.postDelayed({
            if (isPreloadComplete) {
                navigateToMain()
            }
        }, minDisplayTime)
    }

    /**
     * 设置启动页动画（Logo缩放+标题淡入+标语上移）
     */
    private fun setupAnimations() {
        // Logo动画：缩放+淡入
        val logoImage = findViewById<android.widget.ImageView>(R.id.iv_splash_logo)
        logoImage.alpha = 0f
        logoImage.scaleX = 0.7f
        logoImage.scaleY = 0.7f
        logoImage.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(800)
            .setStartDelay(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // 标题动画：延迟淡入
        val titleText = findViewById<TextView>(R.id.tv_splash_title)
        titleText.alpha = 0f
        titleText.translationY = 20f
        titleText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(600)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // 标语动画：延迟上移+淡入
        val sloganText = findViewById<TextView>(R.id.tv_splash_slogan)
        sloganText.alpha = 0f
        sloganText.translationY = 15f
        sloganText.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(900)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withStartAction { sloganText.visibility = TextView.VISIBLE }
            .start()

        // 进度区域动画：延迟淡入
        val progressLayout = findViewById<android.widget.LinearLayout>(R.id.progress_layout)
        progressLayout.alpha = 0f
        progressLayout.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(1200)
            .start()
    }

    /**
     * 设置版本号文字
     */
    private fun setupVersionText() {
        val versionText = findViewById<TextView>(R.id.tv_version)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "v${packageInfo.versionName}"
        } catch (e: Exception) {
            LogUtil.d("SplashActivity", "获取版本号失败: ${e.message}")
            versionText.text = ""
        }
    }

    /**
     * 设置跳过按钮（用户可随时点击跳过）
     */
    private fun setupSkipButton() {
        val skipButton = findViewById<TextView>(R.id.tv_skip)
        skipButton.visibility = View.VISIBLE
        skipButton.text = "跳过"
        skipButton.setOnClickListener {
            navigateToMain()
        }
    }

    /**
     * 开始后台预加载照片数据
     */
    private fun startPreload() {
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.tv_progress_text)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val photos = PhotoScanManager.getPhotos(this@SplashActivity) { scanned, total ->
                    val percent = if (total > 0) (scanned * 100 / total) else 0
                    runOnUiThread {
                        progressBar?.progress = percent
                        progressText?.text = "正在扫描照片... $scanned/$total ($percent%)"
                    }
                }
                isPreloadComplete = true
                runOnUiThread {
                    progressText?.text = "扫描完成，共 ${photos.size} 张照片"
                    // 预加载完成，如果已过最短展示时间则立即跳转
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= minDisplayTime) {
                        navigateToMain()
                    }
                }
            } catch (e: Exception) {
                LogUtil.e("SplashActivity", "异常", e)
                isPreloadComplete = true
                runOnUiThread {
                    progressText?.text = "准备进入..."
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= minDisplayTime) {
                        navigateToMain()
                    }
                }
            }
        }
    }

    /**
     * 跳转到主页面（带最短展示时间保护）
     */
    private fun navigateToMain() {
        if (isFinished) return
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed < minDisplayTime) {
            handler.postDelayed({ navigateToMain() }, minDisplayTime - elapsed)
            return
        }
        isFinished = true
        handler.removeCallbacksAndMessages(null)
        val intent = Intent(this, MainActivity::class.java)
        ActivityAnimationUtil.startActivityWithFade(this, intent)
        finish()
    }

    override fun onBackPressed() {
        // 启动页禁止返回
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
