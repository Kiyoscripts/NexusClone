package com.kiyoscripts.nexusclone.core.identity

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Persistence for [DeviceIdentity] profiles.
 *
 * Profiles are reusable: one identity can be bound to several clones, which is what you
 * want when an app allows multiple accounts but fingerprints the device.
 */
class IdentityStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val DB_NAME = "nexus_identity.db"
        private const val DB_VERSION = 2
        const val TABLE = "identities"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                brand TEXT NOT NULL,
                manufacturer TEXT NOT NULL,
                model TEXT NOT NULL,
                device TEXT NOT NULL,
                product TEXT NOT NULL,
                board TEXT NOT NULL,
                hardware TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                android_id TEXT NOT NULL,
                imei TEXT NOT NULL,
                meid TEXT NOT NULL,
                mac_wifi TEXT NOT NULL,
                serial TEXT NOT NULL,
                gsf_id TEXT NOT NULL,
                locale TEXT NOT NULL,
                timezone TEXT NOT NULL,
                density_dpi INTEGER NOT NULL,
                screen_width INTEGER NOT NULL,
                screen_height INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                sdk INTEGER NOT NULL DEFAULT 31,
                abi TEXT NOT NULL DEFAULT 'arm64-v8a',
                build_id TEXT NOT NULL DEFAULT '',
                last_used_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    /**
     * Additive migrations only.
     *
     * The previous version dropped the table and recreated it, which silently destroyed
     * every saved profile on a schema bump. Each column is added on its own and guarded,
     * so a partially applied upgrade can be re-run without failing.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumn(db, "sdk", "INTEGER NOT NULL DEFAULT 31")
            addColumn(db, "abi", "TEXT NOT NULL DEFAULT 'arm64-v8a'")
            addColumn(db, "build_id", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "last_used_at", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** ALTER TABLE ADD COLUMN, skipped when the column is already there. */
    private fun addColumn(db: SQLiteDatabase, column: String, definition: String) {
        if (columnExists(db, column)) return
        try {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $column $definition")
        } catch (e: Exception) {
            // A concurrent upgrade may have added it between the check and the ALTER.
        }
    }

    /** True when [column] is present in [TABLE], read from PRAGMA table_info. */
    private fun columnExists(db: SQLiteDatabase, column: String): Boolean {
        try {
            db.rawQuery("PRAGMA table_info($TABLE)", null).use { c ->
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (c.moveToNext()) {
                    if (c.getString(nameIndex) == column) return true
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    fun insert(identity: DeviceIdentity): Long {
        val cv = ContentValues().apply {
            put("name", identity.name)
            put("brand", identity.brand)
            put("manufacturer", identity.manufacturer)
            put("model", identity.model)
            put("device", identity.device)
            put("product", identity.product)
            put("board", identity.board)
            put("hardware", identity.hardware)
            put("fingerprint", identity.fingerprint)
            put("build_id", identity.buildId)
            put("sdk", identity.sdk)
            put("abi", identity.abi)
            put("android_id", identity.androidId)
            put("imei", identity.imei)
            put("meid", identity.meid)
            put("mac_wifi", identity.macWifi)
            put("serial", identity.serial)
            put("gsf_id", identity.gsfId)
            put("locale", identity.locale)
            put("timezone", identity.timezone)
            put("density_dpi", identity.densityDpi)
            put("screen_width", identity.screenWidth)
            put("screen_height", identity.screenHeight)
            put("created_at", identity.createdAt)
            put("last_used_at", identity.lastUsedAt)
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun update(identity: DeviceIdentity): Int {
        val cv = ContentValues().apply {
            put("name", identity.name)
            put("brand", identity.brand)
            put("manufacturer", identity.manufacturer)
            put("model", identity.model)
            put("device", identity.device)
            put("product", identity.product)
            put("board", identity.board)
            put("hardware", identity.hardware)
            put("fingerprint", identity.fingerprint)
            put("build_id", identity.buildId)
            put("sdk", identity.sdk)
            put("abi", identity.abi)
            put("android_id", identity.androidId)
            put("imei", identity.imei)
            put("meid", identity.meid)
            put("mac_wifi", identity.macWifi)
            put("serial", identity.serial)
            put("gsf_id", identity.gsfId)
            put("locale", identity.locale)
            put("timezone", identity.timezone)
            put("density_dpi", identity.densityDpi)
            put("screen_width", identity.screenWidth)
            put("screen_height", identity.screenHeight)
            put("last_used_at", identity.lastUsedAt)
        }
        return writableDatabase.update(
            TABLE, cv, "id = ?", arrayOf(identity.id.toString())
        )
    }

    fun delete(id: Long): Int =
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))

    fun all(): List<DeviceIdentity> {
        val out = ArrayList<DeviceIdentity>()
        readableDatabase.query(TABLE, null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) out.add(readRow(c))
        }
        return out
    }

    /**
     * One profile by row id.
     *
     * Uses a real WHERE clause instead of scanning [all], and shares [readRow] with it so
     * the two paths cannot drift apart as columns are added.
     */
    fun byId(id: Long): DeviceIdentity? {
        readableDatabase.query(
            TABLE, null, "id = ?", arrayOf(id.toString()), null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return readRow(c)
        }
        return null
    }

    /** Record that [id] was just bound to a clone. */
    fun markUsed(id: Long) {
        val cv = ContentValues().apply { put("last_used_at", System.currentTimeMillis()) }
        writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id.toString()))
    }

    /** Single row mapper, the only place column names are read. */
    private fun readRow(c: android.database.Cursor): DeviceIdentity = DeviceIdentity(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        brand = c.getString(c.getColumnIndexOrThrow("brand")),
        manufacturer = c.getString(c.getColumnIndexOrThrow("manufacturer")),
        model = c.getString(c.getColumnIndexOrThrow("model")),
        device = c.getString(c.getColumnIndexOrThrow("device")),
        product = c.getString(c.getColumnIndexOrThrow("product")),
        board = c.getString(c.getColumnIndexOrThrow("board")),
        hardware = c.getString(c.getColumnIndexOrThrow("hardware")),
        fingerprint = c.getString(c.getColumnIndexOrThrow("fingerprint")),
        buildId = c.getString(c.getColumnIndexOrThrow("build_id")),
        sdk = c.getInt(c.getColumnIndexOrThrow("sdk")),
        abi = c.getString(c.getColumnIndexOrThrow("abi")),
        androidId = c.getString(c.getColumnIndexOrThrow("android_id")),
        imei = c.getString(c.getColumnIndexOrThrow("imei")),
        meid = c.getString(c.getColumnIndexOrThrow("meid")),
        macWifi = c.getString(c.getColumnIndexOrThrow("mac_wifi")),
        serial = c.getString(c.getColumnIndexOrThrow("serial")),
        gsfId = c.getString(c.getColumnIndexOrThrow("gsf_id")),
        locale = c.getString(c.getColumnIndexOrThrow("locale")),
        timezone = c.getString(c.getColumnIndexOrThrow("timezone")),
        densityDpi = c.getInt(c.getColumnIndexOrThrow("density_dpi")),
        screenWidth = c.getInt(c.getColumnIndexOrThrow("screen_width")),
        screenHeight = c.getInt(c.getColumnIndexOrThrow("screen_height")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"))
    )
}
