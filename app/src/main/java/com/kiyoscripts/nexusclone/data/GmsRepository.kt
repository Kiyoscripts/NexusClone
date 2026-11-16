package com.kiyoscripts.nexusclone.data

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.app.AppManager
import com.kiyoscripts.nexusclone.bean.GmsBean
import com.kiyoscripts.nexusclone.bean.GmsInstallBean
import com.kiyoscripts.nexusclone.util.getString

/**
 *
 * @Description:
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:14
 */
class GmsRepository {

    /**
     * Report which virtual users have GMS installed.
     *
     * Both calls in the loop are binder round-trips into :black (users, then isInstallGms
     * per user), so this must stay off the main thread. It is called from a coroutine on
     * Dispatchers.IO by GmsViewModel.
     */
    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        BlackBoxCore.get().users.forEach {
            val userId = it.id
            val userName =
                AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: ""
            val isInstalled = BlackBoxCore.get().isInstallGms(userId)
            val bean = GmsBean(userId, userName, isInstalled)
            userList.add(bean)
        }

        mInstalledLiveData.postValue(userList)
    }

    /**
     * Install GMS into [userID].
     *
     * This is the expensive one: GmsCore.installGApps() walks thirteen packages and calls
     * installPackageAsUser() for each, and every install parses the apk, copies it into the
     * virtual user's data dir and writes the package settings. On a cold cache that is
     * seconds of work, not milliseconds.
     *
     * Two things follow from that, and both are handled here rather than in the UI:
     *
     *  1. The icon cache must be dropped afterwards. Thirteen new packages appear in the
     *     user's app list, and a stale cache would keep serving the pre-GMS view of the
     *     world until the process restarted.
     *
     *  2. The result is reported as a single terminal event. The caller shows a spinner for
     *     the whole duration; there is no partial-progress channel because the engine does
     *     not expose one, and inventing a fake progress bar would be worse than an honest
     *     indeterminate spinner.
     */
    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val installResult = try {
            BlackBoxCore.get().installGms(userID)
        } catch (e: Exception) {
            mUpdateInstalledLiveData.postValue(
                GmsInstallBean(
                    userID,
                    false,
                    getString(R.string.install_fail, e.message ?: "unknown")
                )
            )
            return
        }

        // The installed set changed, so every cached icon for this user is suspect.
        AppsRepository.clearIconCache()

        val result = if (installResult.success) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, installResult.msg)
        }

        val bean = GmsInstallBean(userID, installResult.success, result)
        mUpdateInstalledLiveData.postValue(bean)
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        // Same reason as install: the package set moved, so the icon cache is stale.
        AppsRepository.clearIconCache()

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID, isSuccess, result)

        mUpdateInstalledLiveData.postValue(bean)
    }
}
