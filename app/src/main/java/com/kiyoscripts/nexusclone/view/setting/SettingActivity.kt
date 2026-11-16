package com.kiyoscripts.nexusclone.view.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.app.AppManager
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import com.kiyoscripts.nexusclone.databinding.ActivitySettingBinding
import com.kiyoscripts.nexusclone.ui.diag.DiagnosticsActivity
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
        binding.rowProxy.setOnClickListener {
            // There is no global proxy: a proxy is bound per clone, because two clones of
            // the same app usually want different exits. Saying so is better than opening
            // an editor that would have nothing to edit.
            toast(getString(R.string.nc_proxy_scope))
        }

        val keepAlive = KeepAliveController.get(this)
        binding.switchKeepAlive.isChecked = keepAlive.isEnabled()
        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            keepAlive.setEnabled(checked)
        }

        initXposed()

        binding.specVersion.text = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("-")

        binding.specEngine.text = "BlackBox 4.0.0"

        binding.specDevice.text =
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · SDK ${android.os.Build.VERSION.SDK_INT}"

        refreshDiagnostics()
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
            BlackBoxCore.get().isXPEnable = checked
        }

        binding.rowXpModules.setOnClickListener {
            startActivity(Intent(this, XpActivity::class.java))
        }

        val loader = AppManager.mBlackBoxLoader

        binding.switchHideXposed.isChecked = loader.hideXposed()
        binding.switchHideXposed.setOnCheckedChangeListener { _, checked ->
            loader.invalidHideXposed(checked)
            toast(R.string.restart_module)
        }

        binding.switchHideRoot.isChecked = loader.hideRoot()
        binding.switchHideRoot.setOnCheckedChangeListener { _, checked ->
            loader.invalidHideRoot(checked)
            toast(R.string.restart_module)
        }

        binding.switchDaemon.isChecked = loader.daemonEnable()
        binding.switchDaemon.setOnCheckedChangeListener { _, checked ->
            loader.invalidDaemonEnable(checked)
            toast(R.string.restart_module)
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
                binding.subDiagnostics.text = ""
            }
        }
    }
}
