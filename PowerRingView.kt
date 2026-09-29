package com.cat.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs

/**
 * VPN-UI style power ring — the primary connect surface of the redesigned home
 * screen (NordVPN/ZenMate genre), replacing the wide map panel as the focal
 * point. The existing [ConnectionGlobeView] stays in the codebase for users who
 * prefer the map; this ring is the compact, focused alternative:
 *
 *  - a large circular button: tap anywhere on the ring to connect/disconnect
 *  - conic gradient arc spins while [VpnState.Starting]/[VpnState.Stopping]
 *  - soft breathing glow when [VpnState.Started]
 *  - state accent: teal (connected), amber (switching), red (error), neutral
 *
 * Draws the destination flag + a one-line status inside the ring so no separate
 * label row is required above it.
 */
class PowerRingView(context: Context) : View(context) {

    private val palette = CatClientDesignTokens.forContext(context)

    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val ringArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = CatClientBodyBoldTypeface
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = CatClientBodyTypeface
    }

    private val arcRect = RectF()

    private var state: VpnState = VpnState.Stopped
    private var flag = ""
    private var statusLine = ""
    private var phase = 0f
    private var lastFrameAt = 0L
    private var frameRunning = false

    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrameAt == 0L) 0.016f
            else ((now - lastFrameAt) / 1_000_000_000f).coerceIn(0.001f, 0.08f)
            lastFrameAt = now
            // ring spins only while switching; glow breathes while connected
            phase = when {
                state == VpnState.Starting || state == VpnState.Stopping -> (phase + dt * 0.9f) % 1f
                state == VpnState.Started -> (phase + dt * 0.12f) % 1f
                else -> phase
            }
            invalidate()
            if (frameRunning) postOnAnimation(this)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frameRunning = true
        lastFrameAt = 0L
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        frameRunning = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    fun setVpnState(newState: VpnState) {
        if (state == newState) return
        state = newState
        invalidate()
    }

    /** flag emoji + one-line status drawn inside the ring (e.g. 🇩🇪 · 42 ms). */
    fun setDestination(flagEmoji: String, status: String) {
        flag = flagEmoji.orEmpty()
        statusLine = status.orEmpty()
        invalidate()
    }

    private fun accent(): Int = when (state) {
        VpnState.Started -> palette.teal
        VpnState.Starting, VpnState.Stopping -> palette.amber
        is VpnState.Error, VpnState.DailyLimitReached -> palette.red
        VpnState.Stopped -> palette.neutral
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val ringRadius = minOf(w, h) / 2f - dp(20f)
        val ringWidth = dp(7f)
        val accent = accent()
        val connected = state == VpnState.Started
        val busy = state == VpnState.Starting || state == VpnState.Stopping

        // soft glow behind the ring
        glowPaint.shader = RadialGradient(
            cx, cy, ringRadius + ringWidth * 3f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(accent, if (connected) 46 else 20), Color.TRANSPARENT),
            floatArrayOf(0f, 0.62f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, ringRadius + ringWidth * 3f, glowPaint)
        glowPaint.shader = null

        // inner disc
        fillPaint.shader = RadialGradient(
            cx, cy - ringRadius * 0.15f, ringRadius,
            intArrayOf(
                withAlpha(palette.surfaceElevated1, if (palette.isDark) 245 else 255),
                withAlpha(palette.surface, if (palette.isDark) 215 else 240),
            ),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, ringRadius - ringWidth / 2f - dp(2f), fillPaint)
        fillPaint.shader = null

        // track
        arcRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        ringTrack.strokeWidth = ringWidth
        ringTrack.color = withAlpha(palette.textPrimary, if (palette.isDark) 28 else 38)
        canvas.drawCircle(cx, cy, ringRadius, ringTrack)

        // arc: full circle when connected (breathing), spinning sweep when busy,
        // short static tick when idle
        ringArc.strokeWidth = ringWidth
        if (busy) {
            val start = phase * 360f
            ringArc.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, withAlpha(accent, 200), accent, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 0.8f, 1f),
            ).also { shader ->
                val m = android.graphics.Matrix()
                m.postRotate(start - 90f, cx, cy)
                shader.setLocalMatrix(m)
            }
            canvas.drawArc(arcRect, 0f, 360f, false, ringArc)
            ringArc.shader = null
        } else {
            val sweep = when {
                connected -> 330f
                state is VpnState.Error || state == VpnState.DailyLimitReached -> 120f
                else -> 70f
            }
            val start = when {
                connected -> -90f + phase * 360f
                else -> -90f - sweep / 2f
            }
            ringArc.color = accent
            canvas.drawArc(arcRect, start, sweep, false, ringArc)
        }

        // inner content: flag + status
        labelPaint.textSize = dp(34f)
        if (flag.isNotEmpty()) {
            canvas.drawText(flag, cx, cy + dp(6f), labelPaint)
        }
        labelPaint.textSize = dp(14.5f)
        labelPaint.color = palette.textPrimary
        val title = when (state) {
            VpnState.Started -> statusLine.ifBlank { context.getString(R.string.globe_route_active) }
            VpnState.Starting -> context.getString(R.string.globe_route_connecting)
            VpnState.Stopping -> context.getString(R.string.globe_route_closing)
            is VpnState.Error -> context.getString(R.string.globe_route_unavailable)
            VpnState.DailyLimitReached -> context.getString(R.string.globe_route_limit)
            VpnState.Stopped -> context.getString(R.string.globe_route_idle)
        }
        val titleY = if (flag.isNotEmpty()) cy + dp(30f) else cy + dp(4f)
        canvas.drawText(title, cx, titleY, labelPaint)
        if (connected && flag.isEmpty()) {
            subPaint.textSize = dp(11f)
            subPaint.color = palette.textSecondary
            canvas.drawText(statusLine, cx, titleY + dp(16f), subPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
