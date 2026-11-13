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
        try {
            KeepAliveController.get(this).start()
        } catch (e: Exception) {
            Log.w(TAG, "job restart failed: ${e.message}")
        }
        // Work is done synchronously; nothing to reschedule.
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
