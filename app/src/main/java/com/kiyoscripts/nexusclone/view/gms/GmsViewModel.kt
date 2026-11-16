package com.kiyoscripts.nexusclone.view.gms

import androidx.lifecycle.MutableLiveData
import com.kiyoscripts.nexusclone.bean.GmsBean
import com.kiyoscripts.nexusclone.bean.GmsInstallBean
import com.kiyoscripts.nexusclone.data.GmsRepository
import com.kiyoscripts.nexusclone.view.base.BaseViewModel

/**
 *
 * @Description: gms viewModel
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:11
 */
class GmsViewModel(private val mRepo: GmsRepository) : BaseViewModel() {

    val mInstalledLiveData = MutableLiveData<List<GmsBean>>()

    val mUpdateInstalledLiveData = MutableLiveData<GmsInstallBean>()

    /**
     * True while an install or uninstall is in flight.
     *
     * GMS install is thirteen sequential package installs inside the engine, so it can take
     * seconds. The screen needs to know that a second tap must not start a second install
     * on the same user, and the row needs to show that it is working rather than looking
     * frozen.
     */
    val mBusyLiveData = MutableLiveData<Boolean>()

    fun getInstalledUser() {
        // launchOnIO, not launchOnUI: the latter is a deprecated alias that also runs on
        // Dispatchers.IO, but the name reads as "main thread" and this body does two binder
        // round-trips per user. Say what it does.
        launchOnIO {
            mRepo.getGmsInstalledList(mInstalledLiveData)
        }
    }

    fun installGms(userID: Int) {
        if (mBusyLiveData.value == true) return
        mBusyLiveData.postValue(true)
        launchOnIO {
            try {
                mRepo.installGms(userID, mUpdateInstalledLiveData)
            } finally {
                mBusyLiveData.postValue(false)
            }
        }
    }

    fun uninstallGms(userID: Int) {
        if (mBusyLiveData.value == true) return
        mBusyLiveData.postValue(true)
        launchOnIO {
            try {
                mRepo.uninstallGms(userID, mUpdateInstalledLiveData)
            } finally {
                mBusyLiveData.postValue(false)
            }
        }
    }
}
