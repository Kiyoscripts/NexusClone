package com.kiyoscripts.nexusclone.core.preview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures a thumbnail of a guest window.
 *
 * Two paths:
 *  - API 26+: [PixelCopy] against the window's surface, which is the accurate one.
 *  - below 26: draw the decor view into a software canvas. Loses surface-backed content
 *    (video, GL surfaces) but is better than nothing.
 *
 * Output is always a 180x320 WebP, aspect preserved with letterboxing, because the grid
 * cell is fixed size and mixed aspect ratios look broken otherwise.
 */
object PreviewCapture {

    private const val TAG = "PreviewCapture"
    const val THUMB_W = 180
    const val THUMB_H = 320
    private const val QUALITY = 82

    private val main = Handler(Looper.getMainLooper())

    /**
     * Capture [window] into [outFile].
     *
     * Must be called on the main thread. [onDone] is invoked on the main thread with true
     * when the file was written.
     */
    fun capture(
        window: Window,
        outFile: File,
        onDone: (Boolean) -> Unit = {}
    ) {
        val decor = window.peekDecorView()
        if (decor == null) {
            Log.w(TAG, "no decor view, cannot capture")
            onDone(false)
            return
        }
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "decor has zero size ${w}x$h")
            onDone(false)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            capturePixelCopy(window, decor, w, h, outFile, onDone)
        } else {
            captureSoftware(decor, outFile, onDone)
        }
    }

    private fun capturePixelCopy(
        window: Window,
        decor: View,
        w: Int,
        h: Int,
        outFile: File,
        onDone: (Boolean) -> Unit
    ) {
        val raw = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM allocating ${w}x$h capture bitmap")
            onDone(false)
            return
        }

        val settled = AtomicBoolean(false)
        try {
            PixelCopy.request(
                window,
                raw,
                { result ->
                    if (!settled.compareAndSet(false, true)) return@request
                    if (result == PixelCopy.SUCCESS) {
                        val ok = writeThumb(raw, outFile)
                        raw.recycle()
                        onDone(ok)
                    } else {
                        Log.w(TAG, "PixelCopy failed code=$result")
                        raw.recycle()
                        onDone(false)
                    }
                },
                main
            )
        } catch (e: IllegalArgumentException) {
            // Window has no valid surface yet (common right after pause).
            Log.w(TAG, "PixelCopy rejected window: ${e.message}")
            raw.recycle()
            onDone(false)
        } catch (e: Exception) {
            Log.w(TAG, "PixelCopy threw: ${e.message}")
            raw.recycle()
            onDone(false)
        }
    }

    private fun captureSoftware(decor: View, outFile: File, onDone: (Boolean) -> Unit) {
        try {
            val raw = Bitmap.createBitmap(
                decor.width, decor.height, Bitmap.Config.ARGB_8888
            )
            decor.draw(Canvas(raw))
            val ok = writeThumb(raw, outFile)
            raw.recycle()
            onDone(ok)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in software capture")
            onDone(false)
        } catch (e: Exception) {
            Log.w(TAG, "software capture failed: ${e.message}")
            onDone(false)
        }
    }

    /** Scale to the fixed thumb box with letterboxing, then write WebP. */
    private fun writeThumb(src: Bitmap, outFile: File): Boolean {
        var scaled: Bitmap? = null
        var canvasBmp: Bitmap? = null
        return try {
            val scale = minOf(
                THUMB_W.toFloat() / src.width,
                THUMB_H.toFloat() / src.height
            )
            val dw = (src.width * scale).toInt().coerceAtLeast(1)
            val dh = (src.height * scale).toInt().coerceAtLeast(1)
            scaled = Bitmap.createScaledBitmap(src, dw, dh, true)

            canvasBmp = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBmp)
            canvas.drawColor(0xFF1A1A1A.toInt())
            canvas.drawBitmap(
                scaled,
                ((THUMB_W - dw) / 2f),
                ((THUMB_H - dh) / 2f),
                null
            )

            outFile.parentFile?.mkdirs()
            val tmp = File(outFile.parentFile, outFile.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                canvasBmp.compress(Bitmap.CompressFormat.WEBP, QUALITY, fos)
                fos.flush()
            }
            if (outFile.exists()) outFile.delete()
            val ok = tmp.renameTo(outFile)
            if (!ok) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM writing thumb")
            false
        } catch (e: Exception) {
            Log.w(TAG, "writeThumb failed: ${e.message}")
            false
        } finally {
            scaled?.recycle()
            canvasBmp?.recycle()
        }
    }

    /** Capture from an [Activity] directly. */
    fun captureActivity(activity: Activity, outFile: File, onDone: (Boolean) -> Unit = {}) {
        capture(activity.window, outFile, onDone)
    }
}
