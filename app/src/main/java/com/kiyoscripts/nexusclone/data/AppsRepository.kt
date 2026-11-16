package com.kiyoscripts.nexusclone.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.app.App
import com.kiyoscripts.nexusclone.app.AppManager
import com.kiyoscripts.nexusclone.bean.AppInfo
import com.kiyoscripts.nexusclone.bean.InstalledAppBean
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.util.MemoryManager
import com.kiyoscripts.nexusclone.util.inflate
import com.kiyoscripts.nexusclone.util.getString
import java.io.File
import android.webkit.URLUtil


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
     * Safely load app icon with fallback to null
     */
    private fun safeLoadAppIcon(applicationInfo: ApplicationInfo): Drawable? {
        val pkg = applicationInfo.packageName ?: return null

        // Cache hit: no resource resolution, no decode, no binder call.
        iconCache.get(pkg)?.let { return it }

        return try {
            // Check if we should skip icon loading to save memory
            if (MemoryManager.shouldSkipIconLoading()) {
                Log.w(TAG, "Memory usage high (${MemoryManager.getMemoryUsagePercentage()}%), skipping icon for $pkg")
                return null
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
            null // Fallback to null icon
        }
    }

    fun previewInstallList() {
        try {
            synchronized(mInstalledList) {
                val installedApplications: List<ApplicationInfo> =
                    BlackBoxCore.getPackageManager().getInstalledApplications(0)
                val installedList = mutableListOf<AppInfo>()

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

                        val isXpModule = BlackBoxCore.get().isXposedModule(file)

                        val info = AppInfo(
                            safeLoadAppLabel(installedApplication),
                            safeLoadAppIcon(installedApplication), // Remove the !! operator to allow null icons
                            installedApplication.packageName,
                            installedApplication.sourceDir,
                            isXpModule
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
     * Call [previewInstallList] first; this reads the list it populated.
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
                    it.icon,
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
                        it.icon, // Remove the !! operator to allow null icons
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
                        it.icon, // Remove the !! operator to allow null icons
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

    fun getVmInstallList(userId: Int, appsLiveData: MutableLiveData<List<AppInfo>>) {
        try {
            // Check memory status before starting
            if (MemoryManager.isMemoryCritical()) {
                Log.w(TAG, "Memory critical (${MemoryManager.getMemoryUsagePercentage()}%), forcing garbage collection")
                MemoryManager.forceGarbageCollectionIfNeeded()
            }
            
            val blackBoxCore = BlackBoxCore.get()
            
            // Add debugging for users
            val users = blackBoxCore.users
            Log.d(TAG, "getVmInstallList: userId=$userId, total users=${users.size}")
            users.forEach { user ->
                Log.d(TAG, "User: id=${user.id}, name=${user.name}")
            }
            
            val sortListData =
                AppManager.mRemarkSharedPreferences.getString("AppList$userId", "")
            val sortList = sortListData?.split(",")

            // Add retry mechanism for getting installed applications
            var applicationList: List<ApplicationInfo>? = null
            var retryCount = 0
            val maxRetries = 3
            
            while (applicationList == null && retryCount < maxRetries) {
                try {
                    applicationList = blackBoxCore.getInstalledApplications(0, userId)
                    if (applicationList == null) {
                        Log.w(TAG, "getVmInstallList: Attempt ${retryCount + 1} returned null, retrying...")
                        retryCount++
                        Thread.sleep(100) // Small delay before retry
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getVmInstallList: Error getting applications on attempt ${retryCount + 1}: ${e.message}")
                    retryCount++
                    if (retryCount < maxRetries) {
                        Thread.sleep(200) // Longer delay for errors
                    }
                }
            }
            
            // Add null check for applicationList
            if (applicationList == null) {
                Log.e(TAG, "getVmInstallList: applicationList is null for userId=$userId after $maxRetries attempts")
                appsLiveData.postValue(emptyList())
                return
            }
            
            // Add debugging
            Log.d(TAG, "getVmInstallList: userId=$userId, applicationList.size=${applicationList.size}")
            if (applicationList.isNotEmpty()) {
                Log.d(TAG, "First app: ${applicationList.first().packageName}")
            } else {
                Log.w(TAG, "getVmInstallList: No applications found for userId=$userId")
            }

            val appInfoList = mutableListOf<AppInfo>()

            // One binder round-trip for the whole list, not one per row. See
            // xposedModulePackages() for why this matters once GMS is installed.
            val xpModules = xposedModulePackages()
            
            // Sort the application list if sort data exists
            val sortedApplicationList = if (!sortList.isNullOrEmpty()) {
                try {
                    applicationList.sortedWith(AppsSortComparator(sortList))
                } catch (e: Exception) {
                    Log.e(TAG, "getVmInstallList: Error sorting applications: ${e.message}")
                    applicationList // Return unsorted list if sorting fails
                }
            } else {
                applicationList
            }
            
            // Process each application with enhanced error handling
            sortedApplicationList.forEachIndexed { index, applicationInfo ->
                try {
                    // Check memory periodically during processing
                    if (index > 0 && index % 25 == 0) {
                        if (MemoryManager.isMemoryCritical()) {
                            Log.w(TAG, "Memory critical during processing, forcing GC")
                            MemoryManager.forceGarbageCollectionIfNeeded()
                        }
                    }
                    
                    // Add null check for applicationInfo
                    if (applicationInfo == null) {
                        Log.w(TAG, "getVmInstallList: Skipping null applicationInfo at index $index")
                        return@forEachIndexed
                    }
                    
                    // Validate package name
                    if (applicationInfo.packageName.isNullOrBlank()) {
                        Log.w(TAG, "getVmInstallList: Skipping app with null/blank package name at index $index")
                        return@forEachIndexed
                    }
                    
                    val info = AppInfo(
                        safeLoadAppLabel(applicationInfo),
                        safeLoadAppIcon(applicationInfo), // Remove the !! operator to allow null icons
                        applicationInfo.packageName,
                        applicationInfo.sourceDir ?: "",
                        applicationInfo.packageName in xpModules
                    )

                    appInfoList.add(info)
                    
                    // Log progress for large lists
                    if (index > 0 && index % 50 == 0) {
                        Log.d(TAG, "getVmInstallList: Processed $index/${sortedApplicationList.size} apps - ${MemoryManager.getMemoryInfo()}")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "getVmInstallList: Error processing app at index $index (${applicationInfo?.packageName}): ${e.message}")
                    // Continue processing other apps instead of failing completely
                }
            }

            Log.d(TAG, "getVmInstallList: processed ${appInfoList.size} apps - ${MemoryManager.getMemoryInfo()}")
            
            // If no virtual apps found, show empty list (correct behavior for new users)
            // Do NOT load regular installed apps as fallback - this causes the bug
            if (appInfoList.isEmpty()) {
                Log.d(TAG, "getVmInstallList: No virtual apps found for userId=$userId, showing empty list (correct for new users)")
            } else {
                Log.d(TAG, "getVmInstallList: Showing ${appInfoList.size} virtual apps for userId=$userId")
            }
            
            // Post the result safely
            try {
                appsLiveData.postValue(appInfoList)
            } catch (e: Exception) {
                Log.e(TAG, "getVmInstallList: Error posting to LiveData: ${e.message}")
                // Try to post on main thread as fallback
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            appsLiveData.postValue(appInfoList)
                        } catch (e2: Exception) {
                            Log.e(TAG, "getVmInstallList: Fallback posting also failed: ${e2.message}")
                        }
                    }
                } catch (e3: Exception) {
                    Log.e(TAG, "getVmInstallList: Could not schedule fallback posting: ${e3.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in getVmInstallList: ${e.message}")
            try {
                appsLiveData.postValue(emptyList())
            } catch (e2: Exception) {
                Log.e(TAG, "getVmInstallList: Error posting empty list: ${e2.message}")
            }
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

    private fun isInstalledXpModule(packageName: String): Boolean {
        return try {
            BlackBoxCore.get().installedXPModules.forEach {
                if (packageName == it.packageName) {
                    return@isInstalledXpModule true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Xposed module: ${e.message}")
            false
        }
    }

    fun installApk(source: String, userId: Int, resultLiveData: MutableLiveData<String>) {
        try {
            // Refuse to install the container into itself.
            //
            // The cheap pre-filter below is a substring test on the source string, which is
            // only a hint: a renamed apk will not match it. The authoritative check is the
            // package-name comparison inside, which compares the archive's package against
            // the host package. The pre-filter exists so the common case does not pay for
            // an archive parse.
            if (source.contains("nexusclone") || source.contains("blackbox") ||
                source.contains("niunaijun") || source.contains("vspace") ||
                source.contains("virtual")
            ) {
                try {
                    val blackBoxCore = BlackBoxCore.get()
                    val hostPackageName = BlackBoxCore.getHostPkg()

                    // If it's a file path, try to check the package name
                    if (!URLUtil.isValidUrl(source)) {
                        val file = File(source)
                        if (file.exists()) {
                            val packageInfo = BlackBoxCore.getPackageManager().getPackageArchiveInfo(source, 0)
                            if (packageInfo != null && packageInfo.packageName == hostPackageName) {
                                resultLiveData.postValue(getString(R.string.install_self_refused))
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not verify whether this is the host app: ${e.message}")
                }
            }
            
            val blackBoxCore = BlackBoxCore.get()
            val installResult = if (URLUtil.isValidUrl(source)) {
                val uri = Uri.parse(source)
                blackBoxCore.installPackageAsUser(uri, userId)
            } else {
                blackBoxCore.installPackageAsUser(source, userId)
            }

            if (installResult.success) {
                updateAppSortList(userId, installResult.packageName, true)
                resultLiveData.postValue(getString(R.string.install_success))
            } else {
                resultLiveData.postValue(getString(R.string.install_fail, installResult.msg))
            }
            scanUser()
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK: ${e.message}")
            resultLiveData.postValue("Installation failed: ${e.message}")
        }
    }

    fun unInstall(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userID)
            updateAppSortList(userID, packageName, false)
            scanUser()
            resultLiveData.postValue(getString(R.string.uninstall_success))
        } catch (e: Exception) {
            Log.e(TAG, "Error uninstalling APK: ${e.message}")
            resultLiveData.postValue("Uninstallation failed: ${e.message}")
        }
    }

    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        try {
            val result = BlackBoxCore.get().launchApk(packageName, userId)
            launchLiveData.postValue(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching APK: ${e.message}")
            launchLiveData.postValue(false)
        }
    }

    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().clearPackage(packageName, userID)
            resultLiveData.postValue(getString(R.string.clear_success))
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing APK data: ${e.message}")
            resultLiveData.postValue("Clear failed: ${e.message}")
        }
    }

    /**
     * 倒序递归扫描用户，
     * 如果用户是空的，就删除用户，删除用户备注，删除应用排序列表
     */
    private fun scanUser() {
        try {
            val blackBoxCore = BlackBoxCore.get()
            val userList = blackBoxCore.users

            if (userList.isEmpty()) {
                return
            }

            val id = userList.last().id

            if (blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
                blackBoxCore.deleteUser(id)
                AppManager.mRemarkSharedPreferences.edit().apply {
                    remove("Remark$id")
                    remove("AppList$id")
                    apply()
                }
                scanUser()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    /**
     * 更新排序列表
     * @param userID Int
     * @param pkg String
     * @param isAdd Boolean true是添加，false是移除
     */
    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {
        try {
            val savedSortList =
                AppManager.mRemarkSharedPreferences.getString("AppList$userID", "")

            val sortList = linkedSetOf<String>()
            if (savedSortList != null) {
                sortList.addAll(savedSortList.split(","))
            }

            if (isAdd) {
                sortList.add(pkg)
            } else {
                sortList.remove(pkg)
            }

            AppManager.mRemarkSharedPreferences.edit().apply {
                putString("AppList$userID", sortList.joinToString(","))
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating app sort list: ${e.message}")
        }
    }

    /**
     * 保存排序后的apk顺序
     */
    fun updateApkOrder(userID: Int, dataList: List<AppInfo>) {
        try {
            AppManager.mRemarkSharedPreferences.edit().apply {
                putString("AppList$userID",
                    dataList.joinToString(",") { it.packageName })
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating APK order: ${e.message}")
        }
    }
}
