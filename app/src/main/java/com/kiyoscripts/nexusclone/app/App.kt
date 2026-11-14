package com.kiyoscripts.nexusclone.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Host application.
 *
 * Order matters: the container must attach before anything touches a guest, and the
 * keep-alive service must start after the container is up so the process it anchors is
 * the one that actually holds the clones.
 */
class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)

            try {
                BlackBoxCore.get().closeCodeInit()
            } catch (e: Exception) {
                Log.e("App", "Error in closeCodeInit: ${e.message}")
            }

            try {
                BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
            } catch (e: Exception) {
                Log.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}")
            }

            mContext = base!!

            try {
                AppManager.doAttachBaseContext(base)
            } catch (e: Exception) {
                Log.e("App", "Error in doAttachBaseContext: ${e.message}")
            }

            try {
                BlackBoxCore.get().onAfterMainApplicationAttach(this, base)
            } catch (e: Exception) {
                Log.e("App", "Error in onAfterMainApplicationAttach: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("App", "Critical error in attachBaseContext: ${e.message}")
            if (base != null) {
                mContext = base
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            AppManager.doOnCreate(mContext)
        } catch (e: Exception) {
            Log.e("App", "Error in onCreate: ${e.message}")
        }

        // Anchor the host process so warm clones survive backgrounding. Failure here is
        // non-fatal: the app still works, clones just get killed sooner.
        //
        // Do NOT start the foreground service from Application.onCreate(). On Android 12+
        // that is a background start, and startForegroundService() then throws
        // ForegroundServiceStartNotAllowedException inside the service, leaving fgRequired
        // set and producing:
        //   Context.startForegroundService() did not then call Service.startForeground()
        //   ServiceRecord{.. KeepAliveService}
        // which ANRs and kills the host. Only the scheduling layers are safe here; the
        // service itself is started once an Activity is actually visible.
        try {
            KeepAliveController.get(this).scheduleOnly()
        } catch (e: Exception) {
            Log.w("App", "keep-alive scheduling failed: ${e.message}")
        }

        // Seed the default virtual user before any Activity exists.
        //
        // The engine seeds user 0 itself in BUserManagerService.systemReady(), in memory
        // and without a disk write, so nothing is needed here. Do NOT call into the
        // engine from Application.onCreate: at this point the :black provider has not
        // been published yet, so any getUsers()/createUser() call blocks on provider
        // startup and the first Activity never gets a chance to draw.
    }
}
