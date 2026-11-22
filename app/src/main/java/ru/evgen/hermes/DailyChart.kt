package ru.evgen.hermes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Daily spend as bars: one per day of the selected period.
 *
 * Drawn rather than built from views because a 30-day period is thirty bars, and
 * thirty LinearLayouts with weights are both slower and harder to label. Heights are
 * relative to the busiest day in the period, which is the comparison that matters —
 * "Tuesday cost twice Monday" — not the absolute rouble scale.
 */
class DailyChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Bar(val day: String, val cost: Double)

    private var bars: List<Bar> = emptyList()
    private var peak = 0.0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.divider) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }

    fun submit(data: List<Pair<String, Double>>) {
        bars = data.map { Bar(it.first, it.second) }
        peak = bars.maxOfOrNull { it.cost } ?: 0.0
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize((96 * resources.displayMetrics.density).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val labelSpace = 14f * resources.displayMetrics.density
        val plotHeight = height - labelSpace
        val slot = width.toFloat() / bars.size
        val barWidth = (slot * 0.62f).coerceAtLeast(1.5f * resources.displayMetrics.density)
        val radius = barWidth / 2f
        val bottom = plotHeight

        for ((index, bar) in bars.withIndex()) {
            val cx = slot * index + slot / 2f
            // Float throughout: the canvas works in pixels, and mixing Float and
            // Double here is what Kotlin refuses (and what a silent cast would hide).
            val ratio = if (peak <= 0.0) 0f else (bar.cost / peak).coerceIn(0.0, 1.0).toFloat()
            // A day with spend always shows something: a zero-height bar reads as a
            // drawing bug, not as "nothing happened".
            val h = if (bar.cost > 0) {
                (plotHeight * ratio).coerceAtLeast(2f * resources.displayMetrics.density)
            } else 0f
            val paint = if (bar.cost > 0) barPaint else emptyPaint
            if (h > 0f) {
                val rect = RectF(cx - barWidth / 2f, bottom - h, cx + barWidth / 2f, bottom)
                canvas.drawRoundRect(rect, radius, radius, paint)
            } else {
                canvas.drawCircle(cx, bottom - 1f * resources.displayMetrics.density, barWidth / 5f, emptyPaint)
            }
        }

        // Label only the edges: with 30 bars every caption would collide.
        val first = bars.first().day.takeLast(5)
        val last = bars.last().day.takeLast(5)
        canvas.drawText(first, 0f, height - 2f * resources.displayMetrics.density, labelPaint)
        val lastWidth = labelPaint.measureText(last)
        canvas.drawText(last, width - lastWidth, height - 2f * resources.displayMetrics.density, labelPaint)
        if (bars.size == 1) {
            canvas.drawCircle(width / 2f, bottom - 1f, 1f, todayPaint)
        }
    }
}
