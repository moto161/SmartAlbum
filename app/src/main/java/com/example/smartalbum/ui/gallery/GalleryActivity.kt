package com.example.smartalbum.ui.gallery
import com.example.smartalbum.util.ActivityAnimationUtil

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.smartalbum.data.LocationGroup
import com.example.smartalbum.databinding.ActivityGalleryBinding
import com.example.smartalbum.util.ExifUtil
import com.example.smartalbum.db.PhotoDatabaseHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var locationGroup: LocationGroup

    @Inject
    lateinit var dbHelper: PhotoDatabaseHelper

    companion object {
        private const val EXTRA_LOCATION_ID = "extra_location_id"
        private const val EXTRA_LATITUDE = "extra_latitude"
        private const val EXTRA_LONGITUDE = "extra_longitude"
        private const val EXTRA_LOCATION_NAME = "extra_location_name"
        /** 分组结果里的照片 id（按详情页展示顺序），用于详情页与其保持同一口径 */
        private const val EXTRA_PHOTO_IDS = "extra_photo_ids"

        fun newIntent(context: Context, group: LocationGroup): Intent {
            return Intent(context, GalleryActivity::class.java).apply {
                putExtra(EXTRA_LOCATION_ID, group.id)
                putExtra(EXTRA_LATITUDE, group.latitude)
                putExtra(EXTRA_LONGITUDE, group.longitude)
                putExtra(EXTRA_LOCATION_NAME, group.locationName)
                // 只传 id 不传整份 photos：一个位置组可能有几千张，Parcelable 全量传有超
                // Binder 1MB 事务上限的风险，LongArray 每条仅 8 字节
                putExtra(EXTRA_PHOTO_IDS, group.photos.map { it.id }.toLongArray())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_LOCATION_ID) ?: ""
        val lat = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        val name = intent.getStringExtra(EXTRA_LOCATION_NAME) ?: ""

        // 2026-10-10 优化：优先用 PhotoScanManager 内存里已解密的照片，
        // 避免每次进详情都从 DB 全量解密读 5768 张（0.5~1秒）。
        // 内存没有时再 fallback 到 dbHelper.loadAllPhotos()。
        val photoIds = intent.getLongArrayExtra(EXTRA_PHOTO_IDS)
        val groupPhotos = if (photoIds != null && photoIds.isNotEmpty()) {
            // 口径统一：直接用分组结果里的 id 集合，与列表页卡片上的张数完全一致。
            // 旧实现按「组中心 ±0.001 度」矩形重过滤，与分组用的 100m 圆形聚类是两套
            // 口径，边界照片必丢 —— 表现为卡片写 87 张、点进去只有 63 张。
            val idSet = photoIds.toSet()
            val pool = com.example.smartalbum.data.PhotoScanManager.getCachedPhotos()
                // 2026-10-10：冷启动 fallback 不再读全表（5768 行 × 2 次 AES 解密），
                // 按已拿到的 id 集合分批查，只解密真正要显示的
                ?: dbHelper.loadPhotosByIds(idSet)
            pool.filter { it.id in idSet }.sortedByDescending { it.dateTaken }
        } else {
            // 兼容没有传 id 的旧调用：退回按经纬度粗筛
            val pool = com.example.smartalbum.data.PhotoScanManager.getCachedPhotos()
                ?: dbHelper.loadAllPhotos()
            pool.filter { photo ->
                photo.hasLocation &&
                kotlin.math.abs((photo.latitude ?: 0.0) - lat) < 0.001 &&
                kotlin.math.abs((photo.longitude ?: 0.0) - lon) < 0.001
            }.sortedByDescending { it.dateTaken }
        }

        locationGroup = LocationGroup(
            id = id,
            latitude = lat,
            longitude = lon,
            locationName = name,
            photos = groupPhotos
        )

        setupToolbar()
        setupPhotoGrid()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            window.decorView.setBackgroundColor(theme.getBackgroundColor())
        } catch (e: Exception) {
            android.util.Log.e("Gallery", "应用主题失败: ${e.message}")
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = locationGroup.locationName
        binding.toolbar.setNavigationOnClickListener { ActivityAnimationUtil.finishWithAnimation(this) }

        // 显示位置信息和照片数量
        val info = buildString {
            append(locationGroup.locationName)
            append("  ·  ")
            append(getString(com.example.smartalbum.R.string.photo_count, locationGroup.photoCount))
            locationGroup.earliestDate?.let {
                append("\n")
                append(ExifUtil.formatDateOnly(it))
                append(" ~ ")
                locationGroup.latestDate?.let { latest ->
                    append(ExifUtil.formatDateOnly(latest))
                }
            }
        }
        binding.locationInfo.text = info
    }

    private fun setupPhotoGrid() {
        val adapter = PhotoAdapter(locationGroup.photos) { photo, position ->
            ActivityAnimationUtil.startActivityWithAnimation(this, 
                PhotoViewActivity.newIntent(
                    this,
                    ArrayList(locationGroup.photos),
                    position
                )
            )
        }
        binding.photoRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.photoRecyclerView.adapter = adapter
    }
}


