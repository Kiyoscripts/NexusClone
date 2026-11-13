package com.kiyoscripts.nexusclone.core.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Schedules and caches preview thumbnails.
 *
 * Refresh policy:
 *  - on guest activity pause (the cheap, always-correct moment)
 *  - on a 5 s timer, but only while the grid is visible and only for instances on the
 *    current page, capped at [MAX_CONCURRENT] in flight
 *  - manual pull-to-refresh
 *
 * The timer is deliberately not a background service: previews of a backgrounded app are
 * stale by definition and capturing them burns battery for nothing.
 */
class PreviewCache(private val context: Context) {

    companion object {
        private const val TAG = "PreviewCache"
        private const val MAX_CONCURRENT = 4
        private const val MIN_INTERVAL_MS = 4_000L
    }

    private val manager = InstanceManager.get(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val lastCapture = ConcurrentHashMap<String, Long>()

    private var timer: Runnable? = null
    private var gridVisible = false

    fun fileFor(inst: CloneInstance): File =
        File(manager.previewDir(), "${inst.userId}_${inst.packageName}.webp")

    /** Absolute file for an instance, whether or not it exists. */
    fun existingFile(inst: CloneInstance): File? =
        fileFor(inst).takeIf { it.exists() && it.length() > 0 }

    /**
     * Capture a thumbnail for [inst] from [window].
     *
     * Debounced: a second call inside [MIN_INTERVAL_MS] is dropped.
     */
    fun captureNow(
        inst: CloneInstance,
        window: android.view.Window,
        force: Boolean = false,
        onDone: (Boolean) -> Unit = {}
    ) {
        val key = inst.key
        val now = System.currentTimeMillis()
        if (!force) {
            val last = lastCapture[key] ?: 0L
            if (now - last < MIN_INTERVAL_MS) {
                onDone(false)
                return
            }
        }
        if (inFlight.size >= MAX_CONCURRENT) {
            onDone(false)
            return
        }
        if (inFlight.putIfAbsent(key, true) != null) {
            onDone(false)
            return
        }
        lastCapture[key] = now

        val out = fileFor(inst)
        PreviewCapture.capture(window, out) { ok ->
            inFlight.remove(key)
            if (ok) {
                main.post {
                    // Fire-and-forget; the store write is cheap and idempotent.
                    scope.launch {
                        manager.setPreview(inst.id, out.name)
                    }
                }
            }
            onDone(ok)
        }
    }

    /** Start the 5 s refresh loop. Call from the grid fragment's onResume. */
    fun startTimer(provider: () -> List<Pair<CloneInstance, android.view.Window>>) {
        if (gridVisible) return
        gridVisible = true
        val r = object : Runnable {
            override fun run() {
                if (!gridVisible) return
                try {
                    provider().take(MAX_CONCURRENT).forEach { (inst, win) ->
                        captureNow(inst, win)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "timer tick failed: ${e.message}")
                }
                main.postDelayed(this, 5_000L)
            }
        }
        timer = r
        main.postDelayed(r, 5_000L)
    }

    /** Stop the refresh loop. Call from the grid fragment's onPause. */
    fun stopTimer() {
        gridVisible = false
        timer?.let { main.removeCallbacks(it) }
        timer = null
    }

    /** Drop every cached thumbnail. */
    fun clearAll() {
        manager.previewDir().listFiles()?.forEach { it.delete() }
        lastCapture.clear()
    }

    /** Drop one instance's thumbnail. */
    fun clear(inst: CloneInstance) {
        fileFor(inst).delete()
        lastCapture.remove(inst.key)
    }
}
