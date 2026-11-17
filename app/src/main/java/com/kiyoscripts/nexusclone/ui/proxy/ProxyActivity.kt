package com.kiyoscripts.nexusclone.ui.proxy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.net.ProxyProfile
import com.kiyoscripts.nexusclone.core.net.ProxyStore
import com.kiyoscripts.nexusclone.core.net.ProxyType
import com.kiyoscripts.nexusclone.databinding.ActivityProxyBinding
import com.kiyoscripts.nexusclone.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Proxy editor for one clone.
 *
 * One profile per clone, edited in place. The previous version was a profile list plus a
 * form, which meant a clone could accumulate several profiles and the screen never made
 * clear which one was actually bound. A clone has exactly one egress path, so the screen
 * edits exactly one.
 *
 * The connection test opens a real TCP connection to the proxy endpoint. It does not
 * prove the proxy forwards traffic, and it does not prove the exit IP; it proves the
 * endpoint is reachable and accepting, which is the failure mode that actually happens
 * (typo in host, wrong port, proxy down). Claiming more than that would be a lie.
 */
class ProxyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProxyActivity"
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
    private var profileId: Long = 0L
    private var type: ProxyType = ProxyType.SOCKS5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProxyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        instanceId = intent.getLongExtra(EXTRA_ID, 0L)
        store = ProxyStore(this)
        manager = InstanceManager.get(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.segSocks5.setOnClickListener { selectType(ProxyType.SOCKS5) }
        binding.segSocks4.setOnClickListener { selectType(ProxyType.SOCKS4) }
        binding.segHttp.setOnClickListener { selectType(ProxyType.HTTP) }

        binding.switchEnabled.setOnCheckedChangeListener { _, on ->
            setFieldsEnabled(on)
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { test() }
        binding.btnClear.setOnClickListener { clear() }

        load()
    }

    /**
     * Read the current binding.
     *
     * The manager snapshot is a cache, so reading it without a refresh shows whatever the
     * last screen left behind. Refresh first, then read, both off the main thread because
     * refresh hits the instance database and byId hits the proxy database.
     */
    private fun load() {
        lifecycleScope.launch {
            val (inst, bound) = withContext(Dispatchers.IO) {
                runCatching { manager.refresh() }
                    .onFailure { Log.w(TAG, "could not refresh instances: ${it.message}") }
                val current = manager.byId(instanceId)
                current to current?.proxyId?.let { store.byId(it) }
            }

            binding.subtitle.text = inst?.let { "${it.label} · ${it.packageName}" } ?: ""

            if (bound == null) {
                binding.switchEnabled.isChecked = false
                setFieldsEnabled(false)
                selectType(ProxyType.SOCKS5)
                return@launch
            }

            profileId = bound.id
            binding.switchEnabled.isChecked = bound.enabled
            binding.host.setText(bound.host)
            binding.port.setText(bound.port.toString())
            binding.user.setText(bound.username ?: "")
            binding.pass.setText(bound.password ?: "")
            binding.name.setText(bound.name)
            selectType(bound.type)
            setFieldsEnabled(bound.enabled)
        }
    }

    /**
     * One segment per protocol, one protocol per segment. The previous version mapped both
     * the HTTP and the HTTPS segment to ProxyType.HTTP, so picking HTTP lit two segments
     * and the control could not express SOCKS4 at all.
     */
    private fun selectType(next: ProxyType) {
        type = next
        val on = ContextCompat.getColor(this, R.color.text_primary)
        val off = ContextCompat.getColor(this, R.color.text_secondary)

        listOf(
            binding.segSocks5 to (next == ProxyType.SOCKS5),
            binding.segSocks4 to (next == ProxyType.SOCKS4),
            binding.segHttp to (next == ProxyType.HTTP)
        ).forEach { (view, selected) ->
            view.setBackgroundResource(
                if (selected) R.drawable.bg_segment_on else 0
            )
            view.setTextColor(if (selected) on else off)
        }
    }

    /**
     * Gate the fields behind the master switch, but never the test button: probing an
     * endpoint before committing to it is the whole point of having a test button.
     */
    private fun setFieldsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.45f
        listOf<View>(
            binding.host, binding.port, binding.user, binding.pass,
            binding.name, binding.typeGroup
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
        binding.btnTest.isEnabled = true
        binding.btnTest.alpha = 1f
    }

    private fun showError(message: String?) {
        if (message == null) {
            binding.errorLine.visibility = View.GONE
            binding.host.setBackgroundResource(R.drawable.bg_input)
            binding.port.setBackgroundResource(R.drawable.bg_input)
        } else {
            binding.errorLine.visibility = View.VISIBLE
            binding.errorText.text = message
        }
    }

    private fun save() {
        showError(null)

        val host = binding.host.text.toString().trim()
        val port = binding.port.text.toString().trim().toIntOrNull() ?: 0

        if (host.isBlank()) {
            binding.host.setBackgroundResource(R.drawable.bg_input_error)
            showError(getString(R.string.nc_host_required))
            return
        }
        if (port !in 1..65535) {
            binding.port.setBackgroundResource(R.drawable.bg_input_error)
            showError(getString(R.string.nc_port_invalid))
            return
        }

        val profile = ProxyProfile(
            id = profileId,
            name = binding.name.text.toString().trim().ifBlank { "$host:$port" },
            type = type,
            host = host,
            port = port,
            username = binding.user.text.toString().trim().ifBlank { null },
            password = binding.pass.text.toString().ifBlank { null },
            enabled = binding.switchEnabled.isChecked
        )

        profileId = if (profile.id == 0L) store.insert(profile) else {
            store.update(profile)
            profile.id
        }

        lifecycleScope.launch {
            try {
                manager.setProxy(instanceId, profileId)
                toast(getString(R.string.proxy_saved))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "could not bind proxy: ${e.message}")
                toast(getString(R.string.net_proxy_save_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * Open a TCP connection to the proxy endpoint.
     *
     * Deliberately a plain socket connect, not a proxied request: the question this
     * answers is "is the endpoint there", and a full proxied round-trip would conflate
     * "proxy is down" with "proxy refuses this destination".
     */
    private fun test() {
        showError(null)

        val host = binding.host.text.toString().trim()
        val port = binding.port.text.toString().trim().toIntOrNull() ?: 0

        if (host.isBlank()) {
            binding.host.setBackgroundResource(R.drawable.bg_input_error)
            showError(getString(R.string.nc_host_required))
            return
        }
        if (port !in 1..65535) {
            binding.port.setBackgroundResource(R.drawable.bg_input_error)
            showError(getString(R.string.nc_port_invalid))
            return
        }

        binding.btnTest.isEnabled = false
        binding.btnTest.text = getString(R.string.nc_connection_test)

        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val reachable = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), 4000)
                        true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "proxy probe failed: ${e.message}")
                    false
                }
            }
            val elapsed = System.currentTimeMillis() - started

            binding.btnTest.isEnabled = true
            binding.btnTest.text = getString(R.string.nc_test)

            if (reachable) {
                if (profileId != 0L) {
                    withContext(Dispatchers.IO) {
                        runCatching { store.markOk(profileId) }
                            .onFailure { Log.w(TAG, "could not record probe: ${it.message}") }
                    }
                }
                toast(getString(R.string.net_proxy_reachable, "$host:$port", elapsed))
            } else {
                showError(getString(R.string.net_proxy_unreachable, "$host:$port"))
            }
        }
    }

    /**
     * Unbind this clone, and delete the profile only when nothing else still points at it.
     *
     * A profile can be shared: cloning a clone copies its proxyId. Deleting the row while
     * another clone still references it would leave that clone pointing at a missing row,
     * which reads back as "no proxy" and silently drops its egress path.
     */
    private fun clear() {
        val id = profileId
        lifecycleScope.launch {
            val refs = withContext(Dispatchers.IO) {
                if (id == 0L) 0 else manager.snapshot().count { it.proxyId == id }
            }

            if (id != 0L && refs <= 1) {
                withContext(Dispatchers.IO) { runCatching { store.delete(id) } }
                profileId = 0L
            }

            runCatching { manager.setProxy(instanceId, null) }
                .onFailure { Log.w(TAG, "could not unbind proxy: ${it.message}") }

            toast(
                if (id != 0L && refs > 1) getString(R.string.net_proxy_kept_shared)
                else getString(R.string.proxy_none)
            )
            finish()
        }
    }
}
