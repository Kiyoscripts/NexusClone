package com.kiyoscripts.nexusclone.view.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.app.AppManager
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import com.kiyoscripts.nexusclone.core.net.ProxyStore
import com.kiyoscripts.nexusclone.core.preview.PreviewCache
import com.kiyoscripts.nexusclone.databinding.ActivitySettingBinding
import com.kiyoscripts.nexusclone.ui.diag.DiagnosticsActivity
import com.kiyoscripts.nexusclone.ui.proxy.ProxyActivity
import com.kiyoscripts.nexusclone.util.toast
import com.kiyoscripts.nexusclone.view.gms.GmsManagerActivity
import com.kiyoscripts.nexusclone.view.xp.XpActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Settings, as a plain screen.
 *
 * This used to be a PreferenceFragmentCompat hosted in a one-line container. The
 * preference framework brought its own row layout, its own typography and its own
 * dividers, none of which matched anything else in the app, and the only way to change
 * how a row looked was to write a custom preference. The rows are now the same Nc.ListRow
 * and switch rows the rest of the app uses.
 *
 * The Xposed module list is still reachable: it is a row here rather than a preference
 * entry, and XpActivity is unchanged.
 */
class SettingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingActivity"

        fun start(context: Context) {
            context.startActivity(Intent(context, SettingActivity::class.java))
        }
    }

    private lateinit var binding: ActivitySettingBinding
    private lateinit var diag: Diagnostics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diag = Diagnostics(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.rowDiagnostics.setOnClickListener { DiagnosticsActivity.start(this) }
        binding.rowGms.setOnClickListener { GmsManagerActivity.start(this) }
        binding.rowProxy.setOnClickListener { showProxyOverview() }

        val keepAlive = KeepAliveController.get(this)
        binding.switchKeepAlive.isChecked = keepAlive.isEnabled()
        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            keepAlive.setEnabled(checked)
        }

        initBootSwitch()
        initPreviewSwitch()
        initXposed()

        binding.subtitle.text = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            getString(
                R.string.set_subtitle,
                info.versionName ?: "?",
                if ((info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"
            )
        }.getOrDefault(getString(R.string.set_subtitle_fallback))

        binding.specVersion.text = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("-")

        // A static fallback, not a read: the engine exposes no version accessor, so this
        // string is the module version this build was compiled against. It is labelled as
        // such rather than presented as a live value.
        binding.specEngine.text = getString(R.string.set_engine_version)

        binding.specDevice.text =
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · SDK ${android.os.Build.VERSION.SDK_INT}"

        refreshGmsLine()
        refreshProxyLine()
        refreshDiagnostics()
    }

    /**
     * The boot re-arm switch.
     *
     * It was declared in the layout and never referenced, so it was a switch the user could
     * flip that did nothing at all. KeepAliveReceiver consults this key before re-arming on
     * BOOT_COMPLETED; writing it here is the whole contract.
     */
    private fun initBootSwitch() {
        val prefs = getSharedPreferences("nexusclone", MODE_PRIVATE)
        binding.switchBoot.isChecked = prefs.getBoolean("keepalive_on_boot", true)
        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keepalive_on_boot", checked).apply()
        }
    }

    /**
     * The preview capture switch.
     *
     * Also declared and never referenced. Turning it off drops the existing thumbnails so
     * the grid falls back to glyphs immediately instead of showing stale images that will
     * never be refreshed again. The capture side reads the same key.
     */
    private fun initPreviewSwitch() {
        val prefs = getSharedPreferences("nexusclone", MODE_PRIVATE)
        binding.switchPreview.isChecked = prefs.getBoolean("preview_capture_enabled", true)
        binding.switchPreview.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("preview_capture_enabled", checked).apply()
            if (!checked) {
                try {
                    PreviewCache(this).clearAll()
                } catch (e: Throwable) {
                    Log.w(TAG, "could not clear previews: ${e.message}")
                }
            }
        }
    }

    /**
     * Fill the GMS row subtitle with the real count.
     *
     * The row had a static subtitle, so it claimed nothing about the device it was running
     * on. The engine may not be ready yet, so a failure renders the not-installed string
     * rather than leaving the row blank.
     */
    private fun refreshGmsLine() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val users = BlackBoxCore.get().users
                    if (users.isEmpty()) {
                        getString(R.string.set_gms_not_installed)
                    } else {
                        val withGms = users.count {
                            try {
                                BlackBoxCore.get().isInstallGms(it.id)
                            } catch (e: Throwable) {
                                false
                            }
                        }
                        if (withGms == 0) getString(R.string.set_gms_not_installed)
                        else getString(R.string.set_gms_installed_in, withGms, users.size)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "gms count failed: ${e.message}")
                    getString(R.string.set_gms_not_installed)
                }
            }
            binding.subGms.text = text
        }
    }

    /** Fill the proxy row subtitle with how many clones are actually routed. */
    private fun refreshProxyLine() {
        lifecycleScope.launch {
            val routed = withContext(Dispatchers.IO) {
                try {
                    InstanceManager.get(this@SettingActivity).snapshot()
                        .count { it.proxyId != null }
                } catch (e: Throwable) {
                    0
                }
            }
            binding.subProxy.text = if (routed == 0) {
                getString(R.string.set_proxy_none_bound)
            } else {
                getString(R.string.set_proxy_routed, routed)
            }
        }
    }

    /**
     * The only global view of proxy bindings in the app.
     *
     * The row used to toast a non-sequitur. A proxy is bound per clone, so there is no
     * global editor to open, but listing which clones are routed is genuinely useful and
     * is the one thing this screen can offer that no other screen does.
     */
    private fun showProxyOverview() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                try {
                    val manager = InstanceManager.get(this@SettingActivity)
                    val store = ProxyStore(this@SettingActivity)
                    manager.snapshot()
                        .mapNotNull { inst ->
                            val id = inst.proxyId ?: return@mapNotNull null
                            val profile = store.byId(id) ?: return@mapNotNull null
                            inst to profile
                        }
                } catch (e: Throwable) {
                    Log.w(TAG, "proxy overview failed: ${e.message}")
                    emptyList()
                }
            }

            if (rows.isEmpty()) {
                AlertDialog.Builder(this@SettingActivity)
                    .setTitle(R.string.set_proxy_overview_title)
                    .setMessage(R.string.set_proxy_overview_empty)
                    .setPositiveButton(R.string.set_proxy_close, null)
                    .show()
                return@launch
            }

            val labels = rows.map { (inst, profile) ->
                "${inst.label}  ·  ${profile.display}"
            }.toTypedArray()

            AlertDialog.Builder(this@SettingActivity)
                .setTitle(R.string.set_proxy_overview_title)
                .setItems(labels) { _, which ->
                    ProxyActivity.start(this@SettingActivity, rows[which].first.id)
                }
                .setNegativeButton(R.string.set_proxy_close, null)
                .show()
        }
    }

    /**
     * Xposed enable, the module list, and the three hiding switches.
     *
     * These were preference rows in the old PreferenceFragmentCompat screen. The hiding
     * flags are read by BlackBoxLoader at guest process init, so a change only takes
     * effect on the next launch; the toast says so rather than pretending otherwise.
     */
    private fun initXposed() {
        binding.switchXposed.isChecked = BlackBoxCore.get().isXPEnable
        binding.switchXposed.setOnCheckedChangeListener { _, checked ->
            // The write can throw on a device where the engine is not up yet. Reverting the
            // switch matters: leaving it flipped would tell the user a setting took effect
            // when the engine never accepted it.
            try {
                BlackBoxCore.get().isXPEnable = checked
            } catch (e: Throwable) {
                Log.w(TAG, "isXPEnable write failed: ${e.message}")
                binding.switchXposed.isChecked = !checked
                toast(getString(R.string.set_xposed_write_failed, e.message ?: "unknown"))
            }
        }

        binding.rowXpModules.setOnClickListener {
            startActivity(Intent(this, XpActivity::class.java))
        }

        val loader = AppManager.mBlackBoxLoader

        binding.switchHideXposed.isChecked = loader.hideXposed()
        binding.switchHideXposed.setOnCheckedChangeListener { _, checked ->
            loader.invalidHideXposed(checked)
            toast(R.string.set_restart_running_clones)
        }

        binding.switchHideRoot.isChecked = loader.hideRoot()
        binding.switchHideRoot.setOnCheckedChangeListener { _, checked ->
            loader.invalidHideRoot(checked)
            toast(R.string.set_restart_running_clones)
        }

        binding.switchDaemon.isChecked = loader.daemonEnable()
        binding.switchDaemon.setOnCheckedChangeListener { _, checked ->
            loader.invalidDaemonEnable(checked)
            toast(R.string.set_restart_running_clones)
        }
    }

    private fun refreshDiagnostics() {
        lifecycleScope.launch {
            try {
                val checks = withContext(Dispatchers.IO) { diag.runAll() }
                val passed = checks.count { it.ok }
                val failed = checks.size - passed

                binding.subDiagnostics.text =
                    getString(R.string.nc_checks_summary, passed, checks.size)
                binding.badgeDiagnostics.visibility =
                    if (failed == 0) View.GONE else View.VISIBLE
                binding.badgeDiagnostics.text = failed.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An empty subtitle looks like "no data yet"; a failure string says what
                // actually happened instead of hiding it.
                Log.w(TAG, "diagnostics sweep failed: ${e.message}")
                binding.subDiagnostics.text = getString(R.string.set_diag_unavailable)
            }
        }
    }
}
