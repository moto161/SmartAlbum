package com.example.smartalbum.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.example.smartalbum.MainActivity
import com.example.smartalbum.R
import com.example.smartalbum.api.LocationApiClient
import com.example.smartalbum.cache.LocationCacheManager
import com.example.smartalbum.util.LogUtil
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import com.example.smartalbum.model.LocationData
import com.example.smartalbum.util.DeviceIdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 位置上报前台服务
 * 每隔10分钟获取一次位置（低功耗模式）并上传到服务器
 */
class LocationUploadService : Service() {

    companion object {
        private const val TAG = "LocationUploadService"
        private const val CHANNEL_ID = "location_upload_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UPLOAD_INTERVAL = 10 * 60 * 1000L // 10分钟

        fun start(context: Context) {
            // 检查位置权限，Android 14+要求启动location类型前台服务必须有位置权限
            val fineGranted = context.checkSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarseGranted = context.checkSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasLocationPermission = fineGranted || coarseGranted
            if (!hasLocationPermission) {
                Log.w(TAG, "无位置权限，不启动位置上报服务")
                return
            }
            val intent = Intent(context, LocationUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "启动位置上报服务")
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationUploadService::class.java)
            context.stopService(intent)
            Log.d(TAG, "停止位置上报服务")
        }
    }

    private var locationClient: AMapLocationClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cacheManager: LocationCacheManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val uploadRunnable = object : Runnable {
        override fun run() {
            getLocationAndUpload(false)
            handler.postDelayed(this, UPLOAD_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i("LocationUploadService", "位置上报服务启动")
        android.util.Log.d(TAG, "LocationUploadService onCreate被调用")
        createNotificationChannel()
        android.util.Log.d(TAG, "通知渠道已创建")
        startForeground(NOTIFICATION_ID, buildNotification("后台运行中"))
        android.util.Log.d(TAG, "startForeground已调用")
        initLocationClient()
        // 初始化本地缓存
        cacheManager = LocationCacheManager(this)
        // 注册网络状态监听
        registerNetworkCallback()
        // 启动时如果有网络，先尝试上传缓存
        if (isNetworkAvailable()) {
            uploadCachedLocations()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即执行一次定位上报
        handler.post { getLocationAndUpload(false) }
        // 开始定时任务
        // 必须先 removeCallbacks：onStartCommand 每次 startService 都会调用，
        // 不先清掉旧的 Runnable 会叠加出多个定时任务（定位频次翻倍、耗电翻倍）
        handler.removeCallbacks(uploadRunnable)
        handler.postDelayed(uploadRunnable, UPLOAD_INTERVAL)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(uploadRunnable)
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
        // 注销网络监听
        try {
            networkCallback?.let { callback ->
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "注销网络监听失败: ${e.message}")
        }
        LogUtil.i("LocationUploadService", "位置上报服务已停止")
        Log.d(TAG, "位置上报服务已销毁")
    }

    private fun initLocationClient() {
        try {
            locationClient = AMapLocationClient(applicationContext)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                isOnceLocation = true
                httpTimeOut = 10000
                isNeedAddress = true
            }
            locationClient?.setLocationOption(option)
        } catch (e: Exception) {
            Log.e(TAG, "初始化定位客户端失败: ${e.message}")
        }
    }

    /**
     * 获取位置并上传
     * @param isHighAccuracy 是否高精度模式（用户手动点击时用true）
     */
    fun getLocationAndUpload(isHighAccuracy: Boolean) {
        try {
            if (locationClient == null) {
                initLocationClient()
            }

            // 如果是高精度模式，临时切换
            if (isHighAccuracy) {
                val option = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = true
                    httpTimeOut = 15000
                    isNeedAddress = true
                }
                locationClient?.setLocationOption(option)
            }

            val listener = AMapLocationListener { location ->
                if (location != null && location.errorCode == 0) {
                    uploadLocation(location, isHighAccuracy)
                } else {
                    Log.e(TAG, "定位失败: ${location?.errorCode} - ${location?.errorInfo}")
                }
                // 高精度模式定位完成后，恢复低功耗模式
                if (isHighAccuracy) {
                    val option = AMapLocationClientOption().apply {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                        isOnceLocation = true
                        httpTimeOut = 10000
                        isNeedAddress = true
                    }
                    locationClient?.setLocationOption(option)
                }
            }

            locationClient?.setLocationListener(listener)
            locationClient?.startLocation()
        } catch (e: Exception) {
            Log.e(TAG, "获取位置失败: ${e.message}")
        }
    }

    private fun uploadLocation(location: AMapLocation, isHighAccuracy: Boolean) {
        Thread {
            try {
                val deviceId = DeviceIdManager.getDeviceId(this)
                val data = LocationData(
                    deviceId = deviceId,
                    deviceName = DeviceIdManager.getDeviceName(),
                    deviceModel = DeviceIdManager.getDeviceModel(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = location.address ?: "",
                    isHighAccuracy = isHighAccuracy,
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
                val success = LocationApiClient.uploadLocation(data)
                if (success) {
                    LogUtil.i("LocationUploadService", "位置上传成功: ${location.latitude}, ${location.longitude}")
                    Log.d(TAG, "位置上传成功: ${location.latitude}, ${location.longitude}")
                    // 上传成功后，尝试上传之前缓存的
                    uploadCachedLocations()
                } else {
                    Log.e(TAG, "位置上传失败，保存到本地缓存")
                    cacheManager.saveLocation(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传位置异常: ${e.message}，保存到本地缓存")
                val data = LocationData(
                    deviceId = DeviceIdManager.getDeviceId(this),
                    deviceName = DeviceIdManager.getDeviceName(),
                    deviceModel = DeviceIdManager.getDeviceModel(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = location.address ?: "",
                    isHighAccuracy = isHighAccuracy,
                    time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
                cacheManager.saveLocation(data)
            }
        }.start()
    }

    /**
     * 上传所有缓存的位置数据
     */
    private fun uploadCachedLocations() {
        Thread {
            try {
                val cachedList = cacheManager.getCachedLocations()
                if (cachedList.isEmpty()) {
                    return@Thread
                }
                Log.d(TAG, "开始上传缓存的位置数据，共${cachedList.size}条")
                var successCount = 0
                for (data in cachedList) {
                    val success = LocationApiClient.uploadLocation(data)
                    if (success) {
                        cacheManager.removeLocation(data)
                        successCount++
                    } else {
                        // 上传失败，停止继续上传（可能网络又断了）
                        Log.e(TAG, "缓存数据上传失败，停止上传")
                        break
                    }
                }
                if (successCount > 0) {
                    LogUtil.i("LocationUploadService", "缓存数据上传成功：$successCount/${cachedList.size}条")
                    Log.d(TAG, "缓存数据上传成功：$successCount/${cachedList.size}条")
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传缓存数据异常: ${e.message}")
            }
        }.start()
    }

    /**
     * 注册网络状态监听
     */
    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    LogUtil.i("LocationUploadService", "网络已恢复，开始上传缓存数据")
                    Log.d(TAG, "网络已连接，开始上传缓存数据")
                    uploadCachedLocations()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    LogUtil.w("LocationUploadService", "网络已断开，位置数据将缓存到本地")
                    Log.d(TAG, "网络已断开")
                }
            }
            val callback = networkCallback
            if (callback != null) {
                val request = NetworkRequest.Builder().build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册网络监听失败: ${e.message}")
        }
    }

    /**
     * 检查网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            network != null
        } catch (e: Exception) {
            Log.e(TAG, "检查位置权限失败: ${e.message}")
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置记录服务",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "记录并上传设备位置信息"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能相册")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

}
