package com.kiyoscripts.nexusclone.core.keepalive

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Single source of truth for whether the keep-alive subsystem may run at all.
 *
 * Default is OFF. The subsystem has ANR'd the host process on every layer it was given
 * (KeepAliveService, KeepAliveReceiver, KeepAliveJobService, KeepAliveController), and a
 * black-screen host is a worse outcome than a clone that gets reaped after backgrounding.
 * The user turns it on from Settings once the rest of the app is known good.
 */
object KeepAlivePrefs {

    const val KEY_ENABLED = "keepalive_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("nexusclone", Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }
}
