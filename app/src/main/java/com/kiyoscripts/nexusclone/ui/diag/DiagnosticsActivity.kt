package com.kiyoscripts.nexusclone.ui.diag

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.diag.Check
import com.kiyoscripts.nexusclone.core.diag.CheckRemedy
import com.kiyoscripts.nexusclone.core.diag.CheckSeverity
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.databinding.ActivityDiagnosticsBinding
import com.kiyoscripts.nexusclone.databinding.ItemCheckBinding
import com.kiyoscripts.nexusclone.view.setting.SettingActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runtime self-check screen.
 *
 * Answers "why did my clone not work" without adb. The summary ring is the only thing on
 * the screen that is read at a glance; the rows below it are the detail.
 *
 * Status colours come from the theme (@color/success, @color/warn, @color/danger), not
 * from hardcoded ints. The previous version wrote 0xFF2E7D32 and 0xFFC62828 directly into
 * setTextColor, so in dark mode the PASS/FAIL text was light-theme green and red on a
 * near-black background.
 */
class DiagnosticsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DiagnosticsActivity"

        fun start(context: Context) {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }

    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var diag: Diagnostics
    private val adapter = CheckAdapter()

    /**
     * In-flight guard for [run].
     *
     * onResume fires again after the battery-exemption dialog and on every rotation, so
     * without this a second sweep could start while the first is still running and the two
     * would race to submit into the adapter.
     */
    @Volatile
    private var running = false

    private var ringAnimator: ValueAnimator? = null

    /** Last rendered sweep, so the long-press report matches what is on screen. */
    private var lastChecks: List<Check> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diag = Diagnostics(this)

        binding.btnBack.setOnClickListener { finish() }
        // Manual buttons always re-sweep; onResume keeps the cached path.
        binding.btnRerun.setOnClickListener { run(force = true) }
        binding.btnRunAll.setOnClickListener { run(force = true) }

        binding.ringTitle.setOnLongClickListener { copyReport() }
        binding.ringSub.setOnLongClickListener { copyReport() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // Re-check on return from the system exemption dialog so the row flips to PASS.
        run()
    }

    private fun run(force: Boolean = false) {
        // runAll() is not cheap: isBlackProcessRunning() goes through
        // ProviderCall.callSafely() -> ContentProviderCompat.acquireContentProviderClientRetry(),
        // a blocking sleep loop with a 2000 ms budget, and the engine probe is a binder
        // round-trip. Sweep on IO and submit back on the main dispatcher.
        if (running) return
        running = true
        lifecycleScope.launch {
            try {
                val checks = withContext(Dispatchers.IO) { diag.runAll(force) }
                adapter.submit(checks)
                renderSummary(checks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "diagnostics sweep failed: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    private fun renderSummary(checks: List<Check>) {
        lastChecks = checks
        val passed = checks.count { it.ok }
        val total = checks.size
        // Only blockers drive the ring and the title. A degraded or informational failure
        // is context, not a reason to shout "needs attention" on a healthy install.
        val blockers = checks.count { !it.ok && it.severity == CheckSeverity.BLOCKER }

        binding.ringNum.text = passed.toString()
        binding.ringDen.text = "/$total"

        val fraction = if (total == 0) 0f else passed.toFloat() / total
        animateRing(fraction)

        val ringColor = when {
            blockers == 0 -> R.color.success
            blockers <= 2 -> R.color.warn
            else -> R.color.danger
        }
        binding.ring.setRingColor(ContextCompat.getColor(this, ringColor))

        binding.ringTitle.text = if (blockers == 0) {
            getString(R.string.nc_healthy)
        } else {
            getString(R.string.nc_needs_attention, blockers)
        }
        binding.ringSub.text = getString(R.string.nc_checks_summary, passed, total)

        val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        binding.ringMeta.text = getString(R.string.nc_last_run, stamp)
        binding.subtitle.text = getString(R.string.nc_checks_summary, passed, total)
    }

    /**
     * Copy the whole sweep to the clipboard as plain text.
     *
     * Long-press on the summary, because the useful thing to do with a failing report is
     * paste it into a bug report and there is no share target on this screen.
     */
    private fun copyReport(): Boolean {
        val checks = lastChecks
        if (checks.isEmpty()) return true
        val report = buildString {
            appendLine("NexusClone diagnostics")
            appendLine("${checks.count { it.ok }} of ${checks.size} checks passing")
            appendLine()
            checks.forEach { c ->
                val state = if (c.ok) "PASS" else "FAIL"
                appendLine("[$state] ${c.title}: ${c.detail}")
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NexusClone diagnostics", report))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun animateRing(target: Float) {
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(0f, target).apply {
            duration = 420L
            addUpdateListener { binding.ring.setProgress(it.animatedValue as Float) }
            start()
        }
    }

    override fun onDestroy() {
        ringAnimator?.cancel()
        super.onDestroy()
    }

    /**
     * Fire the system "ignore battery optimizations" dialog for this package.
     *
     * Some OEM ROMs ship without the activity or reject the caller outright, so both
     * failure modes are swallowed rather than crashing the diagnostics screen.
     */
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "battery exemption dialog unavailable: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "battery exemption request rejected: ${e.message}")
        }
    }

    private inner class CheckAdapter : RecyclerView.Adapter<CheckAdapter.VH>() {

        private val items = ArrayList<Check>()

        fun submit(list: List<Check>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCheckBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemCheckBinding) : RecyclerView.ViewHolder(b.root) {

            fun bind(c: Check) {
                b.title.text = c.title
                b.detail.text = c.detail

                // Theme colours, not hardcoded ints: these flip with the night palette.
                // A failing row is tinted by severity so a blocker reads louder than an
                // informational miss.
                val glyph: Int
                val tint: Int
                val soft: Int
                val label: Int
                if (c.ok) {
                    glyph = R.drawable.ic_nc_check
                    tint = R.color.success
                    soft = R.color.success_soft
                    label = R.string.nc_pass
                } else {
                    glyph = R.drawable.ic_nc_alert
                    label = R.string.nc_fail
                    when (c.severity) {
                        CheckSeverity.BLOCKER -> {
                            tint = R.color.danger
                            soft = R.color.danger_soft
                        }
                        CheckSeverity.DEGRADED -> {
                            tint = R.color.warn
                            soft = R.color.warn_soft
                        }
                        CheckSeverity.INFO -> {
                            tint = R.color.text_secondary
                            soft = R.color.warn_soft
                        }
                    }
                }

                val color = ContextCompat.getColor(this@DiagnosticsActivity, tint)
                b.checkGlyph.setImageResource(glyph)
                b.checkGlyph.setColorFilter(color)
                b.checkIcon.backgroundTintList = ContextCompat.getColorStateList(
                    this@DiagnosticsActivity,
                    soft
                )
                b.state.setText(label)
                b.state.setTextColor(color)

                // A failing row with a remedy is a one-tap fix. Rows without one stay inert
                // so a tap never looks like it did something.
                val remedy = if (c.ok) CheckRemedy.NONE else c.remedy
                if (remedy == CheckRemedy.NONE) {
                    b.root.isClickable = false
                    b.root.setOnClickListener(null)
                } else {
                    b.root.isClickable = true
                    b.root.setOnClickListener { applyRemedy(remedy) }
                }
            }
        }
    }

    /** Run the one-tap fix attached to a failing check. */
    private fun applyRemedy(remedy: CheckRemedy) {
        when (remedy) {
            CheckRemedy.BATTERY_EXEMPTION -> requestBatteryExemption()
            CheckRemedy.KEEPALIVE_SETTINGS -> startActivity(Intent(this, SettingActivity::class.java))
            CheckRemedy.OVERLAY_PERMISSION -> {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "overlay permission screen unavailable: ${e.message}")
                }
            }
            CheckRemedy.NONE -> Unit
        }
    }
}
