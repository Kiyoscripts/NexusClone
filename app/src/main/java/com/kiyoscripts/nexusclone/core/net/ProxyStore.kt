package com.kiyoscripts.nexusclone.core.net

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A proxy profile bound to one or more clones.
 *
 * [type] is HTTP, SOCKS4 or SOCKS5. [username]/[password] are optional.
 *
 * [lastOkAt] is the wall clock time of the last successful reachability probe, or 0 when
 * the endpoint has never answered.
 */
data class ProxyProfile(
    val id: Long = 0L,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val enabled: Boolean = true,
    val lastOkAt: Long = 0L
) {
    fun toJavaProxy(): Proxy = Proxy(
        when (type) {
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.SOCKS4, ProxyType.SOCKS5 -> Proxy.Type.SOCKS
        },
        InetSocketAddress.createUnresolved(host, port)
    )

    val display: String
        get() = "${type.name.lowercase()}://$host:$port"
}

enum class ProxyType { HTTP, SOCKS4, SOCKS5 }

/**
 * Per-instance proxy resolution.
 *
 * Honest scope: this covers the Java networking stack only, meaning OkHttp and
 * HttpURLConnection, which is what the vast majority of apps use. It does not cover a
 * native connect() from a bundled .so, a raw java.net.Socket opened by the guest, or DNS
 * resolution performed below the Java layer. Apps that do their own networking in native
 * code will bypass this entirely.
 */
class ProxyStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val TAG = "ProxyStore"
        private const val DB_NAME = "nexus_proxy.db"
        private const val DB_VERSION = 2
        const val TABLE = "proxies"

        private const val COL_LAST_OK = "last_ok_at"

        /**
         * True when [table] already has [column].
         *
         * PRAGMA table_info is the portable way to ask SQLite this question, and ALTER
         * TABLE ADD COLUMN has no IF NOT EXISTS form, so a migration that must be safe to
         * re-run has to check first.
         */
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
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                username TEXT,
                password TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                $COL_LAST_OK INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    /**
     * Additive migration. A schema bump must never cost the user their saved proxies, so
     * this only ever adds columns; it never drops the table.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                if (!columnExists(db, TABLE, COL_LAST_OK)) {
                    db.execSQL(
                        "ALTER TABLE $TABLE ADD COLUMN $COL_LAST_OK INTEGER NOT NULL DEFAULT 0"
                    )
                }
            } catch (e: Exception) {
                // A failed ALTER leaves the table usable; the mapper falls back to 0 when
                // the column is absent.
                Log.w(TAG, "could not add $COL_LAST_OK: ${e.message}")
            }
        }
    }

    fun insert(p: ProxyProfile): Long {
        val cv = ContentValues().apply {
            put("name", p.name)
            put("type", p.type.name)
            put("host", p.host)
            put("port", p.port)
            put("username", p.username)
            put("password", p.password)
            put("enabled", if (p.enabled) 1 else 0)
            put(COL_LAST_OK, p.lastOkAt)
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun update(p: ProxyProfile): Int {
        val cv = ContentValues().apply {
            put("name", p.name)
            put("type", p.type.name)
            put("host", p.host)
            put("port", p.port)
            put("username", p.username)
            put("password", p.password)
            put("enabled", if (p.enabled) 1 else 0)
            put(COL_LAST_OK, p.lastOkAt)
        }
        return writableDatabase.update(TABLE, cv, "id = ?", arrayOf(p.id.toString()))
    }

    fun delete(id: Long): Int =
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))

    /**
     * Record a successful probe. Separate from [update] so the probe path does not have to
     * read the whole row back and write it again.
     */
    fun markOk(id: Long): Int {
        val cv = ContentValues().apply { put(COL_LAST_OK, System.currentTimeMillis()) }
        return writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id.toString()))
    }

    fun all(): List<ProxyProfile> {
        val out = ArrayList<ProxyProfile>()
        readableDatabase.query(TABLE, null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) out.add(readRow(c))
        }
        return out
    }

    /**
     * Single row by primary key.
     *
     * This used to be all().firstOrNull { it.id == id }, which built every profile in the
     * table to return one of them. BlackBoxLoader calls this on the guest bind path, so
     * that cost was paid on every clone start.
     */
    fun byId(id: Long): ProxyProfile? {
        readableDatabase.query(
            TABLE, null, "id = ?", arrayOf(id.toString()), null, null, null, "1"
        ).use { c ->
            return if (c.moveToFirst()) readRow(c) else null
        }
    }

    /**
     * The one place a cursor becomes a [ProxyProfile]. all() and byId() both go through
     * it, so the two cannot drift apart as columns are added.
     */
    private fun readRow(c: android.database.Cursor): ProxyProfile {
        val typeName = c.getString(c.getColumnIndexOrThrow("type"))
        val lastOkIdx = c.getColumnIndex(COL_LAST_OK)
        return ProxyProfile(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            type = ProxyType.entries.firstOrNull { it.name == typeName } ?: ProxyType.HTTP,
            host = c.getString(c.getColumnIndexOrThrow("host")),
            port = c.getInt(c.getColumnIndexOrThrow("port")),
            username = c.getString(c.getColumnIndexOrThrow("username")),
            password = c.getString(c.getColumnIndexOrThrow("password")),
            enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0,
            lastOkAt = if (lastOkIdx >= 0) c.getLong(lastOkIdx) else 0L
        )
    }
}

/**
 * A [ProxySelector] that returns one fixed proxy for every request.
 *
 * Installed into the guest's OkHttp client and into the JVM default selector while a
 * guest process is active. Idempotent: wrapping an already-wrapped selector returns the
 * same instance, so repeated installs do not stack.
 */
class FixedProxySelector(
    private val proxy: Proxy,
    private val fallback: ProxySelector? = null
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> = listOf(proxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
        fallback?.connectFailed(uri, sa, ioe)
    }

    companion object {
        /**
         * Wrap [existing] with [proxy]. Returns [existing] unchanged when [proxy] is null,
         * so callers can pass an unconfigured instance through without branching.
         */
        fun wrap(existing: ProxySelector?, proxy: Proxy?): ProxySelector? {
            if (proxy == null) return existing
            if (existing is FixedProxySelector) return existing
            return FixedProxySelector(proxy, existing)
        }

        /**
         * Install the JVM-wide default selector for a guest process.
         *
         * This is the seam that was missing. [wrap] existed and was correct, but nothing
         * ever called it, so a proxy bound to a clone was stored and then ignored: the
         * clone's traffic went out direct.
         *
         * Setting ProxySelector.setDefault() covers HttpURLConnection and anything else
         * that consults the default selector. OkHttp builds its own selector from
         * ProxySelector.getDefault() at client construction, so a client created after this
         * call picks it up; a client already built keeps the old one, which is why this runs
         * during guest process init rather than lazily.
         *
         * Idempotent: wrapping an already-wrapped selector returns the same instance, so a
         * guest that re-enters init does not stack selectors.
         *
         * @return true when a proxy is now installed.
         */
        fun installDefault(proxy: Proxy?): Boolean {
            if (proxy == null) return false
            return try {
                val current = ProxySelector.getDefault()
                val wrapped = wrap(current, proxy)
                if (wrapped !== current) {
                    ProxySelector.setDefault(wrapped)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
