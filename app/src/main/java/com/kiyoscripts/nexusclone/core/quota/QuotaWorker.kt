package com.kiyoscripts.nexusclone.core.quota

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Periodic quota sweep.
 *
 * Sizes are only known after a directory walk, so the sweep is deliberately cheap and
 * infrequent: recompute sizes, then stop whatever crossed its quota. An inexact repeating
 * alarm is enough - a few minutes of drift costs nothing here, and exact alarms would need
 * SCHEDULE_EXACT_ALARM on API 31+ for no benefit.
 *
 * WorkManager is not on the classpath (see app/build.gradle), so this uses AlarmManager,
 * the same mechanism [com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController] uses
 * for its resurrection pulse. Nothing here needs a dependency.
 *
 * Wiring: call [schedule] once from Application.onCreate. The alarm survives process death
 * but not reboot, so a BOOT_COMPLETED receiver should call [schedule] again.
 */
class QuotaWorker {

    companion object {
        private const val TAG = "QuotaWorker"

        /** Sweep cadence: 15 minutes. */
        const val INTERVAL_MS = 15 * 60 * 1000L

        private const val REQUEST_CODE = 0x5157

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuotaReceiver::class.java).apply {
                action = QuotaReceiver.ACTION_SWEEP
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Arm the repeating sweep. Re-arming replaces the existing alarm. */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am == null) {
                Log.w(TAG, "no AlarmManager, quota sweep not scheduled")
                return
            }
            try {
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS,
                    pendingIntent(app)
                )
                Log.i(TAG, "quota sweep scheduled every ${INTERVAL_MS / 1000}s")
            } catch (e: Exception) {
                Log.e(TAG, "schedule failed: ${e.message}", e)
            }
        }

        /** Disarm the sweep. */
        fun cancel(context: Context) {
            val app = context.applicationContext
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am == null) {
                Log.w(TAG, "no AlarmManager, nothing to cancel")
                return
            }
            try {
                am.cancel(pendingIntent(app))
                Log.i(TAG, "quota sweep cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "cancel failed: ${e.message}", e)
            }
        }

        /**
         * Run one sweep on the calling coroutine's dispatcher.
         *
         * Recomputes sizes and stops every clone over its quota. This is the only place
         * that knows the order (refresh, then enforce), so both the alarm receiver and any
         * foreground caller go through it.
         */
        suspend fun sweep(context: Context): List<CloneInstance> {
            val app = context.applicationContext
            val manager = InstanceManager.get(app)
            return QuotaEnforcer(app).sweep(manager)
        }

        /**
         * Fire-and-forget sweep on an IO coroutine.
         *
         * Used by [QuotaReceiver], which must return from onReceive immediately. The
         * process may be killed before the walk finishes; that is the deliberate trade
         * documented on the receiver.
         */
        fun sweepAsync(context: Context) {
            val app = context.applicationContext
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val stopped = sweep(app)
                    if (stopped.isEmpty()) {
                        Log.i(TAG, "sweep done, nothing over quota")
                    } else {
                        Log.i(
                            TAG,
                            "sweep done, stopped ${stopped.size}: " +
                                stopped.joinToString { it.key }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sweep failed: ${e.message}", e)
                }
            }
        }
    }
}

/**
 * Receives the quota sweep alarm.
 *
 * onReceive returns immediately; the directory walk and the stops happen on an IO
 * coroutine. That is a deliberate trade: the process may be killed before the work
 * finishes, but blocking a receiver for a full size scan would be worse.
 *
 * The action is checked rather than assumed: the receiver is exported="false" but a
 * PendingIntent can still be replayed with a different action by the alarm framework, and
 * an unrelated broadcast should not trigger a full disk walk.
 */
class QuotaReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuotaReceiver"

        const val ACTION_SWEEP = "com.kiyoscripts.nexusclone.action.QUOTA_SWEEP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION_SWEEP) {
            Log.d(TAG, "ignoring action $action")
            return
        }
        Log.d(TAG, "sweep: $action")
        QuotaWorker.sweepAsync(context)
    }
}
