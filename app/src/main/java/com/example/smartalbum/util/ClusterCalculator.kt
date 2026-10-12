package com.example.smartalbum.util

import com.example.smartalbum.data.LocationGroup
import kotlin.math.pow

/**
 * 聚合计算器（纯Kotlin，无Android依赖，可单元测试）
 *
 * 从MarkerClusterManager中提取的核心算法：
 * - 网格大小随缩放级别连续指数衰减
 * - O(n)网格聚合
 *
 * 网格大小公式：gridSize = BASE_GRID_SIZE * 2^(BASE_ZOOM - zoomLevel)
 * 缩放每增加1级，网格大小减半，可见点数约翻倍
 */
class ClusterCalculator(
    private val baseGridSize: Double = DEFAULT_BASE_GRID_SIZE,
    private val baseZoom: Float = DEFAULT_BASE_ZOOM
) {
    companion object {
        const val DEFAULT_BASE_GRID_SIZE = 1.0
        const val DEFAULT_BASE_ZOOM = 7f
        const val DEFAULT_MAX_RENDER_COUNT = 500
    }

    /**
     * 根据缩放级别计算网格大小（连续指数衰减）
     */
    fun calculateGridSize(zoomLevel: Float): Double {
        val exponent = baseZoom - zoomLevel
        return baseGridSize * 2.0.pow(exponent.toDouble())
    }

    /**
     * 网格聚合算法（O(n)复杂度）
     * 把点按网格分组，每个网格内的点聚合成一个簇
     *
     * @param groups 位置分组列表
     * @param gridSize 网格大小（度）
     * @return 聚合簇列表
     */
    fun gridCluster(groups: List<LocationGroup>, gridSize: Double): List<Cluster> {
        if (groups.isEmpty()) return emptyList()

        val gridMap = mutableMapOf<String, MutableList<LocationGroup>>()

        groups.forEach { group ->
            val gridX = (group.longitude / gridSize).toInt()
            val gridY = (group.latitude / gridSize).toInt()
            val key = "$gridX,$gridY"
            gridMap.getOrPut(key) { mutableListOf() }.add(group)
        }

        return gridMap.values.map { gridGroups ->
            val avgLat = gridGroups.sumOf { it.latitude } / gridGroups.size
            val avgLon = gridGroups.sumOf { it.longitude } / gridGroups.size
            val totalPhotos = gridGroups.sumOf { it.photoCount }
            Cluster(
                latitude = avgLat,
                longitude = avgLon,
                count = gridGroups.size,
                totalPhotos = totalPhotos,
                locationName = gridGroups.first().locationName,
                groups = gridGroups
            )
        }
    }

    /**
     * 渲染前的聚合处理：包含可见区域过滤+自适应网格大小
     *
     * @param groups 所有位置分组
     * @param zoomLevel 当前缩放级别
     * @param visibleBounds 可见区域（可为null表示不过滤）
     * @param maxRenderCount 最大渲染数量，超过时自动加大网格
     * @return 需要渲染的聚合簇列表
     */
    fun calculateClustersToRender(
        groups: List<LocationGroup>,
        zoomLevel: Float,
        visibleBounds: VisibleBounds? = null,
        maxRenderCount: Int = DEFAULT_MAX_RENDER_COUNT
    ): List<Cluster> {
        // 可见区域过滤
        val visibleGroups = if (visibleBounds != null) {
            groups.filter { group ->
                group.longitude in visibleBounds.minLongitude..visibleBounds.maxLongitude &&
                group.latitude in visibleBounds.minLatitude..visibleBounds.maxLatitude
            }
        } else {
            groups
        }

        if (visibleGroups.isEmpty()) return emptyList()

        val gridSize = calculateGridSize(zoomLevel)
        var clusters = gridCluster(visibleGroups, gridSize)

        // 超过最大渲染数量时，自适应加大网格
        if (clusters.size > maxRenderCount) {
            val largerGridSize = gridSize * (clusters.size / maxRenderCount + 1)
            clusters = gridCluster(visibleGroups, largerGridSize).take(maxRenderCount)
        }

        return clusters
    }

    /**
     * 可见区域（纯数据类，替代AMap的LatLngBounds，便于测试）
     */
    data class VisibleBounds(
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double
    )

    /**
     * 聚合簇数据类
     */
    data class Cluster(
        val latitude: Double,
        val longitude: Double,
        val count: Int,
        val totalPhotos: Int,
        val locationName: String,
        val groups: List<LocationGroup>
    )
}
