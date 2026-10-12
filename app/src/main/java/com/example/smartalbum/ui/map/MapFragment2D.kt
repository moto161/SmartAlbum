package com.example.smartalbum.ui.map

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.smartalbum.data.Photo
import com.example.smartalbum.data.PhotoScanManager
import com.example.smartalbum.util.ActivityAnimationUtil

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps2d.AMap
import com.amap.api.maps2d.CameraUpdateFactory
import com.amap.api.maps2d.model.CameraPosition
import com.amap.api.maps2d.model.LatLng
import com.amap.api.maps2d.model.LatLngBounds
import com.amap.api.maps2d.model.Marker
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.example.smartalbum.R
import com.example.smartalbum.data.LocationGroup
import com.example.smartalbum.data.PhotoRepository
import com.example.smartalbum.databinding.FragmentMap2dBinding
import com.example.smartalbum.service.LocationUploadService
import com.example.smartalbum.ui.gallery.GalleryActivity
import com.example.smartalbum.util.LogUtil
import com.example.smartalbum.util.ClusterCalculator
import com.example.smartalbum.util.MarkerClusterManager
import com.example.smartalbum.ui.common.StateLayoutHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MapFragment2D : Fragment(), PoiSearch.OnPoiSearchListener {

    /** 单次最多做多少个位置组的逆地理编码（与位置列表页口径一致） */
    private val maxReverseGeocode = 50

    private var _binding: FragmentMap2dBinding? = null
    private val binding get() = _binding!!
    private var aMap: AMap? = null
    @Inject
    lateinit var repository: PhotoRepository
    private var locationGroups = listOf<LocationGroup>()
    private var isLoaded = false
    private var savedCameraPosition: CameraPosition? = null
    private val onDataChangedListener: (List<Photo>) -> Unit = { _ ->
        // 数据变化时更新地图标记点。地图永远只显示有位置的照片，忽略传入的过滤结果
        if (isAdded && _binding != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val photos = com.example.smartalbum.data.PhotoScanManager.getCachedPhotos()
                        ?.filter { it.hasLocation } ?: emptyList()
                    locationGroups = repository.groupByLocation(photos)
                    markerController?.setGroups(locationGroups)
                    if (_binding != null) {
                        showMarkers()
                        _binding?.tvPhotoCount?.text = "${photos.size}张照片"
                    }
                } catch (e: Exception) {
                    LogUtil.e("MapFragment2D", "数据变化更新异常", e)
                }
            }
        }
    }
    private val markers = mutableMapOf<String, Marker>()
    private var clusterManager: MarkerClusterManager? = null
    private var markerController: MapMarkerController? = null
    private var searchController: MapSearchController? = null
    private var stateHelper: StateLayoutHelper? = null

    // 定位相关（已委托给MapLocationController）
    private var locationController: MapLocationController? = null
    private var isSatelliteMode = false

    // 相册变化监听
    private var photoContentObserver: ContentObserver? = null
    private val photoHandler = Handler(Looper.getMainLooper())

    // 搜索相关
    // 搜索逻辑已委托给MapSearchController


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMap2dBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 注册主题变化监听
        com.example.smartalbum.util.ThemeManager.addOnThemeChangedListener(themeChangedListener)
        binding.mapView.onCreate(savedInstanceState)
        aMap = binding.mapView.map
        markerController = MapMarkerController(aMap, requireContext())
        locationController = MapLocationController(this, aMap).apply {
            onLocationResult = { latLng, title, locationName ->
                _binding?.tvCurrentLocation?.text = locationName
            }
            onLocationError = { errorMsg ->
                // 2026-10-04 修复：定位失败时若不更新文案，界面会一直停在
                // 「定位中…」（那行是 layout 里的静态文本），用户无法判断发生了什么。
                _binding?.tvCurrentLocation?.text = "定位失败"
                android.widget.Toast.makeText(requireContext(), errorMsg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        searchController = MapSearchController(
            binding.searchBar,
            binding.searchInput,
            binding.searchResults
        ) { poiItem ->
            onPoiItemClick(poiItem)
        }
        searchController?.setupSearch()

        // 默认显示合肥（用户所在城市），而不是北京
        val hefeiLatLng = com.amap.api.maps2d.model.LatLng(31.82, 117.23)
        aMap?.moveCamera(com.amap.api.maps2d.CameraUpdateFactory.newLatLngZoom(hefeiLatLng, 12f))

        // 初始化统一状态管理器
        stateHelper = StateLayoutHelper(
            loadingLayout = binding.loadingLayout,
            emptyLayout = binding.emptyLayout,
            contentLayout = binding.mapView,
            loadingText = binding.loadingText
        )
        stateHelper?.showContent()
        binding.searchBar.visibility = View.GONE
        binding.searchResults.visibility = View.GONE

        setupSearch()
        setupButtons()
        registerPhotoObserver { rescanPhotos() }

        // 2026-10-04 修复：定位文案「定位中…」是 layout 里的写死静态文本，
        // 而定位只在我主动点右下角定位按钮时才会启动
        // → 用户一进地图页就永远看到「定位中…」，像是坏了。
        // 这里在地图就绪后自动发起一次定位，用户不必手点。
        // 注意要等地图布局完成，否则高德 SDK 还没准备好会失败。
        binding.mapView.postDelayed({
            if (_binding != null && isAdded) {
                startLocation()
            }
        }, 800)

        // 延迟加载照片，让地图先显示出来，提升启动速度
        // loadPhotos() 移到 onResume 中执行
    }

    // ============================================================
    // 按钮事件处理
    // ============================================================
    private fun setupButtons() {
        binding.btnSearch.setOnClickListener {
            if (binding.searchBar.visibility == View.VISIBLE) {
                hideSearchBar()
            } else {
                showSearchBar()
            }
        }

        binding.btnSearchCancel.setOnClickListener {
            hideSearchBar()
        }

        binding.btnLocation.setOnClickListener {
            startLocation()
        }

        binding.btnLayer.setOnClickListener {
            toggleMapType()
        }
    }

    private fun toggleMapType() {
        val map = aMap ?: return
        isSatelliteMode = !isSatelliteMode
        if (isSatelliteMode) {
            map.mapType = com.amap.api.maps2d.AMap.MAP_TYPE_SATELLITE
            android.widget.Toast.makeText(requireContext(), "已切换到卫星图", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            map.mapType = com.amap.api.maps2d.AMap.MAP_TYPE_NORMAL
            android.widget.Toast.makeText(requireContext(), "已切换到标准图", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 搜索功能（POI搜索）
    // ============================================================
    private fun setupSearch() {
        // 搜索逻辑已委托给MapSearchController
        // 这里只设置键盘搜索按钮监听
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                performSearch()
                true
            } else {
                false
            }
        }
    }

    private fun showSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.infoCard.visibility = View.GONE
        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchBar() {
        binding.searchBar.visibility = View.GONE
        binding.searchResults.visibility = View.GONE
        binding.infoCard.visibility = View.VISIBLE
        binding.searchInput.setText("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun performSearch() {
        val keyword = binding.searchInput.text.toString().trim()
        LogUtil.d("Search", "performSearch被调用，关键词: $keyword")
        if (keyword.isEmpty()) {
            Toast.makeText(requireContext(), "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 隐藏键盘
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)

            // 显示搜索结果区域
            binding.searchResults.visibility = View.VISIBLE

            // 委托给MapSearchController执行搜索
            searchController?.performSearch(keyword)
            LogUtil.i("Search", "POI异步搜索已发起，关键词: $keyword")
        } catch (e: Exception) {
            LogUtil.e("Search", "搜索异常: ${e.message}", e)
            Toast.makeText(requireContext(), "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPoiSearched(result: PoiResult?, rCode: Int) {
        // 搜索结果已由MapSearchController处理
    }

    override fun onPoiItemSearched(poiItem: PoiItem?, rCode: Int) {
        // 不需要实现
    }

    private fun onPoiItemClick(poiItem: PoiItem) {
        try {
            val point = poiItem.latLonPoint
            if (point == null) {
                LogUtil.w("MapFragment2D", "POI 无坐标: ${poiItem.title}")
                Toast.makeText(requireContext(), "该位置无坐标信息", Toast.LENGTH_SHORT).show()
                return
            }
            val latLng = LatLng(point.latitude, point.longitude)
            // 2026-10-10：搜索结果用街道级 zoom=17f，居中放大
            locationController?.moveToLocation(latLng, poiItem.title ?: "搜索结果", true,
                MapLocationController.POI_ZOOM)
            hideSearchBar()
            // 收起软键盘
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(view?.windowToken, 0)
        } catch (e: Exception) {
            LogUtil.e("MapFragment2D", "异常", e)
            Toast.makeText(requireContext(), "无法移动到该位置", Toast.LENGTH_SHORT).show()
        }
    }



    // ============================================================
    // 相册变化监听
    // ============================================================
    private fun registerPhotoObserver(onChanged: () -> Unit) {
        photoContentObserver = object : ContentObserver(photoHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                photoHandler.removeCallbacksAndMessages(null)
                photoHandler.postDelayed({ onChanged() }, 500)
            }
        }
        requireContext().contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            photoContentObserver!!
        )
    }

    // ============================================================
    // 定位功能
    // ============================================================
    private fun startLocation() {
        LogUtil.d("Location", "startLocation被调用")
        locationController?.startLocation()
        // 启动位置上报前台服务（每5分钟自动上报位置）
        LocationUploadService.start(requireContext())
    }



    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationController?.onRequestPermissionsResult(requestCode, grantResults)
    }

    // ============================================================
    // 照片加载与标记点管理
    // ============================================================
    private fun rescanPhotos() {
        if (isAdded && _binding != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val photos = withContext(Dispatchers.IO) {
                        repository.scanPhotosWithCache { _, _ -> }
                    }
                    locationGroups = repository.groupByLocation(photos)
                    markerController?.setGroups(locationGroups)
                    if (_binding != null) {
                        showMarkers()
                    }
                } catch (e: Exception) {
                    LogUtil.e("MapFragment2D", "异常", e)
                }
            }
        }
    }

    private fun loadPhotos() {
        if (_binding == null) return
        
        // 如果已有缓存数据，直接显示，不显示加载界面
        if (com.example.smartalbum.data.PhotoScanManager.isReady()) {
            stateHelper?.showContent()
        } else {
            stateHelper?.showLoading(getString(R.string.scan_photos))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 使用全局单例扫描管理器，保证整个APP只有一个扫描任务
                // 如果启动页已经扫描完成，直接返回数据
                // 如果启动页正在扫描，等待扫描完成
                // 如果都没有，才开始新的扫描
                // 优化：有缓存时立即返回缓存数据，后台增量扫描
                android.util.Log.i("MapFragment", "使用全局扫描管理器获取照片数据")
                // 地图永远只打有坐标的点，不随"显示全部照片"开关变化
                val photos = com.example.smartalbum.data.PhotoScanManager.getPhotosWithLocation(
                    requireContext()
                )
                locationGroups = repository.groupByLocation(photos)
                markerController?.setGroups(locationGroups)
                isLoaded = true

                // 更新顶部信息卡片 - 照片数量
                _binding?.tvPhotoCount?.text = "${photos.size}张照片"

                _binding?.let { b ->
                    if (locationGroups.isEmpty()) {
                        stateHelper?.showEmpty("暂无带位置信息的照片")
                    } else {
                        showMarkers()
                        stateHelper?.showContent()
                    }
                }

                if (locationGroups.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val updatedGroups = withContext(Dispatchers.IO) {
                            // Geocoder 是系统服务、有速率限制（超了会抛
                            // "Service not Available"），位置组可能有几百个。
                            // 与位置列表页口径一致：最多只解析前 MAX_REVERSE_GEOCODE 个，
                            // 其余保留占位名，下次进来再补。
                            val limit = minOf(maxReverseGeocode, locationGroups.size)
                            val head = locationGroups.take(limit).map { group ->
                                val name = repository.reverseGeocode(group.latitude, group.longitude)
                                group.copy(locationName = name)
                            }
                            head + locationGroups.drop(limit)
                        }
                        locationGroups = updatedGroups
                        markerController?.setGroups(locationGroups)
                        updateMarkerTitles()
                    }
                }

            } catch (e: Exception) {
                LogUtil.e("MapFragment2D", "异常", e)
                _binding?.let { b ->
                    b.loadingLayout.visibility = View.GONE
                    b.emptyLayout.visibility = View.VISIBLE
                }
            }
        }
    }


    private fun showMarkers() {
        val map = aMap ?: return
        if (_binding == null || locationGroups.isEmpty()) return

        // 使用MapMarkerController管理标记点渲染
        markerController?.setGroups(locationGroups)
        markerController?.showMarkers()

        // 只在第一次加载时移动相机到所有标记点范围内
        if (!isLoaded && locationGroups.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.Builder()
            locationGroups.take(1000).forEach { group ->
                boundsBuilder.include(LatLng(group.latitude, group.longitude))
            }
            val bounds = boundsBuilder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        } else if (savedCameraPosition != null) {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(savedCameraPosition))
        }

        // 标记点点击事件
        map.setOnMarkerClickListener { marker ->
            onMarkerClick(marker)
            false
        }
    }

    /**
     * 处理标记点点击事件
     */
    private fun onMarkerClick(marker: Any) {
        if (!isAdded) return
        val m = marker as? com.amap.api.maps2d.model.Marker ?: return
        val obj = m.`object`
        if (obj !is ClusterCalculator.Cluster) return
        if (obj.count == 1 && obj.groups.isNotEmpty()) {
            // 单个标记点：进入照片画廊
            val intent = GalleryActivity.newIntent(requireContext(), obj.groups[0])
            ActivityAnimationUtil.startActivityWithAnimation(requireContext(), intent)
        } else if (obj.count > 1) {
            // 聚合标记点：放大地图，让聚合点散开
            val zoomIncrement = when {
                obj.count >= 50 -> 4f
                obj.count >= 10 -> 3f
                else -> 2f
            }
            val targetZoom = minOf(aMap?.cameraPosition?.zoom ?: 10f + zoomIncrement, 18f)
            aMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(obj.latitude, obj.longitude),
                    targetZoom
                )
            )
        }
    }

    private fun updateMarkerTitles() {
        if (_binding == null) return
        locationGroups.forEach { group ->
            markers[group.id]?.title = group.locationName
        }
    }

    // ============================================================
    // 生命周期管理
    // ============================================================
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 保护：Fragment视图还没创建或已销毁时，不处理
        if (_binding == null || aMap == null) {
            LogUtil.w("Map", "onHiddenChanged: 视图未就绪，跳过 (hidden=$hidden)")
            return
        }
        if (hidden) {
            LogUtil.i("Map", "Fragment被隐藏，释放地图资源")
            // 注销观察者，避免隐藏时收到通知更新UI
            com.example.smartalbum.data.PhotoScanManager.removeOnDataChangedListener(onDataChangedListener)
            try {
                markerController?.release()
                locationController?.stopLocation()
                _binding?.mapView?.onPause()
            } catch (e: Exception) {
                LogUtil.e("Map", "隐藏时清理异常: ${e.message}")
            }
        } else {
            LogUtil.i("Map", "Fragment重新显示，恢复地图")
            // 应用主题（切换主题后恢复显示时更新颜色）
            applyTheme()
            // 重新注册观察者
            com.example.smartalbum.data.PhotoScanManager.addOnDataChangedListener(onDataChangedListener)
            locationController?.setAMap(aMap)
            try {
                _binding?.mapView?.onResume()
                markerController?.restore(aMap)
                if (isLoaded && locationGroups.isNotEmpty()) {
                    showMarkers()
                }
            } catch (e: Exception) {
                LogUtil.e("Map", "显示时恢复异常: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        _binding?.mapView?.onResume()
        // 注册数据变化观察者
        com.example.smartalbum.data.PhotoScanManager.addOnDataChangedListener(onDataChangedListener)
        // 延迟加载照片（首次显示时），让地图先渲染
        if (!isLoaded) {
            view?.post {
                loadPhotos()
            }
        } else {
            showMarkers()
        }
        // 恢复相机位置，保持用户之前的画面
        if (savedCameraPosition != null && isLoaded && aMap != null) {
            try {
                aMap?.moveCamera(CameraUpdateFactory.newCameraPosition(savedCameraPosition))
            } catch (e: Exception) {
                LogUtil.e("Map", "onResume恢复相机异常: ${e.message}")
            }
        }
        // 只在第一次加载时扫描，后续不重新扫描，保持位置
        // 相册变化监听已在registerPhotoObserver中处理
    }

    // 主题变化回调
    private val themeChangedListener = com.example.smartalbum.util.ThemeManager.OnThemeChangedListener {
        view?.post { applyTheme() }
    }

    private fun applyTheme() {
        try {
            val theme = com.example.smartalbum.util.ThemeManager
            binding.root.setBackgroundColor(theme.getBackgroundColor())
            binding.loadingText.setTextColor(theme.getTextColor())
        } catch (e: Exception) {
            android.util.Log.e("MapFragment", "应用主题失败: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        // 注销数据变化观察者
        PhotoScanManager.removeOnDataChangedListener(onDataChangedListener)
        // 保存当前相机位置
        try {
            savedCameraPosition = aMap?.cameraPosition
        } catch (e: Exception) {
            LogUtil.e("Map", "onPause保存相机异常: ${e.message}")
        }
        _binding?.mapView?.onPause()
        locationController?.stopLocation()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        com.example.smartalbum.util.ThemeManager.removeOnThemeChangedListener(themeChangedListener)
        super.onDestroyView()
        LogUtil.i("Map", "onDestroyView，释放所有资源")
        try {
            markerController?.onDestroy()
            markerController = null
            searchController?.onDestroy()
            searchController = null
            clusterManager?.clear()
            clusterManager = null
            locationController?.onDestroy()
            locationController = null
            // 清理相册变化监听
            photoContentObserver?.let {
                requireContext().contentResolver.unregisterContentObserver(it)
            }
            photoHandler.removeCallbacksAndMessages(null)
            _binding?.mapView?.onDestroy()
        } catch (e: Exception) {
            LogUtil.e("MapFragment2D", "异常", e)
        }
        _binding = null
    }
}
