package com.example.smartalbum.data

import android.content.Context
import com.example.smartalbum.db.PhotoDatabaseHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 全局照片扫描管理器（单例）
 * 保证整个APP只有一个扫描任务在运行，启动页和首页共享同一份数据
 * 优化：APP启动时立即从本地缓存加载数据，后台再增量扫描新照片
 */
object PhotoScanManager {
    const val PREFS_NAME = "app_settings"
    
    @Volatile
    private var photosWithLocation: List<Photo>? = null
    @Volatile
    private var photosWithoutLocation: List<Photo>? = null
    @Volatile
    private var isScanning = false
    // 2026-10-10：原为普通 Boolean，多线程下「检查再置位」有窗口，
    // 会同时起两个后台增量扫描。改 CAS 保证同一时刻只有一个。
    private val backgroundScanning = java.util.concurrent.atomic.AtomicBoolean(false)
    // 当前扫描进度（用于启动页和主界面共享进度）
    @Volatile
    private var currentScanned = 0
    @Volatile
    private var currentTotal = 0
    private var scanError: Exception? = null
    private var cacheLoaded = false
    // 2026-10-11：initFromCache 在后台线程把数据库缓存读进内存，
    // getPhotos 必须等它完成再判断「缓存是否为空」，否则会误判为未初始化
    // 而多跑一次 MediaStore 扫描（用户感知的"杀后台再进又全盘扫描"）。
    private var cacheLoadedDeferred: CompletableDeferred<Unit>? = null

    // 2026-10-10：替代 while+Thread.sleep(200) 忙等
    // 全量扫描开始时创建，结束时 complete；其他协程 getPhotos 时 await() 即可挂起不占线程
    // 改为 Mutex 保护 + 复用同一个 Deferred：
    // 旧实现用 `@Volatile isScanning` 做 check-then-act，两个协程（启动页 + 地图页）
    // 同时进来会 ①都跑到 isScanning=true 各扫一遍全量 ②后到的读到 isScanning=true
    // 但 scanDeferred 还是 null，`?.await()` 直接跳过 → 拿到空列表 → 显示「暂无照片」
    private val scanMutex = Mutex()
    private var scanDeferred: CompletableDeferred<List<Photo>>? = null
    private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 数据变化观察者列表（地图页、位置页等注册）
    // 使用CopyOnWriteArrayList保证线程安全，避免后台线程通知时主线程注册/注销导致ConcurrentModificationException
    private val dataChangedListeners = java.util.concurrent.CopyOnWriteArrayList<(List<Photo>) -> Unit>()

    /**
     * 注册数据变化监听器
     */
    fun addOnDataChangedListener(listener: (List<Photo>) -> Unit) {
        dataChangedListeners.add(listener)
    }

    /**
     * 注销数据变化监听器
     */
    fun removeOnDataChangedListener(listener: (List<Photo>) -> Unit) {
        dataChangedListeners.remove(listener)
    }

    /**
     * 通知所有观察者数据已变化
     */
    private fun notifyDataChanged(photoList: List<Photo>) {
        dataChangedListeners.forEach { it(photoList) }
    }

    /**
     * 把全量照片按 hasLocation 拆成两份内存缓存。
     * 地图页打点/位置列表分组只用有位置那份；无位置那份由 getNoLocationPhotos() 按需过滤。
     */
    private fun assignLists(all: List<Photo>) {
        photosWithLocation = all.filter { it.hasLocation }
        photosWithoutLocation = all.filter { !it.hasLocation }
    }

    /**
     * 根据设置过滤照片（公共函数，消除重复代码）
     */
    private fun filterPhotosBySettings(photoList: List<Photo>, context: Context): List<Photo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showAll = prefs.getBoolean("show_all_photos", false)
        val months = prefs.getInt("no_location_time_months", 6)
        var filtered = if (showAll) photoList else photoList.filter { it.hasLocation }
        if (showAll && months > 0) {
            val cutoff = System.currentTimeMillis() - months.toLong() * 30 * 24 * 60 * 60 * 1000
            filtered = filtered.filter { it.hasLocation || it.dateTaken >= cutoff }
        }
        android.util.Log.i("PhotoScanManager", "filterPhotosBySettings: showAll=" + showAll + ", months=" + months + ", 输入=" + photoList.size + ", 输出=" + filtered.size)
        return filtered
    }

    /**
     * 从本地缓存立即加载数据（APP启动时调用，不阻塞UI）
     * 调用后photos会立即有值，UI可以直接显示
     */
    fun initFromCache(context: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        val d = CompletableDeferred<Unit>()
        cacheLoadedDeferred = d
        android.util.Log.i("PhotoScanManager", "initFromCache被调用，开始从数据库加载缓存")
        // 在后台线程加载，避免阻塞UI
        Thread {
            try {
                val appContext = context.applicationContext
                // 优先从SQLite数据库加载（比SharedPreferences快很多）
                val dbHelper = PhotoDatabaseHelper.getInstance(appContext)
                val dbPhotos = dbHelper.loadAllPhotos()
                android.util.Log.i("PhotoScanManager", "数据库加载完成，照片数量: ${dbPhotos.size}")
                if (dbPhotos.isNotEmpty()) {
                    // 按有无位置分流到两份内存缓存
                    assignLists(dbPhotos)
                    android.util.Log.i("PhotoScanManager", "从数据库加载${dbPhotos.size}张（有位置${photosWithLocation?.size}，无位置${photosWithoutLocation?.size}）")
                    return@Thread
                }
                android.util.Log.i("PhotoScanManager", "数据库为空，等待全量扫描")
                // 数据库没有数据，等待扫描
            } catch (e: Exception) {
                android.util.Log.e("PhotoScanManager", "加载缓存失败: ${e.message}", e)
            } finally {
                d.complete(Unit)
            }
        }.start()
    }

    /**
     * 获取照片数据（如果已扫描完成直接返回，否则开始扫描）
     * 优化：如果有本地缓存，先返回缓存数据，同时后台增量扫描新照片
     * @param context 上下文
     * @param onProgress 进度回调 (已扫描, 总数)
     * @return 照片列表
     */
    suspend fun getPhotos(
        context: Context,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Photo> = withContext(Dispatchers.IO) {
        // 2026-10-11：先等 initFromCache 把数据库缓存读进内存。
        // 不 etc 待会再判空 → 否则缓存还没就绪就误判未初始化，跑去走扫描。
        if (!cacheLoaded) initFromCache(context)
        cacheLoadedDeferred?.await()
        android.util.Log.i("PhotoScanManager", "getPhotos被调用，当前缓存是否为空: ${photosWithLocation == null && photosWithoutLocation == null}, isScanning: $isScanning")
        // 如果已经有数据（内存缓存或刚从本地缓存加载），直接返回
        if (photosWithLocation != null || photosWithoutLocation != null) {
            val all = (photosWithLocation ?: emptyList()) + (photosWithoutLocation ?: emptyList())
            // 根据设置过滤缓存数据（使用公共函数，消除重复代码）
            val filtered = filterPhotosBySettings(all, context)
            android.util.Log.i("PhotoScanManager", "使用缓存数据，全量: ${all.size}，最终${filtered.size}张")
            // 后台增量扫描新照片（不阻塞当前返回）
            if (!backgroundScanning.get() && !isScanning) {
                startBackgroundScan(context)
            }
            // 通知观察者数据已变化
            notifyDataChanged(filtered)
            return@withContext filtered
        }

        // 没有缓存：用 Mutex 保证全量扫描全局只跑一次，其余协程复用同一个 Deferred 等待
        val deferred: Deferred<List<Photo>>? = scanMutex.withLock {
            if (photosWithLocation != null || photosWithoutLocation != null) {
                // 排队等锁的这段时间内被别人填上了，直接走下面的读缓存分支
                null
            } else {
                scanDeferred ?: CompletableDeferred<List<Photo>>().also { d ->
                    scanDeferred = d
                    isScanning = true
                    scanError = null
                    val appContext = context.applicationContext
                    scanScope.launch {
                        try {
                            val repository = PhotoRepository(appContext)
                            val result = repository.scanPhotosWithCache { scanned, total ->
                                currentScanned = scanned
                                currentTotal = total
                                onProgress?.invoke(scanned, total)
                            }
                            assignLists(result)
                            // 扫描完成后保存到数据库（后台线程，不阻塞返回）
                            if (result.isNotEmpty()) {
                                try {
                                    PhotoDatabaseHelper.getInstance(appContext).savePhotos(result)
                                    android.util.Log.i("PhotoScanManager", "已保存${result.size}张照片到数据库")
                                } catch (e: Exception) {
                                    android.util.Log.e("PhotoScanManager", "保存到数据库失败: ${e.message}")
                                }
                            }
                            d.complete(result)
                        } catch (e: Exception) {
                            scanError = e
                            photosWithLocation = emptyList()
                            photosWithoutLocation = emptyList()
                            d.completeExceptionally(e)
                        } finally {
                            isScanning = false
                            scanMutex.withLock { scanDeferred = null }
                        }
                    }
                }
            }
        }

        val scanned = if (deferred != null) {
            try {
                deferred.await()
            } catch (e: Exception) {
                android.util.Log.e("PhotoScanManager", "全量扫描失败: ${e.message}")
                emptyList()
            }
        } else {
            (photosWithLocation ?: emptyList()) + (photosWithoutLocation ?: emptyList())
        }
        return@withContext filterPhotosBySettings(scanned, context)
    }


    /**
     * 获取无位置照片（按时间倒序排列，自动应用时间范围过滤）
     * 与有位置照片分开管理，调用更清晰
     * @param context 上下文
     * @return 无位置照片列表（已按时间范围过滤）
     */
    suspend fun getNoLocationPhotos(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        // 直接使用无位置那份内存缓存，不重新调用getPhotos()避免重复扫描
        val all = (photosWithLocation ?: emptyList()) + (photosWithoutLocation ?: emptyList())
        if (all.isEmpty() && photosWithLocation == null) {
            // 还没初始化，先拉一次
            getPhotos(context)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showAll = prefs.getBoolean("show_all_photos", false)
        if (!showAll) {
            return@withContext emptyList()
        }
        val months = prefs.getInt("no_location_time_months", 6)
        var noLocation = photosWithoutLocation ?: emptyList()
        if (months > 0) {
            val cutoff = System.currentTimeMillis() - months.toLong() * 30 * 24 * 60 * 60 * 1000
            noLocation = noLocation.filter { it.dateTaken >= cutoff }
        }
        // 按时间倒序排列（必须赋值，否则返回未排序列表）
        noLocation = noLocation.sortedByDescending { it.dateTaken }
        android.util.Log.i("PhotoScanManager", "getNoLocationPhotos: months=" + months + ", 无位置缓存=" + (photosWithoutLocation?.size ?: 0) + ", 过滤后=" + noLocation.size)
        return@withContext noLocation
    }

    /**
     * 2026-10-10 新增：只取有位置的照片（地图打点/位置列表分组用）。
     * 不随"显示全部照片"开关变化——地图上永远只打有坐标的点。
     */
    suspend fun getPhotosWithLocation(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        if (photosWithLocation == null) {
            // 还没初始化，走一次 getPhotos 触发加载，然后直接返回有位置那份
            getPhotos(context)
        }
        photosWithLocation ?: emptyList()
    }

    /**
     * 后台增量扫描（有缓存时使用，不阻塞UI）
     * 只扫描新增的照片，扫描完成后更新数据
     */
    private fun startBackgroundScan(context: Context) {
        // CAS 保证同一时刻只有一个后台增量扫描（旧实现是 check-then-act，有窗口）
        if (!backgroundScanning.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.i("PhotoScanManager", "开始后台增量扫描")
                val repository = PhotoRepository(context)
                val result = repository.scanPhotosWithCache { _, _ -> }
                // 2026-10-05 修复：原来只判断「结果比缓存多」才更新，
                // 导致用户在相册里【删除照片】后缓存永远不减少（只有新增才生效）。
                // 现在改成：只要数量有任何变化就更新（增、减、变更都同步）。
                val oldCount = (photosWithLocation?.size ?: 0) + (photosWithoutLocation?.size ?: 0)
                if (result.size != oldCount) {
                    assignLists(result)
                    // 保存到数据库
                    Thread {
                        try {
                            PhotoDatabaseHelper.getInstance(context.applicationContext).savePhotos(result)
                        } catch (e: Exception) {
                            android.util.Log.e("PhotoScanManager", "保存照片到数据库失败", e)
                        }
                    }.start()
                    android.util.Log.i("PhotoScanManager", "后台扫描完成，照片数 $oldCount -> ${result.size}张")
                    // 通知观察者数据已变化（按设置过滤）
                    val filtered = filterPhotosBySettings(result, context)
                    notifyDataChanged(filtered)
                } else {
                    android.util.Log.i("PhotoScanManager", "后台扫描完成，照片数无变化")
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoScanManager", "后台扫描失败: ${e.message}")
            } finally {
                backgroundScanning.set(false)
            }
        }
    }

    /**
     * 强制刷新（下拉刷新用）：立即做一次增量扫描并返回最新照片列表。
     * 2026-10-05 新增。
     *
     * 走的是 PhotoRepository.scanPhotosWithCache()，它内部就是增量扫描：
     *   - 以 MediaStore（系统相册）里实际存在的照片为准
     *   - 库里有缓存里没有的 → 新增
     *   - 缓存里有库里没有的 → 删除（用户在相册删了照片，缓存跟着减）
     *   - 信息变了的 → 更新
     * **不清空原有缓存、不做全量重扫**，只同步差异。
     */
    suspend fun forceRefresh(context: Context): List<Photo> = withContext(Dispatchers.IO) {
        val repository = PhotoRepository(context)
        // 2026-10-11：轻量增量刷新。
        // 已有内存缓存时，只做「MediaStore 当前 ID 集合」 vs 「内存缓存 ID 集合」差集，
        // 不重读全表 DB、不写外部存储、不重扫已有照片 —— 照片没变动时 O(IDs) 毫秒级返回。
        // 仅当确有新增/删除时才 scanPhotosByIds 提取变更部分。
        val cached = getCachedPhotos()
        if (cached != null) {
            val cachedIds = cached.map { it.id }.toSet()
            val currentIds = repository.getCurrentMediaStoreIds()
            val deletedIds = cachedIds - currentIds
            val newIds = currentIds - cachedIds
            if (newIds.isEmpty() && deletedIds.isEmpty()) {
                android.util.Log.i("PhotoScanManager", "下拉刷新：照片无变化，直接复用内存缓存（秒回）")
                return@withContext cached
            }
            val newPhotos = if (newIds.isNotEmpty()) {
                repository.scanPhotosByIds(newIds.toList())
            } else emptyList()
            val merged = (cached.filter { it.id !in deletedIds } + newPhotos)
            assignLists(merged)
            val filtered = filterPhotosBySettings(merged, context)
            notifyDataChanged(filtered)
            android.util.Log.i("PhotoScanManager", "下拉刷新完成（增量）：新增${newPhotos.size}张，删除${deletedIds.size}张")
            return@withContext merged
        }
        // 内存无缓存（首次/极端情况）：走原全路径
        val result = repository.scanPhotosWithCache { _, _ -> }
        assignLists(result)
        android.util.Log.i("PhotoScanManager", "下拉刷新完成，共${result.size}张照片")
        result
    }

    /**
     * 清除缓存（用户手动清除缓存时调用）
     */
    fun clearCache() {
        photosWithLocation = null
        photosWithoutLocation = null
        isScanning = false
        backgroundScanning.set(false)
        scanError = null
        cacheLoaded = false
        scanDeferred = null
        cacheLoadedDeferred = null
    }

    /**
     * 是否已扫描完成
     */
    fun isReady(): Boolean = photosWithLocation != null || photosWithoutLocation != null

    /**
     * 是否正在扫描
     */
    fun isCurrentlyScanning(): Boolean = isScanning || backgroundScanning.get()

    /**
     * 获取已扫描的照片数量（全量 = 有位置 + 无位置）
     */
    fun getPhotoCount(): Int = (photosWithLocation?.size ?: 0) + (photosWithoutLocation?.size ?: 0)

    /**
     * 2026-10-10 新增：同步拿内存里已解密的照片列表（不过滤，全量）
     *
     * 用途：像 GalleryActivity 这种"已经进 APP 了，内存里就有全量照片"的场景，
     * 不要再走 dbHelper.loadAllPhotos() 从 DB 解密读一遍（5768 张要 0.5~1秒），
     * 直接拿内存里的 List<Photo> 用即可。
     *
     * @return 内存全量照片列表；如果还没初始化返回 null，调用方需要自己 fallback
     */
    fun getCachedPhotos(): List<Photo>? {
        val wl = photosWithLocation ?: return null
        val wol = photosWithoutLocation ?: emptyList()
        return wl + wol
    }
}
