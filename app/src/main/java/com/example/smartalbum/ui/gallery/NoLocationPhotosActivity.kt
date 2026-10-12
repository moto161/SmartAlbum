package com.example.smartalbum.ui.gallery

import com.example.smartalbum.data.PhotoScanManager.PREFS_NAME
import com.example.smartalbum.util.ActivityAnimationUtil

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.ArrayList
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.smartalbum.data.Photo
import com.example.smartalbum.data.PhotoRepository
import com.example.smartalbum.db.PhotoDatabaseHelper
import com.example.smartalbum.databinding.ActivityNoLocationPhotosBinding
import com.example.smartalbum.util.PhotoLocationUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class NoLocationPhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoLocationPhotosBinding

    @Inject
    lateinit var repository: PhotoRepository

    @Inject
    lateinit var dbHelper: PhotoDatabaseHelper
    private lateinit var adapter: PhotoSelectAdapter
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var allPhotos = listOf<Photo>()
    private var isViewMode = false  // 纯查看模式（从位置列表页进入）

    companion object {
        private const val EXTRA_LATITUDE = "extra_latitude"
        private const val EXTRA_LONGITUDE = "extra_longitude"
        private const val REQUEST_CODE_WRITE_MEDIA = 1001

        fun newIntent(context: Context, latitude: Double, longitude: Double): Intent {
            return Intent(context, NoLocationPhotosActivity::class.java).apply {
                putExtra(EXTRA_LATITUDE, latitude)
                putExtra(EXTRA_LONGITUDE, longitude)
            }
        }
    }

    private var pendingPhotos: List<com.example.smartalbum.data.Photo>? = null

    // 权限设置返回后重新检查
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasManageMediaPermission()) {
            Toast.makeText(this, "媒体编辑权限已开启，可以粘贴位置了", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未开启媒体编辑权限，无法修改照片位置", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查是否有MANAGE_MEDIA权限（Android 12+）
     */
    private fun hasManageMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaStore.canManageMedia(this)
        } else {
            // Android 11及以下，假设有权限（旧版存储模型）
            true
        }
    }

    /**
     * 请求MANAGE_MEDIA权限，跳转到系统设置页面
     */
    private fun requestManageMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlertDialog.Builder(this)
                .setTitle("需要媒体编辑权限")
                .setMessage("修改照片位置信息需要「媒体管理」权限。\n\n点击「去开启」后，在系统设置中允许本应用管理媒体文件。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
                        permissionLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开设置页面，请手动在设置中开启媒体管理权限", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoLocationPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        isViewMode = latitude == 0.0 && longitude == 0.0

        // 2026-10-10：repository 改为 Hilt 注入，不再手动 new

        setupToolbar()
        setupRecyclerView()
        loadNoLocationPhotos()

        if (isViewMode) {
            // 纯查看模式：隐藏位置信息、选择计数和粘贴按钮
            binding.tvLocationInfo.visibility = android.view.View.GONE
            binding.tvSelectedCount.visibility = android.view.View.GONE
            binding.btnPaste.visibility = android.view.View.GONE
            // 2026-10-04 实测 bug：本页从「位置页的无位置照片」入口进来时 isViewMode=true，
            // 粘贴按钮被隐藏，但 layout 里的引导语仍写着「点击『粘贴位置』…」，
            // 引导语与实际功能矛盾 → 用户找不到按钮，以为功能坏了。
            // 此处按实际模式改写文案，不再承诺这个模式下做不到的事。
            binding.tvGuide.text = "以下照片没有位置信息（仅查看）。如需把某个位置批量粘贴给它们，请从地图上任意有位置的照片进入「照片详情」，再点「粘贴位置」。"
        } else {
            binding.btnPaste.setOnClickListener {
                pasteLocation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_WRITE_MEDIA && resultCode == RESULT_OK) {
            // 用户授权了写入权限，执行实际粘贴
            val photos = pendingPhotos
            if (photos != null && photos.isNotEmpty()) {
                doPasteLocation(photos)
            }
            pendingPhotos = null
        } else if (requestCode == REQUEST_CODE_WRITE_MEDIA) {
            Toast.makeText(this, "未授权修改照片，粘贴取消", Toast.LENGTH_SHORT).show()
            binding.btnPaste.isEnabled = true
            binding.btnPaste.text = "粘贴位置"
            pendingPhotos = null
        }
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            window.decorView.setBackgroundColor(theme.getBackgroundColor())
        } catch (e: Exception) {
            android.util.Log.e("NoLocation", "应用主题失败: ${e.message}")
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            ActivityAnimationUtil.finishWithAnimation(this@NoLocationPhotosActivity)
        }
        if (isViewMode) {
            binding.toolbar.title = "无位置照片"
        } else {
            binding.tvLocationInfo.text = "复制的位置：纬度 $latitude, 经度 $longitude"
        }
    }

    private fun setupRecyclerView() {
        adapter = PhotoSelectAdapter(emptyList()) { selectedPositions ->
            binding.tvSelectedCount.text = "已选择 ${selectedPositions.size} 张"
            binding.btnPaste.isEnabled = selectedPositions.isNotEmpty()
        }
        binding.photoRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.photoRecyclerView.adapter = adapter
    }

    private fun loadNoLocationPhotos() {
        lifecycleScope.launch {
            binding.tvSelectedCount.text = "正在加载..."
            val photos = withContext(Dispatchers.IO) {
                // 直接从数据库加载全部照片，不经过开关过滤
                // 2026-10-10：dbHelper 改为 Hilt 注入
                val allPhotos = dbHelper.loadAllPhotos()
                var noLocationPhotos = allPhotos.filter { !it.hasLocation }
                // 按时间范围过滤
                val prefs = getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val months = prefs.getInt("no_location_time_months", 6)
                if (months > 0) {
                    val cutoff = System.currentTimeMillis() - months * 30L * 24 * 60 * 60 * 1000
                    noLocationPhotos = noLocationPhotos.filter { it.dateTaken >= cutoff }
                }
                noLocationPhotos
            }
            allPhotos = photos
            adapter = PhotoSelectAdapter(photos) { selectedPositions ->
                binding.tvSelectedCount.text = "已选择 ${selectedPositions.size} 张"
                binding.btnPaste.isEnabled = selectedPositions.isNotEmpty()
            }
            binding.photoRecyclerView.adapter = adapter
            binding.tvSelectedCount.text = "共 ${photos.size} 张无位置照片，已选择 0 张"

            // 空状态显示
            if (photos.isEmpty()) {
                binding.emptyView.visibility = android.view.View.VISIBLE
                binding.photoRecyclerView.visibility = android.view.View.GONE
            } else {
                binding.emptyView.visibility = android.view.View.GONE
                binding.photoRecyclerView.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun pasteLocation() {
        val selectedPhotos = adapter.getSelectedPhotos()
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show()
            return
        }

        // Android 10+ 需要请求媒体写入授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                pendingPhotos = selectedPhotos
                val uris = ArrayList(selectedPhotos.map { it.uri })
                val writeRequest = MediaStore.createWriteRequest(contentResolver, uris)
                startIntentSenderForResult(
                    writeRequest.intentSender,
                    REQUEST_CODE_WRITE_MEDIA,
                    null, 0, 0, 0
                )
                return
            } catch (e: Exception) {
                // 如果createWriteRequest失败，尝试直接写入（Android 9及以下）
                android.util.Log.e("NoLocation", "createWriteRequest失败，直接写入: ${e.message}")
            }
        }

        doPasteLocation(selectedPhotos)
    }

    private fun doPasteLocation(selectedPhotos: List<com.example.smartalbum.data.Photo>) {
        binding.btnPaste.isEnabled = false
        binding.btnPaste.text = "正在粘贴..."

        lifecycleScope.launch {
            PhotoLocationUtil.clearPendingSecurityException()
            val successCount = withContext(Dispatchers.IO) {
                val uris = selectedPhotos.map { it.uri }
                PhotoLocationUtil.setPhotosLocation(this@NoLocationPhotosActivity, uris, latitude, longitude)
            }

            // 检查是否需要授权
            val securityException = PhotoLocationUtil.pendingSecurityException
            if (securityException != null && successCount < selectedPhotos.size) {
                // 需要用户授权，启动系统授权对话框
                try {
                    startIntentSenderForResult(
                        securityException.userAction.actionIntent.intentSender,
                        REQUEST_CODE_WRITE_MEDIA,
                        null, 0, 0, 0
                    )
                    pendingPhotos = selectedPhotos
                    binding.btnPaste.isEnabled = true
                    binding.btnPaste.text = "粘贴位置"
                    Toast.makeText(this@NoLocationPhotosActivity, "请在系统弹窗中允许修改照片", Toast.LENGTH_LONG).show()
                    return@launch
                } catch (e: Exception) {
                    android.util.Log.e("NoLocation", "启动授权失败: ${e.message}")
                }
            }

            Toast.makeText(
                this@NoLocationPhotosActivity,
                "成功粘贴 $successCount/${selectedPhotos.size} 张照片的位置",
                Toast.LENGTH_SHORT
            ).show()

            // 清除内存缓存，让下次进入地图/位置页面时重新扫描
            com.example.smartalbum.data.PhotoScanManager.clearCache()

            ActivityAnimationUtil.finishWithAnimation(this@NoLocationPhotosActivity)
        }
    }
}




