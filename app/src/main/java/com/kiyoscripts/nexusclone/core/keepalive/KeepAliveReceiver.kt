package com.kiyoscripts.nexusclone.core.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the resurrection pulse and the boot broadcast.
 *
 * Both paths do the same thing: make sure the foreground service is up. Nothing else is
 * safe to do from a receiver, because the process may be killed the moment onReceive
 * returns.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "pulse: $action")
        when (action) {
            KeepAliveService.ACTION_PULSE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // onReceive runs on the main thread with a hard deadline (10s for a
                // foreground broadcast, less under load). KeepAliveController.start()
                // also arms AlarmManager and JobScheduler, which is enough work to
                // produce
                //   am_anr: Broadcast of Intent { act=..KEEPALIVE_PULSE .. }
                // Only the service start belongs here; the scheduling layers are re-armed
                // by the service itself once it is running.
                if (!KeepAlivePrefs.isEnabled(context)) {
                    Log.d(TAG, "keep-alive disabled by preference, ignoring $action")
                    return
                }
                try {
                    KeepAliveService.start(context)
                } catch (e: Exception) {
                    Log.w(TAG, "restart failed: ${e.message}")
                }
            }
        }
    }
}
