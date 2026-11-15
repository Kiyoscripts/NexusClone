package com.kiyoscripts.nexusclone.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import com.kiyoscripts.nexusclone.core.identity.IdentityProvider
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import com.kiyoscripts.nexusclone.core.quota.QuotaWorker
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
        } catch (e: Exception) {
            Log.e("App", "Error in onCreate: ${e.message}")
        }

        // Bring the container engine up on a dedicated background thread.
        //
        // AppManager.doOnCreate() -> BlackBoxLoader.doOnCreate() -> BlackBoxCore.doCreate()
        // is a blocking chain: a provider probe that retries with SystemClock.sleep(200) for
        // up to 2 s, a 5-iteration Thread.sleep(500) wait loop that re-runs that probe, and
        // then a getService() per service, each of which sleeps 1 s and then makes a 3 s
        // provider call. Worst case that is ~66 s. Running it inline in Application.onCreate()
        // meant onCreate never returned, the first traversal never happened, the window stayed
        // at mDrawState=DRAW_PENDING and the user got a black screen.
        //
        // mContext is assigned in attachBaseContext(), which the framework always calls before
        // onCreate(), so reading it from this thread is safe. The thread is started here, after
        // that assignment, so there is no window where it could observe an uninitialised value.
        try {
            val context = mContext
            Thread({
                try {
                    // Register the identity bridge before the engine can start a guest, so
                    // the first bind already carries the clone's own fingerprint instead of
                    // the shared fallback.
                    try {
                        IdentityProvider.install(context)
                    } catch (e: Throwable) {
                        Log.w("App", "identity provider install failed: ${e.message}")
                    }
                    AppManager.doOnCreate(context)
                } catch (e: Throwable) {
                    // A background thread must never take the process down. doOnCreate()
                    // already publishes engine readiness in its finally block, so the UI is
                    // released even when this fires.
                    Log.e("App", "engine init failed: ${e.message}", e)
                }
            }, "nexus-engine-init").start()
        } catch (e: Throwable) {
            // Either the context was never assigned (attachBaseContext failed outright) or
            // thread creation itself failed (OOM, thread limit). Release anything waiting on
            // the readiness signal so the UI degrades to its empty state instead of hanging.
            Log.e("App", "could not start engine init: ${e.message}", e)
            AppManager.markEngineReady()
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

        // Arm the storage-quota sweep. AlarmManager only, so this is safe from
        // Application.onCreate; the sweep itself runs in QuotaReceiver, never here.
        try {
            QuotaWorker.schedule(this)
        } catch (e: Exception) {
            Log.w("App", "quota scheduling failed: ${e.message}")
        }

        // Seed the default virtual user before any Activity exists.
        //
        // The engine seeds user 0 itself in BUserManagerService.systemReady(), in memory
        // and without a disk write, so nothing is needed here. Do NOT call into the
        // engine from Application.onCreate: at this point the :black provider has not
        // been published yet, so any getUsers()/createUser() call blocks on provider
        // startup and the first Activity never gets a chance to draw. The engine bring-up
        // above is on its own thread for exactly that reason; the UI waits for
        // AppManager.awaitEngineReady() before it asks the engine for anything.
    }
}
