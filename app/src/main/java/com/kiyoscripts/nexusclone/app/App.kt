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
        try {
            KeepAliveController.get(this).start()
        } catch (e: Exception) {
            Log.w("App", "keep-alive start failed: ${e.message}")
        }

        // Seed the default virtual user off the main thread.
        //
        // The engine never creates user 0 on a fresh install: BUserManagerService
        // .systemReady() only calls scanUserL(), which returns early when userinfo.conf
        // is absent, so getUsers() stays empty and the app grid renders blank.
        //
        // It cannot be seeded inside the engine either. systemReady() runs from
        // SystemCallProvider.onCreate(), which the framework kills after 10 seconds
        // ("timeout publishing content providers"), and getUsers() is a synchronous
        // binder call made from MainActivity.initViewPager() on the main thread, so
        // doing the AtomicFile write there blocks the UI and the screen stays black.
        //
        // A background thread in the host process avoids both: the write happens once,
        // off the main thread, and the result is persisted for every later boot.
        Thread({
            try {
                if (BlackBoxCore.get().users.isEmpty()) {
                    BlackBoxCore.get().createUser(0)
                    Log.i("App", "seeded virtual user 0")
                }
            } catch (e: Throwable) {
                Log.w("App", "failed to seed virtual user 0: ${e.message}")
            }
        }, "nexus-seed-user0").start()
    }
}
