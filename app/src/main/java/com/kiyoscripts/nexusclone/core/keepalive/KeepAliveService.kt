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
                // Use startService(), not startForegroundService().
                //
                // startForegroundService() sets fgRequired and arms a 10 second timer. If
                // the service cannot reach startForeground() in that window — which is
                // what happens on Android 12 when the start is treated as a background
                // start, or when the notification path throws — the framework raises
                //   Context.startForegroundService() did not then call
                //   Service.startForeground()
                // and kills the host with a background ANR. Retrying startForeground()
                // with a fallback notification does not help, because the exception is
                // thrown by the framework's own permission check, not by the notification.
                //
                // startService() carries no such contract. The service still calls
                // startForeground() when it can, so it is promoted to foreground normally;
                // if that fails it simply runs as a background service instead of taking
                // the whole process down.
                context.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
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
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
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
