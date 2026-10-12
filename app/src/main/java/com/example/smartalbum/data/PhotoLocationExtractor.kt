package com.example.smartalbum.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.example.smartalbum.util.CoordinateConverter
import com.example.smartalbum.util.LogUtil
import java.io.File

/**
 * 照片位置提取器
 *
 * 职责：从MediaStore和EXIF中提取照片的地理位置
 * 从PhotoRepository中提取，遵循单一职责原则
 *
 * 提取策略：
 * 1. 优先从MediaStore数据库读取（速度快）
 * 2. MediaStore没有时从EXIF读取（更准确但慢）
 */
class PhotoLocationExtractor(private val context: Context) {

    /**
     * 从MediaStore获取照片位置
     */
    fun getLocationFromMediaStore(photoId: Long): Pair<Double, Double>? {
        return try {
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId.toString())
            val projection = arrayOf(
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val latIndex = cursor.getColumnIndex(MediaStore.Images.Media.LATITUDE)
                val lonIndex = cursor.getColumnIndex(MediaStore.Images.Media.LONGITUDE)
                if (latIndex < 0 || lonIndex < 0) return@use null
                val lat = cursor.getDouble(latIndex)
                val lon = cursor.getDouble(lonIndex)
                if (lat == 0.0 || lon == 0.0) return@use null
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            LogUtil.e("PhotoLocationExtractor", "从MediaStore获取位置失败: ${e.message}")
            null
        }
    }

    /**
     * 从Cursor中读取位置（高效，不需要额外查询MediaStore）
     * 自动转换WGS84→GCJ02坐标
     */
    fun getLocationFromCursor(
        cursor: android.database.Cursor,
        latColumn: Int,
        lonColumn: Int
    ): Pair<Double, Double>? {
        if (latColumn >= 0 && !cursor.isNull(latColumn) &&
            lonColumn >= 0 && !cursor.isNull(lonColumn)) {
            val wgsLat = cursor.getDouble(latColumn)
            val wgsLon = cursor.getDouble(lonColumn)
            if (wgsLat != 0.0 && wgsLon != 0.0) {
                return CoordinateConverter.convertToGcj02(wgsLat, wgsLon)
            }
        }
        return null
    }

    /**
     * 从EXIF获取照片位置（自动转换WGS84→GCJ02坐标）
     */
    fun getLocationFromExif(uri: Uri): Pair<Double, Double>? {
        return try {
            // 2026-10-10 修复：Android 10+ 直接用 contentResolver.openInputStream(uri)
            // 拿到的是**被系统剥离了 EXIF 位置信息**的流，exif.latLong 恒为 null，
            // 于是所有「MediaStore 没回填 LATITUDE 列、只有 EXIF 里有 GPS」的照片
            // 都被误判成无位置 —— 实测：一张带 GPS 的测试图照样被归进「无位置照片」。
            // 必须先 MediaStore.setRequireOriginal(uri)（需 ACCESS_MEDIA_LOCATION，Manifest 已声明），
            // 写法与 ExifUtil.readGpsFromUri 保持一致。
            val actualUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                try {
                    MediaStore.setRequireOriginal(uri)
                } catch (e: Exception) {
                    LogUtil.d("PhotoLocationExtractor", "setRequireOriginal失败，使用原始uri: ${e.message}")
                    uri
                }
            } else {
                uri
            }
            context.contentResolver.openInputStream(actualUri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val latLong = exif.latLong
                if (latLong != null && latLong.size == 2) {
                    val lat = latLong[0]
                    val lon = latLong[1]
                    if (lat != 0.0 && lon != 0.0) {
                        // EXIF是WGS84坐标，转换为GCJ02
                        return CoordinateConverter.convertToGcj02(lat, lon)
                    }
                }
                null
            }
        } catch (e: Exception) {
            // 2026-10-04：读不到 EXIF 是**正常情况**（截图/表情包/无地理信息的照片都会走到这里），
            // 原来按 ERROR 级别逐张记录，667 张照片能刷出几百条同类日志，
            // 把真正的错误彻底淹没（实测 APP 日志里 90% 是这条）。
            // 降为 DEBUG，并去掉堆栈——只有排查具体某张照片时才需要看。
            LogUtil.d("PhotoLocationExtractor", "从EXIF获取位置失败(该照片可能无地理信息): ${e.message}")
            null
        }
    }

    /**
     * 综合获取照片位置（优先MediaStore，回退EXIF）
     */
    fun getPhotoLocation(photoId: Long, uri: Uri): Pair<Double, Double>? {
        // 1. 优先从MediaStore读取
        getLocationFromMediaStore(photoId)?.let { return it }

        // 2. 回退到EXIF
        return getLocationFromExif(uri)
    }

    /**
     * 从文件路径获取EXIF位置
     */
    fun getLocationFromFilePath(filePath: String): Pair<Double, Double>? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null
            val exif = ExifInterface(filePath)
            val latLong = exif.latLong
            if (latLong != null && latLong.size == 2) {
                val lat = latLong[0]
                val lon = latLong[1]
                if (lat != 0.0 && lon != 0.0) {
                    return Pair(lat, lon)
                }
            }
            null
        } catch (e: Exception) {
            LogUtil.e("PhotoLocationExtractor", "从文件路径获取位置失败: ${e.message}")
            null
        }
    }
}
