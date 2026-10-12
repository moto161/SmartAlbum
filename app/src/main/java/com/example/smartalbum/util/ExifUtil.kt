package com.example.smartalbum.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExifUtil {

    fun readGpsFromUri(context: Context, uri: Uri): Pair<Double, Double>? {
        return try {
            // Android 10+ 需要用setRequireOriginal获取原始照片URI才能读取EXIF
            val actualUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    MediaStore.setRequireOriginal(uri)
                } catch (e: Exception) {
                    LogUtil.d("ExifUtil", "setRequireOriginal失败，使用原始uri: ${e.message}")
                    uri
                }
            } else {
                uri
            }
            
            context.contentResolver.openInputStream(actualUri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val latLong = exif.latLong
                if (latLong != null && latLong.size == 2) {
                    Pair(latLong[0], latLong[1])
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            LogUtil.e("ExifUtil", "异常", e)
            null
        }
    }

    fun getDateTaken(context: Context, uri: Uri): Long {
        return try {
            val projection = arrayOf(MediaStore.Images.Media.DATE_TAKEN)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                } else {
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            LogUtil.d("ExifUtil", "获取照片时间失败: ${e.message}")
            System.currentTimeMillis()
        }
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
        return sdf.format(Date(timestamp))
    }

    fun formatDateOnly(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        return sdf.format(Date(timestamp))
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            LogUtil.d("ExifUtil", "获取照片尺寸失败: ${e.message}")
            Pair(0, 0)
        }
    }
}
