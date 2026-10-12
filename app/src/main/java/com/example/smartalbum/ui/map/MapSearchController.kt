package com.example.smartalbum.ui.map

import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.example.smartalbum.util.LogUtil

/**
 * 地图搜索控制器
 *
 * 职责：管理POI搜索、搜索栏显示/隐藏、搜索结果列表展示
 * 从MapFragment2D中提取，遵循单一职责原则
 */
class MapSearchController(
    private val searchBar: View,
    private val searchEditText: EditText,
    private val searchRecyclerView: RecyclerView,
    private val onPoiItemClick: (PoiItem) -> Unit
) : PoiSearch.OnPoiSearchListener {

    private lateinit var searchAdapter: SearchResultAdapter
    private var poiSearch: PoiSearch? = null
    private var currentCity = "合肥" // 默认城市

    // 2026-10-10：搜索令牌，每次发起搜索 +1；回调时不匹配就丢弃（解决快速连续搜索时旧结果覆盖新结果）
    private var searchToken: Int = 0

    /**
     * 初始化搜索（在Fragment的onViewCreated中调用）
     */
    fun setupSearch() {
        searchAdapter = SearchResultAdapter { poiItem ->
            onPoiItemClick(poiItem)
        }
        searchRecyclerView.layoutManager = LinearLayoutManager(searchRecyclerView.context)
        searchRecyclerView.adapter = searchAdapter
    }

    /**
     * 显示搜索栏
     */
    fun showSearchBar() {
        searchBar.visibility = View.VISIBLE
        searchEditText.requestFocus()
        LogUtil.d("MapSearch", "搜索栏已显示")
    }

    /**
     * 隐藏搜索栏
     */
    fun hideSearchBar() {
        searchBar.visibility = View.GONE
        searchEditText.text.clear()
        searchAdapter.submitList(emptyList())
        LogUtil.d("MapSearch", "搜索栏已隐藏")
    }

    /**
     * 搜索栏是否可见
     */
    fun isSearchBarVisible(): Boolean = searchBar.visibility == View.VISIBLE

    /**
     * 执行POI搜索
     * 2026-10-10：每次搜索用闭包 listener 持有自己的 token；快速连续搜索时，
     * 旧 PoiSearch 的回调回来发现 token 已过期，直接丢弃，避免旧结果覆盖新结果。
     */
    fun performSearch(keyword: String) {
        try {
            val myToken = ++searchToken
            val query = PoiSearch.Query(keyword, "", currentCity)
            query.pageSize = 20
            query.pageNum = 0
            poiSearch = PoiSearch(searchBar.context, query)
            poiSearch?.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                    if (myToken != searchToken) {
                        LogUtil.d("MapSearch", "丢弃过期搜索结果 (token=$myToken, current=$searchToken)")
                        return
                    }
                    this@MapSearchController.handleSearchResult(result, rCode)
                }
                override fun onPoiItemSearched(poiItem: PoiItem?, rCode: Int) { /* 暂不处理 */ }
            })
            poiSearch?.searchPOIAsyn()
            LogUtil.d("MapSearch", "开始搜索: $keyword (token=$myToken)")
        } catch (e: Exception) {
            LogUtil.e("MapSearch", "搜索失败: ${e.message}")
        }
    }

    private fun handleSearchResult(result: PoiResult?, rCode: Int) {
        if (rCode == 1000 && result != null) {
            val pois = result.pois ?: emptyList()
            searchAdapter.submitList(pois)
            LogUtil.i("MapSearch", "搜索到${pois.size}个结果")
        } else {
            searchAdapter.submitList(emptyList())
            LogUtil.w("MapSearch", "搜索失败，rCode=$rCode")
        }
    }

    /**
     * POI详情搜索回调（暂不使用）
     */
    override fun onPoiSearched(result: PoiResult?, rCode: Int) {
        // 已废弃：搜索结果现在走每次新建的闭包 listener，this 的回调不会被触发
    }

    override fun onPoiItemSearched(poiItem: PoiItem?, rCode: Int) {
        // 暂不处理POI详情搜索
    }

    /**
     * 设置当前城市
     */
    fun setCity(city: String) {
        this.currentCity = city
    }

    /**
     * 销毁搜索资源
     */
    fun onDestroy() {
        poiSearch = null
        LogUtil.d("MapSearch", "搜索资源已释放")
    }
}
