package com.kiyoscripts.nexusclone.core.keepalive

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kiyoscripts.nexusclone.core.instance.InstanceManager

/**
 * Keep-alive orchestration and honest capability reporting.
 *
 * Layers, strongest first:
 *  1. foreground service (KeepAliveService) - survives normal backgrounding
 *  2. guard process bound to the host - raises oom_adj of both
 *  3. battery optimization exemption - required for anything to survive Doze
 *  4. AlarmManager pulse every 9 min - resurrects after a kill
 *  5. JobScheduler expedited - fallback when alarms are restricted
 *
 * What none of these survive: a force-stop from the recents swipe on most OEM skins, and
 * an OEM "clean up" without the autostart permission. The UI reports that honestly via
 * [strength] rather than pretending otherwise.
 */
class KeepAliveController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "KeepAliveController"
        private const val ALARM_REQUEST = 0x4E4301
        private const val JOB_ID = 0x4E43
        private const val PULSE_INTERVAL_MS = 9 * 60 * 1000L

        @Volatile
        private var INSTANCE: KeepAliveController? = null

        fun get(context: Context): KeepAliveController =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: KeepAliveController(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }

    // Lazy, not eager. InstanceManager's constructor builds an InstanceStore, which opens
    // the SQLite database. KeepAliveController is constructed from
    // KeepAliveService.onStartCommand(), and doing that disk work there ran on the main
    // thread while the framework was timing service execution:
    //   am_anr: executing service .. KeepAliveService
    // Only applyLevel() actually needs the manager, so resolve it on first use.
    private val manager by lazy { InstanceManager.get(context) }

    /** 0 = nothing, 1 = foreground service only, 2 = service + battery exemption. */
    fun strength(): Int {
        var level = 0
        if (isServiceRunning()) level = 1
        if (level == 1 && isIgnoringBatteryOptimizations()) level = 2
        return level
    }

    fun isServiceRunning(): Boolean = serviceStarted

    @Volatile
    private var serviceStarted = false

    internal fun onServiceStarted() {
        serviceStarted = true
        schedulePulse()
        scheduleJob()
    }

    fun start() {
        // Hard gate. Every layer of this subsystem has ANR'd the host process at least
        // once, and the host process is the one that draws the UI. Nothing here runs
        // unless the user has explicitly opted in from Settings.
        if (!KeepAlivePrefs.isEnabled(context)) {
            Log.d(TAG, "keep-alive disabled by preference, not starting")
            return
        }
        KeepAliveService.start(context)
        schedulePulse()
        scheduleJob()
    }

    /**
     * Schedule the resurrection layers without starting the foreground service.
     *
     * Safe to call from Application.onCreate(). Starting the service there is a
     * background start on Android 12+, which throws
     * ForegroundServiceStartNotAllowedException inside the service and leaves the
     * framework waiting for startForeground(), producing an ANR.
     */
    fun scheduleOnly() {
        if (!KeepAlivePrefs.isEnabled(context)) return
        schedulePulse()
        scheduleJob()
    }

    fun stop() {
        KeepAliveService.stop(context)
        cancelPulse()
        cancelJob()
        serviceStarted = false
    }

    /**
     * Apply the user's preference: start everything when enabled, tear everything down
     * when disabled. Called from the Settings switch.
     *
     * Callers that run inside an Activity should call [ensureNotificationPermission] first,
     * so the foreground-service notification is actually visible on API 33+.
     */
    fun setEnabled(enabled: Boolean) {
        KeepAlivePrefs.setEnabled(context, enabled)
        if (enabled) {
            start()
        } else {
            stop()
        }
    }

    fun isEnabled(): Boolean = KeepAlivePrefs.isEnabled(context)

    /**
     * Ask for POST_NOTIFICATIONS on API 33+.
     *
     * The keep-alive foreground service posts an ongoing notification. Without the runtime
     * grant on API 33+ that notification is suppressed: the service still runs, but the
     * user has no visible indication of it and no way to stop it, and some OEM skins kill
     * a foreground service that has no visible notification.
     *
     * Must be called from an Activity — a Service cannot request a runtime permission, and
     * the request has to be tied to a visible window. No-op below API 33 and no-op when the
     * permission is already granted, so it is safe to call on every enable.
     *
     * @param activity the Activity that will receive
     *   [Activity.onRequestPermissionsResult] for [requestCode].
     * @param requestCode caller-owned request code, echoed back to that Activity.
     */
    fun ensureNotificationPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            requestCode
        )
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Intent that opens the system dialog asking for the battery exemption. */
    fun batteryExemptionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    /**
     * Per-OEM autostart settings screen.
     *
     * Returns null when the OEM has no known screen, in which case the caller should fall
     * back to the app details page.
     */
    fun autostartIntent(): Intent? {
        val candidates = listOf(
            // Xiaomi
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // Huawei
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // Oppo / Realme
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            // Vivo
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            // Asus
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity"
            ),
            // Letv
            ComponentName(
                "com.letv.android.letvsafe",
                "com.letv.android.letvsafe.AutobootManageActivity"
            )
        )
        for (c in candidates) {
            try {
                val i = Intent().setComponent(c)
                if (context.packageManager.resolveActivity(i, 0) != null) return i
            } catch (e: Exception) {
                // Component not present on this device.
            }
        }
        return null
    }

    /** App details page, the universal fallback. */
    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Recompute and persist the keep-alive level for every instance. */
    suspend fun refreshLevels() {
        val level = strength()
        manager.snapshot().forEach { manager.setKeepAliveLevel(it.id, level) }
    }

    /** Schedule the resurrection pulse. */
    fun schedulePulse() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pulseIntent()
        val triggerAt = System.currentTimeMillis() + PULSE_INTERVAL_MS
        try {
            // API 31+ can revoke SCHEDULE_EXACT_ALARM at any time: the user can turn
            // "Alarms & reminders" off, and it is off by default for apps targeting 33+
            // on a fresh install. setExactAndAllowWhileIdle() then throws SecurityException
            // on every single schedule, which the catch below turned into one log line per
            // pulse while the alarm was never actually armed. Check the capability first
            // and go straight to the inexact alarm.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // The capability can still be revoked between the check above and the call.
            Log.w(TAG, "exact alarm denied, using inexact: ${e.message}")
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e2: Exception) {
                Log.w(TAG, "inexact alarm also failed: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "schedulePulse failed: ${e.message}")
        }
    }

    fun cancelPulse() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pulseIntent())
    }

    /** Called from the alarm receiver after a kill. */
    fun scheduleResurrection() {
        schedulePulse()
        scheduleJob()
    }

    private fun pulseIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST,
        Intent(context, KeepAliveReceiver::class.java)
            .setAction(KeepAliveService.ACTION_PULSE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleJob() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, KeepAliveJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)

        // JobInfo.build() rejects an expedited job that also carries a time constraint:
        //   IllegalArgumentException("An expedited job cannot have a time delay")
        // setMinimumLatency() sets hasEarlyConstraint, so combining it with
        // setExpedited(true) threw on every API 31+ device and the job was never
        // scheduled at all. The exception was swallowed by the catch below, so this
        // whole keep-alive layer silently did nothing.
        //
        // Expedited jobs run as soon as possible by definition, so the latency is
        // dropped on the expedited path and kept on the legacy path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(true)
        } else {
            builder.setMinimumLatency(PULSE_INTERVAL_MS)
        }

        try {
            js.schedule(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "job schedule failed: ${e.message}")
        }
    }

    private fun cancelJob() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        js.cancel(JOB_ID)
    }
}
