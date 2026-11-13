package com.kiyoscripts.nexusclone.core.quota

import android.content.Context
import android.util.Log
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * How close an instance is to its soft storage quota.
 *
 * [EXCEEDED] is the only state that triggers an action (the clone is stopped); [WARN] is
 * advisory and only drives UI colour.
 */
enum class QuotaStatus {
    OK,
    WARN,
    EXCEEDED
}

/**
 * Soft storage quota enforcement.
 *
 * The quota is soft on purpose: nothing prevents a guest from writing past it, we only
 * notice after the fact when [InstanceManager.refreshSizes] recomputes the data dir size.
 * Enforcement therefore means "stop the clone so it stops growing", never "block the write".
 */
class QuotaEnforcer(private val context: Context) {

    companion object {
        private const val TAG = "QuotaEnforcer"

        /** Fraction of the quota at which we start warning. */
        private const val WARN_RATIO = 0.9
    }

    /**
     * Same verdict as [status], but logs the transition so a background sweep leaves a
     * trace in logcat.
     */
    fun check(inst: CloneInstance): QuotaStatus {
        val verdict = status(inst)
        if (verdict != QuotaStatus.OK) {
            Log.i(TAG, "instance ${inst.key} is $verdict: ${describe(inst)}")
        }
        return verdict
    }

    /**
     * EXCEEDED when a quota is set and the data size has reached it, WARN at 90 percent,
     * OK otherwise. A quota of 0 means unlimited and is always OK.
     */
    fun status(inst: CloneInstance): QuotaStatus {
        val quota = inst.quotaBytes
        if (quota <= 0L) return QuotaStatus.OK
        val used = inst.dataSizeBytes
        if (used >= quota) return QuotaStatus.EXCEEDED
        if (used >= (quota * WARN_RATIO).toLong()) return QuotaStatus.WARN
        return QuotaStatus.OK
    }

    /**
     * Stop every instance that is over its quota.
     *
     * Returns the instances that were actually stopped, so the caller can report them.
     * A failed stop is logged and the instance is left out of the result.
     */
    suspend fun enforceAll(manager: InstanceManager): List<CloneInstance> =
        withContext(Dispatchers.IO) {
            val stopped = ArrayList<CloneInstance>()
            for (inst in manager.snapshot()) {
                if (status(inst) != QuotaStatus.EXCEEDED) continue
                val result = try {
                    manager.stop(inst.id)
                } catch (e: Exception) {
                    Log.e(TAG, "stop(${inst.key}) threw: ${e.message}", e)
                    null
                }
                if (result != null && result.isSuccess) {
                    Log.i(TAG, "stopped ${inst.key} over quota: ${describe(inst)}")
                    stopped.add(inst)
                } else {
                    Log.w(
                        TAG,
                        "could not stop ${inst.key}: ${result?.exceptionOrNull()?.message}"
                    )
                }
            }
            stopped
        }

    /** Human readable usage line, e.g. "1.2 MB / 5.0 MB (24%)". */
    fun describe(inst: CloneInstance): String {
        val used = formatBytes(inst.dataSizeBytes)
        val quota = inst.quotaBytes
        if (quota <= 0L) return "$used / unlimited"
        val percent = (inst.dataSizeBytes * 100L / quota).coerceIn(0L, 999L)
        return "$used / ${formatBytes(quota)} ($percent%)"
    }

    /** Bytes as B/KB/MB/GB with one decimal. */
    private fun formatBytes(bytes: Long): String {
        val value = if (bytes < 0L) 0L else bytes
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val d = value.toDouble()
        return when {
            d >= gb -> String.format(Locale.US, "%.1f GB", d / gb)
            d >= mb -> String.format(Locale.US, "%.1f MB", d / mb)
            d >= kb -> String.format(Locale.US, "%.1f KB", d / kb)
            else -> String.format(Locale.US, "%.1f B", d)
        }
    }
}
