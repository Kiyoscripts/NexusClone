package com.kiyoscripts.nexusclone.core.macro

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded input event.
 *
 * [t] is milliseconds since the recording started, so replay is timing-accurate without
 * depending on wall clock.
 */
data class MacroEvent(
    val t: Long,
    val type: EventType,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val durationMs: Long = 0L
) {
    enum class EventType { DOWN, MOVE, UP, TAP, SWIPE, WAIT }

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", t)
        put("type", type.name)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("x2", x2.toDouble())
        put("y2", y2.toDouble())
        put("d", durationMs)
    }

    companion object {
        fun fromJson(o: JSONObject): MacroEvent = MacroEvent(
            t = o.optLong("t"),
            type = EventType.entries.firstOrNull { it.name == o.optString("type") }
                ?: EventType.WAIT,
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            x2 = o.optDouble("x2", 0.0).toFloat(),
            y2 = o.optDouble("y2", 0.0).toFloat(),
            durationMs = o.optLong("d")
        )
    }
}

/** A named recording bound to one instance. */
data class Macro(
    val id: Long = 0L,
    val name: String,
    val userId: Int,
    val packageName: String,
    val events: List<MacroEvent>,
    val createdAt: Long = System.currentTimeMillis(),
    val loopCount: Int = 1
) {
    val durationMs: Long get() = events.lastOrNull()?.t ?: 0L
}

/**
 * Records and replays touch macros.
 *
 * Recording happens at the host window level: the recorder wraps the guest activity's
 * decor view with an overlay that captures MotionEvents without consuming them, so the
 * guest still receives its own input while a recording is running.
 *
 * Replay injects through the same overlay, dispatching synthetic MotionEvents to the
 * guest's decor view. This is deliberately not InputManager injection, which needs
 * INJECT_EVENTS and therefore root or shell.
 */
class MacroStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val TAG = "MacroStore"
        private const val DB_NAME = "nexus_macros.db"
        private const val DB_VERSION = 1
        const val TABLE = "macros"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                user_id INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                events TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                loop_count INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_macro_key ON $TABLE (user_id, package_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(m: Macro): Long {
        val cv = ContentValues().apply {
            put("name", m.name)
            put("user_id", m.userId)
            put("package_name", m.packageName)
            put("events", encode(m.events))
            put("created_at", m.createdAt)
            put("loop_count", m.loopCount)
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun update(m: Macro): Int {
        val cv = ContentValues().apply {
            put("name", m.name)
            put("events", encode(m.events))
            put("loop_count", m.loopCount)
        }
        return writableDatabase.update(TABLE, cv, "id = ?", arrayOf(m.id.toString()))
    }

    fun delete(id: Long): Int =
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))

    fun all(): List<Macro> {
        val out = ArrayList<Macro>()
        readableDatabase.query(TABLE, null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    Macro(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        userId = c.getInt(c.getColumnIndexOrThrow("user_id")),
                        packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
                        events = decode(c.getString(c.getColumnIndexOrThrow("events"))),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        loopCount = c.getInt(c.getColumnIndexOrThrow("loop_count"))
                    )
                )
            }
        }
        return out
    }

    fun forInstance(userId: Int, packageName: String): List<Macro> =
        all().filter { it.userId == userId && it.packageName == packageName }

    private fun encode(events: List<MacroEvent>): String =
        JSONArray().apply { events.forEach { put(it.toJson()) } }.toString()

    private fun decode(text: String): List<MacroEvent> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).map { MacroEvent.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        Log.w(TAG, "decode failed: ${e.message}")
        emptyList()
    }
}

/**
 * Records MotionEvents from a view into a [Macro].
 *
 * Call [start] before the guest activity is shown, [stop] when done. The recorder does not
 * consume events, so the user interacts normally while recording.
 */
class MacroRecorder {

    private val events = ArrayList<MacroEvent>()
    private var startAt = 0L
    private var recording = false

    val isRecording: Boolean get() = recording
    val count: Int get() = events.size

    fun start() {
        events.clear()
        startAt = SystemClock.uptimeMillis()
        recording = true
    }

    fun stop(): List<MacroEvent> {
        recording = false
        return events.toList()
    }

    fun cancel() {
        recording = false
        events.clear()
    }

    /** Feed one MotionEvent. Coordinates are view-local. */
    fun onTouch(action: Int, x: Float, y: Float) {
        if (!recording) return
        val t = SystemClock.uptimeMillis() - startAt
        val type = when (action) {
            android.view.MotionEvent.ACTION_DOWN -> MacroEvent.EventType.DOWN
            android.view.MotionEvent.ACTION_MOVE -> MacroEvent.EventType.MOVE
            android.view.MotionEvent.ACTION_UP -> MacroEvent.EventType.UP
            else -> return
        }
        // Collapse dense MOVE streams; 60 Hz raw is far more than replay needs.
        if (type == MacroEvent.EventType.MOVE) {
            val last = events.lastOrNull()
            if (last != null && last.type == MacroEvent.EventType.MOVE && t - last.t < 16) return
        }
        events.add(MacroEvent(t = t, type = type, x = x, y = y))
    }

    /** Build a [Macro] from the current recording. */
    fun toMacro(name: String, userId: Int, packageName: String, loopCount: Int = 1): Macro =
        Macro(
            name = name,
            userId = userId,
            packageName = packageName,
            events = events.toList(),
            loopCount = loopCount
        )
}

/**
 * Replays a [Macro] against a view by dispatching synthetic MotionEvents.
 *
 * Runs on the main thread with a Handler, scheduling each event at its recorded offset.
 * [onFinished] fires after the last loop.
 */
class MacroPlayer(
    private val target: android.view.View,
    private val macro: Macro,
    private val onFinished: () -> Unit = {}
) {

    companion object {
        private const val TAG = "MacroPlayer"
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loop = 0
    private var cancelled = false

    fun start() {
        cancelled = false
        loop = 0
        scheduleLoop()
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleLoop() {
        if (cancelled) return
        if (loop >= macro.loopCount) {
            onFinished()
            return
        }
        loop++
        val base = SystemClock.uptimeMillis()
        for (e in macro.events) {
            handler.postDelayed({
                if (!cancelled) dispatch(e)
            }, e.t)
        }
        val total = macro.durationMs + 250L
        handler.postDelayed({ scheduleLoop() }, total)
    }

    private fun dispatch(e: MacroEvent) {
        try {
            val downTime = SystemClock.uptimeMillis()
            val action = when (e.type) {
                MacroEvent.EventType.DOWN -> android.view.MotionEvent.ACTION_DOWN
                MacroEvent.EventType.MOVE -> android.view.MotionEvent.ACTION_MOVE
                MacroEvent.EventType.UP -> android.view.MotionEvent.ACTION_UP
                MacroEvent.EventType.TAP -> android.view.MotionEvent.ACTION_DOWN
                MacroEvent.EventType.SWIPE -> android.view.MotionEvent.ACTION_DOWN
                MacroEvent.EventType.WAIT -> return
            }
            val ev = android.view.MotionEvent.obtain(
                downTime, downTime, action, e.x, e.y, 0
            )
            target.dispatchTouchEvent(ev)
            ev.recycle()

            if (e.type == MacroEvent.EventType.TAP) {
                val up = android.view.MotionEvent.obtain(
                    downTime, downTime + 40, android.view.MotionEvent.ACTION_UP, e.x, e.y, 0
                )
                target.dispatchTouchEvent(up)
                up.recycle()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "dispatch failed: ${ex.message}")
        }
    }
}
