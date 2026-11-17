package com.kiyoscripts.nexusclone.view.xp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.bean.XpModuleInfo
import com.kiyoscripts.nexusclone.databinding.ActivityXpBinding
import com.kiyoscripts.nexusclone.util.InjectionUtil
import com.kiyoscripts.nexusclone.util.inflate
import com.kiyoscripts.nexusclone.util.toast
import com.kiyoscripts.nexusclone.view.base.LoadingActivity

/**
 *
 * @Description: xposed模块管理界面
 * @Author: wukaicheng
 * @CreateDate: 2021/5/2 20:25
 */
class XpActivity : LoadingActivity() {

    private val viewBinding: ActivityXpBinding by inflate()


    private lateinit var viewModel: XpViewModel

    private lateinit var mAdapter: RVAdapter<XpModuleInfo>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.xp_setting, true)

        viewModel = ViewModelProvider(this, InjectionUtil.getXpFactory()).get(XpViewModel::class.java)

        initRecyclerView()
        initFab()
    }


    private fun observeLiveData() {

        viewBinding.stateView.showLoading()
        viewModel.getInstalledModule()
        viewModel.appsLiveData.observe(this) {
            if (it.isNullOrEmpty()) {
                viewBinding.stateView.showEmpty()
            } else {
                mAdapter.setItems(it)
                viewBinding.stateView.showContent()
            }
        }

        viewModel.resultLiveData.observe(this) {
            if (!TextUtils.isEmpty(it)) {
                hideLoading()
                toast(it)
                viewModel.getInstalledModule()
            }
        }
    }

        private fun initRecyclerView() {

            mAdapter = RVAdapter<XpModuleInfo>(this, XpAdapter()).bind(viewBinding.recyclerView)
                .setItemClickListener { view, item, position ->
                    // The engine write can fail. Reverting the toggle matters: leaving it
                    // flipped would tell the user a module was enabled when it was not.
                    val next = !item.enable
                    val ok = try {
                        BlackBoxCore.get().setModuleEnable(item.packageName, next)
                        true
                    } catch (e: Throwable) {
                        Log.w(TAG, "setModuleEnable(${item.packageName}) failed: ${e.message}")
                        false
                    }
                    if (ok) {
                        item.enable = next
                        mAdapter.replaceAt(position, item)
                        toast(R.string.restart_module)
                    } else {
                        toast(getString(R.string.legacy_xp_toggle_failed, item.packageName))
                    }
                }.setItemLongClickListener { _, item, _ ->
                    unInstallModule(item.packageName)
                }

            viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)
            viewBinding.stateView.showEmpty()
        }

    private fun initFab() {
        // A document picker, not the app picker.
        //
        // This used to launch ListActivity with onlyShowXp=true, which lists modules that
        // are ALREADY installed. Picking one re-installed a module the user already had,
        // and there was no way to install a new one from this screen at all. The FAB is an
        // install action, so it opens a file picker for an apk.
        viewBinding.fab.setOnClickListener {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive"))
        }
    }

    private val apkPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Throwable) {
                // Providers are allowed to refuse; the read grant still holds for this
                // process, which is all the install needs.
                Log.d(TAG, "persistable grant refused: ${e.message}")
            }
            installModule(uri.toString())
        }


    override fun onStart() {
        super.onStart()
        observeLiveData()
    }

    override fun onStop() {
        super.onStop()
        viewModel.appsLiveData.value = null
        viewModel.appsLiveData.removeObservers(this)
        viewModel.resultLiveData.value = null
        viewModel.resultLiveData.removeObservers(this)
    }


    private fun unInstallModule(packageName: String) {
        MaterialDialog(this).show {
            title(R.string.uninstall_module)
            message(R.string.uninstall_module_hint)
            positiveButton(R.string.done) {
                showLoading()
                viewModel.unInstallModule(packageName)
            }
            negativeButton(R.string.cancel)
        }
    }


    private fun installModule(source: String) {
        showLoading()
        viewModel.installModule(source)
    }





    companion object {
        private const val TAG = "XpActivity"

        fun start(context: Context) {
            val intent = Intent(context, XpActivity::class.java)
            context.startActivity(intent)
        }
    }

}
