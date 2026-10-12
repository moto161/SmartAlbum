package com.example.smartalbum.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 照片位置工具类
 * 用于读取和修改照片的位置信息
 */
object PhotoLocationUtil {

    // 保存最近一次的RecoverableSecurityException，用于Activity启动授权对话框
    var pendingSecurityException: android.app.RecoverableSecurityException? = null
        private set

    /**
     * 读取照片的位置信息
     * 注意：照片EXIF中的坐标是WGS-84（GPS原始坐标），
     * 高德地图使用GCJ-02（火星坐标），需要进行转换才能正确显示
     */
    fun getPhotoLocation(context: Context, uri: Uri): Pair<Double, Double>? {
        var wgsLocation: Pair<Double, Double>? = null

        // 先从MediaStore读取
        try {
            val projection = arrayOf(
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val lat = cursor.getDouble(0)
                    val lon = cursor.getDouble(1)
                    if (lat != 0.0 && lon != 0.0) {
                        wgsLocation = Pair(lat, lon)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e("PhotoLocationUtil", "异常", e)
        }

        // 从EXIF读取
        if (wgsLocation == null) {
            wgsLocation = ExifUtil.readGpsFromUri(context, uri)
        }

        // WGS-84 转 GCJ-02（高德地图坐标）
        return wgsLocation?.let { (lat, lon) ->
            CoordinateConverter.wgs84ToGcj02(lat, lon)
        }
    }

    /**
     * 修改照片的位置信息
     * 同时更新MediaStore和EXIF
     */
    fun setPhotoLocation(context: Context, uri: Uri, latitude: Double, longitude: Double): Boolean {
        var success = false

        // GCJ02(高德坐标) 转 WGS84(GPS原始坐标)，EXIF标准存储WGS84
        val (wgsLat, wgsLon) = CoordinateConverter.gcj02ToWgs84(latitude, longitude)
        LogUtil.d("PhotoLocationUtil", "写入位置: GCJ02($latitude, $longitude) -> WGS84($wgsLat, $wgsLon)")

        // 1. 更新MediaStore
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.LATITUDE, wgsLat)
                put(MediaStore.Images.Media.LONGITUDE, wgsLon)
            }
            val rows = context.contentResolver.update(uri, values, null, null)
            LogUtil.d("PhotoLocationUtil", "MediaStore更新行数: $rows")
            if (rows > 0) {
                success = true
            }
        } catch (e: Exception) {
            LogUtil.e("PhotoLocationUtil", "MediaStore更新失败", e)
        }

        // 2. 更新EXIF（需要写入文件）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 需要用setRequireOriginal获取原始URI才能写入EXIF
                val actualUri = try {
                    MediaStore.setRequireOriginal(uri)
                } catch (e: Exception) {
                    LogUtil.d("PhotoLocationUtil", "setRequireOriginal失败，使用原始uri: ${e.message}")
                    uri
                }
                context.contentResolver.openFileDescriptor(actualUri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    exif.setLatLong(wgsLat, wgsLon)
                    exif.saveAttributes()
                    success = true
                    LogUtil.d("PhotoLocationUtil", "EXIF写入成功(Android 10+)")
                } ?: run {
                    LogUtil.e("PhotoLocationUtil", "openFileDescriptor返回null，uri=$actualUri")
                }
            } else {
                // Android 9 及以下直接写入文件
                val filePath = getRealPathFromUri(context, uri)
                if (filePath != null) {
                    val file = File(filePath)
                    if (file.canWrite()) {
                        val exif = ExifInterface(file.absolutePath)
                        exif.setLatLong(wgsLat, wgsLon)
                        exif.saveAttributes()
                        success = true
                        LogUtil.d("PhotoLocationUtil", "EXIF写入成功(Android 9-)")
                    } else {
                        LogUtil.e("PhotoLocationUtil", "文件不可写: $filePath")
                    }
                } else {
                    LogUtil.e("PhotoLocationUtil", "无法获取文件路径")
                }
            }
        } catch (e: android.app.RecoverableSecurityException) {
            LogUtil.e("PhotoLocationUtil", "需要授权: ${e.message}")
            pendingSecurityException = e
        } catch (e: Exception) {
            LogUtil.e("PhotoLocationUtil", "EXIF写入失败", e)
        }

        return success
    }

    /**
     * 批量修改照片位置
     */
    fun setPhotosLocation(
        context: Context,
        uris: List<Uri>,
        latitude: Double,
        longitude: Double
    ): Int {
        var successCount = 0
        uris.forEach { uri ->
            if (setPhotoLocation(context, uri, latitude, longitude)) {
                successCount++
            }
        }
        return successCount
    }

    /**
     * 从Uri获取真实文件路径（Android 9及以下）
     */
    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (e: Exception) {
            LogUtil.d("PhotoLocationUtil", "获取位置失败: ${e.message}")
            null
        }
    }

    /**
     * 清除待处理的安全异常
     */
    fun clearPendingSecurityException() {
        pendingSecurityException = null
    }

    /**
     * 经纬度转EXIF格式
     */
}


