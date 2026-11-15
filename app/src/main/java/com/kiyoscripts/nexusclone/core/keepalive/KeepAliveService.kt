package com.kiyoscripts.nexusclone.core.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.view.main.MainActivity

/**
 * Foreground service that anchors the host process.
 *
 * The host process is the guest process, so keeping this alive keeps every warm clone
 * alive. That is the whole trick: one anchor, N instances.
 *
 * On API 34+ the service declares type specialUse, which is the only type that does not
 * require a runtime permission we cannot justify. The manifest carries the matching
 * property.
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        const val CHANNEL_ID = "nexus_keepalive"
        const val NOTIFY_ID = 0x4E43 // "NC"

        const val ACTION_START = "com.kiyoscripts.nexusclone.KEEPALIVE_START"
        const val ACTION_STOP = "com.kiyoscripts.nexusclone.KEEPALIVE_STOP"
        const val ACTION_PULSE = "com.kiyoscripts.nexusclone.KEEPALIVE_PULSE"

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
            try {
                // startForegroundService(), not startService().
                //
                // Every caller of this method is a background context: KeepAliveReceiver
                // runs from the ACTION_PULSE alarm and from BOOT_COMPLETED, and
                // KeepAliveJobService runs from a JobScheduler job. On API 26+ a plain
                // startService() from a background context throws
                //   IllegalStateException: Not allowed to start service Intent { .. }:
                //   app is in background
                // which the catch below reduced to a log line, so the entire resurrection
                // layer silently did nothing.
                //
                // startForegroundService() is the supported way to start from the
                // background. It sets fgRequired and arms a ~10 second timer, so the
                // service MUST reach startForeground() inside that window or the framework
                // raises ForegroundServiceDidNotStartInTimeException ("Context
                // .startForegroundService() did not then call Service.startForeground()")
                // and kills the host. KeepAliveService.onStartCommand() therefore calls
                // startForeground() first, unconditionally, before the preference check and
                // before any scheduling work. Do not move that call down.
                //
                // The Android 12 background-start restriction still applies to the
                // foreground promotion itself, so this is only called from the paths it
                // exempts: BOOT_COMPLETED / MY_PACKAGE_REPLACED broadcasts, an exact alarm,
                // an expedited job, and a visible Activity (MainActivity.onResume). The
                // battery-optimisation exemption this subsystem asks for covers the rest.
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            // stopService(), not startService(ACTION_STOP). A background context cannot
            // start a service on API 26+ either, and the ACTION_STOP branch only tears the
            // notification down and calls stopSelf() — which is exactly what stopService()
            // does, without the background-start restriction.
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() is the first thing that happens here, unconditionally.
        //
        // KeepAliveService.start() uses ContextCompat.startForegroundService(), which sets
        // fgRequired and arms a ~10 second timer. Returning from onStartCommand without
        // reaching startForeground() — on the disabled path, on the ACTION_STOP path, or
        // because the preference check came first — raises
        // ForegroundServiceDidNotStartInTimeException and the framework kills the host.
        // So the promotion happens before the preference check, before the action dispatch
        // and before any scheduling work; the paths that then decide to stop tear the
        // notification back down themselves.
        startForegroundCompat()

        // A sticky restart can arrive after the user turned keep-alive off. Honour the
        // preference before doing any work at all.
        if (!KeepAlivePrefs.isEnabled(this)) {
            Log.d(TAG, "keep-alive disabled by preference, stopping")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // onServiceStarted() arms the alarm and job layers, which touch
                // AlarmManager and JobScheduler. Doing that inline here runs on the main
                // thread while the framework is timing service execution, and produced
                //   am_anr: executing service .. KeepAliveService
                // Hand it to a worker so onStartCommand returns immediately.
                Thread({
                    try {
                        KeepAliveController.get(this).onServiceStarted()
                    } catch (e: Throwable) {
                        Log.w(TAG, "onServiceStarted failed: ${e.message}")
                    }
                }, "nexus-keepalive-schedule").start()
            }
        }
        // START_STICKY: the system restarts us after a low-memory kill.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!KeepAlivePrefs.isEnabled(this)) return
        // Swiping the host from recents must not take the clones with it.
        KeepAliveController.get(this).scheduleResurrection()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFY_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        } catch (e: Exception) {
            // Swallowing this does NOT satisfy the contract. startForeground() is final
            // and already swallows RemoteException internally, so anything reaching here
            // is the framework deliberately signalling a violation:
            // ForegroundServiceStartNotAllowedException (Android 12 background-start
            // restriction) or IllegalArgumentException for a type not covered by the
            // manifest. fgRequired stays true and the 10 second timer still fires, which
            // is exactly the ANR observed on the vivo:
            //   Context.startForegroundService() did not then call
            //   Service.startForeground(): ServiceRecord{.. KeepAliveService}
            //
            // Retry with a bare Notification that cannot fail, so the contract is met.
            Log.w(TAG, "startForeground failed, retrying with minimal notification: ${e.message}")
            try {
                val fallback = android.app.Notification()
                fallback.icon = android.R.drawable.ic_dialog_info
                fallback.`when` = System.currentTimeMillis()
                startForeground(NOTIFY_ID, fallback)
            } catch (t: Throwable) {
                Log.e(TAG, "fallback startForeground also failed: ${t.message}")
            }
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }
}
