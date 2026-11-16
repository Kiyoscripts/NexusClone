package com.kiyoscripts.nexusclone.view.gms

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.bean.GmsBean
import com.kiyoscripts.nexusclone.databinding.ActivityGmsBinding
import com.kiyoscripts.nexusclone.util.InjectionUtil
import com.kiyoscripts.nexusclone.util.inflate
import com.kiyoscripts.nexusclone.util.toast
import com.kiyoscripts.nexusclone.view.base.LoadingActivity

/**
 *
 * @Description: gms manager activity
 * @Author: BlackBox
 * @CreateDate: 2022/3/2 21:06
 */
class GmsManagerActivity : LoadingActivity() {

    private lateinit var viewModel: GmsViewModel

    private lateinit var mAdapter: RVAdapter<GmsBean>

    private val viewBinding: ActivityGmsBinding by inflate()

    /**
     * True while an install/uninstall is running.
     *
     * GMS install is thirteen sequential package installs inside the engine. Without this
     * guard a second tap on another row starts a second install concurrently, and the two
     * fight over the engine's install lock while the UI shows one spinner for both.
     */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.gms_manager, true)
        initRecyclerView()
        initViewModel()
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java]
        showLoading()

        viewModel.mInstalledLiveData.observe(this) {
            hideLoading()
            mAdapter.setItems(it)
        }

        viewModel.mBusyLiveData.observe(this) { isBusy ->
            busy = isBusy
            // The spinner is the only progress signal the engine can give us: installGApps()
            // reports once, at the end. An indeterminate spinner is honest; a fake percentage
            // would not be.
            if (isBusy) showLoading() else hideLoading()
        }

        viewModel.mUpdateInstalledLiveData.observe(this) { result ->
            if (result == null) {
                return@observe
            }

            val items = mAdapter.getItems()
            for (index in items.indices) {
                val bean = items[index]
                if (bean.userID == result.userID) {
                    if (result.success) {
                        bean.isInstalledGms = !bean.isInstalledGms
                    }
                    mAdapter.replaceAt(index, bean)
                    break
                }
            }

            hideLoading()

            if (result.success) {
                toast(result.msg)
            } else {
                MaterialDialog(this).show {
                    title(R.string.gms_manager)
                    message(text = result.msg)
                    positiveButton(R.string.done)
                }
            }
        }

        viewModel.getInstalledUser()
    }

    private fun initRecyclerView() {
        mAdapter = RVAdapter<GmsBean>(this, GmsAdapter()).bind(viewBinding.recyclerView)
            .setItemClickListener { view, item, _ ->
                // Ignore taps while an install is already running: the engine serialises
                // installs behind one lock, so a queued second install would just make the
                // spinner lie about what is happening.
                if (busy) {
                    val checkbox = view.findViewById<Switch>(R.id.checkbox)
                    checkbox.isChecked = item.isInstalledGms
                    return@setItemClickListener
                }
                val checkbox = view.findViewById<Switch>(R.id.checkbox)
                if (item.isInstalledGms) {
                    uninstallGms(item.userID, checkbox)
                } else {
                    installGms(item.userID, checkbox)
                }
            }
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun installGms(userID: Int, checkbox: Switch) {
        MaterialDialog(this).show {
            title(R.string.enable_gms)
            message(R.string.enable_gms_hint)
            positiveButton(R.string.done) {
                showLoading()
                viewModel.installGms(userID)
            }
            negativeButton(R.string.cancel) {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    private fun uninstallGms(userID: Int, checkbox: Switch) {
        MaterialDialog(this).show {
            title(R.string.disable_gms)
            message(R.string.disable_gms_hint)
            positiveButton(R.string.done) {
                showLoading()
                viewModel.uninstallGms(userID)
            }
            negativeButton(R.string.cancel) {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GmsManagerActivity::class.java)
            context.startActivity(intent)
        }
    }
}
