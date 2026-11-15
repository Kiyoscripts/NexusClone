package com.kiyoscripts.nexusclone.view.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 23:33
 */
open class BaseViewModel : ViewModel() {

    /**
     * Run [block] on [Dispatchers.IO].
     *
     * The name says IO because that is what it does: the body runs inside
     * `withContext(Dispatchers.IO)`, so it must not touch views. LiveData updates from here
     * have to go through `postValue`.
     */
    fun launchOnIO(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            runOnIO(block)
        }
    }

    /**
     * Run [block] on [Dispatchers.IO] and suspend until it finishes.
     *
     * Use this from inside a coroutine when the next step depends on the work being done;
     * [launchOnIO] only schedules it.
     */
    suspend fun runOnIO(block: suspend CoroutineScope.() -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
            }

        }
    }

    /**
     * Legacy name for [launchOnIO], kept only so call sites outside this change keep
     * compiling. It does **not** run on the main thread — it is IO, exactly like
     * [launchOnIO]. New code must call [launchOnIO] by what it means.
     */
    @Deprecated(
        message = "Misleading name: this runs on Dispatchers.IO. Use launchOnIO.",
        replaceWith = ReplaceWith("launchOnIO(block)")
    )
    fun launchOnUI(block: suspend CoroutineScope.() -> Unit) = launchOnIO(block)


    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
    }

}
