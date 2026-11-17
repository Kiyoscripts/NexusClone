package com.kiyoscripts.nexusclone.ui.identity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.identity.DeviceIdentity
import com.kiyoscripts.nexusclone.core.identity.IdentityFactory
import com.kiyoscripts.nexusclone.core.identity.IdentityStore
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.databinding.ActivityIdentityBinding
import com.kiyoscripts.nexusclone.databinding.ItemIdentityBinding
import kotlinx.coroutines.launch

/**
 * Identity profile editor.
 *
 * Generating a profile only persists it; binding it to a clone is a separate step so the
 * same frozen tuple can be reused across clones that must look like one handset.
 *
 * The summary line always says which profile is live on this clone and whether the
 * selected profile is that one or merely saved, because Apply is otherwise a blind action.
 */
class IdentityActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "instance_id"

        fun start(context: Context, instanceId: Long) {
            context.startActivity(
                Intent(context, IdentityActivity::class.java)
                    .putExtra(EXTRA_ID, instanceId)
            )
        }
    }

    private lateinit var binding: ActivityIdentityBinding
    private lateinit var store: IdentityStore
    private lateinit var manager: InstanceManager
    private var instanceId: Long = 0L

    private var selectedId: Long? = null
    private var expanded: Boolean = false
    private var rows: List<DeviceIdentity> = emptyList()
    private lateinit var adapter: IdentityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIdentityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        instanceId = intent.getLongExtra(EXTRA_ID, 0L)
        store = IdentityStore(this)
        manager = InstanceManager.get(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = "Identity"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { finish() }

        adapter = IdentityAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnGenerate.setOnClickListener { generate() }
        binding.btnApply.setOnClickListener { applySelected() }
        binding.btnClear.setOnClickListener { clearBinding() }

        reload()
    }

    private fun reload() {
        rows = store.all()
        adapter.notifyDataSetChanged()
        refreshSummary()
    }

    /** Re-reads the clone binding, then repaints the summary from the current state. */
    private fun refreshSummary() {
        lifecycleScope.launch {
            manager.refresh()
            renderSummary()
        }
    }

    private fun renderSummary() {
        val boundId = manager.byId(instanceId)?.identityId
        val bound = boundId?.let { store.byId(it) }
        val selected = selectedId?.let { store.byId(it) }

        val text = StringBuilder()
        text.append(
            if (bound == null) {
                getString(R.string.identity_none)
            } else {
                getString(
                    R.string.id_bound_to_clone,
                    "${bound.name} · ${bound.model} / ${bound.imei}"
                )
            }
        )

        if (selected != null) {
            text.append('\n')
            text.append(
                if (selected.id == boundId) {
                    getString(R.string.id_bound_to_clone, selected.name)
                } else {
                    getString(R.string.id_saved_unbound, selected.name)
                }
            )
            if (expanded) {
                text.append('\n')
                text.append(
                    getString(
                        R.string.id_full_tuple,
                        selected.brand,
                        selected.model,
                        selected.device,
                        selected.fingerprint,
                        selected.androidId,
                        selected.imei,
                        selected.meid,
                        selected.macWifi,
                        selected.serial,
                        selected.gsfId,
                        selected.sdk,
                        selected.abi
                    )
                )
            }
        }

        binding.identitySummary.text = text
    }

    private fun generate() {
        val id = IdentityFactory.generate()
        val newId = store.insert(id)
        selectedId = newId
        expanded = false
        reload()
        toast(getString(R.string.identity_generated))
    }

    private fun applySelected() {
        val id = selectedId ?: return
        lifecycleScope.launch {
            val result = manager.setIdentity(instanceId, id)
            if (result.isSuccess) {
                store.markUsed(id)
                toast(getString(R.string.identity_applied))
            } else {
                toast(getString(R.string.id_apply_failed))
            }
            refreshSummary()
        }
    }

    private fun clearBinding() {
        lifecycleScope.launch {
            val result = manager.setIdentity(instanceId, null)
            if (result.isSuccess) {
                toast(getString(R.string.identity_cleared))
            } else {
                toast(getString(R.string.id_clear_failed))
            }
            refreshSummary()
        }
    }

    private fun select(identity: DeviceIdentity) {
        if (selectedId == identity.id) {
            expanded = !expanded
        } else {
            selectedId = identity.id
            expanded = false
        }
        adapter.notifyDataSetChanged()
        renderSummary()
    }

    /**
     * Delete a profile, unbinding it from every clone first.
     *
     * A clone that still points at a deleted row would fall back to the container default
     * silently, so the references are cleared explicitly before the row goes away.
     */
    private fun confirmDelete(identity: DeviceIdentity) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.id_delete_confirm, identity.name))
            .setNegativeButton(R.string.id_cancel, null)
            .setPositiveButton(R.string.id_delete) { _, _ -> delete(identity) }
            .show()
    }

    private fun delete(identity: DeviceIdentity) {
        lifecycleScope.launch {
            manager.refresh()
            for (clone in manager.snapshot()) {
                if (clone.identityId == identity.id) {
                    manager.setIdentity(clone.id, null)
                }
            }
            store.delete(identity.id)
            if (selectedId == identity.id) {
                selectedId = null
                expanded = false
            }
            reload()
            toast(getString(R.string.id_deleted, identity.name))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class IdentityAdapter : RecyclerView.Adapter<IdentityAdapter.Holder>() {

        inner class Holder(val item: ItemIdentityBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemIdentityBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val identity = rows[position]
            val isSelected = identity.id == selectedId

            holder.item.idName.text = identity.name
            holder.item.idMeta.text = "${identity.model} / ${identity.imei}"

            if (isSelected) {
                holder.item.root.setBackgroundResource(R.drawable.bg_input)
            } else {
                holder.item.root.setBackgroundColor(
                    ContextCompat.getColor(this@IdentityActivity, R.color.surface_raised)
                )
            }
            holder.item.idCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

            holder.item.root.setOnClickListener { select(identity) }
            holder.item.root.setOnLongClickListener {
                confirmDelete(identity)
                true
            }
        }
    }
}
