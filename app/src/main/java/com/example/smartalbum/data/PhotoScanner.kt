package com.example.smartalbum.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.smartalbum.cache.PhotoCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 照片扫描器
 *
 * 职责：从MediaStore扫描照片、提取照片信息、获取照片位置
 * 从PhotoRepository中提取，遵循单一职责原则
 *
 * 位置获取优先级：外部缓存 > MediaStore > EXIF
 */
class PhotoScanner(
    private val context: Context,
    private val locationExtractor: PhotoLocationExtractor
) {
    companion object {
        private const val BATCH_SIZE = 500 // 每扫描500张触发一次GC

        /** 单条 SQL 里 IN(...) 的 id 数上限，需 < SQLite 的 999 绑定变量上限 */
        private const val MAX_SQL_BATCH = 500
    }

    /**
     * 获取照片总数
     */
    fun getPhotoCount(): Int {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        var count = 0
        try {
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                count = cursor.count
            }
            android.util.Log.i("PhotoScanner", "getPhotoCount: MediaStore返回 $count 张照片")
        } catch (e: Exception) {
            android.util.Log.e("PhotoScanner", "getPhotoCount查询失败: ${e.message}", e)
        }
        return count
    }

    /**
     * 获取所有照片ID
     */
    fun getAllPhotoIds(): List<Long> {
        val ids = mutableListOf<Long>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(idColumn))
            }
        }
        return ids
    }

    /**
     * 全量扫描（带外部缓存匹配，匹配到的跳过EXIF读取）
     */
    suspend fun scanAllPhotosWithProgress(
        total: Int,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val actualTotal = cursor.count
                android.util.Log.i("PhotoScanner", "scanAllPhotosWithProgress: Cursor数量=$actualTotal, 传入total=$total")
                val columns = PhotoCursorColumns(cursor)
                var count = 0
                while (cursor.moveToNext()) {
                    val photo = extractPhotoFromCursor(cursor, columns, collection, externalPhotos)
                    photos.add(photo)
                    count++
                    onProgress?.invoke(count, actualTotal)
                }
                android.util.Log.i("PhotoScanner", "scanAllPhotosWithProgress: 扫描完成，共${count}张照片")
            }
        } catch (e: Exception) {
            android.util.Log.e("PhotoScanner", "scanAllPhotosWithProgress查询失败: ${e.message}", e)
        }
        photos
    }

    /**
     * 按ID列表扫描（增量扫描用，带外部缓存匹配）
     */
    suspend fun scanPhotosByIds(
        ids: List<Long>,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Photo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<Photo>()
        if (ids.isEmpty()) return@withContext photos

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE
        )

        // SQLite 单条语句的绑定变量上限是 999（SQLite 编译期默认 SQLITE_MAX_VARIABLE_NUMBER），
        // 旧实现把全部 id 拼进一条 IN (?,?,...)，id 数超过上限直接抛异常导致扫描失败。
        // 这里按 MAX_SQL_BATCH 分批查询，既绕开上限也避免单条 SQL 过长。
        val total = ids.size
        ids.chunked(MAX_SQL_BATCH).forEach { batch ->
            val idPlaceholders = batch.joinToString(",") { "?" }
            val selection = "${MediaStore.Images.Media._ID} IN ($idPlaceholders)"
            val selectionArgs = batch.map { it.toString() }.toTypedArray()

            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val columns = PhotoCursorColumns(cursor)
                while (cursor.moveToNext()) {
                    photos.add(extractPhotoFromCursor(cursor, columns, collection, externalPhotos))
                    onProgress?.invoke(photos.size, total)
                }
            }
        }
        photos
    }

    /**
     * 从Cursor中提取单张照片信息（包含位置获取逻辑）
     */
    private fun extractPhotoFromCursor(
        cursor: android.database.Cursor,
        columns: PhotoCursorColumns,
        collection: Uri,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>
    ): Photo {
        val id = cursor.getLong(columns.id)
        val displayName = cursor.getString(columns.name) ?: ""
        val dateTaken = cursor.getLong(columns.date)
        val uri = ContentUris.withAppendedId(collection, id)
        val (latitude, longitude) = getPhotoLocation(cursor, columns, uri, externalPhotos, displayName, dateTaken)
        return Photo(id, uri, displayName, dateTaken, latitude, longitude)
    }

    /**
     * 获取照片位置（优先级：外部缓存 > MediaStore > EXIF）
     */
    private fun getPhotoLocation(
        cursor: android.database.Cursor,
        columns: PhotoCursorColumns,
        uri: Uri,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        displayName: String,
        dateTaken: Long
    ): Pair<Double?, Double?> {
        val cacheKey = "${displayName}_$dateTaken"
        // 1. 先检查外部缓存，匹配到的直接用，跳过EXIF读取
        val cached = externalPhotos[cacheKey]
        if (cached != null) {
            return Pair(cached.latitude, cached.longitude)
        }
        // 2. 从MediaStore获取
        val mediaStoreLocation = locationExtractor.getLocationFromCursor(cursor, columns.lat, columns.lon)
        if (mediaStoreLocation != null) {
            return mediaStoreLocation
        }
        // 3. MediaStore没有，读EXIF
        return locationExtractor.getLocationFromExif(uri) ?: Pair(null, null)
    }

    /**
     * Cursor列索引缓存（避免重复getColumnIndex）
     */
    private data class PhotoCursorColumns(
        val id: Int,
        val name: Int,
        val date: Int,
        val lat: Int,
        val lon: Int
    ) {
        constructor(cursor: android.database.Cursor) : this(
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID),
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME),
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN),
            cursor.getColumnIndex(MediaStore.Images.Media.LATITUDE),
            cursor.getColumnIndex(MediaStore.Images.Media.LONGITUDE)
        )
    }
}
