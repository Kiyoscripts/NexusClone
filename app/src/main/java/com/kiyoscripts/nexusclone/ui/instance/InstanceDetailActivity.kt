package com.kiyoscripts.nexusclone.ui.instance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.backup.SnapshotManager
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.core.floating.FloatingWindowController
import com.kiyoscripts.nexusclone.core.identity.IdentityStore
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceState
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.net.ProxyStore
import com.kiyoscripts.nexusclone.databinding.ActivityInstanceDetailBinding
import com.kiyoscripts.nexusclone.ui.diag.DiagnosticsActivity
import com.kiyoscripts.nexusclone.ui.identity.IdentityActivity
import com.kiyoscripts.nexusclone.ui.macro.MacroActivity
import com.kiyoscripts.nexusclone.ui.proxy.ProxyActivity
import com.kiyoscripts.nexusclone.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Per-clone control panel.
 *
 * Every action maps to one InstanceManager intent; nothing mutates state directly.
 *
 * The previous version was fifteen identical full-width buttons stacked in a ScrollView,
 * with Launch and Delete the same size and weight as Freeze and Clear data. The actions
 * are now grouped: two primary verbs at the top, then a configuration card of rows, then
 * the identity spec, then the destructive action alone at the bottom where it cannot be
 * hit by accident.
 */
class InstanceDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InstanceDetailActivity"
        private const val EXTRA_ID = "instance_id"

        fun start(context: Context, instanceId: Long) {
            context.startActivity(
                Intent(context, InstanceDetailActivity::class.java)
                    .putExtra(EXTRA_ID, instanceId)
            )
        }
    }

    private lateinit var binding: ActivityInstanceDetailBinding
    private lateinit var manager: InstanceManager
    private lateinit var snapshots: SnapshotManager
    private lateinit var diag: Diagnostics
    private lateinit var proxyStore: ProxyStore
    private lateinit var identityStore: IdentityStore

    /**
     * One controller for the whole Activity lifetime.
     *
     * Constructing a controller per tap made the toggle useless: isShowing() reads a
     * per-instance field, so a fresh controller always reported false, show() could not
     * remove the previous overlay, and every tap stacked another window holding this
     * Activity's context.
     */
    private lateinit var floatingWindow: FloatingWindowController

    private var instanceId: Long = 0L
    private var current: CloneInstance? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstanceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        instanceId = intent.getLongExtra(EXTRA_ID, 0L)
        manager = InstanceManager.get(this)
        snapshots = SnapshotManager(this)
        diag = Diagnostics(this)
        proxyStore = ProxyStore(this)
        identityStore = IdentityStore(this)
        floatingWindow = FloatingWindowController(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { showOverflow() }

        wireActions()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            try {
                manager.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "refresh failed: ${e.message}")
            }

            val inst = manager.byId(instanceId)
            current = inst
            if (inst == null) {
                finish()
                return@launch
            }
            bind(inst)
        }
    }

    private fun bind(inst: CloneInstance) {
        binding.header.text = inst.label
        binding.stateLine.text = "${inst.packageName} · u${inst.userId}"
        binding.heroGlyph.text = inst.label.take(1).uppercase()

        val resident = inst.state.isResident
        binding.statePillText.text = if (resident) {
            getString(R.string.nc_running)
        } else {
            stateLabel(inst.state)
        }
        val pillColor = if (resident) R.color.success else R.color.text_secondary
        val pillSoft = if (resident) R.color.success_soft else R.color.surface_raised
        binding.statePillText.setTextColor(ContextCompat.getColor(this, pillColor))
        binding.statePill.backgroundTintList =
            ContextCompat.getColorStateList(this, pillSoft)

        binding.sizeLine.text = formatBytes(inst.dataSizeBytes)
        binding.keepAliveLine.text = when (inst.keepAliveLevel) {
            2 -> getString(R.string.keepalive_level_2)
            1 -> getString(R.string.keepalive_level_1)
            else -> getString(R.string.keepalive_level_0)
        }

        // Configuration rows carry live state, not static labels.
        val proxy = inst.proxyId?.let { proxyStore.byId(it) }
        binding.subProxy.text = proxy?.display ?: getString(R.string.proxy_none)
        binding.badgeProxy.visibility = if (proxy != null) View.VISIBLE else View.GONE

        val identity = inst.identityId?.let { identityStore.byId(it) }
        binding.subIdentity.text = identity?.let { "${it.model} · ${it.brand}" }
            ?: getString(R.string.identity_none)

        binding.subSnapshot.text = if (inst.previewUpdatedAt > 0L) {
            "Last saved " + relativeTime(inst.previewUpdatedAt)
        } else {
            getString(R.string.no_snapshots)
        }

        binding.subMacro.text = ""

        binding.btnFreezeLabel.text = if (inst.frozen) {
            getString(R.string.instance_unfreeze)
        } else {
            getString(R.string.nc_freeze)
        }

        binding.detail.text = diag.describe(inst)

        // Identity spec table. Values come from the bound identity when there is one, and
        // from the container default otherwise; showing the host's real values here would
        // be actively misleading on a screen whose purpose is to show what the clone sees.
        binding.specModel.text = identity?.model ?: getString(R.string.identity_none)
        binding.specAndroid.text = identity?.let { "${it.brand} ${it.device}" } ?: "-"
        binding.specImei.text = identity?.imei ?: "-"
        binding.specMac.text = identity?.macWifi ?: "-"
        binding.specSerial.text = identity?.serial ?: "-"
    }

    private fun wireActions() {
        binding.btnLaunch.setOnClickListener {
            lifecycleScope.launch {
                manager.launch(instanceId)
                    .onFailure { toast(getString(R.string.launch_failed, it.message ?: "unknown")) }
                reload()
            }
        }

        binding.btnStop.setOnClickListener {
            lifecycleScope.launch {
                manager.stop(instanceId)
                reload()
            }
        }

        binding.btnProxy.setOnClickListener { ProxyActivity.start(this, instanceId) }
        binding.btnIdentity.setOnClickListener { IdentityActivity.start(this, instanceId) }
        binding.btnDiagnostics.setOnClickListener { DiagnosticsActivity.start(this) }

        binding.rowSnapshot.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            lifecycleScope.launch {
                snapshots.snapshot(inst)
                    .onSuccess { toast(getString(R.string.snapshot_created)) }
                    .onFailure { toast(getString(R.string.snapshot_failed, it.message ?: "unknown")) }
                reload()
            }
        }

        binding.rowMacro.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            MacroActivity.start(this, inst.userId, inst.packageName)
        }

        binding.btnFreeze.setOnClickListener {
            lifecycleScope.launch {
                val inst = current ?: return@launch
                if (inst.frozen) manager.unfreeze(instanceId) else manager.freeze(instanceId)
                reload()
            }
        }

        binding.btnRename.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            val input = EditText(this).apply {
                setText(inst.label)
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.instance_rename)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val label = input.text.toString().trim()
                    if (label.isNotEmpty()) {
                        lifecycleScope.launch {
                            manager.rename(instanceId, label)
                            reload()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnQuota.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            val currentMb =
                if (inst.quotaBytes > 0) (inst.quotaBytes / (1024 * 1024)).toString() else "0"
            val input = EditText(this).apply { setText(currentMb) }
            AlertDialog.Builder(this)
                .setTitle(R.string.quota_set)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val mb = input.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 0
                    lifecycleScope.launch {
                        manager.setQuota(instanceId, mb * 1024 * 1024)
                        reload()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnFloat.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            if (!floatingWindow.canDraw()) {
                toast(getString(R.string.float_permission))
                try {
                    startActivity(floatingWindow.permissionIntent())
                } catch (e: Exception) {
                    toast(getString(R.string.overlay_settings_unavailable))
                }
                return@setOnClickListener
            }
            if (floatingWindow.isShowing()) {
                floatingWindow.hide()
            } else {
                floatingWindow.show(inst.label) { floatingWindow.hide() }
            }
        }

        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_clear)
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        manager.clearData(instanceId)
                        reload()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete)
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        manager.destroy(instanceId)
                        finish()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showOverflow() {
        val items = arrayOf(
            getString(R.string.instance_snapshot),
            getString(R.string.instance_restore),
            getString(R.string.instance_macro)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                val inst = current ?: return@setItems
                when (which) {
                    0 -> lifecycleScope.launch {
                        snapshots.snapshot(inst)
                            .onSuccess { toast(getString(R.string.snapshot_created)) }
                            .onFailure {
                                toast(getString(R.string.snapshot_failed, it.message ?: "unknown"))
                            }
                        reload()
                    }
                    1 -> restoreLatest(inst)
                    2 -> MacroActivity.start(this, inst.userId, inst.packageName)
                }
            }
            .show()
    }

    /**
     * Restore the newest archive for this clone.
     *
     * SnapshotManager.restore takes an explicit archive, so the newest one is resolved
     * here by name prefix rather than guessed inside the manager.
     */
    private fun restoreLatest(inst: CloneInstance) {
        val prefix = "${inst.userId}_${inst.packageName}"
        val newest = snapshots.list()
            .filter { it.name.startsWith(prefix) }
            .maxByOrNull { it.lastModified() }

        if (newest == null) {
            toast(getString(R.string.no_snapshots))
            return
        }

        lifecycleScope.launch {
            snapshots.restore(inst, newest)
                .onSuccess { toast(getString(R.string.snapshot_restored)) }
                .onFailure { toast(getString(R.string.snapshot_failed, it.message ?: "unknown")) }
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        // The proxy and identity screens write through the manager; re-read on return.
        if (instanceId != 0L) reload()
    }

    override fun onDestroy() {
        // The overlay's root view holds this Activity as its context; leaving it attached
        // leaks the Activity and outlives the screen that owns it.
        try {
            if (this::floatingWindow.isInitialized) floatingWindow.hide()
        } catch (e: Exception) {
            // Best effort: a dead WindowManager must not break teardown.
        }
        super.onDestroy()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun relativeTime(epochMillis: Long): String {
        val delta = System.currentTimeMillis() - epochMillis
        val minutes = delta / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }

    private fun stateLabel(s: InstanceState): String = when (s) {
        InstanceState.CREATED -> getString(R.string.state_created)
        InstanceState.INSTALLED -> getString(R.string.state_installed)
        InstanceState.COLD -> getString(R.string.state_cold)
        InstanceState.WARM -> getString(R.string.state_warm)
        InstanceState.FOREGROUND -> getString(R.string.state_foreground)
        InstanceState.BACKGROUND -> getString(R.string.state_background)
        InstanceState.FROZEN -> getString(R.string.state_frozen)
        InstanceState.DEAD -> getString(R.string.state_dead)
    }
}
