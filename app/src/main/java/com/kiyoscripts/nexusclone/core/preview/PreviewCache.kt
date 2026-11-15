package com.kiyoscripts.nexusclone.core.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
 *
 * Lifecycle: call [attach] once with the owning Activity or Fragment. The timer then runs
 * between ON_START and ON_STOP, and the on-disk cache is trimmed on ON_DESTROY. Without
 * [attach] nothing refreshes and nothing is ever evicted, which is what used to happen.
 */
class PreviewCache(private val context: Context) {

    companion object {
        private const val TAG = "PreviewCache"
        private const val MAX_CONCURRENT = 4
        private const val MIN_INTERVAL_MS = 4_000L

        /**
         * Upper bound on the on-disk thumbnail cache.
         *
         * A thumbnail is a 180x320 WebP, so a few KB each; 200 of them is well under a
         * megabyte and covers far more clones than anyone runs at once. The bound exists
         * so a long-lived install cannot accumulate one file per clone ever created.
         */
        private const val MAX_CACHED_FILES = 200

        /** Refresh cadence while the grid is visible. */
        private const val TICK_MS = 5_000L

        /**
         * How many ticks between housekeeping passes when there is nothing to capture.
         *
         * 12 ticks is once a minute: often enough that the cache cannot drift far past
         * [MAX_CACHED_FILES], rare enough that a visible grid is not listing a directory
         * every five seconds for nothing.
         */
        private const val HOUSEKEEPING_EVERY = 12
    }

    private val manager = InstanceManager.get(context)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val lastCapture = ConcurrentHashMap<String, Long>()

    private var timer: Runnable? = null
    private var gridVisible = false

    /** Set once [attach] has been called, so a second attach cannot double-register. */
    private var observer: LifecycleEventObserver? = null

    fun fileFor(inst: CloneInstance): File =
        File(manager.previewDir(), "${inst.userId}_${inst.packageName}.webp")

    /** Absolute file for an instance, whether or not it exists. */
    fun existingFile(inst: CloneInstance): File? =
        fileFor(inst).takeIf { it.exists() && it.length() > 0 }

    /**
     * Bind the refresh timer and the cache eviction to [owner]'s lifecycle.
     *
     * ON_START starts the 5 s loop, ON_STOP stops it, ON_DESTROY trims the cache. Safe to
     * call more than once: the second call is a no-op, so an Activity that recreates its
     * cache in onCreate does not leak an observer.
     */
    fun attach(owner: LifecycleOwner) {
        if (observer != null) return
        // LifecycleEventObserver rather than DefaultLifecycleObserver: the latter only
        // exists from lifecycle 2.4.0, and app/build.gradle declares lifecycle-runtime-ktx
        // twice (2.6.2 and 2.3.1). Resolution picks 2.6.2 today, but this form works on
        // every version the build file mentions.
        val o = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_START -> startTimer { emptyList() }
                    Lifecycle.Event.ON_STOP -> stopTimer()
                    Lifecycle.Event.ON_DESTROY -> {
                        stopTimer()
                        scope.launch { housekeeping() }
                    }
                    else -> Unit
                }
            }
        }
        observer = o
        owner.lifecycle.addObserver(o)
    }

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
                        trimToBound()
                    }
                }
            }
            onDone(ok)
        }
    }

    /**
     * Start the 5 s refresh loop.
     *
     * [provider] supplies the (instance, window) pairs to capture on each tick. The
     * lifecycle path passes an empty provider: the host holds no handle on a guest's
     * Window from the grid, so on those ticks the loop does cache housekeeping instead of
     * inventing captures. Callers that do have a window (a guest activity wrapper) pass a
     * real provider and get the documented 5 s refresh.
     */
    fun startTimer(provider: () -> List<Pair<CloneInstance, android.view.Window>>) {
        if (gridVisible) return
        gridVisible = true
        var ticks = 0
        val r = object : Runnable {
            override fun run() {
                if (!gridVisible) return
                val pairs = try {
                    provider()
                } catch (e: Exception) {
                    Log.w(TAG, "timer provider failed: ${e.message}")
                    emptyList()
                }
                try {
                    pairs.take(MAX_CONCURRENT).forEach { (inst, win) ->
                        captureNow(inst, win)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "timer tick failed: ${e.message}")
                }
                if (pairs.isEmpty() && ++ticks % HOUSEKEEPING_EVERY == 0) {
                    // Nothing to capture: keep the cache honest instead of idling.
                    scope.launch { housekeeping() }
                }
                main.postDelayed(this, TICK_MS)
            }
        }
        timer = r
        main.postDelayed(r, TICK_MS)
    }

    /** Stop the refresh loop. */
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

    /**
     * Evict the oldest thumbnails until at most [MAX_CACHED_FILES] remain.
     *
     * LRU by last-modified time, which is what a capture writes. Cheap enough to run after
     * every capture and on ON_DESTROY: the directory holds one small file per clone.
     */
    fun trimToBound() {
        try {
            val files = manager.previewDir().listFiles() ?: return
            if (files.size <= MAX_CACHED_FILES) return
            val doomed = files
                .sortedBy { it.lastModified() }
                .take(files.size - MAX_CACHED_FILES)
            var removed = 0
            for (f in doomed) {
                if (f.delete()) removed++
            }
            if (removed > 0) {
                Log.i(TAG, "evicted $removed thumbnail(s), ${files.size - removed} left")
            }
        } catch (e: Exception) {
            Log.w(TAG, "trim failed: ${e.message}")
        }
    }

    /**
     * Trim the cache, drop thumbnails of clones that no longer exist, and forget debounce
     * state for them.
     *
     * Runs on the IO scope from the timer tick and from ON_DESTROY. This is what makes
     * [clear] unnecessary in the common case: a clone deleted while the grid was closed
     * leaves its thumbnail behind, and nothing else would ever remove it.
     */
    private fun housekeeping() {
        trimToBound()
        try {
            val live = manager.snapshot().mapTo(HashSet<String>()) { it.key }
            // An empty snapshot means refresh() has not run yet in this process, not that
            // every clone is gone. Pruning on it would wipe the whole cache on a cold
            // start, so bail out instead.
            if (live.isEmpty()) return
            lastCapture.keys.retainAll(live)
            manager.previewDir().listFiles()?.forEach { f ->
                val key = keyOf(f.name) ?: return@forEach
                if (key !in live) f.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "housekeeping failed: ${e.message}")
        }
    }

    /**
     * Recover the instance key from a thumbnail filename, or null if it is not ours.
     *
     * The name is "<userId>_<packageName>.webp" (see [fileFor]). The userId is the numeric
     * prefix before the first underscore; the package name may itself contain underscores,
     * so only the first one is a separator.
     */
    private fun keyOf(fileName: String): String? {
        if (!fileName.endsWith(".webp")) return null
        val stem = fileName.removeSuffix(".webp")
        val sep = stem.indexOf('_')
        if (sep <= 0) return null
        val userId = stem.substring(0, sep).toIntOrNull() ?: return null
        return "$userId:${stem.substring(sep + 1)}"
    }
}
