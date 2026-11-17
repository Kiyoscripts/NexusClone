package com.kiyoscripts.nexusclone.ui.macro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.macro.Macro
import com.kiyoscripts.nexusclone.core.macro.MacroPlayer
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

    /** The macro currently selected in the list, if any. */
    private var selected: Macro? = null

    /** The running player, if a replay is in progress. */
    private var player: MacroPlayer? = null

    /** True while a replay is in flight, which turns Play into Stop. */
    private var playing: Boolean = false

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
        binding.btnPlay.setOnClickListener { if (playing) stopPlayback() else playSelected() }
        updatePlayState()

        reload()
    }

    override fun onDestroy() {
        // Never leave a recorder published after this screen goes away: the guest hook would
        // keep feeding a recorder nobody can stop.
        MacroRecorder.detach(recorder)
        player?.cancel()
        player = null
        playing = false
        super.onDestroy()
    }

    /** Play is only meaningful with a selection, and it becomes Stop while replaying. */
    private fun updatePlayState() {
        binding.btnPlay.isEnabled = playing || selected != null
        binding.btnPlay.text = getString(if (playing) R.string.macro_stop else R.string.macro_play)
    }

    /** Cancel an in-flight replay and put the button back to Play. */
    private fun stopPlayback() {
        player?.cancel()
        player = null
        playing = false
        binding.status.text = getString(R.string.macro_stopped)
        updatePlayState()
    }

    /**
     * Replay the selected macro against the guest window.
     *
     * The player dispatches synthetic MotionEvents into the guest's decor view. That view
     * belongs to the guest Activity, which is not this one, so the target is resolved from
     * the container rather than from this screen's hierarchy. When no guest is running there
     * is nothing to replay against, and saying so is more useful than a silent no-op.
     */
    private fun playSelected() {
        val macro = selected
        if (macro == null) {
            toast(getString(R.string.macro_select_first))
            return
        }
        if (macro.events.isEmpty()) {
            toast(getString(R.string.macro_empty_events))
            return
        }
        val target = MacroRecorder.currentGuestView()
        if (target == null) {
            toast(getString(R.string.macro_guest_not_running))
            return
        }
        player?.cancel()
        binding.status.text = getString(R.string.macro_playing, macro.name)
        playing = true
        updatePlayState()
        val p = MacroPlayer(target, macro) {
            runOnUiThread {
                binding.status.text = getString(R.string.macro_played, macro.name)
                player = null
                playing = false
                updatePlayState()
            }
        }
        player = p
        p.start()
    }

    private fun toggleRecord() {
        if (recorder.isRecording) {
            val events = recorder.stop()
            MacroRecorder.detach(recorder)
            binding.btnRecord.text = getString(R.string.macro_record)
            binding.status.text = getString(R.string.macro_events_captured, events.size)
            if (events.isEmpty()) {
                toast(getString(R.string.macro_nothing_recorded))
                return
            }
            val input = EditText(this).apply { hint = getString(R.string.macro_name) }
            val loopInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.macro_loop_count_hint)
                setText("1")
            }
            val loopLabel = android.widget.TextView(this).apply {
                text = getString(R.string.macro_loop_count_label)
            }
            // Built in code because the dialog layout is not ours to edit.
            val form = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 0)
                addView(input)
                addView(loopLabel)
                addView(loopInput)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.macro_save)
                .setView(form)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val name = input.text.toString().ifBlank {
                        getString(R.string.macro_default_name, System.currentTimeMillis())
                    }
                    val loops = loopInput.text.toString().toIntOrNull() ?: 1
                    val m = recorder.toMacro(
                        name,
                        userId,
                        packageName,
                        loops.coerceIn(1, 999)
                    )
                    store.insert(m)
                    reload()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            recorder.start()
            // Publish the recorder so the guest's touch hook can feed it. Without this the
            // recorder collects nothing and every save reports "nothing recorded".
            MacroRecorder.attach(recorder)
            binding.btnRecord.text = getString(R.string.macro_stop)
            binding.status.text = getString(R.string.macro_recording)
        }
    }

    private fun reload() {
        val list = store.forInstance(userId, packageName)
        adapter.submit(list)
        if (selected != null && list.none { it.id == selected?.id }) selected = null
        updatePlayState()
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
                b.meta.text = getString(
                    R.string.macro_meta,
                    m.events.size,
                    m.durationMs,
                    m.loopCount
                )
                // Selecting a row is what arms the Play button. Previously Play was a
                // hardcoded toast because nothing tracked a selection.
                b.root.setBackgroundResource(
                    if (selected?.id == m.id) R.drawable.bg_input else 0
                )
                b.root.setOnClickListener {
                    selected = m
                    binding.status.text = getString(R.string.macro_selected, m.name)
                    // Repaint the whole list so the previous selection clears.
                    notifyDataSetChanged()
                    updatePlayState()
                }
                b.root.setOnLongClickListener {
                    AlertDialog.Builder(this@MacroActivity)
                        .setMessage(getString(R.string.macro_delete_confirm, m.name))
                        .setPositiveButton(R.string.ok) { _, _ ->
                            store.delete(m.id)
                            if (selected?.id == m.id) selected = null
                            reload()
                            updatePlayState()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
        }
    }
}
