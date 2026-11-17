package com.kiyoscripts.nexusclone.view.main

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.util.toast
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Shortcut trampoline.
 *
 * A pinned shortcut carries the package and virtual user id and this Activity launches the
 * clone, then finishes. It used to pass a possibly null package straight into the engine
 * and ignore the boolean result, so a stale shortcut (the clone was deleted) produced a
 * silent no-op with no explanation.
 */
class ShortcutActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShortcutActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra("pkg")
        val userID = intent.getIntExtra("userId", 0)

        if (pkg.isNullOrBlank()) {
            Log.w(TAG, "shortcut has no package, nothing to launch")
            finish()
            return
        }

        lifecycleScope.launch {
            val ok = try {
                BlackBoxCore.get().launchApk(pkg, userID)
            } catch (e: Throwable) {
                Log.w(TAG, "launch($pkg/$userID) failed: ${e.message}")
                false
            }
            if (!ok) {
                toast(getString(R.string.launch_failed, pkg))
            }
            finish()
        }
    }
}
