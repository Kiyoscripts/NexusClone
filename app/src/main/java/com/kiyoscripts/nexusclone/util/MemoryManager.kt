package com.kiyoscripts.nexusclone.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.lang.Runtime

/**
 * Memory management utility to prevent crashes during memory-intensive operations
 */
/**
 * created by alex5402 on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * TFNQw5HgWUS33Ke1eNmSFTwoQySGU7XNsK (USDT TRC20)
 */
object MemoryManager {
    
    private const val TAG = "MemoryManager"
    private const val MEMORY_THRESHOLD = 0.8 // 80% memory usage threshold
    private const val CRITICAL_MEMORY_THRESHOLD = 0.9 // 90% memory usage threshold
    
    /**
     * Check if memory usage is within safe limits
     */
    fun isMemorySafe(): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()
            
            memoryUsage < MEMORY_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "Error checking memory: ${e.message}")
            true // Assume safe if we can't check
        }
    }
    
    /**
     * Check if memory usage is critical
     */
    fun isMemoryCritical(): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()
            
            memoryUsage > CRITICAL_MEMORY_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "Error checking critical memory: ${e.message}")
            false // Assume not critical if we can't check
        }
    }
    
    /**
     * Get current memory usage percentage
     */
    fun getMemoryUsagePercentage(): Int {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()
            
            (memoryUsage * 100).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting memory usage: ${e.message}")
            0
        }
    }
    
    /**
     * Force garbage collection if memory usage is high.
     *
     * Deliberately does not force a collection. On ART a forced full GC is a synchronous
     * stop-the-world pause, not a hint, and this is reached from a RecyclerView scroll
     * callback on the main thread — the worst possible moment to freeze the UI. ART already
     * collects on allocation pressure; the useful thing to do here is report the pressure so
     * the caller can drop caches.
     *
     * @return true when memory is critical, i.e. the caller should shed caches.
     */
    fun forceGarbageCollectionIfNeeded(): Boolean {
        return try {
            if (isMemoryCritical()) {
                Log.w(
                    TAG,
                    "Memory usage critical (${getMemoryUsagePercentage()}%), caller should trim caches"
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking memory pressure: ${e.message}")
            false
        }
    }
    
    /**
     * Optimize memory for RecyclerView operations.
     *
     * No-op by design. This used to force a full collection (twice, via the runtime as well)
     * from a RecyclerView `SCROLL_STATE_IDLE` callback, which is a synchronous
     * stop-the-world pause on the main thread exactly when the list settles. ART collects on
     * allocation pressure on its own; forcing it from a scroll callback only produces jank.
     * Kept as a no-op so existing callers still compile.
     */
    fun optimizeMemoryForRecyclerView() {
        // Intentionally empty: see the KDoc above.
    }
    
    /**
     * Check if we should skip icon loading to save memory
     */
    fun shouldSkipIconLoading(): Boolean {
        return try {
            val memoryUsage = getMemoryUsagePercentage()
            memoryUsage > 75 // Skip icon loading if memory usage > 75%
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if should skip icon loading: ${e.message}")
            false
        }
    }
    
    /**
     * Get memory info for debugging
     */
    fun getMemoryInfo(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val maxMemory = runtime.maxMemory()
            
            "Memory: ${usedMemory / 1024 / 1024}MB used / ${maxMemory / 1024 / 1024}MB max (${getMemoryUsagePercentage()}%)"
        } catch (e: Exception) {
            "Memory: Unknown (${e.message})"
        }
    }
}
