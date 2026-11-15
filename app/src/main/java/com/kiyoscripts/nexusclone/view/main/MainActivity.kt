package com.kiyoscripts.nexusclone.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.app.App
import com.kiyoscripts.nexusclone.app.AppManager
import com.kiyoscripts.nexusclone.databinding.ActivityMainBinding
import com.kiyoscripts.nexusclone.util.Resolution
import com.kiyoscripts.nexusclone.util.inflate
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import com.kiyoscripts.nexusclone.view.apps.AppsFragment
import com.kiyoscripts.nexusclone.view.base.LoadingActivity
import com.kiyoscripts.nexusclone.view.fake.FakeManagerActivity
import com.kiyoscripts.nexusclone.view.list.ListActivity
import com.kiyoscripts.nexusclone.view.setting.SettingActivity


class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0

    companion object {
        private const val TAG = "MainActivity"
        
        /**
         * How long the first user-list load waits for the container engine. The engine is
         * brought up on its own thread from App.onCreate(); if it has not finished by then
         * the UI keeps its default page instead of blocking.
         */
        private const val ENGINE_READY_TIMEOUT_MS = 10_000L

        /** Request code for the API 33+ POST_NOTIFICATIONS prompt. */
        private const val REQUEST_NOTIFICATIONS = 0x4E44

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initViewPager()
            initFab()
            initToolbarSubTitle()

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
            // Show error dialog to user
            showErrorDialog("Failed to initialize app: ${e.message}")
        }
    }

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = "Error")
                message(text = message)
                positiveButton(text = "OK") {
                    finish()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
            finish()
        }
    }

    private fun initToolbarSubTitle() {
        try {
            updateUserRemark(0)
            //hack code
            viewBinding.toolbarLayout.toolbar.getChildAt(1)?.setOnClickListener {
                try {
                    MaterialDialog(this).show {
                        title(res = R.string.userRemark)
                        input(
                            hintRes = R.string.userRemark,
                            prefill = viewBinding.toolbarLayout.toolbar.subtitle
                        ) { _, input ->
                            try {
                                AppManager.mRemarkSharedPreferences.edit {
                                    putString("Remark$currentUser", input.toString())
                                    viewBinding.toolbarLayout.toolbar.subtitle = input
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving user remark: ${e.message}")
                            }
                        }
                        positiveButton(res = R.string.done)
                        negativeButton(res = R.string.cancel)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing remark dialog: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initToolbarSubTitle: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Start the keep-alive service here, not in Application.onCreate(). By the time
        // an Activity is resumed the app is foreground, so startForegroundService() is
        // permitted on Android 12+ and the service can reach startForeground() in time.
        try {
            val keepAlive = KeepAliveController.get(this)
            // On API 33+ the foreground-service notification is suppressed until
            // POST_NOTIFICATIONS is granted, which leaves the user with no visible way to
            // stop the service. Only ask when the user has actually opted in.
            if (keepAlive.isEnabled()) {
                keepAlive.ensureNotificationPermission(this, REQUEST_NOTIFICATIONS)
            }
            keepAlive.start()
        } catch (e: Exception) {
            Log.w(TAG, "keep-alive start failed: ${e.message}")
        }
    }

    private fun initViewPager() {
        try {
            // BlackBoxCore.get().users is a synchronous binder round-trip into the :black
            // engine process. Calling it here, on the main thread inside onCreate, is what
            // kept the window at isReadyForDisplay()=false: while the engine was still
            // publishing its provider the call blocked, onCreate never returned, and the
            // framework eventually ANR'd the process.
            //
            // Build the pager with a single default page immediately so the window can
            // draw, then swap in the real user list once the engine answers.
            val initialUser = 0
            fragmentList.add(AppsFragment.newInstance(initialUser))
            currentUser = initialUser

            mViewPagerAdapter = ViewPagerAdapter(this)
            mViewPagerAdapter.replaceData(fragmentList)
            viewBinding.viewPager.adapter = mViewPagerAdapter
            viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
            viewBinding.viewPager.registerOnPageChangeCallback(object :
                ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    try {
                        super.onPageSelected(position)
                        currentUser = fragmentList[position].userID
                        updateUserRemark(currentUser)
                        showFloatButton(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onPageSelected: ${e.message}")
                    }
                }
            })

            loadUsersAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Error in initViewPager: ${e.message}")
        }
    }

    /**
     * Resolve the virtual user list off the main thread and rebuild the pager if it
     * differs from the single default page created in initViewPager().
     *
     * The engine is initialised on a background thread from App.onCreate(), so it is not
     * necessarily up when this Activity starts. Wait for AppManager's readiness signal
     * (bounded by [ENGINE_READY_TIMEOUT_MS]) before asking the engine anything, and keep the
     * default page plus the loading indicator visible while waiting. If the engine never
     * becomes ready we log and leave the empty state in place rather than blocking or
     * crashing.
     */
    private fun loadUsersAsync() {
        // Dispatchers.Main (not Main.immediate) so the loading dialog is shown after
        // onCreate() has returned, never in the middle of it.
        lifecycleScope.launch(Dispatchers.Main) {
            // Only put the loading dialog up when there is actually something to wait for;
            // on a warm start the engine is usually already up and a dialog would just flash.
            var loadingShown = false
            try {
                if (!AppManager.engineReady.value && !isFinishing && !isDestroyed) {
                    try {
                        showLoading()
                        loadingShown = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing loading: ${e.message}")
                    }
                }

                if (!AppManager.awaitEngineReady(ENGINE_READY_TIMEOUT_MS)) {
                    Log.w(
                        TAG,
                        "engine not ready after ${ENGINE_READY_TIMEOUT_MS}ms, keeping default page"
                    )
                    return@launch
                }

                val userList = withContext(Dispatchers.IO) { BlackBoxCore.get().users }
                if (userList.isEmpty()) {
                    return@launch
                }

                // Build a fresh list rather than mutating fragmentList in place:
                // ViewPagerAdapter.replaceData() keeps a reference to the list it
                // was given, so clearing the original would empty the adapter's
                // backing store before notifyDataSetChanged() ran.
                val rebuilt = mutableListOf<AppsFragment>()
                userList.forEach { rebuilt.add(AppsFragment.newInstance(it.id)) }
                currentUser = userList.firstOrNull()?.id ?: 0
                rebuilt.add(AppsFragment.newInstance(userList.size))
                fragmentList.clear()
                fragmentList.addAll(rebuilt)
                mViewPagerAdapter.replaceData(rebuilt)
                viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
            } catch (e: CancellationException) {
                // The Activity went away while we were waiting; let the scope unwind.
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "failed to load virtual users: ${e.message}")
            } finally {
                if (loadingShown) {
                    try {
                        hideLoading()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error hiding loading: ${e.message}")
                    }
                }
            }
        }
    }

    private fun initFab() {
        try {
            viewBinding.fab.setOnClickListener {
                try {
                    val userId = viewBinding.viewPager.currentItem
                    val intent = Intent(this, ListActivity::class.java)
                    intent.putExtra("userID", userId)
                    apkPathResult.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ListActivity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    fun showFloatButton(show: Boolean) {
        try {
            val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
            val time = 200L
            if (show) {
                viewBinding.fab.animate().translationY(0f).alpha(1f).setDuration(time)
                    .start()
            } else {
                viewBinding.fab.animate().translationY(tranY).alpha(0f).setDuration(time)
                    .start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showFloatButton: ${e.message}")
        }
    }

    fun scanUser() {
        try {
            // Same trap as the initial load: BlackBoxCore.get().users is a binder round-trip
            // into the :black process. This is called from AppsFragment after an install or
            // uninstall, which can happen while the engine is still coming up on its own
            // thread. Skip the refresh in that case instead of blocking the main thread; the
            // initial load in loadUsersAsync() will populate the pager once the engine is up.
            if (!AppManager.engineReady.value) {
                Log.d(TAG, "scanUser skipped, engine not ready yet")
                return
            }
            val userList = BlackBoxCore.get().users

            // Rebuild and hand the adapter a fresh list, exactly as loadUsersAsync() does.
            //
            // The previous version appended to `fragmentList` and called
            // notifyDataSetChanged(), but replaceData() had already given the adapter a
            // different list object, so the adapter re-read its own untouched list and the
            // new page never appeared. Worse, if the adapter had ever grown past
            // fragmentList, onPageSelected would index past the end of this list.
            val rebuilt = mutableListOf<AppsFragment>()
            userList.forEach { rebuilt.add(AppsFragment.newInstance(it.id)) }
            // Trailing page is the "add user" entry, so there is always one more page than
            // there are virtual users.
            rebuilt.add(AppsFragment.newInstance(userList.size))

            fragmentList.clear()
            fragmentList.addAll(rebuilt)
            mViewPagerAdapter.replaceData(rebuilt)
            viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    private fun updateUserRemark(userId: Int) {
        try {
            var remark = AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId")
            if (remark.isNullOrEmpty()) {
                remark = "User $userId"
            }

            viewBinding.toolbarLayout.toolbar.subtitle = remark
        } catch (e: Exception) {
            Log.e(TAG, "Error updating user remark: ${e.message}")
            viewBinding.toolbarLayout.toolbar.subtitle = "User $userId"
        }
    }

    private val apkPathResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            try {
                if (it.resultCode == RESULT_OK) {
                    it.data?.let { data ->
                        val userId = data.getIntExtra("userID", 0)
                        val source = data.getStringExtra("source")
                        if (source != null) {
                            fragmentList[userId].installApk(source)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling APK path result: ${e.message}")
            }
        }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        try {
            menuInflater.inflate(R.menu.menu_main, menu)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu: ${e.message}")
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            when (item.itemId) {
                R.id.main_instances -> {
                    com.kiyoscripts.nexusclone.ui.instances.InstancesActivity.start(this)
                }

                R.id.main_diagnostics -> {
                    com.kiyoscripts.nexusclone.ui.diag.DiagnosticsActivity.start(this)
                }

                R.id.main_git -> {
                    val intent =
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ALEX5402/NewBlackbox"))
                    startActivity(intent)
                }

                R.id.main_setting -> {
                    SettingActivity.start(this)
                }

                R.id.main_tg -> {
                    val intent =
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/newblackboxa"))
                    startActivity(intent)
                }

                R.id.fake_location -> {
                    // toast("Still Developing")
                    val intent = Intent(this, FakeManagerActivity::class.java)
                    intent.putExtra("userID", 0)
                    startActivity(intent)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling menu item selection: ${e.message}")
            return false
        }
    }
}
