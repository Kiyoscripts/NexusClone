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
                try {
                    KeepAliveController.get(context).start()
                } catch (e: Exception) {
                    Log.w(TAG, "restart failed: ${e.message}")
                }
            }
        }
    }
}
