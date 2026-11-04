/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import com.limelight.util.AccentTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A color palette: an HSV color wheel over a brightness slider.
 *
 * The wheel closes the hue loop that a square could not: angle is hue, radius is
 * saturation, so red sits at one angle rather than at both ends of a straightened
 * 0..360 axis, and neither black nor a duplicated hue claims any of its area.
 * Brightness is a full-width slider below; a dark setting is a thin strip rather
 * than half the UI.
 *
 * By default ([quantized]) the markers snap to the levels AccentTheme can actually render
 * (see gen_accent.xml), so what the preview chip shows is exactly the theme the app adopts.
 * Callers that apply the color at runtime - like the assist ball tint - can turn this off
 * to pick any continuous color, including white and grays that the accent grid cannot
 * represent.
 */
class ColorPickerView(context: Context, attrs: AttributeSet? = null) : android.view.View(context, attrs) {

    private var hue = 262f            // degrees
    private var sat = 1f              // 0..1 (snapped to AccentTheme.SAT_LEVELS when quantized)
    private var value = 1f            // 0..1 (snapped to AccentTheme.VAL_LEVELS when quantized)

    /**
     * Snap the markers to [AccentTheme.SAT_LEVELS] / [AccentTheme.VAL_LEVELS] so the chosen
     * color is guaranteed to match one of the pre-generated theme variants. Set this before
     * [setArgb]; flipping it on re-snaps the current selection.
     */
    var quantized = true
        set(v) {
            field = v
            if (v) snapToLevels()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheelRect = RectF()
    private val valRect = RectF()
    private val chipRect = RectF()

    // The wheel is pixel-computed per brightness and scaled up from a small bitmap; its colors
    // are smooth gradients, so the bilinear blur is invisible while dragging the slider stays
    // cheap. [wheelValue] is the brightness the current bitmap was built at, so it is only
    // recomputed when that changes.
    private var wheelBm: Bitmap? = null
    private var wheelValue = -1f

    var onColorChanged: ((Int) -> Unit)? = null

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    /** Positions the markers on [argb] (snapped to the grid when [quantized]). */
    fun setArgb(argb: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        if (quantized) snapToLevels()
        invalidate()
    }

    private fun snapToLevels() {
        sat = AccentTheme.SAT_LEVELS[AccentTheme.nearestLevel(sat, AccentTheme.SAT_LEVELS)]
        value = AccentTheme.VAL_LEVELS[AccentTheme.nearestLevel(value, AccentTheme.VAL_LEVELS)]
    }

    /**
     * The palette is a square, and it used to take whatever width it was handed - which in
     * landscape made the whole thing taller than the dialog and pushed the hex field and the
     * 'restore default' row out of sight below it. So the square is now capped by the height on
     * offer as well: by the bound the caller gave when there is one, and otherwise - inside a
     * scroll container, where the height bound is absent by design - by a share of the screen.
     *
     * The share has to be conservative, because the screen is not all ours: a dialog spends part
     * of its height on the title and the button row. 60% of the height still leaves the palette
     * the dominant element, and in landscape it leaves enough of a wheel to aim at while the rows
     * beside it stay on the same screen. Width remains the binding constraint wherever it is
     * tighter, which is every portrait dialog: those are measured exactly as they were before.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pad = dp(PADDING).toFloat()
        val extras = dp(VAL_BAR_H) + dp(CHIP_H) + 2 * dp(HUE_GAP) + 2 * pad

        val budget = if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED)
            MeasureSpec.getSize(heightMeasureSpec).toFloat()
        else resources.displayMetrics.heightPixels * 0.6f

        val side = min(MeasureSpec.getSize(widthMeasureSpec).toFloat() - 2 * pad, budget - extras)
                .coerceAtLeast(0f)
        setMeasuredDimension((side + 2 * pad).toInt(), (side + extras).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = dp(PADDING).toFloat()
        val wheelSize = w - 2 * pad
        wheelRect.set(pad, pad, pad + wheelSize, pad + wheelSize)
        valRect.set(pad, wheelRect.bottom + dp(HUE_GAP), pad + wheelSize, wheelRect.bottom + dp(HUE_GAP) + dp(VAL_BAR_H))
        chipRect.set(pad, valRect.bottom + dp(HUE_GAP), pad + dp(CHIP_H), valRect.bottom + dp(HUE_GAP) + dp(CHIP_H))
    }

    override fun onDraw(canvas: Canvas) {
        // The brightness bar's right end keeps the selection's saturation at full value, so a
        // low-saturation (near white) choice shows black -> white rather than black -> the fully
        // saturated hue. That keeps the bar honest about what the marker at its right end means.
        val brightFull = Color.HSVToColor(floatArrayOf(hue, sat, 1f))

        // The marker/chip code below flips paint.style between FILL and STROKE and leaves
        // paint.color on semi-transparent black; reset both here so the wheel and the
        // gradient fills below are drawn at full strength on every redraw and not
        // modulated by that leftover color (a shader or bitmap draw multiplies into the
        // paint color - a dim one washes everything out, a red-tinted one blacks out
        // every hue without red).
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE

        // Color wheel. It is rendered at the selection's actual brightness, so the disc is a
        // faithful preview: a dark choice reads dark and black reads black. The hues stay
        // reachable even at value 0 - raise the brightness slider to light the disc, or type a
        // code in the hex field below.
        if (wheelBm == null || wheelValue != value) {
            wheelBm?.recycle()
            wheelBm = Bitmap.createBitmap(WHEEL_PX, WHEEL_PX, Bitmap.Config.ARGB_8888).also { fillWheel(it, value) }
            wheelValue = value
        }
        canvas.drawBitmap(wheelBm!!, null, wheelRect, paint)
        // Rim so the wheel's edge reads as an edge against the white dialog.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33000000.toInt()
        canvas.drawCircle(wheelRect.centerX(), wheelRect.centerY(), wheelRect.width() / 2f, paint)

        // Brightness slider: black -> the selection's hue at full value, i.e. HSV(hue, sat, v)
        // across it, so a desaturated choice fades to white rather than to a vivid hue.
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = LinearGradient(valRect.left, 0f, valRect.right, 0f,
            Color.BLACK, brightFull, Shader.TileMode.CLAMP)
        canvas.drawRect(valRect, paint)
        paint.shader = null

        // Wheel marker. It sits at the exact HSV position of the selection - the same point
        // the finger lands on - so it tracks the touch instead of lagging inward. It may
        // overhang the rim at full saturation; that is preferable to a marker that never
        // lines up with where the user is pointing. hue is in degrees, so convert to radians
        // before feeding the trig functions (which take radians).
        val rad = wheelRect.width() / 2f
        val ang = Math.toRadians(hue.toDouble())
        val wx = wheelRect.centerX() + (rad * sat * cos(ang)).toFloat()
        val wy = wheelRect.centerY() - (rad * sat * sin(ang)).toFloat()
        paint.color = currentColor()
        canvas.drawCircle(wx, wy, dp(MARKER_R), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.WHITE
        canvas.drawCircle(wx, wy, dp(MARKER_R), paint)

        // Brightness marker.
        val vx = valRect.left + valRect.width() * value
        paint.color = Color.WHITE
        canvas.drawCircle(vx, valRect.centerY(), dp(VAL_BAR_H / 2f) - dp(2f), paint)
        paint.color = Color.HSVToColor(floatArrayOf(hue, sat, value))
        canvas.drawCircle(vx, valRect.centerY(), dp(VAL_BAR_H / 2f) - dp(4f), paint)

        // Preview chip with the exact color the app would adopt, outlined so that a
        // near-white selection does not vanish into the dialog.
        paint.style = Paint.Style.FILL
        paint.color = currentColor()
        canvas.drawRoundRect(chipRect, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33000000.toInt()
        canvas.drawRoundRect(chipRect, dp(4f), dp(4f), paint)
    }

    /**
     * Fills [bm] with HSV(angle, radius, [brightness]) per pixel, fading out over the rim.
     * [brightness] is the selection's real value, so the disc previews the true result.
     */
    private fun fillWheel(bm: Bitmap, brightness: Float) {
        val n = bm.width
        val px = IntArray(n * n)
        val c = n / 2f
        val feather = 1f / c // one bitmap pixel; keeps the magnified rim from aliasing
        val hsv = FloatArray(3)
        hsv[2] = brightness
        var i = 0
        for (y in 0 until n) {
            val dy = c - (y + 0.5f)
            for (x in 0 until n) {
                val dx = (x + 0.5f) - c
                val r = hypot(dx, dy) / c
                if (r <= 1f + feather) {
                    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (deg < 0) deg += 360f
                    hsv[0] = deg
                    hsv[1] = r.coerceAtMost(1f)
                    val a = (255 * (1f + feather - r) / feather).roundToInt().coerceIn(0, 255)
                    px[i] = Color.HSVToColor(hsv) and 0x00FFFFFF or (a shl 24)
                }
                i++
            }
        }
        bm.setPixels(px, 0, n, 0, 0, n, n)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                if (y <= wheelRect.bottom + dp(12f)) {
                    val dx = x - wheelRect.centerX()
                    val dy = wheelRect.centerY() - y
                    sat = (hypot(dx, dy) / (wheelRect.width() / 2f)).coerceIn(0f, 1f)
                    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (deg < 0) deg += 360f
                    hue = deg.roundToInt().coerceIn(0, 359).toFloat()
                    if (quantized) snapToLevels()
                } else if (y <= valRect.bottom + dp(12f)) {
                    value = (x - valRect.left).coerceIn(0f, valRect.width()) / valRect.width()
                    if (quantized) snapToLevels()
                } else {
                    return super.onTouchEvent(event)
                }
                onColorChanged?.invoke(currentColor())
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        private const val PADDING = 16f
        private const val HUE_GAP = 16f
        private const val VAL_BAR_H = 28f
        private const val CHIP_H = 28f
        private const val MARKER_R = 12f
        private const val WHEEL_PX = 256
    }
}
