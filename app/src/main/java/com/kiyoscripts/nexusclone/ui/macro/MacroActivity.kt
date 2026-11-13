package com.kiyoscripts.nexusclone.ui.macro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.macro.Macro
import com.kiyoscripts.nexusclone.core.macro.MacroRecorder
import com.kiyoscripts.nexusclone.core.macro.MacroStore
import com.kiyoscripts.nexusclone.databinding.ActivityMacroBinding
import com.kiyoscripts.nexusclone.databinding.ItemMacroBinding

/**
 * Macro list for one instance.
 *
 * Recording itself happens against the guest window, which this screen does not own, so
 * the record button here starts a recorder that the guest activity feeds. When the guest
 * is not in the foreground the recorder simply collects nothing, which is the honest
 * behaviour rather than a fake recording.
 */
class MacroActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_USER = "user_id"
        private const val EXTRA_PKG = "package_name"

        fun start(context: Context, userId: Int, packageName: String) {
            context.startActivity(
                Intent(context, MacroActivity::class.java)
                    .putExtra(EXTRA_USER, userId)
                    .putExtra(EXTRA_PKG, packageName)
            )
        }
    }

    private lateinit var binding: ActivityMacroBinding
    private lateinit var store: MacroStore
    private val adapter = MacroAdapter()
    private var userId: Int = 0
    private var packageName: String = ""
    private val recorder = MacroRecorder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMacroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getIntExtra(EXTRA_USER, 0)
        packageName = intent.getStringExtra(EXTRA_PKG) ?: ""
        store = MacroStore(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = getString(R.string.instance_macro)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRecord.setOnClickListener { toggleRecord() }
        binding.btnPlay.setOnClickListener { toast("select a macro to play") }

        reload()
    }

    private fun toggleRecord() {
        if (recorder.isRecording) {
            val events = recorder.stop()
            binding.btnRecord.text = getString(R.string.macro_record)
            binding.status.text = "${events.size} events captured"
            if (events.isEmpty()) {
                toast("nothing recorded")
                return
            }
            val input = EditText(this).apply { hint = getString(R.string.macro_name) }
            AlertDialog.Builder(this)
                .setTitle(R.string.macro_save)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val name = input.text.toString().ifBlank { "macro ${System.currentTimeMillis()}" }
                    val m = recorder.toMacro(name, userId, packageName)
                    store.insert(m)
                    reload()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            recorder.start()
            binding.btnRecord.text = getString(R.string.macro_stop)
            binding.status.text = "recording…"
        }
    }

    private fun reload() {
        val list = store.forInstance(userId, packageName)
        adapter.submit(list)
        if (list.isEmpty()) {
            binding.status.text = getString(R.string.macro_empty)
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private inner class MacroAdapter : RecyclerView.Adapter<MacroAdapter.VH>() {

        private val items = ArrayList<Macro>()

        fun submit(list: List<Macro>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemMacroBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(private val b: ItemMacroBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(m: Macro) {
                b.name.text = m.name
                b.meta.text = "${m.events.size} events · ${m.durationMs} ms · loop ${m.loopCount}"
                b.root.setOnLongClickListener {
                    AlertDialog.Builder(this@MacroActivity)
                        .setMessage("Delete ${m.name}?")
                        .setPositiveButton(R.string.ok) { _, _ ->
                            store.delete(m.id)
                            reload()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
        }
    }
}
