package com.kiyoscripts.nexusclone.ui.diag

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.core.diag.Check
import com.kiyoscripts.nexusclone.core.diag.Diagnostics
import com.kiyoscripts.nexusclone.databinding.ActivityDiagnosticsBinding
import com.kiyoscripts.nexusclone.databinding.ItemCheckBinding

/**
 * Runtime self-check screen.
 *
 * Answers "why did my clone not work" without adb.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diag = Diagnostics(this)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.title = getString(R.string.diag_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarLayout.toolbar.setNavigationOnClickListener { finish() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRun.setOnClickListener { run() }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on return from the system exemption dialog so the row flips to PASS.
        run()
    }

    private fun run() {
        adapter.submit(diag.runAll())
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

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(private val b: ItemCheckBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(c: Check) {
                val tag = if (c.ok) getString(R.string.diag_pass) else getString(R.string.diag_fail)
                b.title.text = "[$tag] ${c.title}"
                b.title.setTextColor(if (c.ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                b.detail.text = c.detail

                // Only the failing battery row is actionable: tapping it asks the system
                // for the exemption that the check is reporting as missing.
                if (c.id == "battery" && !c.ok) {
                    b.root.isClickable = true
                    b.root.setOnClickListener { requestBatteryExemption() }
                } else {
                    b.root.isClickable = false
                    b.root.setOnClickListener(null)
                }
            }
        }
    }
}
