package com.kiyoscripts.nexusclone.core.instance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite store for clone instances.
 *
 * One table, one row per clone. All writes go through [InstanceManager]; this class is
 * deliberately dumb so the state machine stays in one place.
 */
class InstanceStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val TAG = "InstanceStore"
        private const val DB_NAME = "nexus_instances.db"
        private const val DB_VERSION = 1

        const val TABLE = "instances"

        private const val COL_ID = "id"
        private const val COL_USER_ID = "user_id"
        private const val COL_PKG = "package_name"
        private const val COL_LABEL = "label"
        private const val COL_STATE = "state"
        private const val COL_CREATED = "created_at"
        private const val COL_LAST_LAUNCH = "last_launch_at"
        private const val COL_DATA_SIZE = "data_size"
        private const val COL_QUOTA = "quota_bytes"
        private const val COL_PREVIEW = "preview_path"
        private const val COL_PREVIEW_AT = "preview_updated_at"
        private const val COL_KEEPALIVE = "keep_alive_level"
        private const val COL_PROXY = "proxy_id"
        private const val COL_IDENTITY = "identity_id"
        private const val COL_FROZEN = "frozen"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_USER_ID INTEGER NOT NULL,
                $COL_PKG TEXT NOT NULL,
                $COL_LABEL TEXT NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_CREATED INTEGER NOT NULL,
                $COL_LAST_LAUNCH INTEGER NOT NULL DEFAULT 0,
                $COL_DATA_SIZE INTEGER NOT NULL DEFAULT 0,
                $COL_QUOTA INTEGER NOT NULL DEFAULT 0,
                $COL_PREVIEW TEXT,
                $COL_PREVIEW_AT INTEGER NOT NULL DEFAULT 0,
                $COL_KEEPALIVE INTEGER NOT NULL DEFAULT 0,
                $COL_PROXY INTEGER,
                $COL_IDENTITY INTEGER,
                $COL_FROZEN INTEGER NOT NULL DEFAULT 0,
                UNIQUE ($COL_USER_ID, $COL_PKG)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_state ON $TABLE ($COL_STATE)")
        db.execSQL("CREATE INDEX idx_pkg ON $TABLE ($COL_PKG)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "onUpgrade $oldVersion -> $newVersion, recreating")
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(instance: CloneInstance): Long {
        val cv = ContentValues().apply {
            put(COL_USER_ID, instance.userId)
            put(COL_PKG, instance.packageName)
            put(COL_LABEL, instance.label)
            put(COL_STATE, instance.state.name)
            put(COL_CREATED, instance.createdAt)
            put(COL_LAST_LAUNCH, instance.lastLaunchAt)
            put(COL_DATA_SIZE, instance.dataSizeBytes)
            put(COL_QUOTA, instance.quotaBytes)
            put(COL_PREVIEW, instance.previewPath)
            put(COL_PREVIEW_AT, instance.previewUpdatedAt)
            put(COL_KEEPALIVE, instance.keepAliveLevel)
            put(COL_PROXY, instance.proxyId)
            put(COL_IDENTITY, instance.identityId)
            put(COL_FROZEN, if (instance.frozen) 1 else 0)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun update(instance: CloneInstance): Int {
        val cv = ContentValues().apply {
            put(COL_LABEL, instance.label)
            put(COL_STATE, instance.state.name)
            put(COL_LAST_LAUNCH, instance.lastLaunchAt)
            put(COL_DATA_SIZE, instance.dataSizeBytes)
            put(COL_QUOTA, instance.quotaBytes)
            put(COL_PREVIEW, instance.previewPath)
            put(COL_PREVIEW_AT, instance.previewUpdatedAt)
            put(COL_KEEPALIVE, instance.keepAliveLevel)
            put(COL_PROXY, instance.proxyId)
            put(COL_IDENTITY, instance.identityId)
            put(COL_FROZEN, if (instance.frozen) 1 else 0)
        }
        return writableDatabase.update(
            TABLE, cv, "$COL_ID = ?", arrayOf(instance.id.toString())
        )
    }

    fun delete(id: Long): Int =
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))

    fun deleteByUser(userId: Int, packageName: String): Int =
        writableDatabase.delete(
            TABLE, "$COL_USER_ID = ? AND $COL_PKG = ?",
            arrayOf(userId.toString(), packageName)
        )

    fun all(): List<CloneInstance> = query(null, null)

    fun byPackage(packageName: String): List<CloneInstance> =
        query("$COL_PKG = ?", arrayOf(packageName))

    fun byId(id: Long): CloneInstance? =
        query("$COL_ID = ?", arrayOf(id.toString())).firstOrNull()

    fun byKey(userId: Int, packageName: String): CloneInstance? =
        query(
            "$COL_USER_ID = ? AND $COL_PKG = ?",
            arrayOf(userId.toString(), packageName)
        ).firstOrNull()

    fun byState(state: InstanceState): List<CloneInstance> =
        query("$COL_STATE = ?", arrayOf(state.name))

    /** Highest virtual user id currently allocated, or 0 when the store is empty. */
    fun maxUserId(): Int {
        readableDatabase.rawQuery(
            "SELECT MAX($COL_USER_ID) FROM $TABLE", null
        ).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
        }
    }

    private fun query(selection: String?, args: Array<String>?): List<CloneInstance> {
        val out = ArrayList<CloneInstance>()
        readableDatabase.query(
            TABLE, null, selection, args, null, null, "$COL_ID ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CloneInstance(
                        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        userId = c.getInt(c.getColumnIndexOrThrow(COL_USER_ID)),
                        packageName = c.getString(c.getColumnIndexOrThrow(COL_PKG)),
                        label = c.getString(c.getColumnIndexOrThrow(COL_LABEL)),
                        state = InstanceState.fromName(
                            c.getString(c.getColumnIndexOrThrow(COL_STATE))
                        ),
                        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED)),
                        lastLaunchAt = c.getLong(c.getColumnIndexOrThrow(COL_LAST_LAUNCH)),
                        dataSizeBytes = c.getLong(c.getColumnIndexOrThrow(COL_DATA_SIZE)),
                        quotaBytes = c.getLong(c.getColumnIndexOrThrow(COL_QUOTA)),
                        previewPath = c.getString(c.getColumnIndexOrThrow(COL_PREVIEW)),
                        previewUpdatedAt = c.getLong(c.getColumnIndexOrThrow(COL_PREVIEW_AT)),
                        keepAliveLevel = c.getInt(c.getColumnIndexOrThrow(COL_KEEPALIVE)),
                        proxyId = c.getLongOrNull(c.getColumnIndexOrThrow(COL_PROXY)),
                        identityId = c.getLongOrNull(c.getColumnIndexOrThrow(COL_IDENTITY)),
                        frozen = c.getInt(c.getColumnIndexOrThrow(COL_FROZEN)) != 0
                    )
                )
            }
        }
        return out
    }

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)
}
