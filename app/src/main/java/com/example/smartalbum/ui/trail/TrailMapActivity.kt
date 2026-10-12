package com.example.smartalbum.ui.trail
import com.example.smartalbum.util.ActivityAnimationUtil

import android.app.AlertDialog
import android.os.Bundle
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps2d.AMap
import com.amap.api.maps2d.CameraUpdateFactory
import com.amap.api.maps2d.MapView
import com.amap.api.maps2d.model.BitmapDescriptorFactory
import com.amap.api.maps2d.model.LatLng
import com.amap.api.maps2d.model.LatLngBounds
import com.amap.api.maps2d.model.MarkerOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.amap.api.maps2d.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import com.example.smartalbum.R
import com.example.smartalbum.api.LocationApiClient

/**
 * 运动轨迹地图Activity
 * 在高德地图上显示用户的运动轨迹（折线+标记点）
 */
class TrailMapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    private lateinit var mapView: MapView
    private lateinit var aMap: AMap
    private lateinit var tvTitle: TextView
    private lateinit var tvPointCount: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvTimeRange: TextView
    private lateinit var tvLatestAddress: TextView
    private lateinit var infoCard: CardView
    private lateinit var btnBack: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvPlaybackTime: TextView
    private lateinit var btnSpeed: Button
    private lateinit var btnSelectDate: Button

    private var deviceId: String = ""
    private var deviceName: String = ""

    // 播放相关
    private var allTrails: List<com.example.smartalbum.model.TrailPoint> = emptyList()
    private var filteredTrails: List<com.example.smartalbum.model.TrailPoint> = emptyList()
    private var isPlaying = false
    private var currentIndex = 0
    private var playbackSpeed = 1 // 1x, 2x, 4x
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var playbackMarker: com.amap.api.maps2d.model.Marker? = null
    private var selectedDate: String = "全部"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trail_map)

        // 获取传递的参数
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "未知设备"

        initViews()
        setupMap(savedInstanceState)
        loadTrails()
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        tvTitle = findViewById(R.id.tvTitle)
        tvPointCount = findViewById(R.id.tvPointCount)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvTimeRange = findViewById(R.id.tvTimeRange)
        tvLatestAddress = findViewById(R.id.tvLatestAddress)
        infoCard = findViewById(R.id.infoCard)
        btnBack = findViewById(R.id.btnBack)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBar = findViewById(R.id.seekBar)
        tvPlaybackTime = findViewById(R.id.tvPlaybackTime)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnSelectDate = findViewById(R.id.btnSelectDate)

        tvTitle.text = "$deviceName - 运动轨迹"
        tvDeviceName.text = deviceName

        btnBack.setOnClickListener { ActivityAnimationUtil.finishWithAnimation(this) }

        // 播放/暂停按钮
        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        // 进度条拖动
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && filteredTrails.isNotEmpty()) {
                    currentIndex = (progress * (filteredTrails.size - 1) / 100).coerceIn(0, filteredTrails.size - 1)
                    updatePlaybackMarker()
                    updatePlaybackUI()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { /* 开始拖动时不处理 */ }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { /* 停止拖动时不处理 */ }
        })

        // 速度切换
        btnSpeed.setOnClickListener {
            playbackSpeed = when (playbackSpeed) {
                1 -> 2
                2 -> 4
                else -> 1
            }
            btnSpeed.text = "${playbackSpeed}x"
        }

        // 日期选择
        btnSelectDate.setOnClickListener {
            showDatePicker()
        }
    }

    private fun setupMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        aMap = mapView.map
        aMap.uiSettings.isZoomControlsEnabled = true
        aMap.uiSettings.isScaleControlsEnabled = true
    }

    private fun loadTrails() {
        // 2026-10-10：裸 Thread 迁移到 lifecycleScope 协程
        lifecycleScope.launch {
            try {
                // 获取最近一周的轨迹（最多500个点）
                allTrails = withContext(Dispatchers.IO) {
                    LocationApiClient.getUserTrails(deviceId, 500)
                }

                if (allTrails.isNotEmpty()) {
                    applyDateFilter()
                } else {
                    Toast.makeText(this@TrailMapActivity, "暂无轨迹数据", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@TrailMapActivity, "加载轨迹失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 应用日期过滤
     */
    private fun applyDateFilter() {
        filteredTrails = if (selectedDate == "全部") {
            allTrails.sortedBy { it.createdAt }
        } else {
            allTrails.filter { it.createdAt.startsWith(selectedDate) }.sortedBy { it.createdAt }
        }

        if (filteredTrails.isNotEmpty()) {
            // 清除之前的播放标记
            playbackMarker?.remove()
            playbackMarker = null
            currentIndex = 0
            isPlaying = false
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)

            showTrailsOnMap(filteredTrails)
            updateInfoCard(filteredTrails)
            updatePlaybackUI()
        } else {
            Toast.makeText(this, "该日期暂无轨迹数据", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示日期选择对话框
     */
    private fun showDatePicker() {
        // 从所有轨迹中提取有数据的日期
        val dateSet = mutableSetOf<String>()
        allTrails.forEach { 
            val date = it.createdAt.substring(0, 10) // "yyyy-MM-dd"
            dateSet.add(date)
        }
        val dates = listOf("全部") + dateSet.sortedDescending()
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, dates)
        AlertDialog.Builder(this)
            .setTitle("选择日期")
            .setAdapter(adapter) { _, which ->
                selectedDate = dates[which]
                btnSelectDate.text = if (selectedDate == "全部") "全部" else selectedDate.substring(5) // 显示MM-dd
                applyDateFilter()
            }
            .show()
    }

    /**
     * 开始播放
     */
    private fun startPlayback() {
        if (filteredTrails.isEmpty()) return
        if (currentIndex >= filteredTrails.size - 1) {
            currentIndex = 0
        }
        isPlaying = true
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        playbackRunnable.run()
    }

    /**
     * 暂停播放
     */
    private fun pausePlayback() {
        isPlaying = false
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        playbackHandler.removeCallbacks(playbackRunnable)
    }

    /**
     * 播放Runnable
     */
    private val playbackRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying || currentIndex >= filteredTrails.size - 1) {
                pausePlayback()
                return
            }
            currentIndex++
            updatePlaybackMarker()
            updatePlaybackUI()
            // 播放间隔：基础500ms，速度越快间隔越短
            val delay = (500L / playbackSpeed).coerceAtLeast(100L)
            playbackHandler.postDelayed(this, delay)
        }
    }

    /**
     * 更新播放标记位置
     */
    private fun updatePlaybackMarker() {
        if (filteredTrails.isEmpty()) return
        val point = filteredTrails[currentIndex]
        val latLng = LatLng(point.latitude, point.longitude)
        
        if (playbackMarker == null) {
            playbackMarker = aMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("播放中")
                    .snippet(point.createdAt)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        } else {
            playbackMarker?.position = latLng
            playbackMarker?.snippet = point.createdAt
        }
        
        // 移动地图相机跟随播放点
        aMap.animateCamera(CameraUpdateFactory.changeLatLng(latLng), 300, null)
    }

    /**
     * 更新播放UI（进度条、时间）
     */
    private fun updatePlaybackUI() {
        if (filteredTrails.isEmpty()) {
            tvPlaybackTime.text = "0/0"
            seekBar.progress = 0
            return
        }
        tvPlaybackTime.text = "${currentIndex + 1}/${filteredTrails.size}"
        seekBar.progress = if (filteredTrails.size > 1) {
            currentIndex * 100 / (filteredTrails.size - 1)
        } else {
            0
        }
    }

    private fun showTrailsOnMap(trails: List<com.example.smartalbum.model.TrailPoint>) {
        // 过滤异常坐标点（经纬度为0或超出中国范围）并按时间排序
        val validTrails = trails.filter {
            it.latitude != 0.0 && it.longitude != 0.0 &&
            it.latitude in 3.0..54.0 && it.longitude in 73.0..135.0
        }.sortedBy { it.createdAt }

        if (validTrails.isEmpty()) {
            Toast.makeText(this, "无有效轨迹点", Toast.LENGTH_SHORT).show()
            return
        }

        val latLngList = validTrails.map { LatLng(it.latitude, it.longitude) }

        // 绘制轨迹折线
        val polylineOptions = PolylineOptions()
            .addAll(latLngList)
            .width(12f)
            .color(0xFF4A90D9.toInt())

        aMap.addPolyline(polylineOptions)

        // 计算序号间隔：点越多间隔越大，保证屏幕上不会太密集
        // 2026-10-10 修复：用 validTrails.size 而不是 trails.size，避免索引错位
        val numberInterval = when {
            validTrails.size > 100 -> 20
            validTrails.size > 50 -> 10
            validTrails.size > 20 -> 5
            else -> 1
        }

        // 在途经点上添加带序号的标记（显示运动方向和顺序）
        for (i in numberInterval until latLngList.size - 1 step numberInterval) {
            aMap.addMarker(
                MarkerOptions()
                    .position(latLngList[i])
                    .title("第${i + 1}个点")
                    // 2026-10-10 修复：用 validTrails[i] 而不是 trails[i]，否则时间戳错位
                    .snippet(validTrails[i].createdAt)
                    .icon(createNumberMarker(i + 1))
            )
        }

        // 添加起点标记（绿色，标"起"）
        val startPoint = latLngList.first()
        aMap.addMarker(
            MarkerOptions()
                .position(startPoint)
                .title("起点")
                // 2026-10-10 修复：validTrails.first() 才是真正的起点（过滤掉了坏点）
                .snippet(validTrails.first().createdAt)
                .icon(createTextMarker("起", Color.parseColor("#4CAF50")))
        )

        // 添加终点标记（红色，标"终"）
        val endPoint = latLngList.last()
        aMap.addMarker(
            MarkerOptions()
                .position(endPoint)
                .title("终点")
                // 2026-10-10 修复：validTrails.last() 才是真正的终点
                .snippet(validTrails.last().address.ifEmpty { "未知地址" })
                .icon(createTextMarker("终", Color.parseColor("#F44336")))
        )

        // 点击标记点显示详细信息
        aMap.setOnMarkerClickListener { marker ->
            marker.showInfoWindow()
            true
        }

        // 自动缩放地图到包含所有轨迹点的范围
        val boundsBuilder = LatLngBounds.builder()
        latLngList.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()
        aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))

        // 计算总距离
        val totalDistance = calculateTotalDistance(latLngList)
        val distanceText = if (totalDistance >= 1000) {
            String.format(Locale.US, "%.2f公里", totalDistance / 1000)
        } else {
            String.format(Locale.US, "%.0f米", totalDistance)
        }
        // 2026-10-10 修复：显示有效点数而不是原始点数
        tvPointCount.text = "${validTrails.size}个点 · $distanceText"
    }



    /**
     * 创建带数字的标记图标（橙色圆形气泡）
     */
    private fun createNumberMarker(number: Int): com.amap.api.maps2d.model.BitmapDescriptor {
        val text = number.toString()
        val size = 90
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景圆（橙色）
        paint.color = Color.parseColor("#FF9800")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, paint)

        // 白色边框
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, paint)

        // 数字文字
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = if (text.length > 2) 24f else 32f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true

        val textBounds = Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)
        val textY = size / 2f + textBounds.height() / 2f
        canvas.drawText(text, size / 2f, textY, paint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * 创建带文字的标记图标（起点/终点用）
     */
    private fun createTextMarker(text: String, bgColor: Int): com.amap.api.maps2d.model.BitmapDescriptor {
        val size = 100
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景圆
        paint.color = bgColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, paint)

        // 白色边框
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, paint)

        // 文字
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = 36f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true

        val textBounds = Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)
        val textY = size / 2f + textBounds.height() / 2f
        canvas.drawText(text, size / 2f, textY, paint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * 计算轨迹总距离（米）
     */
    private fun calculateTotalDistance(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceBetween(points[i], points[i + 1])
        }
        return total
    }

    /**
     * 计算两点之间的距离（米）
     */
    private fun distanceBetween(p1: LatLng, p2: LatLng): Double {
        val earthRadius = 6371000.0 // 地球半径（米）
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2) * sin(deltaLon / 2)
        // 正确的Haversine公式：c = 2 * atan2(sqrt(a), sqrt(1-a))
        // 之前错误地用了acos(a)，导致a很小时c≈π，距离被放大到2万公里/段
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    private fun updateInfoCard(trails: List<com.example.smartalbum.model.TrailPoint>) {
        val sortedTrails = trails.sortedBy { it.createdAt }
        val startTime = sortedTrails.first().createdAt
        val endTime = sortedTrails.last().createdAt
        tvTimeRange.text = "时间范围: $startTime ~ $endTime"
        tvLatestAddress.text = "最新位置: ${trails.last().address.ifEmpty { "未知地址" }}"
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackHandler.removeCallbacks(playbackRunnable)
        mapView.onDestroy()
    }
}


