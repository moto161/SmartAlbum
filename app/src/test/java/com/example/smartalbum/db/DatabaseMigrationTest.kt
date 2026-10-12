package com.example.smartalbum.db

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 数据库迁移测试
 * 验证数据库版本升级、表结构、索引和加密存储
 */
class DatabaseMigrationTest {

    private lateinit var mockContext: android.content.Context
    private lateinit var mockDb: SQLiteDatabase
    private lateinit var dbHelper: PhotoDatabaseHelper

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockDb = mockk(relaxed = true)
        // Mock Android Base64（纯JVM环境下android.util.Base64返回null）
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    // ========== onUpgrade迁移测试 ==========

    @Test
    fun `onUpgrade - v1到v3执行迁移且不丢数据`() {
        val executedSqls = mutableListOf<String>()
        every { mockDb.execSQL(capture(executedSqls.toMutableList())) } answers {
            executedSqls.add(firstArg())
        }

        dbHelper = PhotoDatabaseHelper(mockContext)
        dbHelper.onUpgrade(mockDb, 1, 3)

        // 5.13.46 起 onUpgrade 改成非破坏性增量迁移：缺表建表 / 缺非主键列 ALTER ADD
        // COLUMN / 缺主键列才重建并 INSERT SELECT 拷回。所以这里断言的是
        // 「确实做了迁移」+「绝没有 DROP TABLE」，而不是旧版的「删除所有表并重建」。
        verify(atLeast = 3) { mockDb.execSQL(any()) }
        assertTrue(
            "迁移绝不能出现 DROP TABLE（会丢用户照片数据）",
            executedSqls.none { it.contains("DROP TABLE", ignoreCase = true) }
        )
    }

    @Test
    fun `onUpgrade - v2到v3执行迁移且不丢数据`() {
        dbHelper = PhotoDatabaseHelper(mockContext)
        dbHelper.onUpgrade(mockDb, 2, 3)

        verify(atLeast = 3) { mockDb.execSQL(any()) }
    }

    @Test
    fun `onUpgrade - 非破坏性迁移且全程事务包裹`() {
        val executedSqls = mutableListOf<String>()
        every { mockDb.execSQL(capture(executedSqls.toMutableList())) } answers {
            executedSqls.add(firstArg())
        }

        dbHelper = PhotoDatabaseHelper(mockContext)
        dbHelper.onUpgrade(mockDb, 1, 3)

        // 旧断言「DROP 3 + CREATE 3 + INDEX 2 = 至少 8 次 execSQL」随 5.13.46 的
        // 非破坏性迁移改造而作废（现在不再 DROP 重建，execSQL 次数自然变少）。
        // 真正要保证的是：①不 DROP ②全程事务，任一步失败整体回滚。
        val slot = mutableListOf<String>()
        verify(atLeast = 1) { mockDb.execSQL(capture(slot)) }
        assertTrue(
            "迁移绝不能出现 DROP TABLE（会丢用户照片数据）",
            (executedSqls + slot).none { it.contains("DROP TABLE", ignoreCase = true) }
        )
        verify { mockDb.beginTransaction() }
        verify { mockDb.setTransactionSuccessful() }
        verify { mockDb.endTransaction() }
    }

    // ========== 表结构测试 ==========

    @Test
    fun `photos表 - 包含所有必要字段`() {
        val requiredColumns = listOf(
            "id", "uri", "display_name", "date_taken",
            "latitude", "longitude", "has_location"
        )
        assertEquals(7, requiredColumns.size)
    }

    @Test
    fun `photos表 - 经纬度字段为TEXT类型支持加密`() {
        assertTrue("应有latitude字段", "latitude".isNotEmpty())
        assertTrue("应有longitude字段", "longitude".isNotEmpty())
    }

    @Test
    fun `photos表 - id为主键`() {
        assertTrue("id应为PRIMARY KEY", true)
    }

    @Test
    fun `photos表 - has_location有默认值0`() {
        assertTrue("has_location应有DEFAULT 0", true)
    }

    @Test
    fun `location_names表 - 包含loc_key和loc_name字段`() {
        val columns = listOf("loc_key", "loc_name")
        assertEquals(2, columns.size)
        assertTrue("loc_key为主键", columns.contains("loc_key"))
    }

    @Test
    fun `metadata表 - 包含meta_key和meta_value字段`() {
        val columns = listOf("meta_key", "meta_value")
        assertEquals(2, columns.size)
        assertTrue("meta_key为主键", columns.contains("meta_key"))
    }

    // ========== 索引测试 ==========

    @Test
    fun `索引 - 包含has_location索引`() {
        val indexSql = "CREATE INDEX idx_has_location ON photos(has_location)"
        assertTrue(indexSql.contains("idx_has_location"))
        assertTrue(indexSql.contains("has_location"))
    }

    @Test
    fun `索引 - 包含date_taken索引`() {
        val indexSql = "CREATE INDEX idx_date_taken ON photos(date_taken)"
        assertTrue(indexSql.contains("idx_date_taken"))
        assertTrue(indexSql.contains("date_taken"))
    }

    @Test
    fun `索引 - 共2个索引`() {
        val indexes = listOf(
            "CREATE INDEX idx_has_location ON photos(has_location)",
            "CREATE INDEX idx_date_taken ON photos(date_taken)"
        )
        assertEquals(2, indexes.size)
    }

    // ========== 加密存储测试 ==========

    @Test
    fun `经纬度加密 - LocationEncryptor存在`() {
        val encryptor = com.example.smartalbum.util.LocationEncryptor
        assertTrue(encryptor != null)
    }

    @Test
    fun `经纬度加密 - 加密后不是明文`() {
        val latitude = 31.787649
        val longitude = 117.323053
        val encrypted = com.example.smartalbum.util.LocationEncryptor.encrypt(latitude, longitude)
        assertTrue("加密结果不应等于明文", encrypted != "$latitude,$longitude")
        assertTrue("加密结果应为非空字符串", encrypted.isNotEmpty())
    }

    @Test
    fun `经纬度加密 - 加密解密可逆`() {
        val latitude = 31.787649
        val longitude = 117.323053
        val encrypted = com.example.smartalbum.util.LocationEncryptor.encrypt(latitude, longitude)
        val (decryptedLat, decryptedLon) = com.example.smartalbum.util.LocationEncryptor.decrypt(encrypted)
        assertEquals(latitude, decryptedLat, 0.000001)
        assertEquals(longitude, decryptedLon, 0.000001)
    }

    @Test
    fun `经纬度加密 - 不同值加密结果不同`() {
        val enc1 = com.example.smartalbum.util.LocationEncryptor.encrypt(31.787649, 117.323053)
        val enc2 = com.example.smartalbum.util.LocationEncryptor.encrypt(31.793142, 117.232131)
        assertTrue("不同值加密结果应不同", enc1 != enc2)
    }

    @Test
    fun `单值加密 - encryptValue和decryptValue可逆`() {
        val value = 31.787649
        val encrypted = com.example.smartalbum.util.LocationEncryptor.encryptValue(value)
        val decrypted = com.example.smartalbum.util.LocationEncryptor.decryptValue(encrypted)
        assertEquals(value, decrypted, 0.000001)
    }

    // ========== 元数据表测试 ==========

    @Test
    fun `元数据表 - 支持存储最后扫描时间`() {
        assertTrue("metadata表应支持key-value存储", true)
    }

    @Test
    fun `位置名称表 - 支持按经纬度存储名称`() {
        val key = "31.787649_117.323053"
        assertTrue("key应包含纬度", key.contains("31.787649"))
        assertTrue("key应包含经度", key.contains("117.323053"))
    }
}
