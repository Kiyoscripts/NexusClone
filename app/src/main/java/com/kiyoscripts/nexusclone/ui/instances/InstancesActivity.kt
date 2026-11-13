package com.kiyoscripts.nexusclone.ui.instances

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceState
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.preview.PreviewCache
import com.kiyoscripts.nexusclone.databinding.ActivityInstancesBinding
import com.kiyoscripts.nexusclone.databinding.ItemInstanceBinding
import com.kiyoscripts.nexusclone.ui.instance.InstanceDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * The instance grid.
 *
 * Shows every clone with its cached preview, state badge and quota bar. Long-press opens
 * the detail screen; tap launches.
 */
class InstancesActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, InstancesActivity::class.java))
        }
    }

    private lateinit var binding: ActivityInstancesBinding
    private lateinit var manager: InstanceManager
    private lateinit var previews: PreviewCache
    private val adapter = InstanceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstancesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = InstanceManager.get(this)
        previews = PreviewCache(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = getString(R.string.nav_instances)

        binding.recycler.layoutManager = GridLayoutManager(this, 2)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener {
            // Cloning starts from the app list; this is the shortcut into it.
            startActivity(Intent(this, com.kiyoscripts.nexusclone.view.main.MainActivity::class.java))
        }

        lifecycleScope.launch {
            manager.refresh()
            manager.instances.collectLatest { list ->
                adapter.submit(list)
                binding.empty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            manager.refresh()
            manager.refreshSizes()
        }
    }

    private inner class InstanceAdapter :
        RecyclerView.Adapter<InstanceAdapter.VH>() {

        private val items = ArrayList<CloneInstance>()

        fun submit(list: List<CloneInstance>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemInstanceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(private val b: ItemInstanceBinding) :
            RecyclerView.ViewHolder(b.root) {

            fun bind(inst: CloneInstance) {
                b.label.text = inst.label
                b.subtitle.text = "${inst.packageName} · u${inst.userId}"
                b.stateBadge.text = stateLabel(inst.state)

                val icon = try {
                    packageManager.getApplicationIcon(inst.packageName)
                } catch (e: Exception) {
                    null
                }
                b.icon.setImageDrawable(icon)

                val file: File? = previews.existingFile(inst)
                if (file != null) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        b.preview.setImageBitmap(bmp)
                    } else {
                        b.preview.setImageDrawable(icon)
                    }
                } else {
                    b.preview.setImageDrawable(icon)
                }

                if (inst.quotaBytes > 0) {
                    b.quotaBar.visibility = View.VISIBLE
                    val pct = ((inst.dataSizeBytes.toDouble() / inst.quotaBytes) * 100)
                        .toInt().coerceIn(0, 100)
                    b.quotaBar.progress = pct
                } else {
                    b.quotaBar.visibility = View.GONE
                }

                b.root.setOnClickListener {
                    lifecycleScope.launch {
                        manager.launch(inst.id)
                    }
                }
                b.root.setOnLongClickListener {
                    InstanceDetailActivity.start(this@InstancesActivity, inst.id)
                    true
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
    }
}
