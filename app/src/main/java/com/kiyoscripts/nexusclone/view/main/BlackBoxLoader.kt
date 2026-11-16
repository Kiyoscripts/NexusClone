package com.kiyoscripts.nexusclone.view.main

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.BActivityThread
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import com.kiyoscripts.nexusclone.app.App
import com.kiyoscripts.nexusclone.app.rocker.RockerManager
import com.kiyoscripts.nexusclone.biz.cache.AppSharedPreferenceDelegate
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/5/6 23:38
 */
class BlackBoxLoader {

    private var mHideRoot by AppSharedPreferenceDelegate(App.getContext(), false)
    private var mHideXposed by AppSharedPreferenceDelegate(App.getContext(), false)
    private var mDaemonEnable by AppSharedPreferenceDelegate(App.getContext(), false)
    private var mShowShortcutPermissionDialog by AppSharedPreferenceDelegate(App.getContext(), true)

    fun hideRoot(): Boolean {
        return try {
            mHideRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hideRoot: ${e.message}")
            false
        }
    }

    fun invalidHideRoot(hideRoot: Boolean) {
        try {
            this.mHideRoot = hideRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error setting hideRoot: ${e.message}")
        }
    }

    fun hideXposed(): Boolean {
        return try {
            mHideXposed
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hideXposed: ${e.message}")
            false
        }
    }

    fun invalidHideXposed(hideXposed: Boolean) {
        try {
            this.mHideXposed = hideXposed
        } catch (e: Exception) {
            Log.e(TAG, "Error setting hideXposed: ${e.message}")
        }
    }

    fun daemonEnable(): Boolean {
        return try {
            mDaemonEnable
        } catch (e: Exception) {
            Log.e(TAG, "Error getting daemonEnable: ${e.message}")
            false
        }
    }

    fun invalidDaemonEnable(enable: Boolean) {
        try {
            this.mDaemonEnable = enable
        } catch (e: Exception) {
            Log.e(TAG, "Error setting daemonEnable: ${e.message}")
        }
    }

    fun showShortcutPermissionDialog(): Boolean {
        return try {
            mShowShortcutPermissionDialog
        } catch (e: Exception) {
            Log.e(TAG, "Error getting showShortcutPermissionDialog: ${e.message}")
            true
        }
    }

    fun invalidShortcutPermissionDialog(show: Boolean) {
        try {
            this.mShowShortcutPermissionDialog = show
        } catch (e: Exception) {
            Log.e(TAG, "Error setting showShortcutPermissionDialog: ${e.message}")
        }
    }

    fun getBlackBoxCore(): BlackBoxCore {
        return try {
            BlackBoxCore.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BlackBoxCore: ${e.message}")
            throw e
        }
    }

    fun addLifecycleCallback() {
        try {
            BlackBoxCore.get().addAppLifecycleCallback(object : AppLifecycleCallback() {
                override fun beforeCreateApplication(
                    packageName: String?,
                    processName: String?,
                    context: Context?,
                    userId: Int
                ) {
                    try {
                        Log.d(
                            TAG,
                            "beforeCreateApplication: pkg $packageName, processName $processName,userID:${BActivityThread.getUserId()}"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in beforeCreateApplication: ${e.message}")
                    }
                }

                override fun beforeApplicationOnCreate(
                    packageName: String?,
                    processName: String?,
                    application: Application?,
                    userId: Int
                ) {
                    try {
                        Log.d(TAG, "beforeApplicationOnCreate: pkg $packageName, processName $processName")

                        // Install the clone's proxy before the guest's Application runs.
                        //
                        // This is the only point where it takes effect: OkHttp captures
                        // ProxySelector.getDefault() when a client is constructed, and a
                        // guest typically builds its clients in Application.onCreate or
                        // shortly after. Installing later would leave the first (and often
                        // only) client on the direct path.
                        //
                        // A clone with no proxy bound resolves to null and this is a no-op,
                        // so the common case pays one database read.
                        if (packageName != null) {
                            try {
                                val ctx = application ?: App.getContext()
                                val inst = com.kiyoscripts.nexusclone.core.instance.InstanceManager
                                    .get(ctx).byKey(userId, packageName)
                                val proxyId = inst?.proxyId
                                if (proxyId != null) {
                                    val profile = com.kiyoscripts.nexusclone.core.net.ProxyStore(ctx)
                                        .byId(proxyId)
                                    if (profile != null && profile.enabled) {
                                        val ok = com.kiyoscripts.nexusclone.core.net
                                            .FixedProxySelector.installDefault(profile.toJavaProxy())
                                        Log.d(
                                            TAG,
                                            "proxy ${profile.display} for $packageName/$userId installed=$ok"
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "proxy install failed for $packageName: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in beforeApplicationOnCreate: ${e.message}")
                    }
                }

                override fun afterApplicationOnCreate(
                    packageName: String?,
                    processName: String?,
                    application: Application?,
                    userId: Int
                ) {
                    try {
                        Log.d(TAG, "afterApplicationOnCreate: pkg $packageName, processName $processName")
                        RockerManager.init(application,userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in afterApplicationOnCreate: ${e.message}")
                    }
                }

                override fun onActivityResumed(activity: android.app.Activity) {
                    try {
                        val pkg = activity.packageName ?: return
                        val uid = BActivityThread.getUserId()

                        // Feed the macro recorder from the guest's own touch stream. The
                        // recorder is a no-op unless a recording is active, so this costs a
                        // null check per event in the common case.
                        try {
                            val decor = activity.window?.decorView
                            if (decor != null) {
                                // Publish the view so macro replay has a real target, and
                                // feed the recorder from the guest's own touch stream. The
                                // recorder is a no-op unless a recording is active, so this
                                // costs a null check per event in the common case.
                                com.kiyoscripts.nexusclone.core.macro.MacroRecorder
                                    .attachGuestView(decor)
                                decor.setOnTouchListener { v, ev ->
                                    com.kiyoscripts.nexusclone.core.macro.MacroRecorder
                                        .feed(ev, v)
                                    false
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "macro touch hook failed for $pkg: ${e.message}")
                        }

                        GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.kiyoscripts.nexusclone.core.instance.InstanceManager
                                .get(activity.applicationContext)
                                .onGuestResumed(pkg, uid)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onActivityResumed failed: ${e.message}")
                    }
                }

                override fun onActivityPaused(activity: android.app.Activity) {
                    try {
                        val pkg = activity.packageName ?: return
                        val uid = BActivityThread.getUserId()

                        // Capture the thumbnail here, synchronously, before the coroutine
                        // below yields. This is the last moment the guest's Window still has
                        // a valid surface: once the pause completes the surface can be
                        // destroyed and PixelCopy returns ERROR_SOURCE_INVALID. Doing it
                        // inside the coroutine would race the surface teardown.
                        try {
                            com.kiyoscripts.nexusclone.core.preview.PreviewCache(
                                activity.applicationContext
                            ).captureGuest(activity, pkg, uid)
                        } catch (e: Exception) {
                            Log.w(TAG, "preview capture failed for $pkg: ${e.message}")
                        }

                        GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.kiyoscripts.nexusclone.core.instance.InstanceManager
                                .get(activity.applicationContext)
                                .onGuestPaused(pkg, uid)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onActivityPaused failed: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error adding lifecycle callback: ${e.message}")
        }
    }

    fun attachBaseContext(context: Context) {
        try {
            BlackBoxCore.get().doAttachBaseContext(context, object : ClientConfiguration() {
                override fun getHostPackageName(): String {
                    return try {
                        context.packageName
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting package name: ${e.message}")
                        "unknown"
                    }
                }

                override fun isHideRoot(): Boolean {
                    return try {
                        mHideRoot
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking hideRoot: ${e.message}")
                        false
                    }
                }

                override fun isHideXposed(): Boolean {
                    return try {
                        mHideXposed
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking hideXposed: ${e.message}")
                        false
                    }
                }

                override fun isEnableDaemonService(): Boolean {
                    return try {
                        mDaemonEnable
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking daemonEnable: ${e.message}")
                        false
                    }
                }

                override fun requestInstallPackage(file: File?, userId: Int): Boolean {
                    return try {
                        if (file == null) {
                            Log.w(TAG, "requestInstallPackage: file is null")
                            return false
                        }
                        val packageInfo =
                            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                        false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in requestInstallPackage: ${e.message}")
                        false
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in attachBaseContext: ${e.message}")
        }
    }

    /**
     * One-time engine bring-up.
     *
     * This is a blocking call: [BlackBoxCore.doCreate] probes the `:black` provider with
     * retries and then makes a binder round-trip per service, which can take tens of seconds
     * on a cold start. It must never be called from the main thread — `App.onCreate()` runs
     * it on the "nexus-engine-init" thread and publishes readiness through
     * `AppManager.awaitEngineReady()`.
     *
     * Nothing here touches the UI or requires a Looper, so it is safe on any thread. The
     * service-available callback registered below only logs, and `BlackBoxCore` may invoke it
     * on whichever thread finishes service initialisation.
     */
    fun doOnCreate(context: Context) {
        try {
            BlackBoxCore.get().doCreate()
            
            // Register callback to refresh app list when services become available
            try {
                BlackBoxCore.get().addServiceAvailableCallback {
                    Log.d(TAG, "Services became available, triggering app list refresh")
                    // This will be called when services are ready
                    // The UI components can listen for this and refresh their data
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering service available callback: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in doOnCreate: ${e.message}")
        }
    }

    companion object {
        val TAG: String = BlackBoxLoader::class.java.simpleName
    }
}
