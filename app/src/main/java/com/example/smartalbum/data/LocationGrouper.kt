package com.example.smartalbum.data

import com.example.smartalbum.util.GeoUtils
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/**
 * 位置分组器
 *
 * 职责：将照片按地理位置聚类分组
 * 从PhotoRepository中提取，遵循单一职责原则，便于单元测试
 *
 * 算法（2026-10-10 重写为网格分桶 + 并查集）：
 * 1. 按经纬度网格分桶，桶内增量维护 sumLat/sumLon/count —— O(n)
 * 2. 只在 3×(2r+1) 邻域内比较桶中心，用并查集合并距离小于阈值的桶
 *
 * 旧实现是「按顺序遍历 + 每次命中都重算均值中心」的贪心：
 * - 复杂度 O(n·G)，5768 张照片时是扫描慢的主因
 * - 每次命中都 `groups[i].map{}.average()` 分配临时 List
 * - 中心是动态均值 ⇒ 结果依赖输入顺序 ⇒ 重扫一次分组就变、
 *   id="loc_$index" 跟着变、列表跳来跳去
 *
 * 现在：复杂度 O(n + C·k)（C=桶数，k=邻域桶数，通常 <20），
 * 且处理顺序由网格坐标决定，与输入顺序无关 ⇒ 结果稳定可复现。
 */
class LocationGrouper(
    private val clusterThreshold: Float = DEFAULT_CLUSTER_THRESHOLD
) {

    private data class CellKey(val latIdx: Int, val lonIdx: Int)

    private class Bucket {
        var sumLat = 0.0
        var sumLon = 0.0
        var count = 0
        val photos = ArrayList<Photo>()

        fun add(photo: Photo, lat: Double, lon: Double) {
            sumLat += lat
            sumLon += lon
            count++
            photos.add(photo)
        }
    }

    /**
     * 将照片按位置分组
     *
     * @param photos 照片列表
     * @return 按照片数量降序排列的位置分组
     */
    fun groupByLocation(photos: List<Photo>): List<LocationGroup> {
        if (photos.isEmpty()) return emptyList()

        val located = photos.filter { it.hasLocation }
        if (located.isEmpty()) return emptyList()

        val cellSize = (clusterThreshold.toDouble() / METERS_PER_DEGREE)
            .coerceAtLeast(MIN_CELL_SIZE_DEG)

        // 经度方向 1 度对应的米数随纬度衰减（×cos(lat)），高纬度要用更大的横向
        // 搜索半径，否则本该同组的点会因为跨了不止一格被拆开
        val maxAbsLat = located.maxOf { abs(it.latitude ?: 0.0) }
        val cosLat = cos(Math.toRadians(maxAbsLat)).coerceAtLeast(0.02)
        val lonReach = (ceil(1.0 / cosLat).toInt() + 1).coerceIn(1, MAX_LON_REACH)

        // 1) 网格分桶 —— O(n)
        val buckets = HashMap<CellKey, Bucket>()
        for (photo in located) {
            val lat = photo.latitude ?: continue
            val lon = photo.longitude ?: continue
            val key = CellKey(
                Math.floor(lat / cellSize).toInt(),
                Math.floor(lon / cellSize).toInt()
            )
            buckets.getOrPut(key) { Bucket() }.add(photo, lat, lon)
        }

        // 2) 按格坐标排序 —— 处理顺序由坐标决定，与 photos 的输入顺序无关
        val cells = buckets.entries.sortedWith(
            compareBy({ it.key.latIdx }, { it.key.lonIdx })
        )
        val indexOf = HashMap<CellKey, Int>(cells.size * 2)
        cells.forEachIndexed { idx, entry -> indexOf[entry.key] = idx }

        // 3) 并查集合并邻域内的桶；中心和计数增量维护，不重算 average
        val parent = IntArray(cells.size) { it }
        val sumLat = DoubleArray(cells.size)
        val sumLon = DoubleArray(cells.size)
        val count = IntArray(cells.size)
        cells.forEachIndexed { idx, entry ->
            sumLat[idx] = entry.value.sumLat
            sumLon[idx] = entry.value.sumLon
            count[idx] = entry.value.count
        }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (idx in cells.indices) {
            val key = cells[idx].key
            for (di in -1..1) {
                for (dj in -lonReach..lonReach) {
                    if (di == 0 && dj == 0) continue
                    val nb = indexOf[CellKey(key.latIdx + di, key.lonIdx + dj)] ?: continue
                    // 只与已处理过的格合并：既避免重复比较，也保证结果与遍历顺序无关
                    if (nb >= idx) continue
                    val rootA = find(idx)
                    val rootB = find(nb)
                    if (rootA == rootB) continue
                    val d = GeoUtils.distance(
                        sumLat[rootA] / count[rootA], sumLon[rootA] / count[rootA],
                        sumLat[rootB] / count[rootB], sumLon[rootB] / count[rootB]
                    )
                    if (d < clusterThreshold) {
                        parent[rootA] = rootB
                        sumLat[rootB] += sumLat[rootA]
                        sumLon[rootB] += sumLon[rootA]
                        count[rootB] += count[rootA]
                    }
                }
            }
        }

        // 4) 收集分组；组顺序按「首个出现的格子坐标」确定，不受照片数量变化影响
        val roots = ArrayList<Int>()
        val members = HashMap<Int, MutableList<Photo>>()
        for (idx in cells.indices) {
            val root = find(idx)
            val list = members[root]
            if (list == null) {
                members[root] = ArrayList<Photo>().apply { addAll(cells[idx].value.photos) }
                roots.add(root)
            } else {
                list.addAll(cells[idx].value.photos)
            }
        }

        return roots.mapIndexed { index, root ->
            LocationGroup(
                // id 绑定格子坐标而不是排序下标 —— 重扫后同一个位置仍是同一个 id，
                // 地图 marker 不会整体重建、列表也不会跳
                id = "loc_${cells[root].key.latIdx}_${cells[root].key.lonIdx}",
                latitude = sumLat[root] / count[root],
                longitude = sumLon[root] / count[root],
                locationName = "位置 ${index + 1}",
                photos = members[root]!!.sortedByDescending { it.dateTaken }
            )
        }.sortedByDescending { it.photoCount }
    }

    companion object {
        /** 默认聚类阈值（米），小于此距离的照片归为一组 */
        const val DEFAULT_CLUSTER_THRESHOLD = 100f

        /** 纬度 1 度对应的米数 */
        private const val METERS_PER_DEGREE = 111_320.0

        /** 网格最小边长（度），防止阈值为 0 时除出天文数字的格子索引 */
        private const val MIN_CELL_SIZE_DEG = 1e-6

        /** 经度方向邻域搜索半径上限，避免极端高纬度下退化成全表比较 */
        private const val MAX_LON_REACH = 6
    }
}
