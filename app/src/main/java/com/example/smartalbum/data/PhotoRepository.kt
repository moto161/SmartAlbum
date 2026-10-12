package com.example.smartalbum.data

import com.example.smartalbum.data.PhotoScanManager.PREFS_NAME

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.smartalbum.cache.PhotoCacheManager
import com.example.smartalbum.db.PhotoDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoRepository(private val context: Context) {
    /**
     * 把WGS-84坐标转换为GCJ-02坐标（高德地图使用）
     * @param wgsLat WGS-84纬度（非空，非0）
     * @param wgsLon WGS-84经度（非空，非0）
     * @return Pair(lat, lon)
     */

    companion object {
            private const val BATCH_SIZE = 200

        /**
         * 是否显示全部照片（包括无位置的照片）
         */
        fun shouldShowAllPhotos(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("show_all_photos", false)
        }
    }

    private val dbHelper = PhotoDatabaseHelper.getInstance(context)
    private val externalCacheManager = PhotoCacheManager(context)
    private val locationExtractor = PhotoLocationExtractor(context)
    private val photoScanner = PhotoScanner(context, locationExtractor)
    private val locationGrouper = LocationGrouper()

    /**
     * 扫描照片（带SQLite数据库缓存+外部存储缓存+增量更新）
     * 卸载重装后能从外部存储自动恢复缓存
     */
    suspend fun scanPhotosWithCache(
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Photo> = withContext(Dispatchers.IO) {
        android.util.Log.i("PhotoRepository", "scanPhotosWithCache开始")
        // 1. 加载外部缓存并同步位置名称
        val (externalPhotos, total) = loadExternalCacheAndSync()
        android.util.Log.i("PhotoRepository", "外部缓存: ${externalPhotos.size}张, MediaStore总数: $total")

        // 2. 加载数据库缓存
        val cachedPhotos = dbHelper.loadAllPhotos()
        val lastScanTime = dbHelper.getLastScanTime()
        android.util.Log.i("PhotoRepository", "数据库缓存: ${cachedPhotos.size}张, 上次扫描时间: $lastScanTime")

        // 3. 数据库缓存为空但外部缓存有数据，用外部缓存初始化
        if (cachedPhotos.isEmpty() && externalPhotos.isNotEmpty()) {
            android.util.Log.i("PhotoRepository", "使用外部缓存初始化")
            val result = tryInitFromExternalCache(externalPhotos, total, onProgress)
            if (result != null) {
                android.util.Log.i("PhotoRepository", "外部缓存初始化成功，返回${result.size}张照片")
                return@withContext result
            }
        }

        // 4. 数据库缓存为空，或 APP 刚升级 → 全量扫描
        //    2026-10-10：位置提取逻辑修过之后（如 5.13.90 修 Android 10+ EXIF 读取），
        //    库里已有的旧结果不会自己变好，增量扫描只处理新增/变更的照片。
        //    所以版本号变了就强制全量重扫一次，用户升级后直接受益，不用手动清缓存。
        val lastFullScanVersion = dbHelper.getLastFullScanVersion()
        val currentVersion = com.example.smartalbum.BuildConfig.VERSION_CODE
        if (cachedPhotos.isEmpty() || lastScanTime == 0L || lastFullScanVersion < currentVersion) {
            android.util.Log.i(
                "PhotoRepository",
                "开始全量扫描（缓存${cachedPhotos.size}张, 上次全量版本$lastFullScanVersion, 当前$currentVersion）"
            )
            val result = fullScanAndSave(externalPhotos, total, onProgress)
            dbHelper.setLastFullScanVersion(currentVersion)
            android.util.Log.i("PhotoRepository", "全量扫描完成，返回${result.size}张照片")
            return@withContext result
        }

        // 5. 数据库缓存不为空，增量扫描
        android.util.Log.i("PhotoRepository", "开始增量扫描")
        val result = incrementalScanAndSave(cachedPhotos, externalPhotos, total, onProgress)
        android.util.Log.i("PhotoRepository", "增量扫描完成，返回${result.size}张照片")
        result
    }

    /**
     * 加载外部缓存
     * @return Pair(外部照片缓存, 照片总数)
     */
    private fun loadExternalCacheAndSync(): Pair<Map<String, PhotoCacheManager.PhotoCacheEntry>, Int> {
        val externalCache = externalCacheManager.loadFromExternalStorage()
        val externalPhotos = externalCache?.photos ?: emptyMap()
        val total = getPhotoCount()
        return Pair(externalPhotos, total)
    }

    /**
     * 尝试从外部缓存初始化（数据库缓存为空时）
     * @return 成功返回照片列表，失败返回null
     */
    private suspend fun tryInitFromExternalCache(
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        total: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Photo>? {
        val matchedPhotos = matchExternalCacheToMediaStore(externalPhotos) { scanned ->
            onProgress?.invoke(scanned, total)
        }
        if (matchedPhotos.isNotEmpty()) {
            savePhotoCache(matchedPhotos)
            onProgress?.invoke(total, total)
            return if (shouldShowAllPhotos(context)) matchedPhotos else matchedPhotos.filter { it.hasLocation }
        }
        return null
    }

    /**
     * 全量扫描并保存缓存
     */
    private suspend fun fullScanAndSave(
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        total: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Photo> {
        val allPhotos = scanAllPhotosWithProgress(total, externalPhotos) { scanned, actualTotal ->
            onProgress?.invoke(scanned, actualTotal)
        }
        savePhotoCache(allPhotos)
        onProgress?.invoke(total, total)
        return if (shouldShowAllPhotos(context)) allPhotos else allPhotos.filter { it.hasLocation }
    }

    /**
     * 增量扫描并保存缓存
     */
    private suspend fun incrementalScanAndSave(
        cachedPhotos: List<Photo>,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        total: Int,
        onProgress: ((Int, Int) -> Unit)?
    ): List<Photo> {
        val cachedIds = cachedPhotos.map { it.id }.toSet()
        val allPhotoIds = getAllPhotoIds()
        val newIds = allPhotoIds.filter { it !in cachedIds }
        val deletedIds = cachedIds - allPhotoIds.toSet()

        var resultPhotos = cachedPhotos.toMutableList()
        var scanned = cachedPhotos.size

        if (newIds.isNotEmpty()) {
            val newPhotos = scanPhotosByIds(newIds, externalPhotos) { current, _ ->
                onProgress?.invoke(scanned + current, total)
            }
            resultPhotos.addAll(newPhotos)
            scanned += newIds.size
        }

        if (deletedIds.isNotEmpty()) {
            resultPhotos.removeAll { it.id in deletedIds }
        }

        savePhotoCache(resultPhotos)
        onProgress?.invoke(total, total)
        return if (shouldShowAllPhotos(context)) resultPhotos else resultPhotos.filter { it.hasLocation }
    }

    /**
     * 保存照片缓存（数据库+外部存储）
     */
    private fun savePhotoCache(photos: List<Photo>) {
        dbHelper.savePhotos(photos)
        dbHelper.setLastScanTime(System.currentTimeMillis())
        externalCacheManager.saveToExternalStorage(photos)
    }

    /**
     * 把外部缓存匹配到当前MediaStore的照片
     */
    private fun matchExternalCacheToMediaStore(
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        onProgress: ((Int) -> Unit)? = null
    ): List<Photo> {
        val photos = mutableListOf<Photo>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            var count = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(nameColumn) ?: ""
                val dateTaken = cursor.getLong(dateColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val key = "${displayName}_$dateTaken"

                val cached = externalPhotos[key]
                if (cached != null) {
                    // 匹配到外部缓存，直接用缓存的经纬度
                    photos.add(
                        Photo(
                            id = id,
                            uri = uri,
                            displayName = displayName,
                            dateTaken = dateTaken,
                            latitude = cached.latitude,
                            longitude = cached.longitude
                        )
                    )
                } else {
                    // 没匹配到，添加无位置的照片
                    photos.add(
                        Photo(
                            id = id,
                            uri = uri,
                            displayName = displayName,
                            dateTaken = dateTaken,
                            latitude = null,
                            longitude = null
                        )
                    )
                }

                count++
                onProgress?.invoke(count)
            }
        }
        return photos
    }

    private fun getPhotoCount(): Int {
        return photoScanner.getPhotoCount()
    }

    private fun getAllPhotoIds(): List<Long> {
        return photoScanner.getAllPhotoIds()
    }

    /**
     * 当前 MediaStore 中全部照片 ID（纯 ID 查询，不读 EXIF/位置）。
     * 供下拉刷新做 O(IDs) 快速差异检测：照片无变动时毫秒级返回，
     * 不重读全表 DB、不写外部存储、不重扫已有照片。
     */
    fun getCurrentMediaStoreIds(): Set<Long> = photoScanner.getAllPhotoIds().toSet()

    /**
     * 全量扫描（委托给PhotoScanner）
     */
    private suspend fun scanAllPhotosWithProgress(
        total: Int,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Photo> {
        return photoScanner.scanAllPhotosWithProgress(total, externalPhotos, onProgress)
    }


    /**
     * 按ID列表扫描（委托给PhotoScanner）
     */
    /**
     * 按ID列表扫描（委托给PhotoScanner）。externalPhotos 默认空（下拉刷新增量分支只处理
     * 新增照片，缓存里本就没有对应项，直接读 EXIF 即可）。
     * 2026-10-11 改为 public：供 PhotoScanManager.forceRefresh 轻量增量刷新复用。
     */
    suspend fun scanPhotosByIds(
        ids: List<Long>,
        externalPhotos: Map<String, PhotoCacheManager.PhotoCacheEntry> = emptyMap(),
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Photo> {
        return photoScanner.scanPhotosByIds(ids, externalPhotos, onProgress)
    }

    fun groupByLocation(photos: List<Photo>): List<LocationGroup> {
        return locationGrouper.groupByLocation(photos)
    }

    fun reverseGeocode(latitude: Double, longitude: Double): String {
        dbHelper.getLocationName(latitude, longitude)?.let { return it }

        val name = try {
            val geocoder = android.location.Geocoder(context, java.util.Locale.CHINA)
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val parts = mutableListOf<String>()
                address.adminArea?.let { parts.add(it) }
                address.locality?.let { parts.add(it) }
                address.subLocality?.let { parts.add(it) }
                address.thoroughfare?.let { parts.add(it) }
                if (parts.isNotEmpty()) parts.joinToString("") else "未知位置"
            } else {
                "未知位置"
            }
        } catch (e: Exception) {
            android.util.Log.e("PhotoRepository", "获取位置名称失败", e)
            "未知位置"
        }

        dbHelper.saveLocationName(latitude, longitude, name)
        return name
    }
}
