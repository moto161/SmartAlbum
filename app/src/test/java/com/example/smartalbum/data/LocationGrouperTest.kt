package com.example.smartalbum.data

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LocationGrouper单元测试
 * 测试位置分组算法：空列表、单点、近距离聚合、远距离分组、排序等
 */
class LocationGrouperTest {

    private lateinit var grouper: LocationGrouper

    @Before
    fun setUp() {
        grouper = LocationGrouper()
    }

    private fun mockPhoto(
        id: Long,
        lat: Double? = 31.0,
        lon: Double? = 117.0,
        dateTaken: Long = 1700000000000L
    ): Photo {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://media/$id"
        return Photo(
            id = id,
            uri = uri,
            displayName = "photo_$id.jpg",
            dateTaken = dateTaken,
            latitude = lat,
            longitude = lon
        )
    }

    // ========== 基础测试 ==========

    @Test
    fun `空列表返回空`() {
        val result = grouper.groupByLocation(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `单个有位置照片返回一个组`() {
        val photos = listOf(mockPhoto(1, 31.0, 117.0))
        val result = grouper.groupByLocation(photos)
        assertEquals(1, result.size)
        assertEquals(1, result[0].photoCount)
        assertEquals(31.0, result[0].latitude, 0.0001)
        assertEquals(117.0, result[0].longitude, 0.0001)
    }

    @Test
    fun `无位置照片被过滤`() {
        val photos = listOf(
            mockPhoto(1, null, null),
            mockPhoto(2, 31.0, 117.0)
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(1, result.size)
        assertEquals(1, result[0].photoCount)
    }

    // ========== 聚合测试 ==========

    @Test
    fun `近距离照片聚合成一组`() {
        // 两点相距约50米（小于100米阈值）
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 31.00045, 117.0) // 约50米
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(1, result.size)
        assertEquals(2, result[0].photoCount)
    }

    @Test
    fun `远距离照片分成两组`() {
        // 两点相距约10公里（大于100米阈值）
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 31.1, 117.1)
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(2, result.size)
    }

    @Test
    fun `三组照片正确分组`() {
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 31.0001, 117.0001), // 近点1
            mockPhoto(3, 32.0, 118.0),
            mockPhoto(4, 32.0001, 118.0001), // 近点2
            mockPhoto(5, 33.0, 119.0)
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(3, result.size)
    }

    // ========== 组中心测试 ==========

    @Test
    fun `组中心是成员平均值`() {
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 32.0, 118.0)
        )
        // 使用大阈值确保聚合成一组
        val customGrouper = LocationGrouper(clusterThreshold = 200000f)
        val result = customGrouper.groupByLocation(photos)
        assertEquals(1, result.size)
        assertEquals(31.5, result[0].latitude, 0.0001)
        assertEquals(117.5, result[0].longitude, 0.0001)
    }

    // ========== 排序测试 ==========

    @Test
    fun `结果按照片数量降序排列`() {
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 31.0001, 117.0001), // 组1：2张
            mockPhoto(3, 32.0, 118.0),
            mockPhoto(4, 32.0001, 118.0001),
            mockPhoto(5, 32.0002, 118.0002), // 组2：3张
            mockPhoto(6, 33.0, 119.0) // 组3：1张
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(3, result.size)
        assertTrue("第一组应该最多", result[0].photoCount >= result[1].photoCount)
        assertTrue("第二组应该比第三组多", result[1].photoCount >= result[2].photoCount)
        assertEquals(3, result[0].photoCount)
        assertEquals(2, result[1].photoCount)
        assertEquals(1, result[2].photoCount)
    }

    // ========== 组内排序测试 ==========

    @Test
    fun `组内照片按时间降序排列`() {
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0, dateTaken = 1000),
            mockPhoto(2, 31.0001, 117.0001, dateTaken = 3000),
            mockPhoto(3, 31.0002, 117.0002, dateTaken = 2000)
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(1, result.size)
        val groupPhotos = result[0].photos
        assertEquals(3000, groupPhotos[0].dateTaken)
        assertEquals(2000, groupPhotos[1].dateTaken)
        assertEquals(1000, groupPhotos[2].dateTaken)
    }

    // ========== 自定义阈值测试 ==========

    @Test
    fun `自定义阈值生效`() {
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 31.001, 117.0) // 约111米
        )
        // 默认阈值100米，应该分成两组
        val defaultResult = grouper.groupByLocation(photos)
        assertEquals(2, defaultResult.size)

        // 自定义阈值200米，应该聚合成一组
        val customGrouper = LocationGrouper(clusterThreshold = 200f)
        val customResult = customGrouper.groupByLocation(photos)
        assertEquals(1, customResult.size)
    }

    // ========== 边界条件测试 ==========

    @Test
    fun `大量照片性能可接受`() {
        val photos = (1..1000).map { i ->
            mockPhoto(i.toLong(), 31.0 + (i % 10) * 0.0001, 117.0 + (i / 10) * 0.0001)
        }
        val start = System.currentTimeMillis()
        val result = grouper.groupByLocation(photos)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000张照片分组应在1秒内完成，实际: ${elapsed}ms", elapsed < 1000)
        assertTrue(result.isNotEmpty())
    }

    // ========== 稳定性测试（2026-10-10 网格化重写后新增） ==========

    @Test
    fun `分组结果与输入顺序无关`() {
        val photos = listOf(
            mockPhoto(1, 31.0, 117.0),
            mockPhoto(2, 31.0002, 117.0002),
            mockPhoto(3, 32.0, 118.0),
            mockPhoto(4, 32.0002, 118.0002),
            mockPhoto(5, 32.0004, 118.0004),
            mockPhoto(6, 33.0, 119.0)
        )
        val forward = grouper.groupByLocation(photos)
        val reversed = grouper.groupByLocation(photos.reversed())
        val shuffled = grouper.groupByLocation(photos.shuffled(java.util.Random(42)))

        // 旧实现（贪心 + 每次重算均值中心）结果依赖输入顺序，重扫一次分组就变、
        // id 跟着变、列表跳来跳去。现在 id 绑定网格坐标，必须完全一致。
        assertEquals(forward.map { it.id }.toSet(), reversed.map { it.id }.toSet())
        assertEquals(forward.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
        assertEquals(forward.map { it.photoCount }.sorted(), reversed.map { it.photoCount }.sorted())
        assertEquals(forward.map { it.photoCount }.sorted(), shuffled.map { it.photoCount }.sorted())
        assertEquals(forward.map { it.id }.sorted(), shuffled.map { it.id }.sorted())
    }

    @Test
    fun `大量照片分组性能可接受`() {
        // 模拟真实规模：5768 张照片，分布在 30 个地点附近
        val photos = (1..5768).map { i ->
            val spot = i % 30
            mockPhoto(
                i.toLong(),
                31.0 + spot * 0.01 + (i % 17) * 0.00002,
                117.0 + spot * 0.01 + (i % 13) * 0.00002
            )
        }
        val start = System.currentTimeMillis()
        val result = grouper.groupByLocation(photos)
        val elapsed = System.currentTimeMillis() - start
        // 旧实现是 O(n·G)，这个规模下要几十秒；网格分桶后应在 1 秒内
        assertTrue("5768张照片分组应在1秒内完成，实际: ${elapsed}ms", elapsed < 1000)
        assertEquals(5768, result.sumOf { it.photoCount })
    }

    @Test
    fun `全是无位置照片返回空`() {
        val photos = listOf(
            mockPhoto(1, null, null),
            mockPhoto(2, null, null)
        )
        val result = grouper.groupByLocation(photos)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `零坐标照片正确处理`() {
        // 注意：Photo.hasLocation会过滤(0,0)坐标，所以用接近零的非零坐标测试
        val photos = listOf(
            mockPhoto(1, 0.00001, 0.00001),
            mockPhoto(2, 0.0001, 0.0001)
        )
        val result = grouper.groupByLocation(photos)
        assertEquals(1, result.size)
        assertEquals(2, result[0].photoCount)
    }
}
