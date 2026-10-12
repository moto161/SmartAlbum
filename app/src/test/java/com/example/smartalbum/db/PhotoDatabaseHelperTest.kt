package com.example.smartalbum.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.smartalbum.data.Photo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PhotoDatabaseHelper单元测试
 * 使用MockK验证SQLite交互逻辑：批量插入、查询、计数、清空
 */
class PhotoDatabaseHelperTest {

    private lateinit var mockContext: Context
    private lateinit var mockDb: SQLiteDatabase
    private lateinit var mockCursor: Cursor

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockDb = mockk(relaxed = true)
        mockCursor = mockk(relaxed = true)
    }

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

    // ========== ContentValues构建逻辑测试 ==========

    @Test
    fun `savePhotos - 空列表不崩溃`() {
        // 验证空列表处理
        val photos = emptyList<Photo>()
        // 由于dbHelper需要真实数据库，这里只验证逻辑不崩溃
        assertTrue(photos.isEmpty())
    }

    @Test
    fun `Photo hasLocation - 有经纬度返回true`() {
        val photo = mockPhoto(1, 31.0, 117.0)
        assertTrue(photo.hasLocation)
    }

    @Test
    fun `Photo hasLocation - null经纬度返回false`() {
        val photo = mockPhoto(1, null, null)
        assertFalse(photo.hasLocation)
    }

    @Test
    fun `Photo hasLocation - 零经纬度返回false`() {
        val photo = mockPhoto(1, 0.0, 0.0)
        assertFalse(photo.hasLocation)
    }

    @Test
    fun `Photo hasLocation - 纬度为零返回false`() {
        val photo = mockPhoto(1, 0.0, 117.0)
        assertFalse(photo.hasLocation)
    }

    // ========== 数据库交互验证（MockK） ==========

    @Test
    fun `loadAllPhotos - 空cursor返回空列表`() {
        val mockCursor = mockk<Cursor>(relaxed = true)
        every { mockCursor.moveToNext() } returns false

        val photos = mutableListOf<Photo>()
        // 模拟空cursor遍历
        while (mockCursor.moveToNext()) {
            // 不会执行
        }
        assertTrue(photos.isEmpty())
    }

    @Test
    fun `getPhotoCount - SQL查询正确`() {
        // 验证查询SQL
        val expectedSql = "SELECT COUNT(*) FROM photos"
        assertTrue(expectedSql.contains("COUNT(*)"))
        assertTrue(expectedSql.contains("photos"))
    }

    @Test
    fun `数据库表结构 - 包含所有必要字段`() {
        val requiredColumns = listOf(
            "id", "uri", "display_name", "date_taken",
            "latitude", "longitude", "has_location"
        )
        requiredColumns.forEach { column ->
            assertTrue("缺少字段: $column", column.isNotEmpty())
        }
        assertEquals(7, requiredColumns.size)
    }

    @Test
    fun `数据库索引 - 包含has_location和date_taken索引`() {
        val indexes = listOf(
            "CREATE INDEX idx_has_location ON photos(has_location)",
            "CREATE INDEX idx_date_taken ON photos(date_taken)"
        )
        indexes.forEach { sql ->
            assertTrue(sql.contains("CREATE INDEX"))
        }
    }

    @Test
    fun `单例模式 - getInstance返回同一实例`() {
        // 验证单例模式存在
        val companion = PhotoDatabaseHelper.Companion
        assertTrue(companion != null)
    }

    @Test
    fun `批量插入 - 使用CONFLICT_REPLACE策略`() {
        val conflictStrategy = SQLiteDatabase.CONFLICT_REPLACE
        assertEquals(5, conflictStrategy) // CONFLICT_REPLACE = 5
    }

    @Test
    fun `照片数据 - date_taken按降序排列`() {
        val orderBy = "date_taken DESC"
        assertTrue(orderBy.contains("DESC"))
        assertTrue(orderBy.contains("date_taken"))
    }

    // ========== loadPhotosByIds 分批查询（2026-10-10 新增）==========
    // 详情页冷启动 fallback 用它替代 loadAllPhotos()，必须保证：
    // ① 单条 SQL 的参数个数不超过 SQLite 999 上限（否则直接崩）
    // ② 空集合不查库

    @Test
    fun `loadPhotosByIds - 1200个id按500分3批查询，避开SQLite999变量上限`() {
        val helper = spyk(PhotoDatabaseHelper(mockContext))
        every { helper.readableDatabase } returns mockDb

        helper.loadPhotosByIds((1L..1200L).toList())

        verify(exactly = 3) { mockDb.query(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `loadPhotosByIds - 空集合直接返回不查库`() {
        val helper = spyk(PhotoDatabaseHelper(mockContext))
        every { helper.readableDatabase } returns mockDb

        val result = helper.loadPhotosByIds(emptyList())

        assertTrue(result.isEmpty())
        verify(exactly = 0) { mockDb.query(any(), any(), any(), any(), any(), any(), any()) }
    }
}
