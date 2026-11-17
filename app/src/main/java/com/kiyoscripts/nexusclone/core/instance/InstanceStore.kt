package com.kiyoscripts.nexusclone.core.instance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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
        private const val DB_VERSION = 2

        const val TABLE = "instances"

        private const val COL_ID = "id"
        private const val COL_USER_ID = "user_id"
        private const val COL_PKG = "package_name"
        private const val COL_LABEL = "label"
        private const val COL_STATE = "state"
        private const val COL_CREATED = "created_at"
        private const val COL_LAST_LAUNCH = "last_launch_at"
        private const val COL_DATA_SIZE = "data_size"
        private const val COL_LAST_SIZE = "last_size_at"
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
                $COL_LAST_SIZE INTEGER NOT NULL DEFAULT 0,
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

    /**
     * Additive migrations only.
     *
     * A destructive drop is forbidden here. The virtual users, the apks installed into
     * them and their data directories live on disk in the BlackBox root and outlive this
     * table, so dropping the table would delete every row while the on-disk state stays
     * behind: clones that can never be reached again. Add columns with `ALTER TABLE` and
     * backfill them instead.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "onUpgrade $oldVersion -> $newVersion")
        when (oldVersion) {
            1 -> migrateV1toV2(db)
            // Future versions append their step here, e.g.
            // 2 -> db.execSQL("ALTER TABLE $TABLE ADD COLUMN ...")
            else -> Log.w(TAG, "no migration path from $oldVersion, schema left as is")
        }
    }

    /**
     * v1 -> v2: add the persisted size-walk stamp.
     *
     * The column is added only when it is absent. A device that installed a build which
     * already created the column (or a database restored from a newer backup) would
     * otherwise fail the ALTER with "duplicate column name", and a throw inside
     * onUpgrade leaves the helper in a state where every later open retries the same
     * failing migration. The check plus the catch keeps the upgrade idempotent.
     */
    private fun migrateV1toV2(db: SQLiteDatabase) {
        if (columnExists(db, TABLE, COL_LAST_SIZE)) {
            Log.i(TAG, "$COL_LAST_SIZE already present, skipping v1 -> v2")
            return
        }
        try {
            db.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COL_LAST_SIZE INTEGER NOT NULL DEFAULT 0"
            )
        } catch (e: Exception) {
            Log.w(TAG, "v1 -> v2 add $COL_LAST_SIZE failed: ${e.message}")
        }
    }

    /** True when [table] already has a column named [column]. */
    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return false
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return true
            }
        }
        return false
    }

    /**
     * Insert a new row.
     *
     * Returns the new row id, or `-1` when the insert failed. Failure is reported as `-1`
     * instead of an exception so callers have a single check to make: a real id is always
     * `>= 1` because the column is AUTOINCREMENT, so `-1` can never collide with a row.
     *
     * The unique key `(user_id, package_name)` is enforced with
     * [SQLiteDatabase.CONFLICT_ABORT]: a duplicate insert fails and is reported as `-1`
     * rather than silently replacing the existing row (which would hand back a new id and
     * destroy the previous row's data).
     */
    fun insert(instance: CloneInstance): Long {
        val cv = ContentValues().apply {
            put(COL_USER_ID, instance.userId)
            put(COL_PKG, instance.packageName)
            put(COL_LABEL, instance.label)
            put(COL_STATE, instance.state.name)
            put(COL_CREATED, instance.createdAt)
            put(COL_LAST_LAUNCH, instance.lastLaunchAt)
            put(COL_DATA_SIZE, instance.dataSizeBytes)
            put(COL_LAST_SIZE, instance.lastSizeAt)
            put(COL_QUOTA, instance.quotaBytes)
            put(COL_PREVIEW, instance.previewPath)
            put(COL_PREVIEW_AT, instance.previewUpdatedAt)
            put(COL_KEEPALIVE, instance.keepAliveLevel)
            put(COL_PROXY, instance.proxyId)
            put(COL_IDENTITY, instance.identityId)
            put(COL_FROZEN, if (instance.frozen) 1 else 0)
        }
        return try {
            writableDatabase.insertWithOnConflict(
                TABLE, null, cv, SQLiteDatabase.CONFLICT_ABORT
            )
        } catch (e: SQLiteConstraintException) {
            Log.w(TAG, "insert ${instance.key} rejected: ${e.message}")
            -1L
        }
    }

    fun update(instance: CloneInstance): Int {
        val cv = ContentValues().apply {
            put(COL_LABEL, instance.label)
            put(COL_STATE, instance.state.name)
            put(COL_LAST_LAUNCH, instance.lastLaunchAt)
            put(COL_DATA_SIZE, instance.dataSizeBytes)
            put(COL_LAST_SIZE, instance.lastSizeAt)
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

    /**
     * Delete every row in [state] and report how many were removed.
     *
     * Used to sweep DEAD rows. A DEAD row is a clone whose uninstall succeeded but whose
     * delete did not, so it must be removable in bulk without touching the live rows.
     */
    fun deleteByState(state: InstanceState): Int =
        writableDatabase.delete(TABLE, "$COL_STATE = ?", arrayOf(state.name))

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
                        lastSizeAt = c.getLong(c.getColumnIndexOrThrow(COL_LAST_SIZE)),
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
