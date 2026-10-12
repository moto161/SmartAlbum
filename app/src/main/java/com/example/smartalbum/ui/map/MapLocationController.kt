package com.example.smartalbum.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps2d.AMap
import com.amap.api.maps2d.model.BitmapDescriptor
import com.amap.api.maps2d.model.BitmapDescriptorFactory
import com.amap.api.maps2d.model.LatLng
import com.example.smartalbum.util.LogUtil

/**
 * 地图定位控制器
 *
 * 职责：管理定位Client的生命周期、启动定位、处理定位结果、移动地图相机到当前位置
 * 从MapFragment2D中提取，遵循单一职责原则
 *
 * 使用方式：
 * ```
 * locationController = MapLocationController(this, aMap)
 * locationController.startLocation()
 * locationController.onDestroy()
 * ```
 */
class MapLocationController(
    private val fragment: Fragment,
    private var aMap: AMap?
) : AMapLocationListener {

    private var locationClient: AMapLocationClient? = null
    private var locationOption: AMapLocationClientOption? = null
    private var currentLocation: LatLng? = null
    private var locationUpdateCount = 0

    // 最多定位3次，取最新结果后停止（省电）
    private val maxLocationUpdates = 3

    /**
     * 「我的位置」标记点。
     * 2026-10-04 修复：原先 addMarker() 的返回值被丢弃、且全文没有一处 removeMarker，
     * 导致点几次定位按钮就在地图上堆出几个红点（每次定位 interval=2s 至少回调 3 次，
     * 每次都 add 一个 marker，locationUpdateCount 还只增不减）→ 地图上出现一堆坐标。
     * 现在保存引用，下次定位前先移除旧的，保证地图上永远只有一个「我的位置」。
     */
    private var myLocationMarker: com.amap.api.maps2d.model.Marker? = null

    // 定位结果回调，通知Fragment更新UI
    var onLocationResult: ((LatLng, String, String) -> Unit)? = null  // (位置, 标题, 位置名称)
    var onLocationError: ((String) -> Unit)? = null

    /**
     * 启动定位
     */
    fun startLocation() {
        val context = fragment.context ?: return

        // 检查权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            fragment.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }

        LogUtil.d("Location", "startLocation被调用")

        try {
            if (locationClient == null) {
                locationClient = AMapLocationClient(context.applicationContext)
                locationOption = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = false
                    interval = 2000
                    isNeedAddress = true
                }
                locationClient?.setLocationOption(locationOption)
                locationClient?.setLocationListener(this)
            }

            locationUpdateCount = 0
            // 2026-10-04：反复点定位按钮会反复调 startLocation()，
            // 高德在已监听状态上再 start 会叠加请求。改为先 stop 再 start，
            // 保证任何时候只有一个定位任务在跑（这也是"点几次就多几个点"的另一半原因）。
            try {
                locationClient?.stopLocation()
            } catch (_: Exception) {
            }
            locationClient?.startLocation()
            LogUtil.i("Location", "位置上报服务启动")
        } catch (e: Exception) {
            LogUtil.e("Location", "定位启动失败: ${e.message}")
            onLocationError?.invoke("定位启动失败: ${e.message}")
        }
    }

    /**
     * 定位结果回调
     */
    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null) {
            LogUtil.i("Location", "onLocationChanged被调用，location是否为空: true")
            return
        }

        LogUtil.i("Location", "onLocationChanged被调用，location是否为空: false")

        if (location.errorCode != 0) {
            LogUtil.e("Location", "定位失败: errorCode=${location.errorCode}, info=${location.errorInfo}")
            onLocationError?.invoke("定位失败: ${location.errorInfo}")
            return
        }

        locationUpdateCount++
        val latLng = LatLng(location.latitude, location.longitude)
        currentLocation = latLng

        // 获取位置名称
        val locationName = location.poiName
                ?: location.address
                ?: "${location.city ?: ""}${location.district ?: ""}".ifEmpty { "我的位置" }

        LogUtil.d("Location", "定位结果: errorCode=${location.errorCode}, errorInfo=${location.errorInfo}, " +
                "latitude=${location.latitude}, longitude=${location.longitude}, " +
                "accuracy=${location.accuracy}, provider=${location.provider}")
        LogUtil.i("Location", "定位成功(第${locationUpdateCount}次)，经纬度: ${location.latitude}, ${location.longitude}")

        // 移动地图到当前位置
        moveToLocation(latLng, "我的位置", true)

        // 通知Fragment更新UI
        onLocationResult?.invoke(latLng, "我的位置", locationName)

        // 达到最大次数后停止定位
        if (locationUpdateCount >= maxLocationUpdates) {
            stopLocation()
            LogUtil.i("Location", "已获取${maxLocationUpdates}次定位结果，停止定位")
        }
    }

    /**
     * 移动地图相机到指定位置
     */
    fun moveToLocation(latLng: LatLng, title: String) {
        moveToLocation(latLng, title, false, DEFAULT_ZOOM)
    }

    /**
     * 移动地图相机到指定位置
     * @param useRedMarker 是否使用红色标记（我的位置、搜索结果用红色）
     * @param zoom 目标缩放级别。默认 15f（区县级）；搜索 POI 时传 17f（街道级）
     */
    fun moveToLocation(latLng: LatLng, title: String, useRedMarker: Boolean, zoom: Float = DEFAULT_ZOOM) {
        val map = aMap ?: run {
            LogUtil.w("Map", "aMap 还未就绪，moveToLocation 被丢弃: $title")
            return
        }
        LogUtil.d("Map", "moveToLocation被调用，经纬度: ${latLng.latitude}, ${latLng.longitude}, 标题: $title, 红色标记: $useRedMarker, zoom: $zoom")

        val cameraUpdate = com.amap.api.maps2d.CameraUpdateFactory.newLatLngZoom(latLng, zoom)
        map.animateCamera(cameraUpdate, 500, null)
        LogUtil.d("Map", "地图动画已开始")

        // 添加标记点
        val markerOptions = com.amap.api.maps2d.model.MarkerOptions()
            .position(latLng)
            .title(title)
        // 使用红色标记（我的位置、搜索结果）
        if (useRedMarker || title == "我的位置") {
            // 红点恢复默认大小（用户反馈 1.5 倍过大挡视野），zIndex 置顶保证不被聚合气泡遮挡
            markerOptions.icon(com.amap.api.maps2d.model.BitmapDescriptorFactory.defaultMarker(0f))
        }
        // 2026-10-04 修复重复标记点：
        // 「我的位置」是持续更新的，同一时刻只应存在一个。
        // 先移除上一次的，再添加新的 —— 否则点几次定位按钮就会堆出几个红点。
        if (useRedMarker || title == "我的位置") {
            myLocationMarker?.remove()
            myLocationMarker = map.addMarker(markerOptions)
            // 2026-10-11：zIndex 置顶，确保红色搜索/定位标记永远盖在聚合气泡之上，
            // 不会被相机缩放时重新渲染的聚合点遮挡。
            myLocationMarker?.zIndex = 1000f
        } else {
            map.addMarker(markerOptions)
        }
        LogUtil.i("Map", "标记已添加，标题: $title")
    }

    /**
     * 停止定位
     */
    fun stopLocation() {
        try {
            locationClient?.stopLocation()
            LogUtil.d("Location", "定位已停止")
        } catch (e: Exception) {
            LogUtil.e("Location", "停止定位失败: ${e.message}")
        }
    }

    /**
     * 生成放大版红色图钉（用于「我的位置」和搜索结果标记）。
     * 高德默认图钉无法缩放且偏小，这里用 Canvas 自绘一个更大的红色水滴图钉，
     * 配合 setZIndex(1000) 保证它始终盖在聚合气泡之上。
     * @param scale 相对于默认图钉的放大倍数（1.5 ≈ 明显大一圈）
     */
    private fun createRedMarkerIcon(scale: Float): BitmapDescriptor {
        val ctx = fragment.context ?: return BitmapDescriptorFactory.defaultMarker(0f)
        val density = ctx.resources.displayMetrics.density
        val w = (72f * scale * density).toInt().coerceAtLeast(2)
        val h = (104f * scale * density).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = w / 2f
        val headR = w / 2f - 8f * density
        val headCy = headR + 10f * density
        val tipY = h - 4f * density

        val path = Path()
        path.moveTo(cx, tipY)
        path.lineTo(cx - headR * 0.72f, headCy + headR * 0.7f)
        path.arcTo(RectF(cx - headR, headCy - headR, cx + headR, headCy + headR), 135f, 270f, false)
        path.close()

        // 淡阴影，增加立体感
        p.color = Color.argb(50, 0, 0, 0)
        c.drawPath(path, p)
        // 红色填充
        p.color = Color.parseColor("#E53935")
        c.drawPath(path, p)
        // 白色圆心
        p.color = Color.WHITE
        c.drawCircle(cx, headCy, headR * 0.5f, p)

        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /**
     * 清除「我的位置」标记点。
     * 2026-10-04 新增：页面退出时调用，避免标记残留在地图上。
     */
    fun clearMyLocationMarker() {
        try {
            myLocationMarker?.remove()
            myLocationMarker = null
        } catch (e: Exception) {
            LogUtil.e("Location", "清除我的位置标记失败: ${e.message}")
        }
    }

    /**
     * 暂停定位（Fragment onPause时调用）
     */
    fun onPause() {
        stopLocation()
    }

    /**
     * 恢复定位（Fragment onResume时调用，如果之前在定位）
     */
    fun onResume() {
        // 如果之前在定位中，可以在这里恢复
        // 当前策略：不自动恢复，需要用户手动触发
    }

    /**
     * 销毁定位资源（Fragment onDestroyView时调用）
     */
    fun onDestroy() {
        stopLocation()
        // 2026-10-04：退出页面时一并清掉「我的位置」标记，否则会残留在地图上
        clearMyLocationMarker()
        try {
            locationClient?.onDestroy()
            locationClient = null
            locationOption = null
            LogUtil.i("Location", "定位资源已释放")
        } catch (e: Exception) {
            LogUtil.e("Location", "销毁定位资源失败: ${e.message}")
        }
    }

    /**
     * 更新AMap引用（地图恢复时调用）
     */
    fun setAMap(map: AMap?) {
        this.aMap = map
    }

    /**
     * 获取当前位置
     */
    fun getCurrentLocation(): LatLng? = currentLocation

    /**
     * 权限请求结果处理
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocation()
            } else {
                onLocationError?.invoke("位置权限被拒绝")
            }
        }
    }

    companion object {
        const val REQUEST_LOCATION_PERMISSION = 1001
        // 我的位置/普通移动默认 15f（区县级）
        const val DEFAULT_ZOOM = 15f
        // 搜索 POI 结果用 17f（街道级，能看到周边门牌和马路）
        const val POI_ZOOM = 17f
    }
}
