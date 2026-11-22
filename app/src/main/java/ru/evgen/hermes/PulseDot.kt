package ru.evgen.hermes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The "this session is working right now" marker: a dot with a ring breathing
 * outwards from it.
 *
 * A still dot would be indistinguishable from the unread dot, and a spinner would
 * read as "loading the list". A pulse says activity, and only rows that are really
 * running animate — RecyclerView recycles views, so the animation is tied to
 * attach/detach instead of being started in `bind` and left running on a row that
 * scrolled off screen.
 */
class PulseDot @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
    }

    private val core = 3.5f * resources.displayMetrics.density
    private val ring = 9f * resources.displayMetrics.density
    /** 0 → ring at the core, 1 → ring fully expanded and faded. */
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // The pulse needs room for the ring, not just for the dot.
        minimumWidth = (ring * 2).toInt()
        minimumHeight = (ring * 2).toInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        if (animator.isStarted) animator.cancel()
        phase = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(minimumWidth, widthMeasureSpec),
            resolveSize(minimumHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * resources.displayMetrics.density
        paint.alpha = ((1f - phase) * 150).toInt()
        canvas.drawCircle(cx, cy, core + (ring - core) * phase, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawCircle(cx, cy, core, paint)
    }
}
