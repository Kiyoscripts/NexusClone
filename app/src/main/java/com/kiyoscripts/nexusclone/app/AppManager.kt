package com.kiyoscripts.nexusclone.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kiyoscripts.nexusclone.view.main.BlackBoxLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * updated by alex5402 on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * TFNQw5HgWUS33Ke1eNmSFTwoQySGU7XNsK (USDT TRC20)
 */
object AppManager {
    private const val TAG = "AppManager"
    
    /**
     * Readiness signal for the container engine.
     *
     * [doOnCreate] is a blocking chain (provider probes with retries, then a binder
     * round-trip per service) that runs on a background thread started from
     * `App.onCreate()`. Nothing may assume the engine is up before this flips to true, and
     * it flips even when initialisation fails so the UI can fall back to an error/empty
     * state instead of waiting forever.
     */
    private val _engineReady = MutableStateFlow(false)

    /** True once [doOnCreate] has finished, successfully or not. */
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    /**
     * Suspend until the engine has finished its one-time initialisation.
     *
     * @param timeoutMs how long to wait before giving up.
     * @return true if the engine is ready, false if [timeoutMs] elapsed first. Never blocks
     *         the calling thread and never throws on timeout.
     */
    suspend fun awaitEngineReady(timeoutMs: Long): Boolean {
        if (_engineReady.value) {
            return true
        }
        return withTimeoutOrNull(timeoutMs) {
            _engineReady.first { it }
            true
        } ?: false
    }

    /**
     * Publish readiness without running the engine. Used when the init thread could not be
     * started at all, so that anything waiting on [awaitEngineReady] is released.
     */
    fun markEngineReady() {
        _engineReady.value = true
    }

    @JvmStatic
    val mBlackBoxLoader by lazy {
        try {
            BlackBoxLoader()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating BlackBoxLoader: ${e.message}")

            BlackBoxLoader() // Try again, but this might fail
        }
    }

    @JvmStatic
    val mBlackBoxCore by lazy {
        try {
            mBlackBoxLoader.getBlackBoxCore()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting BlackBoxCore: ${e.message}")
            throw e // Re-throw as this is critical
        }
    }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        try {
            App.getContext().getSharedPreferences("UserRemark", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SharedPreferences: ${e.message}")
            throw e // Re-throw as this is critical
        }
    }

    fun doAttachBaseContext(context: Context) {
        try {
            mBlackBoxLoader.attachBaseContext(context)
            mBlackBoxLoader.addLifecycleCallback()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doAttachBaseContext: ${e.message}")
            // Don't re-throw as this might not be critical for app startup
        }
    }

    fun doOnCreate(context: Context) {
        try {
            mBlackBoxLoader.doOnCreate(context)
            initThirdService(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error in doOnCreate: ${e.message}")
            // Don't re-throw as this might not be critical for app startup
        } finally {
            // Always publish readiness, including on failure: the UI waits on this signal
            // and must be able to show its empty/error state rather than hang.
            _engineReady.value = true
        }
    }

    private fun initThirdService(context: Context) {
        try {
            // Initialize any third-party services here
            // Currently empty, but with error handling for future additions
        } catch (e: Exception) {
            Log.e(TAG, "Error in initThirdService: ${e.message}")
        }
    }
}
