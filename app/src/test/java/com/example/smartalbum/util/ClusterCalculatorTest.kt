package com.example.smartalbum.util

import com.example.smartalbum.data.Photo
import com.example.smartalbum.data.LocationGroup
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ClusterCalculator单元测试
 * 测试聚合算法的正确性：网格大小计算、网格聚合、可见区域过滤、自适应渲染
 *
 * 使用MockK mock Android依赖（Uri/Photo），在纯JVM环境运行
 */
class ClusterCalculatorTest {

    private lateinit var calculator: ClusterCalculator

    @Before
    fun setUp() {
        calculator = ClusterCalculator()
    }

    // ========== 辅助方法：用MockK构造测试数据 ==========

    private fun mockPhoto(id: Long, lat: Double? = 31.0, lon: Double? = 117.0): Photo {
        val uri = mockk<android.net.Uri>()
        every { uri.toString() } returns "content://media/$id"
        return Photo(
            id = id,
            uri = uri,
            displayName = "photo_$id.jpg",
            dateTaken = 1700000000000L + id * 1000,
            latitude = lat,
            longitude = lon
        )
    }

    private fun makeGroup(id: String, lat: Double, lon: Double, count: Int = 1, name: String = "测试位置"): LocationGroup {
        val photos = (1..count).map { mockPhoto(id.hashCode().toLong() + it, lat, lon) }
        return LocationGroup(
            id = id,
            latitude = lat,
            longitude = lon,
            locationName = name,
            photos = photos
        )
    }

    // ========== calculateGridSize 测试 ==========

    @Test
    fun `calculateGridSize - 基准缩放级别时网格等于基准大小`() {
        val gridSize = calculator.calculateGridSize(ClusterCalculator.DEFAULT_BASE_ZOOM)
        assertEquals(ClusterCalculator.DEFAULT_BASE_GRID_SIZE, gridSize, 0.0001)
    }

    @Test
    fun `calculateGridSize - 缩放每增加1级网格减半`() {
        val base = calculator.calculateGridSize(7f)
        val zoomed = calculator.calculateGridSize(8f)
        assertEquals(base / 2, zoomed, 0.0001)
    }

    @Test
    fun `calculateGridSize - 缩放每减少1级网格翻倍`() {
        val base = calculator.calculateGridSize(7f)
        val zoomedOut = calculator.calculateGridSize(6f)
        assertEquals(base * 2, zoomedOut, 0.0001)
    }

    @Test
    fun `calculateGridSize - 高缩放级别网格很小`() {
        val gridSize = calculator.calculateGridSize(18f)
        assertTrue("高缩放时网格应很小，实际: $gridSize", gridSize < 0.001)
    }

    @Test
    fun `calculateGridSize - 低缩放级别网格很大`() {
        val gridSize = calculator.calculateGridSize(1f)
        assertTrue("低缩放时网格应很大，实际: $gridSize", gridSize > 10)
    }

    // ========== gridCluster 测试 ==========

    @Test
    fun `gridCluster - 空列表返回空`() {
        val result = calculator.gridCluster(emptyList(), 1.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `gridCluster - 单个点返回一个簇`() {
        val group = makeGroup("1", 31.0, 117.0, 3)
        val result = calculator.gridCluster(listOf(group), 1.0)
        assertEquals(1, result.size)
        assertEquals(1, result[0].count)
        assertEquals(3, result[0].totalPhotos)
    }

    @Test
    fun `gridCluster - 同一网格内的点聚合成一个簇`() {
        val groups = listOf(
            makeGroup("1", 31.0, 117.0, 2),
            makeGroup("2", 31.1, 117.1, 3),
            makeGroup("3", 31.2, 117.2, 5)
        )
        val result = calculator.gridCluster(groups, 10.0)
        assertEquals(1, result.size)
        assertEquals(3, result[0].count)
        assertEquals(10, result[0].totalPhotos)
    }

    @Test
    fun `gridCluster - 不同网格的点分成不同簇`() {
        val groups = listOf(
            makeGroup("1", 31.0, 117.0, 2),
            makeGroup("2", 50.0, 130.0, 3)
        )
        val result = calculator.gridCluster(groups, 1.0)
        assertEquals(2, result.size)
    }

    @Test
    fun `gridCluster - 簇中心是网格内点的平均值`() {
        val groups = listOf(
            makeGroup("1", 31.0, 117.0),
            makeGroup("2", 33.0, 119.0)
        )
        val result = calculator.gridCluster(groups, 10.0)
        assertEquals(1, result.size)
        assertEquals(32.0, result[0].latitude, 0.0001)
        assertEquals(118.0, result[0].longitude, 0.0001)
    }

    @Test
    fun `gridCluster - 大量点性能可接受`() {
        val groups = (1..10000).map { i ->
            makeGroup("$i", 30.0 + (i % 100) * 0.01, 116.0 + (i / 100) * 0.01)
        }
        val start = System.currentTimeMillis()
        val result = calculator.gridCluster(groups, 1.0)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("10000点聚合理应在1秒内完成，实际: ${elapsed}ms", elapsed < 1000)
        assertTrue(result.isNotEmpty())
    }

    // ========== calculateClustersToRender 测试 ==========

    @Test
    fun `calculateClustersToRender - 空列表返回空`() {
        val result = calculator.calculateClustersToRender(emptyList(), 10f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `calculateClustersToRender - 可见区域外的点被过滤`() {
        val groups = listOf(
            makeGroup("1", 31.0, 117.0),
            makeGroup("2", 50.0, 130.0)
        )
        val bounds = ClusterCalculator.VisibleBounds(
            minLatitude = 30.0,
            maxLatitude = 32.0,
            minLongitude = 116.0,
            maxLongitude = 118.0
        )
        val result = calculator.calculateClustersToRender(groups, 10f, bounds)
        assertEquals(1, result.size)
    }

    @Test
    fun `calculateClustersToRender - 无可见区域时不过滤`() {
        val groups = listOf(
            makeGroup("1", 31.0, 117.0),
            makeGroup("2", 50.0, 130.0)
        )
        val result = calculator.calculateClustersToRender(groups, 10f, null)
        assertEquals(2, result.size)
    }

    @Test
    fun `calculateClustersToRender - 超过最大渲染数时自适应加大网格`() {
        val groups = (1..1000).map { i ->
            makeGroup("$i", 30.0 + i * 0.01, 116.0 + i * 0.01)
        }
        val result = calculator.calculateClustersToRender(
            groups = groups,
            zoomLevel = 15f,
            visibleBounds = null,
            maxRenderCount = 100
        )
        assertTrue("渲染数量不应超过上限，实际: ${result.size}", result.size <= 100)
    }

    @Test
    fun `calculateClustersToRender - 低缩放时聚合程度高`() {
        val groups = (1..100).map { i ->
            makeGroup("$i", 31.0 + i * 0.001, 117.0 + i * 0.001)
        }
        val lowZoom = calculator.calculateClustersToRender(groups, 3f, null)
        val highZoom = calculator.calculateClustersToRender(groups, 15f, null)
        assertTrue("低缩放时聚合簇应更少，低: ${lowZoom.size}, 高: ${highZoom.size}",
            lowZoom.size <= highZoom.size)
    }

    // ========== 边界条件测试 ==========

    @Test
    fun `gridCluster - 负经纬度正确处理`() {
        val groups = listOf(
            makeGroup("1", -31.0, -117.0),
            makeGroup("2", -31.5, -117.5)
        )
        val result = calculator.gridCluster(groups, 10.0)
        assertEquals(1, result.size)
        assertTrue(result[0].latitude < 0)
        assertTrue(result[0].longitude < 0)
    }

    @Test
    fun `gridCluster - 零坐标正确处理`() {
        val group = makeGroup("1", 0.0, 0.0)
        val result = calculator.gridCluster(listOf(group), 1.0)
        assertEquals(1, result.size)
        assertEquals(0.0, result[0].latitude, 0.0001)
        assertEquals(0.0, result[0].longitude, 0.0001)
    }

    @Test
    fun `calculateGridSize - 自定义参数生效`() {
        val customCalc = ClusterCalculator(baseGridSize = 2.0, baseZoom = 5f)
        val gridSize = customCalc.calculateGridSize(5f)
        assertEquals(2.0, gridSize, 0.0001)
    }
}
