package com.kiyoscripts.nexusclone.ui.proxy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.net.ProxyProfile
import com.kiyoscripts.nexusclone.core.net.ProxyStore
import com.kiyoscripts.nexusclone.core.net.ProxyType
import com.kiyoscripts.nexusclone.databinding.ActivityProxyBinding
import kotlinx.coroutines.launch

/**
 * Proxy profile editor.
 *
 * Profiles are stored once and bound to clones by id, so the same exit node can be shared
 * by several clones without duplicating credentials.
 */
class ProxyActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "instance_id"

        fun start(context: Context, instanceId: Long) {
            context.startActivity(
                Intent(context, ProxyActivity::class.java)
                    .putExtra(EXTRA_ID, instanceId)
            )
        }
    }

    private lateinit var binding: ActivityProxyBinding
    private lateinit var store: ProxyStore
    private lateinit var manager: InstanceManager
    private var instanceId: Long = 0L

    private var selectedId: Long? = null
    private var rows: List<ProxyProfile> = emptyList()
    private lateinit var adapter: ProxyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProxyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        instanceId = intent.getLongExtra(EXTRA_ID, 0L)
        store = ProxyStore(this)
        manager = InstanceManager.get(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = "Proxy"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { finish() }

        binding.proxyType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ProxyType.entries.map { it.name }
        )

        adapter = ProxyAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { deleteSelected() }

        reload()
    }

    private fun reload() {
        rows = store.all()
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        val name = binding.proxyName.text.toString().trim()
        val host = binding.proxyHost.text.toString().trim()
        val portText = binding.proxyPort.text.toString().trim()
        val port = portText.toIntOrNull() ?: 0

        if (host.isBlank()) {
            toast("Host is required")
            return
        }
        if (port !in 1..65535) {
            toast("Port must be 1..65535")
            return
        }

        val type = ProxyType.entries.getOrElse(binding.proxyType.selectedItemPosition) {
            ProxyType.HTTP
        }
        val user = binding.proxyUser.text.toString().trim().ifBlank { null }
        val pass = binding.proxyPass.text.toString().ifBlank { null }

        val profile = ProxyProfile(
            id = selectedId ?: 0L,
            name = name.ifBlank { "$host:$port" },
            type = type,
            host = host,
            port = port,
            username = user,
            password = pass
        )

        val savedId = if (profile.id == 0L) {
            store.insert(profile)
        } else {
            store.update(profile)
            profile.id
        }

        if (instanceId > 0L) {
            lifecycleScope.launch {
                manager.setProxy(instanceId, savedId)
            }
        }

        selectedId = savedId
        reload()
        toast("Saved")
    }

    private fun deleteSelected() {
        val id = selectedId ?: return
        store.delete(id)
        selectedId = null
        reload()
    }

    private fun select(profile: ProxyProfile) {
        selectedId = profile.id
        binding.proxyName.setText(profile.name)
        binding.proxyHost.setText(profile.host)
        binding.proxyPort.setText(profile.port.toString())
        binding.proxyUser.setText(profile.username ?: "")
        binding.proxyPass.setText(profile.password ?: "")
        val index = ProxyType.entries.indexOf(profile.type)
        if (index >= 0) binding.proxyType.setSelection(index)
        adapter.notifyDataSetChanged()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class ProxyAdapter : RecyclerView.Adapter<ProxyAdapter.Holder>() {

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
            val profile = rows[position]
            holder.text.text = "${profile.name}\n${profile.display}"
            holder.text.setOnClickListener { select(profile) }
        }
    }
}
