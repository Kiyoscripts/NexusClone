package com.kiyoscripts.nexusclone.data

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import com.kiyoscripts.nexusclone.app.App
import com.kiyoscripts.nexusclone.bean.AppInfo
import com.kiyoscripts.nexusclone.bean.InstalledAppBean
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.util.MemoryManager
import java.io.File


/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 23:05
 */

class AppsRepository {
    val TAG: String = "AppsRepository"
    private var mInstalledList = mutableListOf<AppInfo>()

    companion object {
        /**
         * Decoded icons, keyed by package name.
         *
         * Loading an icon is not free: getApplicationIcon() resolves the package's resources
         * through the host PackageManager, which is a binder round-trip, and then decodes the
         * bitmap. The app grid rebinds on every tab switch, every page swipe and every scroll
         * back into view, so without a cache the same handful of icons is re-resolved and
         * re-decoded continuously. That is a large part of why the grid felt heavy once GMS
         * added thirteen more packages to every user.
         *
         * A 96x96 ARGB_8888 icon is ~36 KB, so 128 entries is ~4.6 MB worst case. The bound is
         * deliberately modest: this is a cache, not a store, and the host process is the one
         * that has to stay responsive.
         */
        private val iconCache = LruCache<String, Drawable>(128)

        /** Drop every cached icon. Called when the installed set changes. */
        fun clearIconCache() {
            iconCache.evictAll()
        }
    }

    /**
     * Safely load app label with fallback to package name
     */
    private fun safeLoadAppLabel(applicationInfo: ApplicationInfo): String {
        return try {
            BlackBoxCore.getPackageManager().getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load label for ${applicationInfo.packageName}: ${e.message}")
            applicationInfo.packageName // Fallback to package name
        }
    }
    
    /**
     * Safely load app icon.
     *
     * Never returns null for a package that has an ApplicationInfo: when the memory guard
     * trips, the caller gets [MemoryManager.placeholderIcon] instead of nothing, so a row
     * always has something to draw and the grid does not fall back to a blank cell.
     */
    private fun safeLoadAppIcon(applicationInfo: ApplicationInfo): Drawable? {
        val pkg = applicationInfo.packageName ?: return null

        // Cache hit: no resource resolution, no decode, no binder call.
        iconCache.get(pkg)?.let { return it }

        return try {
            // Check if we should skip icon loading to save memory. The placeholder is not
            // cached: it is cheap to build and caching it would pin a fake icon in place
            // for the rest of the process once memory recovered.
            if (MemoryManager.shouldSkipIconLoading()) {
                Log.w(TAG, "Memory usage high (${MemoryManager.getMemoryUsagePercentage()}%), using placeholder icon for $pkg")
                return MemoryManager.placeholderIcon(App.getContext())
            }

            val icon = BlackBoxCore.getPackageManager().getApplicationIcon(applicationInfo)

            // Optimize icon for memory efficiency
            val optimized: Drawable = if (icon is android.graphics.drawable.BitmapDrawable) {
                val bitmap = icon.bitmap
                // If icon is too large, scale it down to save memory
                if (bitmap.width > 96 || bitmap.height > 96) {
                    try {
                        val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                        android.graphics.drawable.BitmapDrawable(
                            BlackBoxCore.getPackageManager().getResourcesForApplication(pkg),
                            scaledBitmap
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to scale icon for $pkg: ${e.message}")
                        icon
                    }
                } else {
                    icon
                }
            } else {
                icon
            }

            iconCache.put(pkg, optimized)
            optimized
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon for $pkg: ${e.message}")
            MemoryManager.placeholderIcon(App.getContext())
        }
    }

    /**
     * Resolve and cache the icon for one package, on demand.
     *
     * [previewInstallList] can be asked to skip icon decoding entirely, which is what the
     * picker does: it only ever shows the rows that are on screen. This resolves a single
     * icon when a row actually needs one, so the cost is one decode per visible package
     * instead of one per installed package.
     *
     * The ApplicationInfo is looked up from the stored list first, so a package that is not
     * in the list is not resolved at all.
     */
    fun iconFor(packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }

        val stored = synchronized(mInstalledList) {
            mInstalledList.firstOrNull { it.packageName == packageName }
        } ?: return null

        stored.icon?.let {
            iconCache.put(packageName, it)
            return it
        }

        val applicationInfo = try {
            BlackBoxCore.getPackageManager().getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            Log.w(TAG, "no ApplicationInfo for $packageName: ${e.message}")
            return null
        }
        return safeLoadAppIcon(applicationInfo)
    }

    /**
     * Populate the installed-app list.
     *
     * @param withIcons when false (the default) no icon is decoded at all and every stored
     *        [AppInfo] carries a null icon. Callers that only render a few rows should leave
     *        this false and pull icons through [iconFor] as rows bind; decoding the whole
     *        installed set up front is one binder round-trip plus one bitmap decode per
     *        package, which is the single most expensive thing this class does.
     */
    fun previewInstallList(withIcons: Boolean = false) {
        try {
            synchronized(mInstalledList) {
                val installedApplications: List<ApplicationInfo> =
                    BlackBoxCore.getPackageManager().getInstalledApplications(0)
                val installedList = mutableListOf<AppInfo>()

                // Resolve the installed-module set once, before the loop.
                //
                // This is not the same question as "is this apk an Xposed module", which is
                // what BlackBoxCore.get().isXposedModule(file) answers by parsing the
                // archive. The two sets differ: the archive parse says what the apk claims
                // to be, the installed set says what the engine actually has installed as a
                // module. getInstalledModuleList needs the installed answer, so the archive
                // parse cannot be substituted for it. What can go away is doing the parse
                // per app: the installed set is one binder round-trip for the whole list,
                // and a package that is not in it is not a module as far as the engine is
                // concerned, so it is reported as false without touching its archive.
                val xpModules = xposedModulePackages()

                for (installedApplication in installedApplications) {
                    try {
                        val file = File(installedApplication.sourceDir)

                        if ((installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

                        if (!AbiUtils.isSupport(file)) continue

                        // Filter out BlackBox apps to prevent cloning
                        if (BlackBoxCore.get().isBlackBoxApp(installedApplication.packageName)) {
                            Log.d(TAG, "Filtering out BlackBox app: ${installedApplication.packageName}")
                            continue
                        }

                        val info = AppInfo(
                            safeLoadAppLabel(installedApplication),
                            if (withIcons) safeLoadAppIcon(installedApplication) else null,
                            installedApplication.packageName,
                            installedApplication.sourceDir,
                            installedApplication.packageName in xpModules
                        )
                        installedList.add(info)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing app ${installedApplication.packageName}: ${e.message}")
                    }
                }
                this.mInstalledList.clear()
                this.mInstalledList.addAll(installedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in previewInstallList: ${e.message}")
        }
    }

    /**
     * The installed-app list as picker rows, with no per-user install state.
     *
     * [getInstalledAppList] answers "is this package already cloned into user N", which
     * needs a virtual user id. The redesigned picker is not scoped to a user: a clone
     * allocates its own user, so the only question the row asks is whether the package has
     * been cloned at all. That is answered here from the instance store, not from the
     * engine, so this call does not touch the :black process.
     *
     * Call [previewInstallList] first; this reads the list it populated. Rows whose stored
     * [AppInfo] has no icon get one resolved here, so the picker still shows icons while
     * paying for one decode per returned row rather than one per installed package.
     */
    fun installedAppsSnapshot(): List<InstalledAppBean> {
        val cloned: Set<String> = try {
            InstanceManager.get(App.getContext()).snapshot()
                .mapTo(HashSet()) { it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "could not read clone list: ${e.message}")
            emptySet()
        }

        return synchronized(mInstalledList) {
            mInstalledList.map {
                InstalledAppBean(
                    it.name,
                    it.icon ?: iconFor(it.packageName),
                    it.packageName,
                    it.sourceDir,
                    it.packageName in cloned
                )
            }
        }
    }

    fun getInstalledAppList(
        userID: Int,
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        try {
            loadingLiveData.postValue(true)
            synchronized(mInstalledList) {
                val blackBoxCore = BlackBoxCore.get()

                // Resolve the user's installed set once instead of asking the engine
                // "is this one installed?" per row. Each isInstalled() is a synchronous
                // binder transaction into :black, so the old shape cost one round-trip per
                // app in the picker. With GMS enabled that is thirteen extra packages per
                // user, i.e. thirteen extra round-trips every time the picker opens.
                val installedHere = try {
                    blackBoxCore.getInstalledApplications(0, userID)
                        .mapTo(HashSet()) { it.packageName }
                } catch (e: Exception) {
                    Log.w(TAG, "could not list installed apps for user $userID: ${e.message}")
                    emptySet()
                }

                val newInstalledList = mInstalledList.map {
                    InstalledAppBean(
                        it.name,
                        it.icon ?: iconFor(it.packageName),
                        it.packageName,
                        it.sourceDir,
                        it.packageName in installedHere
                    )
                }
                appsLiveData.postValue(newInstalledList)
                loadingLiveData.postValue(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getInstalledAppList: ${e.message}")
            loadingLiveData.postValue(false)
            appsLiveData.postValue(emptyList())
        }
    }

    fun getInstalledModuleList(
        loadingLiveData: MutableLiveData<Boolean>,
        appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        try {
            loadingLiveData.postValue(true)
            synchronized(mInstalledList) {
                // Same hoist as getInstalledAppList: one lookup, not one per row.
                val xpModules = xposedModulePackages()
                val moduleList = mInstalledList.filter {
                    it.isXpModule
                }.map {
                    InstalledAppBean(
                        it.name,
                        it.icon ?: iconFor(it.packageName),
                        it.packageName,
                        it.sourceDir,
                        it.packageName in xpModules
                    )
                }
                appsLiveData.postValue(moduleList)
                loadingLiveData.postValue(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getInstalledModuleList: ${e.message}")
            loadingLiveData.postValue(false)
            appsLiveData.postValue(emptyList())
        }
    }

    /**
     * Resolve the set of installed Xposed module package names once.
     *
     * This used to be called per app, from inside the row loop, and each call is a
     * synchronous binder transaction into the :black process
     * (BXposedManager.getInstalledModules() -> IBXposedManagerService). With a bare user that
     * is a handful of rows and the cost is invisible; once GMS is enabled the engine installs
     * thirteen more packages into the user, so the grid pays thirteen-plus extra binder
     * round-trips on every single refresh, on top of the icon resolution. That is the lag.
     *
     * The module set is global (it lives in BUserHandle.USER_XPOSED, not in the caller's
     * user), so one lookup answers every row. Returns an empty set on failure, which makes
     * every row report "not a module" rather than throwing out of the loop.
     */
    private fun xposedModulePackages(): Set<String> = try {
        BlackBoxCore.get().installedXPModules.mapTo(HashSet()) { it.packageName }
    } catch (e: Exception) {
        Log.w(TAG, "could not read Xposed module list: ${e.message}")
        emptySet()
    }
}
