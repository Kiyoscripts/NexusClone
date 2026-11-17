package com.kiyoscripts.nexusclone.view.main

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.app.AppManager
import com.kiyoscripts.nexusclone.bean.InstalledAppBean
import com.kiyoscripts.nexusclone.core.diag.Check
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.core.instance.CloneInstance
import com.kiyoscripts.nexusclone.core.instance.InstanceManager
import com.kiyoscripts.nexusclone.core.keepalive.KeepAliveController
import com.kiyoscripts.nexusclone.core.preview.PreviewCache
import com.kiyoscripts.nexusclone.data.AppsRepository
import com.kiyoscripts.nexusclone.databinding.ActivityMainBinding
import com.kiyoscripts.nexusclone.databinding.ItemCloneBinding
import com.kiyoscripts.nexusclone.databinding.ItemPackageBinding
import com.kiyoscripts.nexusclone.ui.diag.DiagnosticsActivity
import com.kiyoscripts.nexusclone.ui.instance.InstanceDetailActivity
import com.kiyoscripts.nexusclone.ui.instance.stateLabel
import com.kiyoscripts.nexusclone.util.toast
import com.kiyoscripts.nexusclone.view.fake.FakeManagerActivity
import com.kiyoscripts.nexusclone.view.gms.GmsManagerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

/**
 * The app shell: three tabs over one page host.
 *
 * Clones is the primary surface. It is a grid of clones, not a pager of virtual users:
 * the clone is the object the user thinks in, and the virtual user is an implementation
 * detail of the container. Apps is a picker that creates a clone from an installed
 * package. Settings holds the system rows, the behaviour switches and the about block.
 *
 * Diagnostics is deliberately not a tab. It is reached from the Settings row (which
 * carries a live failing-check count) and from a clone's detail screen, and its summary
 * state is surfaced as the status strip at the top of Clones. A fourth tab for a screen
 * that is opened rarely and read once would cost a permanent slot in the primary
 * navigation for no gain.
 *
 * The engine is brought up on its own thread from App.onCreate(). Nothing here blocks on
 * it: the clone list renders from the local store immediately, and the engine-dependent
 * parts (the status strip, the app picker) fill in when AppManager reports ready.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** How long the app picker waits for the engine before giving up. */
        private const val ENGINE_READY_TIMEOUT_MS = 10_000L

        fun start(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java))
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var manager: InstanceManager
    private lateinit var previews: PreviewCache
    private lateinit var diag: Diagnostics
    private lateinit var repo: AppsRepository

    private val cloneAdapter = CloneAdapter()
    private val appAdapter = AppPickerAdapter()

    /**
     * Scope for preview decoding and icon resolution.
     *
     * Separate from lifecycleScope so an in-flight decode is not cancelled by a
     * configuration change, and cancelled explicitly in onDestroy.
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The full installed-app list, before the search box and the chips narrow it. */
    private var allApps: List<InstalledAppBean> = emptyList()

    private var currentTab = Tab.CLONES

    /** Last diagnostics sweep, used for the status strip and the Settings badge. */
    private var lastChecks: List<Check> = emptyList()

    /** When [lastChecks] was produced, so a quick return does not re-run the sweep. */
    private var lastChecksAt = 0L

    private enum class Tab { CLONES, APPS, SETTINGS }

    /**
     * Import an apk from storage as a new clone.
     *
     * The persistable grant is best effort: some providers refuse it, and the one-shot
     * read grant from the picker is enough for the install that follows immediately.
     */
    private val importApk =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "no persistable grant for $uri: ${e.message}")
            }

            lifecycleScope.launch {
                binding.appsProgress.visibility = View.VISIBLE
                try {
                    manager.create(uri.toString()).fold(
                        onSuccess = { inst ->
                            toast(getString(R.string.clone_created, inst.label))
                            selectTab(Tab.CLONES)
                        },
                        onFailure = { e ->
                            toast(getString(R.string.clone_failed, e.message ?: "unknown"))
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toast(getString(R.string.clone_failed, e.message ?: "unknown"))
                } finally {
                    binding.appsProgress.visibility = View.GONE
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = InstanceManager.get(this)
        previews = PreviewCache(this)
        diag = Diagnostics(this)
        repo = AppsRepository()

        // Starts the preview refresh timer and eviction on ON_START, stops on ON_STOP.
        previews.attach(this)

        // The grid scrolls under the tab bar, so its last row and the FAB would sit behind
        // it. Pad the grid by the bar's measured height, keeping the padding it declares.
        val gridBaseBottom = binding.cloneGrid.paddingBottom
        binding.tabbar.doOnLayout { bar ->
            binding.cloneGrid.setPadding(
                binding.cloneGrid.paddingLeft,
                binding.cloneGrid.paddingTop,
                binding.cloneGrid.paddingRight,
                gridBaseBottom + bar.height
            )
        }

        initTabs()
        initCloneGrid()
        initAppPicker()
        initSettings()
        initFab()
        initOverflow()

        observeInstances()
        refreshDiagnostics()
    }

    // ---------------------------------------------------------------- tabs

    private fun initTabs() {
        binding.tabClones.setOnClickListener { selectTab(Tab.CLONES) }
        binding.tabApps.setOnClickListener { selectTab(Tab.APPS) }
        binding.tabSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
        selectTab(Tab.CLONES)
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab

        binding.pageClones.visibility = if (tab == Tab.CLONES) View.VISIBLE else View.GONE
        binding.pageApps.visibility = if (tab == Tab.APPS) View.VISIBLE else View.GONE
        binding.pageSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE

        // The FAB creates a clone, so it only means something on Clones and Apps.
        binding.fab.visibility = if (tab == Tab.SETTINGS) View.GONE else View.VISIBLE

        binding.title.setText(
            when (tab) {
                Tab.CLONES -> R.string.nav_instances
                Tab.APPS -> R.string.nav_apps
                Tab.SETTINGS -> R.string.nav_settings
            }
        )

        tintTab(binding.tabClonesIcon, binding.tabClonesLabel, tab == Tab.CLONES)
        tintTab(binding.tabAppsIcon, binding.tabAppsLabel, tab == Tab.APPS)
        tintTab(binding.tabSettingsIcon, binding.tabSettingsLabel, tab == Tab.SETTINGS)

        when (tab) {
            Tab.CLONES -> updateClonesSubtitle()
            Tab.APPS -> {
                binding.subtitle.text = getString(R.string.choose_app)
                if (allApps.isEmpty()) loadApps()
            }
            Tab.SETTINGS -> binding.subtitle.text = getString(R.string.app_name)
        }
    }

    private fun tintTab(icon: ImageView, label: TextView, on: Boolean) {
        val color = ContextCompat.getColor(this, if (on) R.color.accent else R.color.text_tertiary)
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    // ---------------------------------------------------------------- clones

    private fun initCloneGrid() {
        binding.cloneGrid.layoutManager = GridLayoutManager(this, 2)
        binding.cloneGrid.adapter = cloneAdapter
        binding.cloneGrid.setHasFixedSize(true)
    }

    private fun observeInstances() {
        lifecycleScope.launch {
            // The first refresh is a store read plus an engine query; the grid is empty
            // until it lands, so the spinner covers that window.
            binding.clonesProgress.visibility = View.VISIBLE
            try {
                manager.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "initial instance refresh failed: ${e.message}")
            }

            // A crash between uninstall and row delete leaves a DEAD row behind. Clearing
            // them here means the grid never shows a clone that cannot be opened, and the
            // diagnostics check stops reporting a dead instance the user cannot act on.
            try {
                val purged = manager.purgeDead()
                if (purged > 0) Log.i(TAG, "purged $purged dead instance row(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "purgeDead failed: ${e.message}")
            }

            manager.instances.collectLatest { list ->
                binding.clonesProgress.visibility = View.GONE
                cloneAdapter.submit(list)
                binding.clonesEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                if (currentTab == Tab.CLONES) updateClonesSubtitle()
            }
        }
    }

    private fun updateClonesSubtitle() {
        val list = manager.snapshot()
        val running = list.count { it.state.isResident }
        binding.subtitle.text = when {
            list.isEmpty() -> getString(R.string.instances_empty)
            else -> {
                val count = if (list.size == 1) {
                    getString(R.string.nc_clone_count_one, list.size)
                } else {
                    getString(R.string.nc_clone_count, list.size)
                }
                if (running > 0) {
                    val runningText = getString(R.string.nc_running_count, running)
                    if (list.size == 1) {
                        getString(R.string.ui_clones_running_one, count, runningText)
                    } else {
                        getString(R.string.ui_clones_running, count, runningText)
                    }
                } else {
                    count
                }
            }
        }
    }

    // ---------------------------------------------------------------- apps

    private fun initAppPicker() {
        binding.appList.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.appList.adapter = appAdapter

        binding.appSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.appSearchClear.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                applyAppFilter()
            }
        })

        binding.appSearchClear.setOnClickListener {
            binding.appSearch.setText("")
        }

        binding.chipAll.setOnClickListener { setAppChip(Chip.ALL) }
        binding.chipUser.setOnClickListener { setAppChip(Chip.NOT_CLONED) }
        binding.chipSystem.setOnClickListener { setAppChip(Chip.CLONED) }
    }

    /**
     * Picker filter.
     *
     * The repository already drops FLAG_SYSTEM packages, so a user/system split would be
     * a lie. The only distinction the picker can honestly make is cloned or not.
     */
    private enum class Chip { ALL, NOT_CLONED, CLONED }

    private var currentChip = Chip.ALL

    private fun setAppChip(chip: Chip) {
        currentChip = chip
        val on = ContextCompat.getColor(this, R.color.text_on_accent)
        val off = ContextCompat.getColor(this, R.color.text_secondary)

        listOf(
            binding.chipAll to (chip == Chip.ALL),
            binding.chipUser to (chip == Chip.NOT_CLONED),
            binding.chipSystem to (chip == Chip.CLONED)
        ).forEach { (view, selected) ->
            view.setBackgroundResource(
                if (selected) R.drawable.bg_chip_on else R.drawable.bg_chip
            )
            view.setTextColor(if (selected) on else off)
        }
        applyAppFilter()
    }

    private fun applyAppFilter() {
        val query = binding.appSearch.text?.toString()?.trim().orEmpty()
        val filtered = allApps.filter { app ->
            val matchesQuery = query.isEmpty() ||
                app.name.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            val matchesChip = when (currentChip) {
                Chip.ALL -> true
                Chip.NOT_CLONED -> !app.isInstall
                Chip.CLONED -> app.isInstall
            }
            matchesQuery && matchesChip
        }
        appAdapter.submit(filtered)
        // The chips filter nothing when there is nothing to filter.
        binding.appChips.visibility = if (allApps.isEmpty()) View.GONE else View.VISIBLE
        binding.appsEmpty.visibility =
            if (filtered.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadApps() {
        binding.appsProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                if (!AppManager.awaitEngineReady(ENGINE_READY_TIMEOUT_MS)) {
                    Log.w(TAG, "engine not ready, app picker stays empty")
                    return@launch
                }
                val list = withContext(Dispatchers.IO) {
                    repo.previewInstallList()
                    repo.installedAppsSnapshot()
                }
                allApps = list
                applyAppFilter()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "app picker load failed: ${e.message}")
            } finally {
                binding.appsProgress.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------- settings

    private fun initSettings() {
        binding.rowDiagnostics.setOnClickListener { DiagnosticsActivity.start(this) }
        binding.rowProxy.setOnClickListener {
            // The default proxy is edited through a clone's proxy screen; there is no
            // global proxy editor, so this row explains that rather than opening nothing.
            toast(getString(R.string.ui_no_global_proxy))
        }
        binding.rowGms.setOnClickListener { GmsManagerActivity.start(this) }

        val keepAlive = KeepAliveController.get(this)
        binding.switchKeepAlive.isChecked = keepAlive.isEnabled()
        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            keepAlive.setEnabled(checked)
        }

        // The capture side gates on this same key, so the switch and the capture path
        // cannot drift apart. Turning it off also drops the previews already on disk,
        // otherwise the grid would keep showing screenshots the user just opted out of.
        val prefs = getSharedPreferences("nexusclone", MODE_PRIVATE)
        binding.switchPreview.isChecked = prefs.getBoolean("preview_capture_enabled", true)
        binding.switchPreview.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("preview_capture_enabled", checked).apply()
            if (!checked) previews.clearAll()
        }

        binding.specVersion.text = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("-")

        binding.specDevice.text =
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · SDK ${android.os.Build.VERSION.SDK_INT}"

        loadGmsSummary()
    }

    /**
     * How many virtual users have Google services installed.
     *
     * Both calls in the loop are binder round-trips into :black, so they run off the main
     * thread. A failure is not an error state: it just means the count is unknown.
     */
    private fun loadGmsSummary() {
        lifecycleScope.launch {
            val summary = try {
                withContext(Dispatchers.IO) {
                    val users = BlackBoxCore.get().users
                    val withGms = users.count {
                        try {
                            BlackBoxCore.get().isInstallGms(it.id)
                        } catch (e: Exception) {
                            false
                        }
                    }
                    withGms to users.size
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "gms summary failed: ${e.message}")
                null
            }

            binding.subGms.text = if (summary != null && summary.first > 0) {
                getString(R.string.ui_gms_installed_in, summary.first, summary.second)
            } else {
                getString(R.string.ui_gms_not_installed)
            }
        }
    }

    private fun refreshDiagnostics() {
        lifecycleScope.launch {
            try {
                val checks = withContext(Dispatchers.IO) { diag.runAll() }
                lastChecks = checks
                lastChecksAt = System.currentTimeMillis()
                renderStrip(checks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "diagnostics sweep failed: ${e.message}")
            }
        }
    }

    /** Paint the status strip and the Settings badge from an already-run sweep. */
    private fun renderStrip(checks: List<Check>) {
        val passed = checks.count { it.ok }
        val failed = checks.size - passed

        binding.statusStrip.text.text =
            if (failed == 0) getString(R.string.nc_checks_passing)
            else getString(R.string.nc_checks_attention, failed)
        binding.statusStrip.meta.text = "$passed/${checks.size}"
        binding.statusStrip.dot.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (failed == 0) R.color.success else R.color.warn
        )

        binding.subDiagnostics.text =
            getString(R.string.nc_checks_summary, passed, checks.size)
        binding.badgeDiagnostics.visibility =
            if (failed == 0) View.GONE else View.VISIBLE
        binding.badgeDiagnostics.text = failed.toString()
    }

    // ---------------------------------------------------------------- fab + overflow

    private fun initFab() {
        binding.fab.setOnClickListener {
            // Creating a clone starts from the app picker, which is the Apps tab.
            selectTab(Tab.APPS)
        }
    }

    private fun initOverflow() {
        binding.btnMore.setOnClickListener { showOverflow() }
    }

    private fun showOverflow() {
        val items = arrayOf(
            getString(R.string.fake_location),
            getString(R.string.ui_import_apk)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> FakeManagerActivity.start(this)
                    1 -> importApk.launch(arrayOf("application/vnd.android.package-archive"))
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        // A clone may have been created, launched or deleted while this screen was away.
        lifecycleScope.launch {
            runCatching { manager.refresh() }
            if (currentTab == Tab.CLONES) updateClonesSubtitle()
        }

        // The sweep is a handful of binder round-trips. Re-running it on every return from
        // a clone screen is wasted work, so a recent result is repainted instead.
        if (lastChecks.isNotEmpty() &&
            System.currentTimeMillis() - lastChecksAt < 60_000L
        ) {
            renderStrip(lastChecks)
        } else {
            refreshDiagnostics()
        }
    }

    override fun onDestroy() {
        workScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- adapters

    /**
     * Clone grid.
     *
     * Diffs on the fields the cell actually renders so a state change repaints one cell
     * instead of the whole grid.
     */
    private inner class CloneAdapter : RecyclerView.Adapter<CloneAdapter.VH>() {

        private val items = ArrayList<CloneInstance>()

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
                    notifyDataSetChanged()
                    return
                }
                if (a.state != b.state ||
                    a.label != b.label ||
                    a.previewUpdatedAt != b.previewUpdatedAt
                ) {
                    notifyItemChanged(i)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemCloneBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(inst: CloneInstance) {
                b.label.text = inst.label
                b.pkg.text = inst.packageName
                b.glyph.text = inst.label.take(1).uppercase()

                // Deterministic tint per clone so a clone keeps its colour across restarts.
                b.previewFrame.setBackgroundResource(tintFor(inst))

                val resident = inst.state.isResident
                b.liveBadge.visibility = if (resident) View.VISIBLE else View.GONE
                b.stateBadge.visibility = if (resident) View.GONE else View.VISIBLE
                b.stateBadge.text = stateLabel(this@MainActivity, inst.state)

                bindPreview(inst)

                b.root.setOnClickListener {
                    InstanceDetailActivity.start(this@MainActivity, inst.id)
                }
            }

            private fun bindPreview(inst: CloneInstance) {
                val file: File? = previews.existingFile(inst)
                val stamp = inst.previewUpdatedAt

                if (file == null) {
                    b.preview.tag = null
                    b.preview.setImageDrawable(null)
                    b.preview.visibility = View.GONE
                    b.glyph.visibility = View.VISIBLE
                    return
                }

                val tag = file.absolutePath + "@" + stamp
                if (b.preview.tag == tag) return
                b.preview.tag = tag

                workScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    }
                    // The holder may have been recycled onto another clone while we were
                    // decoding; only apply if the tag still matches.
                    if (bmp != null && b.preview.tag == tag) {
                        b.preview.setImageBitmap(bmp)
                        b.preview.visibility = View.VISIBLE
                        b.glyph.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * App picker.
     *
     * One row per installed package. Tapping the row clones it; the plus button does the
     * same thing and exists so the affordance is visible rather than implied.
     */
    private inner class AppPickerAdapter : RecyclerView.Adapter<AppPickerAdapter.VH>() {

        private val items = ArrayList<InstalledAppBean>()

        fun submit(list: List<InstalledAppBean>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemPackageBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(app: InstalledAppBean) {
                b.name.text = app.name
                b.packageName.text = app.packageName
                b.icon.setImageDrawable(app.icon)

                b.cloneCount.visibility = if (app.isInstall) View.VISIBLE else View.GONE
                b.cloneCount.text = getString(R.string.nc_cloned)

                val clone = View.OnClickListener { createClone(app.packageName) }
                b.root.setOnClickListener(clone)
                b.btnClone.setOnClickListener(clone)
            }
        }
    }

    private fun createClone(packageName: String) {
        lifecycleScope.launch {
            binding.appsProgress.visibility = View.VISIBLE
            try {
                val result = manager.create(packageName)
                result.fold(
                    onSuccess = { inst ->
                        toast(getString(R.string.clone_created, inst.label))
                        selectTab(Tab.CLONES)
                    },
                    onFailure = { e ->
                        toast(getString(R.string.clone_failed, e.message ?: "unknown"))
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(getString(R.string.clone_failed, e.message ?: "unknown"))
            } finally {
                binding.appsProgress.visibility = View.GONE
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun tintFor(inst: CloneInstance): Int {
        // Stable per clone: the row id never changes, so the colour never changes.
        return when ((inst.id % 4L).toInt()) {
            0 -> R.drawable.bg_clone_tint_1
            1 -> R.drawable.bg_clone_tint_2
            2 -> R.drawable.bg_clone_tint_3
            else -> R.drawable.bg_clone_tint_4
        }
    }
}
