package com.kiyoscripts.nexusclone.core.floating

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A single small overlay window that shows which clone is running.
 *
 * The view is built in code on purpose: an overlay inflated from XML would need a themed
 * context and would drag layout resources into a controller that is also used from a
 * service. Everything here is best effort - a missing overlay permission or a dead
 * WindowManager must never crash the caller, so every window call is guarded.
 */
class FloatingWindowController(private val context: Context) {

    companion object {
        private const val TAG = "FloatingWindow"

        private const val MARGIN_X = 16
        private const val MARGIN_Y = 120
    }

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** True when the app is allowed to draw over other apps. */
    fun canDraw(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            Log.w(TAG, "canDrawOverlays failed: ${e.message}")
            false
        }
    } else {
        true
    }

    /** Intent that opens the system overlay permission screen for this package. */
    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    /**
     * Show the overlay. Returns false when the permission is missing or the window could
     * not be added; an existing overlay is replaced.
     */
    fun show(instanceLabel: String, onClose: () -> Unit): Boolean {
        if (!canDraw()) {
            Log.w(TAG, "overlay permission missing, not showing")
            return false
        }
        val wm = windowManager ?: run {
            Log.w(TAG, "no WindowManager")
            return false
        }
        hide()
        return try {
            val root = buildView(instanceLabel, onClose)
            val lp = buildParams()
            wm.addView(root, lp)
            view = root
            params = lp
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}", e)
            view = null
            params = null
            false
        }
    }

    /** Remove the overlay if it is attached. Safe to call when nothing is showing. */
    fun hide() {
        val current = view ?: return
        val wm = windowManager
        try {
            wm?.removeView(current)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed: ${e.message}")
        } finally {
            view = null
            params = null
        }
    }

    fun isShowing(): Boolean = view != null

    private fun buildView(instanceLabel: String, onClose: () -> Unit): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 12, 12)
            setBackgroundColor(Color.argb(220, 32, 32, 32))
        }

        val label = TextView(context).apply {
            text = instanceLabel
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
        }
        root.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 16 }
        )

        val close = Button(context).apply {
            text = "x"
            textSize = 13f
            setOnClickListener {
                try {
                    onClose()
                } catch (e: Exception) {
                    Log.w(TAG, "onClose threw: ${e.message}")
                }
                hide()
            }
        }
        root.addView(
            close,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = MARGIN_X
            y = MARGIN_Y
        }
    }
}
