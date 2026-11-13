package com.kiyoscripts.nexusclone.core.net

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * A proxy profile bound to one or more clones.
 *
 * [type] is HTTP, SOCKS4 or SOCKS5. [username]/[password] are optional.
 */
data class ProxyProfile(
    val id: Long = 0L,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val enabled: Boolean = true
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
        private const val DB_NAME = "nexus_proxy.db"
        private const val DB_VERSION = 1
        const val TABLE = "proxies"
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
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
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
        }
        return writableDatabase.update(TABLE, cv, "id = ?", arrayOf(p.id.toString()))
    }

    fun delete(id: Long): Int =
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))

    fun all(): List<ProxyProfile> {
        val out = ArrayList<ProxyProfile>()
        readableDatabase.query(TABLE, null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    ProxyProfile(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        type = ProxyType.entries.firstOrNull {
                            it.name == c.getString(c.getColumnIndexOrThrow("type"))
                        } ?: ProxyType.HTTP,
                        host = c.getString(c.getColumnIndexOrThrow("host")),
                        port = c.getInt(c.getColumnIndexOrThrow("port")),
                        username = c.getString(c.getColumnIndexOrThrow("username")),
                        password = c.getString(c.getColumnIndexOrThrow("password")),
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0
                    )
                )
            }
        }
        return out
    }

    fun byId(id: Long): ProxyProfile? = all().firstOrNull { it.id == id }
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
    }
}
