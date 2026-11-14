package com.kiyoscripts.nexusclone.core.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
        schedulePulse()
        scheduleJob()
    }

    fun stop() {
        KeepAliveService.stop(context)
        cancelPulse()
        cancelJob()
        serviceStarted = false
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // Exact alarms denied on API 31+ without SCHEDULE_EXACT_ALARM; fall back.
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
