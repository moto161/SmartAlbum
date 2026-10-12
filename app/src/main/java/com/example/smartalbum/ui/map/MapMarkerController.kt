package com.example.smartalbum.ui.map

import android.content.Context
import com.amap.api.maps2d.AMap
import com.amap.api.maps2d.model.BitmapDescriptor
import com.amap.api.maps2d.model.BitmapDescriptorFactory
import com.example.smartalbum.R
import com.example.smartalbum.data.LocationGroup
import com.example.smartalbum.util.ClusterCalculator
import com.example.smartalbum.util.LogUtil
import com.example.smartalbum.util.MarkerClusterManager

/**
 * 地图标记点控制器
 *
 * 职责：管理标记点数据、聚合渲染、相机变化时重新渲染、资源释放
 * 从MapFragment2D中提取，遵循单一职责原则
 */
class MapMarkerController(
    private var aMap: AMap?,
    private val context: Context
) {
    private var locationGroups = listOf<LocationGroup>()
    private var clusterManager: MarkerClusterManager? = null
    private var markerIcon: BitmapDescriptor? = null

    // 标记点点击回调
    var onMarkerClick: ((ClusterCalculator.Cluster) -> Unit)? = null

    /**
     * 设置标记点数据
     */
    fun setGroups(groups: List<LocationGroup>) {
        this.locationGroups = groups
        if (markerIcon == null) {
            markerIcon = BitmapDescriptorFactory.fromResource(R.drawable.ic_marker)
        }
    }

    /**
     * 初始化聚合管理器（地图准备好后调用）
     */
    fun initClusterManager() {
        val map = aMap ?: return
        if (clusterManager == null) {
            clusterManager = MarkerClusterManager(map, context)
        }
        clusterManager?.setItems(locationGroups)
        setupMarkerClickListener()
    }

    /**
     * 设置标记点点击监听
     */
    private fun setupMarkerClickListener() {
        val map = aMap ?: return
        map.setOnMarkerClickListener { marker ->
            val obj = marker.`object`
            if (obj is ClusterCalculator.Cluster) {
                onMarkerClick?.invoke(obj)
            }
            true
        }
    }

    /**
     * 显示标记点（聚合渲染）
     */
    fun showMarkers() {
        val map = aMap ?: return
        val manager = clusterManager ?: run {
            initClusterManager()
            clusterManager
        } ?: return

        if (locationGroups.isEmpty()) {
            LogUtil.w("MapMarker", "没有位置数据，跳过标记点渲染")
            return
        }

        manager.setItems(locationGroups)
        manager.render()
        // 2026-10-04：Fragment 切换会同时走"恢复"和"渲染"两条路径，
        // 这条日志每次切页都会打 1~2 次，原 INFO 级让 APP 日志被它刷满。
        // 降为 DEBUG，需要排查渲染问题时再看。
        LogUtil.d("MapMarker", "标记点已渲染，共${locationGroups.size}个位置")

        // 设置相机变化监听，移动时重新聚合
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: com.amap.api.maps2d.model.CameraPosition?) { /* 相机移动中不处理 */ }
            override fun onCameraChangeFinish(position: com.amap.api.maps2d.model.CameraPosition?) {
                manager.render()
            }
        })
    }

    /**
     * 清除所有标记点
     */
    fun clearMarkers() {
        clusterManager?.clear()
        LogUtil.d("MapMarker", "标记点已清除")
    }

    /**
     * 更新标记点标题（数据变化时调用）
     */
    fun updateMarkerTitles() {
        // 重新渲染即可更新标题
        showMarkers()
    }

    /**
     * 暂停（Fragment onPause时调用）
     */
    fun onPause() {
        // 暂停时不需要特殊处理，保持标记点显示
    }

    /**
     * 恢复（Fragment onResume时调用）
     */
    fun onResume() {
        // 恢复时重新渲染标记点
        if (locationGroups.isNotEmpty()) {
            showMarkers()
        }
    }

    /**
     * 释放资源（Fragment onHiddenChanged隐藏时调用）
     */
    fun release() {
        clearMarkers()
        clusterManager = null
        LogUtil.i("MapMarker", "标记点资源已释放")
    }

    /**
     * 恢复地图（Fragment onHiddenChanged显示时调用）
     */
    fun restore(map: AMap?) {
        this.aMap = map
        if (locationGroups.isNotEmpty()) {
            initClusterManager()
            showMarkers()
        }
        LogUtil.i("MapMarker", "标记点已恢复")
    }

    /**
     * 销毁（Fragment onDestroyView时调用）
     */
    fun onDestroy() {
        release()
        markerIcon = null
        aMap = null
        LogUtil.i("MapMarker", "标记点控制器已销毁")
    }

    /**
     * 更新AMap引用
     */
    fun setAMap(map: AMap?) {
        this.aMap = map
    }

    /**
     * 获取当前位置分组数据
     */
    fun getGroups(): List<LocationGroup> = locationGroups
}
