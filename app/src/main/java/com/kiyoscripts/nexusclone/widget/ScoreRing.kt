package com.kiyoscripts.nexusclone.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.kiyoscripts.nexusclone.R

/**
 * Circular progress ring for the diagnostics summary.
 *
 * Drawn rather than composed from a ProgressBar because the platform's circular
 * indeterminate drawable cannot show a determinate arc with a rounded cap and a hairline
 * track at an arbitrary stroke width. Two arcs, one Paint each, no allocation in onDraw.
 *
 * The sweep is animated by the caller through [setProgress] with a ValueAnimator; this
 * view only knows how to draw the value it is given.
 */
class ScoreRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.hairline)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_strong)
    }

    private val arcBounds = RectF()

    /** 0f..1f. */
    private var fraction = 0f

    /** Ring colour switches to warn/danger as the score drops. */
    private var tintColor: Int = ContextCompat.getColor(context, R.color.accent_strong)

    init {
        val density = resources.displayMetrics.density
        val stroke = 7f * density
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
    }

    /**
     * @param value 0f..1f, clamped. Values outside the range are a caller bug, not a
     *   reason to draw outside the ring.
     */
    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == fraction) return
        fraction = clamped
        invalidate()
    }

    fun setRingColor(color: Int) {
        if (color == tintColor) return
        tintColor = color
        fillPaint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = fillPaint.strokeWidth / 2f
        arcBounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (arcBounds.isEmpty) return

        // Full track, then the filled sweep on top. Starting at -90 puts the origin at
        // twelve o'clock, which is what every progress ring the user has ever seen does.
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        if (fraction > 0f) {
            canvas.drawArc(arcBounds, -90f, 360f * fraction, false, fillPaint)
        }
    }
}
