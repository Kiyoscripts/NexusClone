package com.kiyoscripts.nexusclone.util

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

/**
 *
 * @Description: viewBinding 扩展类
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 21:23
 */

inline fun <reified T : ViewBinding> Activity.inflate(): Lazy<T> = lazy {
    inflateBinding(layoutInflater)
}

/**
 * Lazy view binding for a [Fragment].
 *
 * Only safe for a Fragment whose view is never destroyed and recreated while the Fragment
 * instance lives. A Fragment hosted by a ViewPager2, or one that survives a configuration
 * change, gets a second `onCreateView` and this delegate would then hand back the previous,
 * already detached view hierarchy. Use the nullable backing-field pattern instead:
 *
 * ```
 * private var _binding: FooBinding? = null
 * private val binding get() = _binding!!
 * ```
 *
 * assigned in `onCreateView` and cleared in `onDestroyView`.
 */
inline fun <reified T : ViewBinding> Fragment.inflate(): Lazy<T> = lazy {
    inflateBinding(layoutInflater)
}

inline fun <reified T : ViewBinding> Dialog.inflate(): Lazy<T> = lazy {
    inflateBinding(layoutInflater)
}


inline fun <reified T : ViewBinding> inflateBinding(layoutInflater: LayoutInflater): T {
    val method = T::class.java.getMethod("inflate", LayoutInflater::class.java)
    return method.invoke(null, layoutInflater) as T
}

inline fun <reified T : ViewBinding> newBindingViewHolder(viewGroup: ViewGroup, attachToParent:Boolean = false): T {
    val method = T::class.java.getMethod("inflate",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Boolean::class.java)
    return method.invoke(null,LayoutInflater.from(viewGroup.context),viewGroup,attachToParent) as T
}
