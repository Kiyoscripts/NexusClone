package com.kiyoscripts.nexusclone.ui.instances

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import com.kiyoscripts.nexusclone.util.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The instance grid.
 *
 * Shows every clone with its cached preview, state badge and quota bar. Long-press opens
 * the detail screen; tap launches.
 */
class InstancesActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InstancesActivity"

        fun start(context: Context) {
            context.startActivity(Intent(context, InstancesActivity::class.java))
        }
    }

    private lateinit var binding: ActivityInstancesBinding
    private lateinit var manager: InstanceManager
    private lateinit var previews: PreviewCache
    private val adapter = InstanceAdapter()

    /**
     * Scope for preview decoding.
     *
     * Separate from lifecycleScope so a decode that is already running is not cancelled
     * mid-flight by a configuration change, and cancelled explicitly in onDestroy.
     */
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Package icons, keyed by package name.
     *
     * getApplicationIcon() is a binder round-trip into the host PackageManager. The grid
     * rebinds cells constantly while scrolling, so resolving it per bind is pure waste.
     */
    private val iconCache = LruCache<String, Drawable>(64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstancesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = InstanceManager.get(this)
        previews = PreviewCache(this)
        // Start the refresh timer and eviction on ON_START, stop on ON_STOP. Without this the
        // cache never refreshes and thumbnails are never evicted.
        previews.attach(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = getString(R.string.nav_instances)

        binding.recycler.layoutManager = GridLayoutManager(this, 2)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener {
            // The app picker lives in ListActivity. Its result was previously discarded,
            // which is why this screen could never gain a row: nothing ever called
            // InstanceManager.create(). The picker now returns a package name and the
            // clone is created here.
            pickerLauncher.launch(Intent(this, com.kiyoscripts.nexusclone.view.list.ListActivity::class.java))
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

    /**
     * Result of the app picker.
     *
     * ListActivity returns the chosen package name in the "source" extra with
     * RESULT_OK. That value is a package name for an installed app, or a content/file URI
     * when the user picked an apk from storage; InstanceManager.create() takes a package
     * name, so a URI is installed into a fresh user first and then registered.
     */
    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val source = result.data?.getStringExtra("source") ?: return@registerForActivityResult
        createClone(source)
    }

    private fun createClone(source: String) {
        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            try {
                val outcome = manager.create(source)
                outcome.fold(
                    onSuccess = {
                        toast(getString(R.string.clone_created))
                    },
                    onFailure = { e ->
                        Log.w(TAG, "clone failed: ${e.message}")
                        toast(getString(R.string.clone_failed, e.message ?: "unknown"))
                    }
                )
            } finally {
                binding.progress.visibility = View.GONE
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

    override fun onDestroy() {
        previewScope.cancel()
        super.onDestroy()
    }

    /** Resolve a package icon, memoised. Returns null when the package is gone. */
    private fun iconFor(packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        return try {
            val d = packageManager.getApplicationIcon(packageName)
            iconCache.put(packageName, d)
            d
        } catch (e: Exception) {
            null
        }
    }

    private inner class InstanceAdapter :
        RecyclerView.Adapter<InstanceAdapter.VH>() {

        private val items = ArrayList<CloneInstance>()

        /**
         * Replace the list and repaint only what actually changed.
         *
         * This used to call notifyDataSetChanged() unconditionally. InstanceManager
         * republishes its StateFlow on every state transition, every size refresh and every
         * preview write, so a full rebind was happening several times per second while a
         * clone was starting. Each rebind re-decoded the preview WebP and re-resolved the
         * package icon on the main thread, which is exactly the stutter this screen had.
         *
         * Diffing on the fields the row actually renders means a state change repaints one
         * cell instead of the whole grid.
         */
        fun submit(list: List<CloneInstance>) {
            val old = ArrayList(items)
            items.clear()
            items.addAll(list)

            if (old.size != items.size) {
                notifyDataSetChanged()
                return
            }
            for (i in items.indices) {
                val a = old[i]
                val b = items[i]
                if (a.id != b.id) {
                    // Order changed: a positional diff would be wrong, so fall back.
                    notifyDataSetChanged()
                    return
                }
                if (a.state != b.state ||
                    a.label != b.label ||
                    a.dataSizeBytes != b.dataSizeBytes ||
                    a.quotaBytes != b.quotaBytes ||
                    a.previewUpdatedAt != b.previewUpdatedAt
                ) {
                    notifyItemChanged(i)
                }
            }
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

                // Decode the preview off the main thread. BitmapFactory.decodeFile on a
                // 180x320 WebP is not free, and this runs for every visible cell on every
                // rebind; doing it inline is what made the grid hitch while scrolling.
                val file: File? = previews.existingFile(inst)
                val stamp = inst.previewUpdatedAt
                if (file != null) {
                    val tag = file.absolutePath + "@" + stamp
                    if (b.preview.tag != tag) {
                        b.preview.tag = tag
                        b.preview.setImageDrawable(null)
                        previewScope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(file.absolutePath)
                            }
                            // The holder may have been recycled onto another instance while
                            // we were decoding; only apply if the tag still matches.
                            if (bmp != null && b.preview.tag == tag) {
                                b.preview.setImageBitmap(bmp)
                            }
                        }
                    }
                } else {
                    b.preview.tag = null
                    b.preview.setImageDrawable(null)
                }

                // The package icon is resolved through the host PackageManager, which is a
                // binder round-trip. Cache it per package so scrolling does not re-resolve.
                b.icon.setImageDrawable(iconFor(inst.packageName))

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
