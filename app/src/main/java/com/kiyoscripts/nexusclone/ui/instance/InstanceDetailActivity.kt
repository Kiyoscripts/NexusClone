package com.kiyoscripts.nexusclone.ui.instance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.backup.SnapshotManager
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.core.floating.FloatingWindowController
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.databinding.ActivityInstanceDetailBinding
import com.kiyoscripts.nexusclone.ui.diag.DiagnosticsActivity
import com.kiyoscripts.nexusclone.ui.identity.IdentityActivity
import com.kiyoscripts.nexusclone.ui.macro.MacroActivity
import com.kiyoscripts.nexusclone.ui.proxy.ProxyActivity
import kotlinx.coroutines.launch

/**
 * Per-instance control panel.
 *
 * Every action here maps to one InstanceManager intent. Nothing mutates state directly.
 */
class InstanceDetailActivity : AppCompatActivity() {

    companion object {
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

    /**
     * One controller for the whole Activity lifetime.
     *
     * Constructing a controller per tap made the toggle useless: `isShowing()` reads a
     * per-instance field, so a fresh controller always reported false, `show()` could not
     * remove the previous overlay, and every tap stacked another window that held this
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
        floatingWindow = FloatingWindowController(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = getString(R.string.nav_instances)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { finish() }

        wireActions()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            manager.refresh()
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
        binding.stateLine.text = "state: ${inst.state}  ·  u${inst.userId}"
        binding.sizeLine.text =
            "data: ${inst.dataSizeBytes / 1024} KB" +
                    if (inst.quotaBytes > 0) " / ${inst.quotaBytes / 1024} KB" else ""
        binding.keepAliveLine.text = when (inst.keepAliveLevel) {
            2 -> getString(R.string.keepalive_level_2)
            1 -> getString(R.string.keepalive_level_1)
            else -> getString(R.string.keepalive_level_0)
        }
        binding.detail.text = diag.describe(inst)
        binding.btnFreeze.text = if (inst.frozen) {
            getString(R.string.instance_unfreeze)
        } else {
            getString(R.string.instance_freeze)
        }
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

        binding.btnFreeze.setOnClickListener {
            lifecycleScope.launch {
                val inst = current ?: return@launch
                if (inst.frozen) manager.unfreeze(instanceId)
                else manager.freeze(instanceId)
                reload()
            }
        }

        binding.btnRename.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            val input = android.widget.EditText(this).apply { setText(inst.label) }
            AlertDialog.Builder(this)
                .setTitle(R.string.instance_rename)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        manager.rename(instanceId, input.text.toString())
                        reload()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnSnapshot.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            lifecycleScope.launch {
                binding.btnSnapshot.isEnabled = false
                val r = snapshots.snapshot(inst)
                binding.btnSnapshot.isEnabled = true
                r.onSuccess {
                    toast(getString(R.string.snapshot_created) + ": " + it.name)
                }.onFailure {
                    toast(getString(R.string.snapshot_failed, it.message ?: "?"))
                }
            }
        }

        binding.btnRestore.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            val files = snapshots.list()
            if (files.isEmpty()) {
                toast(getString(R.string.no_snapshots))
                return@setOnClickListener
            }
            val names = files.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.instance_restore)
                .setItems(names) { _, which ->
                    lifecycleScope.launch {
                        val r = snapshots.restore(inst, files[which])
                        r.onSuccess { toast(getString(R.string.snapshot_restored)) }
                            .onFailure {
                                toast(getString(R.string.snapshot_failed, it.message ?: "?"))
                            }
                        reload()
                    }
                }
                .show()
        }

        binding.btnMacro.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            MacroActivity.start(this, inst.userId, inst.packageName)
        }

        binding.btnProxy.setOnClickListener {
            ProxyActivity.start(this, instanceId)
        }

        binding.btnIdentity.setOnClickListener {
            IdentityActivity.start(this, instanceId)
        }

        binding.btnQuota.setOnClickListener {
            val inst = current ?: return@setOnClickListener
            val currentMb = if (inst.quotaBytes > 0) (inst.quotaBytes / (1024 * 1024)).toString() else "0"
            val input = android.widget.EditText(this).apply { setText(currentMb) }
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

        binding.btnDiagnostics.setOnClickListener {
            DiagnosticsActivity.start(this)
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

    override fun onDestroy() {
        // The overlay's root view holds this Activity as its context; leaving it attached
        // leaks the Activity and outlives the screen that owns it.
        try {
            if (this::floatingWindow.isInitialized) {
                floatingWindow.hide()
            }
        } catch (e: Exception) {
            // Best effort: a dead WindowManager must not break teardown.
        }
        super.onDestroy()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
