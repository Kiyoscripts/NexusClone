package com.kiyoscripts.nexusclone.view.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.bean.InstalledAppBean
import com.kiyoscripts.nexusclone.databinding.ActivityListBinding
import com.kiyoscripts.nexusclone.databinding.ItemPackageBinding
import com.kiyoscripts.nexusclone.util.InjectionUtil
import android.view.LayoutInflater
import android.view.ViewGroup

/**
 * App picker.
 *
 * Returns the chosen package name (or an apk URI) in the "source" extra with RESULT_OK.
 *
 * This screen is still reachable on its own for the Xposed module picker, which passes
 * onlyShowXp=true. The main app no longer routes through it: the Apps tab in MainActivity
 * is the picker, and it creates the clone directly instead of returning a result to a
 * caller that might not exist any more.
 *
 * The SimpleSearchView dependency is gone. It was a full-screen search overlay that
 * replaced the toolbar, which is a heavier interaction than a picker needs; the search
 * field is now inline and always visible.
 */
class ListActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context, onlyShowXp: Boolean) {
            val intent = Intent(context, ListActivity::class.java)
            intent.putExtra("onlyShowXp", onlyShowXp)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityListBinding
    private lateinit var viewModel: ListViewModel
    private val adapter = PackageAdapter()

    private var appList: List<InstalledAppBean> = emptyList()
    private var onlyShowXp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onlyShowXp = intent.getBooleanExtra("onlyShowXp", false)

        binding.btnBack.setOnClickListener { finish() }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.btnClearSearch.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                filterApp(s?.toString().orEmpty())
            }
        })

        binding.btnClearSearch.setOnClickListener { binding.search.setText("") }

        binding.chipAll.setOnClickListener { setChip(Chip.ALL) }
        binding.chipUser.setOnClickListener { setChip(Chip.NOT_CLONED) }
        binding.chipSystem.setOnClickListener { setChip(Chip.CLONED) }

        initViewModel()
    }

    /**
     * Three filters, three distinct predicates.
     *
     * There used to be four chips where SYSTEM and CLONED both tested `app.isInstall`, so
     * two chips did exactly the same thing and one of them was labelled with a word that
     * could never be true: the repository drops FLAG_SYSTEM packages, so no row here is a
     * system app.
     */
    private enum class Chip { ALL, NOT_CLONED, CLONED }

    private var currentChip = Chip.ALL

    private fun setChip(chip: Chip) {
        currentChip = chip
        val on = androidx.core.content.ContextCompat.getColor(this, R.color.text_on_accent)
        val off = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)

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
        filterApp(binding.search.text?.toString().orEmpty())
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getListFactory())
            .get(ListViewModel::class.java)

        if (onlyShowXp) {
            viewModel.getInstalledModules()
            binding.subtitle.text = getString(R.string.installed_module)
        } else {
            // User 0 is the host user, and it is the correct argument here: this picker
            // lists packages installed on the device, not packages installed into a clone.
            // The clone's own user id is allocated when the clone is created, so there is
            // no per-clone user to scope this list to.
            viewModel.getInstallAppList(0)
            binding.subtitle.text = getString(R.string.choose_app)
        }

        viewModel.appsLiveData.observe(this) { list ->
            if (list == null) return@observe
            appList = list
            binding.count.text = list.size.toString()
            filterApp(binding.search.text?.toString().orEmpty())
        }
    }

    private fun filterApp(query: String) {
        val filtered = appList.filter { app ->
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

        adapter.submit(filtered)
        binding.emptyState.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility =
            if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }


    private fun finishWithResult(source: String) {
        // A fresh Intent, not the Activity's own launch intent: mutating that one means a
        // recreated Activity carries a stale result payload.
        val result = Intent().putExtra("source", source)
        setResult(Activity.RESULT_OK, result)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }
        finish()
    }

    private inner class PackageAdapter : RecyclerView.Adapter<PackageAdapter.VH>() {

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
                b.cornerLabel.visibility =
                    if (app.isInstall) View.VISIBLE else View.INVISIBLE

                val pick = View.OnClickListener { finishWithResult(app.packageName) }
                b.root.setOnClickListener(pick)
                b.btnClone.setOnClickListener(pick)
            }
        }
    }
}
