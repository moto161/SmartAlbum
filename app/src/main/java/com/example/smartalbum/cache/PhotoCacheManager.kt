package com.example.smartalbum.cache
import com.example.smartalbum.util.LogUtil

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.smartalbum.data.Photo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * 照片外部存储缓存管理器
 * 仅负责外部存储（Download/SmartAlbum/photo_cache.json）的备份和恢复
 * 用于卸载重装后从外部存储自动恢复照片位置缓存
 * 本地缓存已迁移到SQLite数据库（PhotoDatabaseHelper）
 */
class PhotoCacheManager(private val context: Context) {

    companion object {
        private const val EXTERNAL_CACHE_DIR = "SmartAlbum"
        private const val EXTERNAL_CACHE_FILE = "photo_cache.json"
        private const val CACHE_VERSION = 1
    }

    // ==================== 外部存储缓存（卸载重装可恢复） ====================

    /**
     * 保存缓存到外部存储（Download/SmartAlbum/photo_cache.json）
     * 用文件名+拍摄时间作为唯一标识，不依赖ID（卸载重装后ID会变）
     */
    fun saveToExternalStorage(photos: List<Photo>) {
        try {
            val jsonString = buildCacheJson(photos).toString(2)
            writeJsonToExternalStorage(jsonString)
        } catch (e: Exception) {
            LogUtil.e("PhotoCacheManager", "异常", e)
        }
    }

    /**
     * 2026-10-10 新增：导入外部缓存 JSON
     *
     * 用户在设置页选了一个导出文件（可能来自另一台设备/旧版本），
     * 校验它是合法的 photo_cache.json 格式后，**直接覆盖**外部存储上的
     * photo_cache.json。下次启动时 loadFromExternalStorage() 就能读到。
     *
     * @return true 表示导入并写入成功；false 表示格式不对或写入失败
     */
    fun importExternalCacheJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            // 至少要有 photos 数组才算合法
            root.getJSONArray("photos")
            writeJsonToExternalStorage(jsonString)
            LogUtil.i("PhotoCacheManager", "外部缓存导入成功，覆盖 photo_cache.json")
            true
        } catch (e: Exception) {
            LogUtil.e("PhotoCacheManager", "导入JSON校验或写入失败", e)
            false
        }
    }

    /**
     * 构建缓存JSON（包含照片列表和位置名称）
     */
    private fun buildCacheJson(photos: List<Photo>): JSONObject {
        val rootJson = JSONObject()
        rootJson.put("version", CACHE_VERSION)
        rootJson.put("last_scan_time", System.currentTimeMillis())
        rootJson.put("photos", buildPhotosJsonArray(photos))
        return rootJson
    }

    /**
     * 构建照片列表JSON（只保存有位置信息的照片）
     */
    private fun buildPhotosJsonArray(photos: List<Photo>): JSONArray {
        val photosArray = JSONArray()
        photos.filter { it.hasLocation }.forEach { photo ->
            val pJson = JSONObject()
            pJson.put("display_name", photo.displayName)
            pJson.put("date_taken", photo.dateTaken)
            pJson.put("latitude", photo.latitude)
            pJson.put("longitude", photo.longitude)
            photosArray.put(pJson)
        }
        return photosArray
    }

    /**
     * 写入JSON到外部存储（根据Android版本选择不同方式）
     */
    private fun writeJsonToExternalStorage(jsonString: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStore(jsonString)
        } else {
            writeToFileDirectly(jsonString)
        }
    }

    /**
     * Android 10+ 用 MediaStore 写入
     */
    private fun writeToMediaStore(jsonString: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = findOrCreateCacheUri(collection)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri, "w")?.use { os: OutputStream ->
                os.write(jsonString.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * 查找已存在的缓存文件URI，不存在则创建新文件
     */
    private fun findOrCreateCacheUri(collection: android.net.Uri): android.net.Uri? {
        // 先按文件名查询（不限制路径，因为路径格式可能不同）
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(EXTERNAL_CACHE_FILE)

        var existingUri: android.net.Uri? = null
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    existingUri = ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhotoCache", "查询缓存文件失败: ${e.message}")
        }

        if (existingUri != null) return existingUri

        // 不存在则创建
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, EXTERNAL_CACHE_FILE)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXTERNAL_CACHE_DIR")
            }
            context.contentResolver.insert(collection, contentValues)
        } catch (e: Exception) {
            android.util.Log.e("PhotoCache", "创建缓存文件失败: ${e.message}")
            // 2026-10-10 修复：插入失败时再次查询已存在的同名文件并返回其 URI
            // 原代码这里 use{} lambda 最后一行没 return，整个函数返回 null，导致 fallback 失效
            try {
                var foundUri: android.net.Uri? = null
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        foundUri = ContentUris.withAppendedId(collection, id)
                    }
                }
                foundUri
            } catch (e2: Exception) {
                android.util.Log.e("PhotoCache", "fallback查询失败: ${e2.message}")
                null
            }
        }
    }

    /**
     * Android 9 及以下直接写入文件
     */
    private fun writeToFileDirectly(jsonString: String) {
        val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXTERNAL_CACHE_DIR
        )
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, EXTERNAL_CACHE_FILE)
        file.writeText(jsonString, Charsets.UTF_8)
    }

    /**
     * 从外部存储加载缓存
     * @return 照片缓存列表（用display_name+date_taken作为key）
     */
    fun loadFromExternalStorage(): ExternalCacheData? {
        return try {
            val jsonString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(
            EXTERNAL_CACHE_FILE,
            "${Environment.DIRECTORY_DOWNLOADS}/$EXTERNAL_CACHE_DIR"
        )
                var result: String? = null
                context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = ContentUris.withAppendedId(collection, id)
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                                result = reader.readText()
                            }
                        }
                    }
                }
                result
            } else {
                val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXTERNAL_CACHE_DIR
        )
                val file = java.io.File(dir, EXTERNAL_CACHE_FILE)
                if (file.exists()) file.readText(Charsets.UTF_8) else null
            }

            if (jsonString == null) return null

            val rootJson = JSONObject(jsonString)
            val photosMap = mutableMapOf<String, PhotoCacheEntry>()

            val photosArray = rootJson.optJSONArray("photos")
            if (photosArray != null) {
                for (i in 0 until photosArray.length()) {
                    val pJson = photosArray.getJSONObject(i)
                    val displayName = pJson.getString("display_name")
                    val dateTaken = pJson.getLong("date_taken")
                    val latitude = pJson.getDouble("latitude")
                    val longitude = pJson.getDouble("longitude")
                    val key = "${displayName}_$dateTaken"
                    photosMap[key] = PhotoCacheEntry(displayName, dateTaken, latitude, longitude)
                }
            }

            ExternalCacheData(photosMap, rootJson.optLong("last_scan_time", 0))
        } catch (e: Exception) {
            LogUtil.e("PhotoCacheManager", "异常", e)
            null
        }
    }

    /**
     * 外部缓存数据
     */
    data class ExternalCacheData(
        val photos: Map<String, PhotoCacheEntry>,  // key = display_name_date_taken
        val lastScanTime: Long
    )

    /**
     * 外部缓存中的照片条目
     */
    data class PhotoCacheEntry(
        val displayName: String,
        val dateTaken: Long,
        val latitude: Double,
        val longitude: Double
    )
}

