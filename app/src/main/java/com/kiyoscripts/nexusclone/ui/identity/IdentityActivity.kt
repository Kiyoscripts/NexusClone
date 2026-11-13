package com.kiyoscripts.nexusclone.ui.identity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.core.identity.DeviceIdentity
import com.kiyoscripts.nexusclone.core.identity.IdentityFactory
import com.kiyoscripts.nexusclone.core.identity.IdentityStore
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.databinding.ActivityIdentityBinding
import kotlinx.coroutines.launch

/**
 * Identity profile editor.
 *
 * Generating a profile only persists it; binding it to a clone is a separate step so the
 * same frozen tuple can be reused across clones that must look like one handset.
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

    private fun refreshSummary() {
        lifecycleScope.launch {
            manager.refresh()
            val boundId = manager.byId(instanceId)?.identityId
            val bound = boundId?.let { store.byId(it) }
            binding.identitySummary.text = if (bound == null) {
                "Container default"
            } else {
                "${bound.name} · ${bound.model} / ${bound.imei}"
            }
        }
    }

    private fun generate() {
        val id = IdentityFactory.generate()
        val newId = store.insert(id)
        selectedId = newId
        reload()
        toast("Generated")
    }

    private fun applySelected() {
        val id = selectedId ?: return
        lifecycleScope.launch {
            manager.setIdentity(instanceId, id)
            refreshSummary()
        }
        toast("Applied")
    }

    private fun clearBinding() {
        lifecycleScope.launch {
            manager.setIdentity(instanceId, null)
            refreshSummary()
        }
        toast("Cleared")
    }

    private fun select(identity: DeviceIdentity) {
        selectedId = identity.id
        adapter.notifyDataSetChanged()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class IdentityAdapter : RecyclerView.Adapter<IdentityAdapter.Holder>() {

        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 24, 16, 24)
                textSize = 14f
            }
            return Holder(tv)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val identity = rows[position]
            holder.text.text = "${identity.name}\n${identity.model} / ${identity.imei}"
            holder.text.setOnClickListener { select(identity) }
        }
    }
}
