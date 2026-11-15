package com.kiyoscripts.nexusclone.view.apps

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kiyoscripts.nexusclone.bean.AppInfo
import com.kiyoscripts.nexusclone.data.AppsRepository
import com.kiyoscripts.nexusclone.view.base.BaseViewModel
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 22:36
 */
class AppsViewModel(private val repo: AppsRepository) : BaseViewModel() {

    val appsLiveData = MutableLiveData<List<AppInfo>>()

    val resultLiveData = MutableLiveData<String>()

    val launchLiveData = MutableLiveData<Boolean>()

    //利用LiveData只更新最后一次的特性，用来保存app顺序
    val updateSortLiveData = MutableLiveData<Boolean>()

    fun getInstalledApps(userId: Int) {
        launchOnIO {
            repo.getVmInstallList(userId, appsLiveData)
        }
    }
    
    /**
     * Get installed apps with retry mechanism for when services are not ready.
     *
     * The retry delay runs inside [viewModelScope], so it is cancelled with the ViewModel
     * instead of firing from a main-looper Handler after the owner is gone.
     */
    fun getInstalledAppsWithRetry(userId: Int, maxRetries: Int = 3) {
        viewModelScope.launch {
            var retryCount = 0
            while (true) {
                runOnIO {
                    repo.getVmInstallList(userId, appsLiveData)
                }

                // The repository publishes with postValue, which is delivered on the main
                // looper, so the result is not visible yet at this point. Wait first, then
                // decide whether another attempt is needed.
                delay(1000) // Wait 1 second before retry

                // Check if we got any apps, if not and we haven't exceeded retries, try again
                val currentApps = appsLiveData.value
                if ((currentApps == null || currentApps.isEmpty()) && retryCount < maxRetries) {
                    retryCount++
                    Log.d("AppsViewModel", "No apps loaded, retrying... (${retryCount}/${maxRetries})")
                } else {
                    return@launch
                }
            }
        }
    }

    fun install(source: String, userID: Int) {
        launchOnIO {
            repo.installApk(source, userID, resultLiveData)
        }
    }

    fun unInstall(packageName: String, userID: Int) {
        launchOnIO {
            repo.unInstall(packageName, userID, resultLiveData)
        }
    }

    fun clearApkData(packageName: String,userID: Int){
        launchOnIO {
            repo.clearApkData(packageName,userID,resultLiveData)
        }
    }

    fun launchApk(packageName: String, userID: Int) {
        launchOnIO {
            repo.launchApk(packageName, userID, launchLiveData)
        }
    }

    fun updateApkOrder(userID: Int,dataList:List<AppInfo>){
        launchOnIO {
            repo.updateApkOrder(userID,dataList)
        }
    }
}
