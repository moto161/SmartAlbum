package com.example.smartalbum.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.example.smartalbum.data.Photo
import com.example.smartalbum.util.LocationEncryptor

/**
 * 照片数据库帮助类
 * 使用SQLite持久化存储照片扫描结果，进程被杀后可快速恢复
 *
 * ⚠️ 迁移策略（2026-10-04 修复 P1 丢数据 bug）：
 * 旧版 onUpgrade 无条件 `DROP TABLE` + onCreate，导致每次升版清空
 * 全部照片索引 / 位置名缓存 / 最后扫描时间。现改为**非破坏性增量迁移**：
 * 缺表建表、缺列 ADD COLUMN、主键列缺失才重建表并把共有列数据拷回，
 * 任何路径都不丢用户数据。升版本机无需手动清数据。
 */
class PhotoDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "smart_album_photos.db"
        private const val DATABASE_VERSION = 3
        /** 单条 SQL 里 IN(...) 的分批大小，避开 SQLite 999 个宿主变量上限 */
        private const val ID_BATCH_SIZE = 500

        // 照片表
        private const val TABLE_PHOTOS = "photos"
        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_DATE_TAKEN = "date_taken"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_HAS_LOCATION = "has_location"

        // 位置名称缓存表
        private const val TABLE_LOCATION_NAMES = "location_names"
        private const val KEY_LOC_KEY = "loc_key"
        private const val KEY_LOC_NAME = "loc_name"

        // 元数据表
        private const val TABLE_METADATA = "metadata"
        private const val KEY_META_KEY = "meta_key"
        private const val KEY_META_VALUE = "meta_value"
        private const val META_LAST_SCAN_TIME = "last_scan_time"
        /** 上次全量重扫时的 APP 版本号，用于升级后强制重扫一次 */
        private const val META_LAST_FULL_SCAN_VERSION = "last_full_scan_version"

        // 建表 SQL（IF NOT EXISTS，onCreate 与 onUpgrade 共用，重复执行安全）
        private const val SQL_CREATE_PHOTOS = """
            CREATE TABLE IF NOT EXISTS $TABLE_PHOTOS (
                $KEY_ID INTEGER PRIMARY KEY,
                $KEY_URI TEXT NOT NULL,
                $KEY_DISPLAY_NAME TEXT,
                $KEY_DATE_TAKEN INTEGER,
                $KEY_LATITUDE TEXT,
                $KEY_LONGITUDE TEXT,
                $KEY_HAS_LOCATION INTEGER DEFAULT 0
            )
        """

        private const val SQL_CREATE_LOCATION_NAMES = """
            CREATE TABLE IF NOT EXISTS $TABLE_LOCATION_NAMES (
                $KEY_LOC_KEY TEXT PRIMARY KEY,
                $KEY_LOC_NAME TEXT
            )
        """

        private const val SQL_CREATE_METADATA = """
            CREATE TABLE IF NOT EXISTS $TABLE_METADATA (
                $KEY_META_KEY TEXT PRIMARY KEY,
                $KEY_META_VALUE TEXT
            )
        """

        private const val SQL_INDEX_HAS_LOCATION =
            "CREATE INDEX IF NOT EXISTS idx_has_location ON $TABLE_PHOTOS($KEY_HAS_LOCATION)"
        private const val SQL_INDEX_DATE_TAKEN =
            "CREATE INDEX IF NOT EXISTS idx_date_taken ON $TABLE_PHOTOS($KEY_DATE_TAKEN)"

        /**
         * 表结构规格：迁移时据此判断缺哪些列。
         * columns 的值是 ALTER TABLE ADD COLUMN 用的类型定义，
         * **必须带 DEFAULT**（SQLite 不允许 ADD COLUMN 一个无默认值的 NOT NULL 列），
         * 也因此新增列的旧行会拿到默认值而不是 NULL。
         */
        private data class TableSpec(
            val name: String,
            val createSql: String,
            val primaryKeys: Set<String>,
            val columns: Map<String, String>
        )

        private val SPEC_PHOTOS = TableSpec(
            name = TABLE_PHOTOS,
            createSql = SQL_CREATE_PHOTOS,
            primaryKeys = setOf(KEY_ID),
            columns = mapOf(
                KEY_ID to "INTEGER",
                KEY_URI to "TEXT NOT NULL DEFAULT ''",
                KEY_DISPLAY_NAME to "TEXT DEFAULT ''",
                KEY_DATE_TAKEN to "INTEGER DEFAULT 0",
                KEY_LATITUDE to "TEXT DEFAULT ''",
                KEY_LONGITUDE to "TEXT DEFAULT ''",
                KEY_HAS_LOCATION to "INTEGER DEFAULT 0"
            )
        )

        private val SPEC_LOCATION_NAMES = TableSpec(
            name = TABLE_LOCATION_NAMES,
            createSql = SQL_CREATE_LOCATION_NAMES,
            primaryKeys = setOf(KEY_LOC_KEY),
            columns = mapOf(
                KEY_LOC_KEY to "TEXT",
                KEY_LOC_NAME to "TEXT DEFAULT ''"
            )
        )

        private val SPEC_METADATA = TableSpec(
            name = TABLE_METADATA,
            createSql = SQL_CREATE_METADATA,
            primaryKeys = setOf(KEY_META_KEY),
            columns = mapOf(
                KEY_META_KEY to "TEXT",
                KEY_META_VALUE to "TEXT DEFAULT ''"
            )
        )

        @Volatile
        private var instance: PhotoDatabaseHelper? = null

        fun getInstance(context: Context): PhotoDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: PhotoDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_PHOTOS)
        db.execSQL(SQL_INDEX_HAS_LOCATION)
        db.execSQL(SQL_INDEX_DATE_TAKEN)
        db.execSQL(SQL_CREATE_LOCATION_NAMES)
        db.execSQL(SQL_CREATE_METADATA)
    }

    /**
     * 非破坏性增量迁移：**任何情况下都不丢用户数据**。
     * 1) 表不存在 → 直接建；
     * 2) 表存在但缺非主键列 → ALTER TABLE ADD COLUMN（带默认值，旧行不受影响）；
     * 3) 表存在但缺主键列（SQLite 无法 ADD COLUMN 加主键）→ 旧表改名、按新结构建表、
     *    把两表共有列的数据 INSERT SELECT 拷回、最后删掉临时旧表。
     * 全程包在事务里，任一步失败则整体回滚，不会留下半迁移状态。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            migrateTable(db, SPEC_PHOTOS)
            migrateTable(db, SPEC_LOCATION_NAMES)
            migrateTable(db, SPEC_METADATA)
            db.execSQL(SQL_INDEX_HAS_LOCATION)
            db.execSQL(SQL_INDEX_DATE_TAKEN)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 用户装了更旧的版本：不删数据，按当前结构尽力补齐列
        db.beginTransaction()
        try {
            migrateTable(db, SPEC_PHOTOS)
            migrateTable(db, SPEC_LOCATION_NAMES)
            migrateTable(db, SPEC_METADATA)
            db.execSQL(SQL_INDEX_HAS_LOCATION)
            db.execSQL(SQL_INDEX_DATE_TAKEN)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun migrateTable(db: SQLiteDatabase, spec: TableSpec) {
        if (!tableExists(db, spec.name)) {
            db.execSQL(spec.createSql)
            return
        }
        val existing = columnsOf(db, spec.name)
        val missing = spec.columns.keys - existing
        if (missing.isEmpty()) return

        val missingPrimaryKeys = missing intersect spec.primaryKeys
        if (missingPrimaryKeys.isEmpty()) {
            // 缺的都不是主键 → 逐列 ADD COLUMN
            missing.forEach { column ->
                db.execSQL("ALTER TABLE ${spec.name} ADD COLUMN $column ${spec.columns.getValue(column)}")
            }
        } else {
            // 缺主键列 → 重建表并拷回共有列数据（临时旧表，最后删除）
            val tmp = "${spec.name}_pre_migration"
            db.execSQL("DROP TABLE IF EXISTS $tmp")
            db.execSQL("ALTER TABLE ${spec.name} RENAME TO $tmp")
            db.execSQL(spec.createSql)
            val common = (existing intersect spec.columns.keys)
            if (common.isNotEmpty()) {
                val cols = common.joinToString(", ")
                db.execSQL("INSERT INTO ${spec.name} ($cols) SELECT $cols FROM $tmp")
            }
            db.execSQL("DROP TABLE $tmp")
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        )?.use { it.moveToFirst() } == true

    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        return columns
    }

    // ==================== 照片表操作 ====================

    /**
     * 批量保存照片（事务，高性能）
     */
    fun savePhotos(photos: List<Photo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_PHOTOS, null, null)
            val values = ContentValues()
            for (photo in photos) {
                values.clear()
                values.put(KEY_ID, photo.id)
                values.put(KEY_URI, photo.uri.toString())
                values.put(KEY_DISPLAY_NAME, photo.displayName)
                values.put(KEY_DATE_TAKEN, photo.dateTaken)
                if (photo.hasLocation && photo.latitude != null && photo.longitude != null) {
                    values.put(KEY_LATITUDE, LocationEncryptor.encryptValue(photo.latitude))
                    values.put(KEY_LONGITUDE, LocationEncryptor.encryptValue(photo.longitude))
                } else {
                    values.put(KEY_LATITUDE, "")
                    values.put(KEY_LONGITUDE, "")
                }
                values.put(KEY_HAS_LOCATION, if (photo.hasLocation) 1 else 0)
                db.insertWithOnConflict(TABLE_PHOTOS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 加载所有照片
     */
    fun loadAllPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val db = readableDatabase
        db.query(
            TABLE_PHOTOS, null, null, null, null, null,
            "$KEY_DATE_TAKEN DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val latStr = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LATITUDE))
                val lonStr = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LONGITUDE))
                val lat = if (latStr.isNullOrEmpty()) null else LocationEncryptor.decryptValue(latStr)
                val lon = if (lonStr.isNullOrEmpty()) null else LocationEncryptor.decryptValue(lonStr)
                photos.add(
                    Photo(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID)),
                        uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(KEY_URI))),
                        displayName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DISPLAY_NAME)),
                        dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DATE_TAKEN)),
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
        }
        return photos
    }

    /**
     * 按 id 集合分批查询（2026-10-10 新增）
     *
     * 位置详情页冷启动（内存缓存为空）原来走 loadAllPhotos()：读出全部 5768 行、
     * 做 2×5768 次 AES 解密，只为筛出几十张。这里改成按 id 分批 IN 查询
     * （每批 500，避开 SQLite 999 变量上限），只解密真正要显示的那些。
     */
    fun loadPhotosByIds(ids: Collection<Long>): List<Photo> {
        if (ids.isEmpty()) return emptyList()
        val db = readableDatabase
        val result = ArrayList<Photo>(ids.size)
        val idList = ids.toList()
        var offset = 0
        while (offset < idList.size) {
            val end = minOf(offset + ID_BATCH_SIZE, idList.size)
            val batch = idList.subList(offset, end)
            val placeholders = batch.joinToString(",") { "?" }
            val args = Array(batch.size) { batch[it].toString() }
            db.query(
                TABLE_PHOTOS, null,
                "$KEY_ID IN ($placeholders)", args,
                null, null, "$KEY_DATE_TAKEN DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val latStr = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LATITUDE))
                    val lonStr = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LONGITUDE))
                    result.add(
                        Photo(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID)),
                            uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(KEY_URI))),
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DISPLAY_NAME)),
                            dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DATE_TAKEN)),
                            latitude = if (latStr.isNullOrEmpty()) null else LocationEncryptor.decryptValue(latStr),
                            longitude = if (lonStr.isNullOrEmpty()) null else LocationEncryptor.decryptValue(lonStr)
                        )
                    )
                }
            }
            offset = end
        }
        return result
    }

    /**
     * 获取缓存的照片ID集合
     */
    fun getCachedPhotoIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        val db = readableDatabase
        db.query(TABLE_PHOTOS, arrayOf(KEY_ID), null, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0))
            }
        }
        return ids
    }

    /**
     * 获取有位置信息的照片数量
     */
    fun getPhotoCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_PHOTOS", null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    /**
     * 检查数据库是否有数据
     */
    fun hasData(): Boolean = getPhotoCount() > 0

    /**
     * 清空数据库
     */
    fun clear() {
        writableDatabase.delete(TABLE_PHOTOS, null, null)
        writableDatabase.delete(TABLE_LOCATION_NAMES, null, null)
        writableDatabase.delete(TABLE_METADATA, null, null)
    }

    // ==================== 位置名称缓存操作 ====================

    /**
     * 保存位置名称
     */
    /**
     * 地名缓存的 key。
     *
     * 2026-10-10：原来直接拿 `${latitude}_$longitude`（浮点全精度，如
     * `31.82000000000001_117.22999999999998`）。位置组的中心是成员坐标的平均值，
     * 每扫一次照片数一变中心就漂移一点点，key 跟着变 ⇒ **缓存基本永不命中**，
     * 每次刷新都重新打 Geocoder。按 5 位小数（≈1 米）量化后同一个地点就是同一个 key。
     * Locale.US 固定小数点分隔符，避免部分语言环境下输出逗号。
     */
    private fun locationKey(latitude: Double, longitude: Double): String =
        String.format(java.util.Locale.US, "%.5f_%.5f", latitude, longitude)

    fun saveLocationName(latitude: Double, longitude: Double, name: String) {
        val key = locationKey(latitude, longitude)
        val db = writableDatabase
        val values = ContentValues().apply {
            put(KEY_LOC_KEY, key)
            put(KEY_LOC_NAME, name)
        }
        db.insertWithOnConflict(TABLE_LOCATION_NAMES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 获取位置名称
     */
    fun getLocationName(latitude: Double, longitude: Double): String? {
        val key = locationKey(latitude, longitude)
        val db = readableDatabase
        db.query(
            TABLE_LOCATION_NAMES, arrayOf(KEY_LOC_NAME),
            "$KEY_LOC_KEY = ?", arrayOf(key), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    /**
     * 获取所有位置名称（用于导出缓存）
     */
    fun getAllLocationNames(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val db = readableDatabase
        db.query(TABLE_LOCATION_NAMES, null, null, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LOC_KEY))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_LOC_NAME))
                result[key] = name
            }
        }
        return result
    }

    // ==================== 元数据操作 ====================

    /**
     * 获取最后扫描时间
     */
    fun getLastScanTime(): Long {
        val db = readableDatabase
        db.query(
            TABLE_METADATA, arrayOf(KEY_META_VALUE),
            "$KEY_META_KEY = ?", arrayOf(META_LAST_SCAN_TIME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)?.toLongOrNull() ?: 0L
            }
        }
        return 0L
    }

    /**
     * 设置最后扫描时间
     */
    fun setLastScanTime(time: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(KEY_META_KEY, META_LAST_SCAN_TIME)
            put(KEY_META_VALUE, time.toString())
        }
        db.insertWithOnConflict(TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 上次做「全量重扫」时的 APP 版本号（2026-10-10 新增）
     *
     * 位置提取逻辑一旦修过（例如 5.13.90 修了 Android 10+ 的 EXIF 读取），
     * 已经存进库的旧结果不会自己变好——增量扫描只处理新增/变更的照片。
     * 所以升级后要强制全量重扫一次，否则用户永远看不到修复效果。
     */
    fun getLastFullScanVersion(): Int {
        val db = readableDatabase
        db.query(
            TABLE_METADATA, arrayOf(KEY_META_VALUE),
            "$KEY_META_KEY = ?", arrayOf(META_LAST_FULL_SCAN_VERSION), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)?.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    fun setLastFullScanVersion(versionCode: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(KEY_META_KEY, META_LAST_FULL_SCAN_VERSION)
            put(KEY_META_VALUE, versionCode.toString())
        }
        db.insertWithOnConflict(TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
