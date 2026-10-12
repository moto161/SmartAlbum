package com.example.smartalbum.ui.list

import com.example.smartalbum.data.PhotoScanManager.PREFS_NAME
import com.example.smartalbum.util.LogUtil

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.smartalbum.util.ActivityAnimationUtil

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartalbum.R
import com.example.smartalbum.cache.PhotoCacheManager
import com.example.smartalbum.db.PhotoDatabaseHelper
import com.example.smartalbum.data.LocationGroup
import com.example.smartalbum.data.PhotoRepository
import com.example.smartalbum.databinding.FragmentLocationListBinding
import com.example.smartalbum.ui.common.StateLayoutHelper
import com.example.smartalbum.ui.gallery.GalleryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

@AndroidEntryPoint
class LocationListFragment : Fragment() {

    private var _binding: FragmentLocationListBinding? = null
    private val binding get() = _binding!!
    @Inject
    lateinit var repository: PhotoRepository
    private lateinit var cacheManager: PhotoCacheManager
    @Inject lateinit var dbHelper: PhotoDatabaseHelper
    private lateinit var adapter: LocationAdapter
    private var allGroups = listOf<LocationGroup>()
    // 2026-10-10：原来是 take(100) 之后就没有任何加载更多，分组数 >100 的用户
    // 永远看不到后面的位置（注释写"避免OOM"，实际是静默截断）。改成 50/页 + 滚到底自动追加。
    private val pageSize = 50
    private var displayedGroups = listOf<LocationGroup>()  // 当前显示的分页数据
    private var isLoaded = false
    private var lastShowAllPhotos = false  // 记录上次的开关状态
    private var lastTimeRange = -1  // 记录上次的时间范围，-1表示未初始化
    private val onDataChangedListener: (List<com.example.smartalbum.data.Photo>) -> Unit = { _ ->
        // 数据变化时更新位置列表。有位置的分组永远显示，无位置分组由 buildLocationGroups 根据开关拼
        if (isAdded && _binding != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val photos = com.example.smartalbum.data.PhotoScanManager.getCachedPhotos()
                        ?.filter { it.hasLocation } ?: emptyList()
                    val groups = buildLocationGroups(photos)
                    updateUIWithGroups(photos, groups)
                } catch (e: Exception) {
                    LogUtil.e("LocationListFragment", "数据变化更新异常", e)
                }
            }
        }
    }
    private var sortMode = SortMode.BY_PHOTO_COUNT_DESC // 默认按照片数量降序
    private var stateHelper: StateLayoutHelper? = null

    enum class SortMode {
        BY_PHOTO_COUNT_DESC, // 按照片数量降序
        BY_PHOTO_COUNT_ASC,  // 按照片数量升序
        BY_LOCATION_NAME      // 按地点名称排序
    }

    // 导出/导入缓存的ActivityResultLauncher
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { exportCacheToUri(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importCacheFromUri(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 注册主题变化监听
        com.example.smartalbum.util.ThemeManager.addOnThemeChangedListener(themeChangedListener)
        cacheManager = PhotoCacheManager(requireContext())

        adapter = LocationAdapter { group ->
            if (isAdded) {
                if (group.id == "no_location") {
                    // 无位置照片分组，跳转到NoLocationPhotosActivity（纯查看模式）
                    val intent = Intent(requireContext(), com.example.smartalbum.ui.gallery.NoLocationPhotosActivity::class.java)
                    ActivityAnimationUtil.startActivityWithAnimation(requireContext(), intent)
                } else {
                    val intent = GalleryActivity.newIntent(requireContext(), group)
                    ActivityAnimationUtil.startActivityWithAnimation(requireContext(), intent)
                }
            }
        }

        binding.locationRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.locationRecyclerView.adapter = adapter
        // 2026-10-10：滚到接近底部时自动追加下一页（原来只显示前 100 条且无法加载更多）
        binding.locationRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return   // 只在上滑时触发
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= displayedGroups.size - 3) {
                    appendNextPage()
                }
            }
        })

        // 初始化统一状态管理器
        stateHelper = StateLayoutHelper(
            loadingLayout = binding.loadingContainer,
            emptyLayout = binding.emptyLayout,
            contentLayout = binding.locationRecyclerView,
            loadingText = binding.loadingText
        )

        setupSearch()
        setupToolbarMenu()
        setupSortButton()
        setupSwipeRefresh()

        if (!isLoaded) {
            loadLocations()
        }
    }

    /**
     * 下拉刷新（2026-10-05 新增）
     *
     * 需求：只有【列表区域】下拉才触发，顶部搜索框与统计卡保持不动。
     * 实现：SwipeRefreshLayout 只包住了列表区的 FrameLayout（见 layout），
     *      所以统计区天然不响应下拉。
     *
     * 刷新走 PhotoScanManager.forceRefresh()，它内部是**双向增量**：
     *   以系统相册(MediaStore)里实际存在的照片为准
     *   - 库里有缓存里没有的 → 新增
     *   - 缓存里有库里没有的 → 减少（用户在相册删了照片，缓存跟着减）
     *   - 信息变了的 → 更新
     * 不清空原有缓存、不做全量重扫。
     */
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            refreshFromPull()
        }
        // 主题色（与设置页那套保持一致）
        binding.swipeRefresh.setColorSchemeColors(
            com.example.smartalbum.util.ThemeManager.getAccentColor()
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            com.example.smartalbum.util.ThemeManager.getCardColor()
        )
    }

    private var isRefreshingByPull = false

    private fun refreshFromPull() {
        // 防抖：正在刷新时忽略重复触发，避免用户快速连拉导致多轮扫描
        if (isRefreshingByPull) return
        isRefreshingByPull = true

        viewLifecycleOwner.lifecycleScope.launch {
            val before = com.example.smartalbum.data.PhotoScanManager.getPhotoCount()
            try {
                // 2026-10-11：刷新加 30s 硬超时兜底。
                // 万一底层扫描卡住（如 MediaStore 异常），转圈也一定会在超时后停掉，
                // 不再出现"一直转圈停不下来"的情况。
                val photos = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(30_000) {
                        com.example.smartalbum.data.PhotoScanManager.forceRefresh(requireContext())
                    } ?: run {
                        LogUtil.w("LocationListFragment", "下拉刷新超时，降级使用内存缓存")
                        com.example.smartalbum.data.PhotoScanManager.getCachedPhotos() ?: emptyList()
                    }
                }
                if (_binding == null) return@launch

                val groups = buildLocationGroups(photos)
                updateUIWithGroups(photos, groups)
                applySort()

                val after = photos.size
                val diff = after - before
                // 只有照片有增/减/变更时才重新做逆地理编码（最多 50 次网络请求）。
                // 照片没变动时跳过，下拉刷新秒回，不再无意义地转圈等网络。
                if (diff != 0) reverseGeocodeLocations()

                val tip = when {
                    diff > 0 -> "已刷新：新增 $diff 张照片"
                    diff < 0 -> "已刷新：移除 ${-diff} 张照片"
                    else -> "已刷新：照片没有变化"
                }
                android.widget.Toast.makeText(requireContext(), tip, android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                LogUtil.e("LocationListFragment", "下拉刷新失败", e)
                if (_binding != null) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "刷新失败：${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isRefreshingByPull = false
                _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun setupSortButton() {
        binding.btnSort.setOnClickListener {
            showSortDialog()
        }
    }

    private fun showSortDialog() {
        val sortOptions = arrayOf(
            "按照片数量（多→少）",
            "按照片数量（少→多）",
            "按地点名称（A→Z）"
        )
        val checkedItem = when (sortMode) {
            SortMode.BY_PHOTO_COUNT_DESC -> 0
            SortMode.BY_PHOTO_COUNT_ASC -> 1
            SortMode.BY_LOCATION_NAME -> 2
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("排序方式")
            .setSingleChoiceItems(sortOptions, checkedItem) { dialog, which ->
                sortMode = when (which) {
                    0 -> SortMode.BY_PHOTO_COUNT_DESC
                    1 -> SortMode.BY_PHOTO_COUNT_ASC
                    2 -> SortMode.BY_LOCATION_NAME
                    else -> SortMode.BY_PHOTO_COUNT_DESC
                }
                applySort()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applySort() {
        if (_binding == null) return
        val noLocationGroup = allGroups.find { it.id == "no_location" }
        val locationGroups = allGroups.filter { it.id != "no_location" }
        val sorted = when (sortMode) {
            SortMode.BY_PHOTO_COUNT_DESC -> locationGroups.sortedByDescending { it.photoCount }
            SortMode.BY_PHOTO_COUNT_ASC -> locationGroups.sortedBy { it.photoCount }
            SortMode.BY_LOCATION_NAME -> locationGroups.sortedBy { it.locationName }
        }
        val result = if (noLocationGroup != null) listOf(noLocationGroup) + sorted else sorted
        adapter.submitList(result)
    }

    private fun setupToolbarMenu() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_timeline -> {
                    startActivity(com.example.smartalbum.ui.gallery.TimelineActivity.newIntent(requireContext()))
                    true
                }
                R.id.action_search_photos -> {
                    startActivity(com.example.smartalbum.ui.gallery.SearchPhotosActivity.newIntent(requireContext()))
                    true
                }
                R.id.action_export -> {
                    exportCache()
                    true
                }
                R.id.action_import -> {
                    importCache()
                    true
                }
                else -> false
            }
        }
    }

    private fun exportCache() {
        // 生成文件名：photo_cache_yyyyMMdd_HHmmss.json
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA)
        val fileName = "photo_cache_${sdf.format(java.util.Date())}.json"
        exportLauncher.launch(fileName)
    }

    private fun exportCacheToUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 从外部存储加载完整缓存
                    val externalCache = cacheManager.loadFromExternalStorage()
                    val rootJson = JSONObject()
                    rootJson.put("version", 1)
                    rootJson.put("export_time", System.currentTimeMillis())

                    // 照片列表
                    val photosArray = org.json.JSONArray()
                    externalCache?.photos?.values?.forEach { entry ->
                        val pJson = JSONObject()
                        pJson.put("display_name", entry.displayName)
                        pJson.put("date_taken", entry.dateTaken)
                        pJson.put("latitude", entry.latitude)
                        pJson.put("longitude", entry.longitude)
                        photosArray.put(pJson)
                    }
                    rootJson.put("photos", photosArray)

                    // 位置名称缓存（从数据库读取）
                    val locationNamesJson = JSONObject()
                    dbHelper.getAllLocationNames().forEach { (key, name) ->
                        locationNamesJson.put(key, name)
                    }
                    rootJson.put("location_names", locationNamesJson)

                    // 写入文件
                    requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
                Toast.makeText(requireContext(), "缓存导出成功", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                LogUtil.e("LocationListFragment", "异常", e)
                Toast.makeText(requireContext(), "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importCache() {
        importLauncher.launch(arrayOf("application/json", "*/*"))
    }

    private fun importCacheFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 2026-10-10 修复：之前解析完 photosMap 后直接丢弃（空块），等于没导入。
                // 现在：① 把 JSON 原文直接写回外部存储 photo_cache.json
                //      ② 同步把 location_names 写回数据库（保留用户自定义地名）
                //      ③ forceRefresh 让内存立刻生效
                val ok = withContext(Dispatchers.IO) {
                    val jsonString = requireContext().contentResolver
                        .openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: return@withContext false

                    // 先解析 location_names 写库
                    try {
                        val root = JSONObject(jsonString)
                        val locationNamesJson = root.optJSONObject("location_names")
                        if (locationNamesJson != null) {
                            val keys = locationNamesJson.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val name = locationNamesJson.getString(key)
                                val parts = key.split("_")
                                if (parts.size == 2) {
                                    try {
                                        dbHelper.saveLocationName(
                                            parts[0].toDouble(), parts[1].toDouble(), name
                                        )
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LocationList", "解析 location_names 失败", e)
                    }

                    // 再把 photos 原文写回外部存储
                    cacheManager.importExternalCacheJson(jsonString)
                }

                if (ok) {
                    withContext(Dispatchers.IO) {
                        com.example.smartalbum.data.PhotoScanManager
                            .forceRefresh(requireContext())
                    }
                    isLoaded = false
                    loadLocations()
                    Toast.makeText(requireContext(), "缓存导入成功，已重新扫描", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "导入失败：文件格式不是 photo_cache.json", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                LogUtil.e("LocationListFragment", "异常", e)
                Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSearch() {
        _binding?.searchEditText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { /* 不需要处理 */ }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterLocations(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) { /* 不需要处理 */ }
        })
    }

    private fun filterLocations(query: String) {
        if (_binding == null) return
        val filtered = if (query.isBlank()) {
            allGroups
        } else {
            allGroups.filter { it.locationName.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
        updateEmptyState(filtered.isEmpty())
        // 搜索后同步统计：按当前过滤结果重算「位置数 / 照片数」，
        // 否则会出现「列表已过滤成空、顶部数字却还是全量」的自相矛盾（2026-10-04 实测 bug）
        if (query.isBlank()) {
            updateStatsFromGroups(allGroups)
        } else {
            updateStatsFromGroups(filtered)
        }
    }

    /**
     * 直接按当前展示的分组重算统计数字。
     * 「N 个位置」= 分组数（不含无位置分组）；
     * 「N 张照片」= 这些分组里照片的总数（而不是全量照片数），
     * 否则会出现「1 个位置 / 650 张照片」这种自相矛盾的显示。
     */
    private fun updateStatsFromGroups(groups: List<LocationGroup>) {
        // 口径必须让两个数字「自洽」，否则用户会看到
        // 「1 个位置 / 668 张照片」这种读起来自相矛盾的显示（2026-10-04 实测 bug）。
        // 规则：
        //  - 「个位置」只统计有位置信息的分组（无位置分组不算位置）
        //  - 「张照片」只统计这些有位置分组里的照片
        // 这样 N 个位置 ↔ M 张照片始终对应得上；
        // 「无位置照片」的数量由它自己的分组项单独显示，不混进主统计。
        val locatedGroups = groups.filter { it.id != "no_location" }
        val locationCount = locatedGroups.size
        val photoCount = locatedGroups.sumOf { it.photoCount }
        _binding?.let { b ->
            b.tvTotalPhotos.text = photoCount.toString()
            b.tvTotalLocations.text = locationCount.toString()
        }
    }

    /**
     * 追加下一页分组。已全部加载时什么都不做。
     * 返回本次是否真的追加了数据（供调用方决定是否提示）。
     */
    private fun appendNextPage(): Boolean {
        if (displayedGroups.size >= allGroups.size) return false
        displayedGroups = allGroups.take(displayedGroups.size + pageSize)
        _binding?.let { adapter.submitList(displayedGroups) }
        android.util.Log.i("LocationList", "分页追加: ${displayedGroups.size}/${allGroups.size}")
        val remaining = allGroups.size - displayedGroups.size
        if (remaining > 0 && isAdded) {
            Toast.makeText(requireContext(), "已显示 ${displayedGroups.size} / ${allGroups.size} 个位置", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /**
     * 根据照片列表构建位置分组（公共函数，消除观察者和loadLocations的重复代码）
     */
    private suspend fun buildLocationGroups(photos: List<com.example.smartalbum.data.Photo>): List<com.example.smartalbum.data.LocationGroup> {
        return withContext(Dispatchers.IO) {
            val locationGroups = repository.groupByLocation(photos)
            // 如果开关打开，从PhotoScanManager获取无位置照片（已按时间范围过滤+按时间排序）
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val showAll = prefs.getBoolean("show_all_photos", false)
            if (showAll) {
                // 数据分开管理：无位置照片通过专门方法获取，不与有位置照片混在一起
                // 2026-10-10：即使无位置照片为0，也拼一个空分组置顶显示（用户要求）
                val noLocationPhotos = com.example.smartalbum.data.PhotoScanManager.getNoLocationPhotos(requireContext())
                android.util.Log.i("LocationList", "buildLocationGroups: photos=" + photos.size + ", 无位置照片=" + noLocationPhotos.size)
                val noLocationGroup = com.example.smartalbum.data.LocationGroup(
                    id = "no_location",
                    latitude = 0.0,
                    longitude = 0.0,
                    locationName = "无位置照片",
                    photos = noLocationPhotos
                )
                listOf(noLocationGroup) + locationGroups
            } else {
                locationGroups
            }
        }
    }

    /**
     * 更新UI显示（公共函数，消除观察者和loadLocations的重复代码）
     */
    private fun updateUIWithGroups(photos: List<com.example.smartalbum.data.Photo>, groups: List<com.example.smartalbum.data.LocationGroup>) {
        allGroups = groups
        isLoaded = true
        // 记录当前设置状态，用于onResume判断是否需要重新加载
        val currentPrefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        lastShowAllPhotos = currentPrefs.getBoolean("show_all_photos", false)
        lastTimeRange = currentPrefs.getInt("no_location_time_months", 6)

        // 只加载第一页，避免OOM
        displayedGroups = allGroups.take(pageSize)

        _binding?.let { b ->
            adapter.submitList(displayedGroups)
            if (allGroups.isEmpty()) {
                val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val showAll = prefs.getBoolean("show_all_photos", false)
                stateHelper?.showEmpty(if (showAll) "暂无照片" else "暂无带位置信息的照片")
            } else {
                stateHelper?.showContent()
            }
            // 统计口径统一走 updateStatsFromGroups：按实际展示的分组算，
            // 避免「1 个位置 / 650 张照片」这类自相矛盾（2026-10-04 实测 bug）
            updateStatsFromGroups(allGroups)
            // 2026-10-10：数据更新后强制滚到顶部，让第0位的"无位置照片"分组不被统计卡片遮挡
            binding.locationRecyclerView.scrollToPosition(0)
        }
    }

    private fun loadLocations() {
        if (_binding == null) return
        
        // 如果已有缓存数据，直接显示，不显示加载界面
        if (com.example.smartalbum.data.PhotoScanManager.isReady()) {
            stateHelper?.showContent()
        } else {
            stateHelper?.showLoading("正在扫描照片...")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 位置列表：有位置的分组永远显示；无位置分组由 buildLocationGroups 根据开关额外拼
                val photos = com.example.smartalbum.data.PhotoScanManager.getPhotosWithLocation(
                    requireContext()
                )

                val groups = buildLocationGroups(photos)
                updateUIWithGroups(photos, groups)
                applySort() // 应用排序

                // 对前50个位置做逆地理编码（避免5万+网络请求）
                reverseGeocodeLocations()

            } catch (e: Exception) {
                LogUtil.e("LocationListFragment", "异常", e)
                stateHelper?.showError("加载失败: ${e.message}", "重试") {
                    loadLocations()
                }
            }
        }
    }

    /**
     * 对前50个位置做逆地理编码，更新位置名称
     */
    private fun reverseGeocodeLocations() {
        if (allGroups.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            // 跳过无位置分组，只对有位置的分组做逆地理编码
            val locationGroups = allGroups.filter { it.id != "no_location" }
            if (locationGroups.isEmpty()) return@launch
            val geocodeCount = minOf(50, locationGroups.size)
            val updatedGroups = withContext(Dispatchers.IO) {
                locationGroups.take(geocodeCount).map { group ->
                    try {
                        val name = repository.reverseGeocode(group.latitude, group.longitude)
                        group.copy(locationName = name)
                    } catch (e: Exception) {
                        LogUtil.d("LocationListFragment", "逆地理编码失败: ${e.message}")
                        group
                    }
                }
            }
            // 只更新已编码的部分，保留无位置分组在最前面
            val noLocationGroup = allGroups.find { it.id == "no_location" }
            val result = mutableListOf<com.example.smartalbum.data.LocationGroup>()
            if (noLocationGroup != null) result.add(noLocationGroup)
            for (i in 0 until geocodeCount) {
                result.add(updatedGroups[i])
            }
            // 添加剩余未编码的分组
            val remainingGroups = allGroups.filter { it.id != "no_location" }.drop(geocodeCount)
            result.addAll(remainingGroups)
            allGroups = result
            displayedGroups = allGroups.take(pageSize)
            _binding?.let {
                adapter.submitList(displayedGroups)
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            stateHelper?.showEmpty("暂无带位置信息的照片")
        } else {
            stateHelper?.showContent()
        }
    }

    /**
     * 检查设置是否变化，如果变化则重新加载数据
     * 在onResume和onHiddenChanged中都调用（因为hide/show切换Fragment不触发onResume）
     */
    private fun checkAndReloadIfNeeded() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val currentShowAll = prefs.getBoolean("show_all_photos", false)
        val currentTimeRange = prefs.getInt("no_location_time_months", 6)
        // 首次加载：初始化last值并加载
        if (!isLoaded || lastTimeRange == -2) {
            lastShowAllPhotos = currentShowAll
            lastTimeRange = currentTimeRange
            loadLocations()
            return
        }
        // 只有设置真正变化时才重新加载
        if (currentShowAll != lastShowAllPhotos || currentTimeRange != lastTimeRange) {
            android.util.Log.i("LocationList", "检测到设置变化: showAll=" + currentShowAll + "(上次=" + lastShowAllPhotos + "), timeRange=" + currentTimeRange + "(上次=" + lastTimeRange + "), 重新加载")
            lastShowAllPhotos = currentShowAll
            lastTimeRange = currentTimeRange
            loadLocations()
        }
        // 设置没变化且已有数据，不重新扫描，直接用缓存
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // 隐藏时注销观察者，避免收到通知更新UI
            com.example.smartalbum.data.PhotoScanManager.removeOnDataChangedListener(onDataChangedListener)
        } else {
            // 应用主题（切换主题后恢复显示时更新颜色）
            applyTheme()
            // 显示时重新注册观察者
            com.example.smartalbum.data.PhotoScanManager.addOnDataChangedListener(onDataChangedListener)
            // 检查设置变化并重新加载（关键！hide/show切换Fragment不触发onResume，必须在这里检测）
            checkAndReloadIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        // 注册数据变化观察者（数据变化时自动更新）
        com.example.smartalbum.data.PhotoScanManager.addOnDataChangedListener(onDataChangedListener)
        // 检查设置变化并重新加载
        checkAndReloadIfNeeded()
    }

    // 主题变化回调
    private val themeChangedListener = com.example.smartalbum.util.ThemeManager.OnThemeChangedListener {
        view?.post { applyTheme() }
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            binding.root.setBackgroundColor(theme.getBackgroundColor())
            binding.toolbar.setBackgroundColor(theme.getAccentColor())
            binding.toolbar.setTitleTextColor(theme.getButtonTextColor())
            binding.loadingText.setTextColor(theme.getTextColor())
        } catch (e: Exception) {
            android.util.Log.e("LocationList", "应用主题失败: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        // 注销数据变化观察者
        com.example.smartalbum.data.PhotoScanManager.removeOnDataChangedListener(onDataChangedListener)
    }

    override fun onDestroyView() {
        com.example.smartalbum.util.ThemeManager.removeOnThemeChangedListener(themeChangedListener)
        super.onDestroyView()
        _binding = null
    }
}

