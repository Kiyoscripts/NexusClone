package com.kiyoscripts.nexusclone.view.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
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

    private val mViewPagerAdapter by lazy { ViewPagerAdapter(this) }

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
            // So the pager starts empty and the window draws immediately. loadUsersAsync()
            // fills it in once the engine answers. An empty adapter is a valid state for
            // ViewPager2; the empty-state view below covers the gap.
            currentUser = 0

            viewBinding.viewPager.adapter = mViewPagerAdapter
            viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
            viewBinding.viewPager.registerOnPageChangeCallback(object :
                ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    try {
                        super.onPageSelected(position)
                        // Read the id from the adapter, not from a parallel fragment list:
                        // the adapter is the single source of truth for what is on screen,
                        // and a stale list is exactly how the old code ended up pointing at
                        // a virtual user that did not exist.
                        val userId = mViewPagerAdapter.userIdAt(position) ?: return
                        currentUser = userId
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

                // Hand the adapter the real user ids in order. It owns the page set now;
                // there is no parallel fragment list to keep in sync, and no trailing
                // "add user" page (see ViewPagerAdapter for why that page was removed).
                val ids = userList.map { it.id }
                currentUser = ids.firstOrNull() ?: 0
                mViewPagerAdapter.submit(ids)
                viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
                updateDotsVisibility()
                updateUserRemark(currentUser)
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
                    // Read the user id from the adapter rather than from
                    // viewPager.currentItem. currentItem is a page index; the install
                    // target is a virtual user id, and the two only coincide when the
                    // users happen to be numbered 0..n in page order. Passing the index
                    // installed the apk into the wrong user as soon as any user had been
                    // deleted and the ids had gaps.
                    val userId = mViewPagerAdapter.userIdAt(viewBinding.viewPager.currentItem)
                    if (userId == null) {
                        Log.w(TAG, "clone tapped with no page selected")
                        return@setOnClickListener
                    }
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

    /**
     * A single dot under a single page is noise. Hide the indicator until there is
     * something to indicate.
     */
    private fun updateDotsVisibility() {
        try {
            viewBinding.dotsIndicator.visibility =
                if (mViewPagerAdapter.itemCount > 1) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateDotsVisibility: ${e.message}")
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

            // Preserve the page the user is actually looking at. Rebuilding the adapter
            // resets ViewPager2 to position 0, which yanks the user back to the first user
            // every time they install or uninstall something on a later page.
            val previousUser = currentUser
            val ids = userList.map { it.id }
            mViewPagerAdapter.submit(ids)
            viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)
            updateDotsVisibility()

            val restored = mViewPagerAdapter.indexOfUser(previousUser)
            if (restored >= 0 && restored != viewBinding.viewPager.currentItem) {
                viewBinding.viewPager.setCurrentItem(restored, false)
            }
            currentUser = mViewPagerAdapter.userIdAt(viewBinding.viewPager.currentItem) ?: 0
            updateUserRemark(currentUser)
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
                            // Resolve the page that owns this virtual user instead of
                            // indexing a list by user id. The old code did
                            // `fragmentList[userId]`, which conflated a page index with a
                            // virtual user id and threw IndexOutOfBoundsException as soon as
                            // the two diverged (any deleted user leaves a gap in the ids).
                            val page = mViewPagerAdapter.indexOfUser(userId)
                            if (page < 0) {
                                Log.w(TAG, "install result for unknown user $userId")
                                return@let
                            }
                            val fragment = supportFragmentManager.findFragmentByTag(
                                "f" + mViewPagerAdapter.getItemId(page)
                            ) as? AppsFragment
                            if (fragment != null) {
                                fragment.installApk(source)
                            } else {
                                // The page is not instantiated (ViewPager2 only keeps a
                                // window of pages alive). Move to it first; the install is
                                // then applied by the fragment once it exists.
                                viewBinding.viewPager.setCurrentItem(page, false)
                                pendingInstall = userId to source
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling APK path result: ${e.message}")
            }
        }

    /**
     * An install requested for a page that was not alive yet. Applied by
     * [consumePendingInstall] once that page's fragment exists.
     */
    private var pendingInstall: Pair<Int, String>? = null

    /** Called by AppsFragment once it is attached, to pick up a deferred install. */
    fun consumePendingInstall(userId: Int): String? {
        val pending = pendingInstall ?: return null
        if (pending.first != userId) return null
        pendingInstall = null
        return pending.second
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

                R.id.main_setting -> {
                    SettingActivity.start(this)
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
