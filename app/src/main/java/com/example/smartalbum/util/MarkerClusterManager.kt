package com.example.smartalbum.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.amap.api.maps2d.AMap
import com.amap.api.maps2d.model.BitmapDescriptor
import com.amap.api.maps2d.model.BitmapDescriptorFactory
import com.amap.api.maps2d.model.LatLng
import com.amap.api.maps2d.model.Marker
import com.amap.api.maps2d.model.MarkerOptions
import com.example.smartalbum.R
import com.example.smartalbum.data.LocationGroup

/**
 * 标记点聚合管理器（渐进式聚合）
 * 
 * 核心思想：所有缩放级别都进行聚合，网格大小随缩放级别连续变化。
 * 放大时网格变小，聚合点逐渐"释放"为单个点，实现平滑过渡。
 * 
 * 网格大小公式：gridSize = baseSize * 2^(baseZoom - zoomLevel)
 * - 缩放每增加1级，网格大小减半，可见点数约翻倍
 * - 远看概览（聚合），近看细节（单个），无硬切换
 */
class MarkerClusterManager(
    private val aMap: AMap,
    private val context: android.content.Context
) {
    private val markers = mutableListOf<Marker>()
    private var allGroups = listOf<LocationGroup>()
    private var singleMarkerIcon: BitmapDescriptor? = null
    private val clusterIconCache = mutableMapOf<Int, BitmapDescriptor>()

    // 聚合计算委托给纯Kotlin类（可单元测试）
    private val calculator = ClusterCalculator()

    /**
     * 设置所有标记点数据
     */
    fun setItems(groups: List<LocationGroup>) {
        allGroups = groups
        if (singleMarkerIcon == null) {
            singleMarkerIcon = BitmapDescriptorFactory.fromResource(R.drawable.ic_marker)
        }
    }

    /**
     * 根据当前地图状态渲染标记点
     */
    fun render() {
        clear()
        val zoomLevel = aMap.cameraPosition.zoom
        val visibleBounds = aMap.projection.visibleRegion.latLngBounds

        // 构建可见区域（转换为纯数据类，便于委托计算）
        val bounds = ClusterCalculator.VisibleBounds(
            minLatitude = visibleBounds.southwest.latitude,
            maxLatitude = visibleBounds.northeast.latitude,
            minLongitude = visibleBounds.southwest.longitude,
            maxLongitude = visibleBounds.northeast.longitude
        )

        // 委托给ClusterCalculator计算需要渲染的聚合簇
        val clustersToShow = calculator.calculateClustersToRender(
            groups = allGroups,
            zoomLevel = zoomLevel,
            visibleBounds = bounds
        )

        if (clustersToShow.isEmpty()) return

        renderClusters(clustersToShow)
    }

    /**
     * 渲染聚合标记点
     * 单个点用普通图标，多个点用带数字的聚合气泡
     */
    private fun renderClusters(clusters: List<ClusterCalculator.Cluster>) {
        clusters.forEach { cluster ->
            val position = LatLng(cluster.latitude, cluster.longitude)
            
            val marker = if (cluster.count == 1) {
                // 单个点：用普通标记图标
                val group = cluster.groups[0]
                aMap.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(group.locationName)
                        .snippet("${group.photoCount}张照片")
                        .icon(singleMarkerIcon)
                )
            } else {
                // 多个点：用带数字的聚合气泡
                val title = "${cluster.count}个位置，${cluster.totalPhotos}张照片"
                aMap.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(title)
                        .icon(getClusterIcon(cluster.count))
                )
            }
            marker.`object` = cluster
            markers.add(marker)
        }
    }

    /**
     * 获取聚合点图标（带数字的圆形气泡）
     * 缓存已生成的图标，避免重复创建
     */
    private fun getClusterIcon(count: Int): BitmapDescriptor {
        // 数量分级显示：9+, 50+, 100+, 500+
        val displayCount = when {
            count >= 500 -> "500+"
            count >= 100 -> "100+"
            count >= 50 -> "50+"
            count >= 10 -> "9+"
            else -> count.toString()
        }
        
        clusterIconCache[displayCount.hashCode()]?.let { return it }

        // 绘制圆形气泡
        val size = 120
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景圆（半透明蓝色）
        paint.color = Color.argb(220, 59, 130, 246)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, paint)

        // 白色边框
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, paint)

        // 数字文字
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = if (displayCount.length > 2) 28f else 36f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true

        val textBounds = Rect()
        paint.getTextBounds(displayCount, 0, displayCount.length, textBounds)
        val textY = size / 2f + textBounds.height() / 2f
        canvas.drawText(displayCount, size / 2f, textY, paint)

        val icon = BitmapDescriptorFactory.fromBitmap(bitmap)
        clusterIconCache[displayCount.hashCode()] = icon
        return icon
    }

    /**
     * 清除所有标记点
     */
    fun clear() {
        markers.forEach { it.remove() }
        markers.clear()
    }

}
