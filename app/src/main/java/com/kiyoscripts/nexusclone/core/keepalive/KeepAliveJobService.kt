package com.kiyoscripts.nexusclone.core.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * Expedited job fallback for the resurrection pulse.
 *
 * Used when AlarmManager is restricted (API 31+ without SCHEDULE_EXACT_ALARM, or an OEM
 * that drops inexact alarms). The job does nothing but restart the foreground service.
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJobService"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        // onStartJob runs on the main thread and the framework times it. Calling
        // KeepAliveController.start() here also arms AlarmManager and JobScheduler, which
        // was enough to produce
        //   am_anr: executing service .. KeepAliveJobService
        // Do the work on a worker and report completion from there.
        Thread({
            try {
                if (KeepAlivePrefs.isEnabled(this)) {
                    KeepAliveController.get(this).start()
                } else {
                    Log.d(TAG, "keep-alive disabled by preference, job is a no-op")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "job restart failed: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }, "nexus-keepalive-job").start()
        // Returning true means work is still in progress; jobFinished() above ends it.
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
