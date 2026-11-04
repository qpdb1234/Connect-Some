/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.limelight.util.AccentTheme
import com.limelight.util.AppPreferences
import com.limelight.viewmodel.VncViewModel
import kotlin.math.hypot

/**
 * The floating assist ball: a draggable shortcut on top of the stream for users whose
 * display, gesture navigation or reach makes the edge swipe that opens the toolbar
 * awkward.
 *
 * It is deliberately an overlay inside the viewer rather than a system-wide floating
 * window: both of its actions - opening the drawer and raising the keyboard - only mean
 * something on this screen, and staying inside the activity needs no
 * `SYSTEM_ALERT_WINDOW` grant from the user.
 *
 * All of its settings (on/off, opacity, size, tap action) live in the toolbar drawer and
 * are held in [VncViewModel]; this class only watches them and redraws the ball, so the
 * drawer stays the single place where the ball is configured. Position is stored as a
 * pair of biases instead of raw coordinates, which is what keeps the ball in the same
 * relative spot across rotation and window resizing.
 */
class AssistBall(private val activity: VncActivity) {

    private val viewModel = activity.viewModel
    private val ball get() = activity.binding.assistBall
    private val parent get() = activity.binding.frameContainer

    private val touchSlop by lazy { ViewConfiguration.get(activity).scaledTouchSlop }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragged = false

    /**
     * Whether the current gesture started here. A MOVE or UP that arrives without a DOWN
     * (the parent took the gesture first, or the ball appeared under a finger that was
     * already down) has no baseline: without this flag the stale defaults of the fields
     * above would be used, and the ball would jump to wherever the absolute screen
     * coordinates happen to place it.
     */
    private var tracking = false

    fun initialize() {
        viewModel.assistBallVisible.observe(activity) { visible ->
            ball.isVisible = visible == true

            // Appearing from 'gone' starts a layout pass, and only after it the ball has
            // real bounds to position itself in. Without this it would show up in the
            // top left corner, which is where a freshly measured child of a FrameLayout
            // starts.
            if (visible == true) ball.doOnNextLayout { positionFromBias() }
        }
        viewModel.assistBallOpacity.observe(activity) { it?.let { ball.alpha = it.coerceIn(0f, 1f) } }
        viewModel.assistBallSizeDp.observe(activity) { it?.let { applySize(it) } }
        viewModel.assistBallColor.observe(activity) { it?.let { applyColor(it) } }

        ball.alpha = (viewModel.assistBallOpacity.value ?: AppPreferences.ASSIST_BALL_OPACITY_DEFAULT).coerceIn(0f, 1f)
        viewModel.assistBallSizeDp.value?.let { applySize(it) }
        applyColor(viewModel.assistBallColor.value ?: AppPreferences.ASSIST_BALL_COLOR_DEFAULT)

        setupDrag()

        // Rotations, resizes and the first layout all arrive here. The ball keeps its
        // relative position, so re-clamp it whenever the area it lives in changes.
        parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) positionFromBias()
        }
    }

    /**
     * Touch is handled here instead of through a click listener because the ball is both
     * pressed and dragged: a click would fire at the end of every drag, and the gesture
     * has to be resolved before deciding what the user meant.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = ball.x
                    startY = ball.y
                    dragged = false
                    tracking = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    // Only leave "this is a tap" once the finger has clearly travelled,
                    // so that a shaky tap still opens the toolbar.
                    if (!dragged && hypot(dx, dy) > touchSlop) dragged = true

                    if (dragged) place(startX + dx, startY + dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking) {
                        if (dragged) saveBias() else performAction()
                    }
                    tracking = false
                    dragged = false
                }
                else -> {
                    // Pointer changes of an already claimed gesture (POINTER_DOWN/UP) are
                    // not the ball's business, but they must not end it either.
                }
            }

            // Always consumed: a tap or a drag on the ball must never reach the remote
            // desktop underneath it.
            true
        }
    }

    /**
     * The ball is a plain disc: what a tap does is decided here rather than shown on
     * the ball, which carries no glyph.
     */
    private fun performAction() {
        when (viewModel.assistBallAction.value) {
            VncViewModel.ASSIST_BALL_ACTION_KEYBOARD -> activity.showKeyboard()
            else -> activity.toolbar.open()
        }
    }

    private fun applySize(sizeDp: Int) {
        // The value comes back off disk, and the drawer's slider is the only thing meant to
        // write it. Clamped here as well as in the viewmodel, because a persisted diameter
        // outside that range would turn the ball into an overlay over the whole stream.
        val dp = VncViewModel.clampAssistBallSize(sizeDp)
        val px = (dp * activity.resources.displayMetrics.density).toInt()

        ball.layoutParams = ball.layoutParams.apply {
            width = px
            height = px
        }

        // A larger ball may now exceed the stored spot.
        place(ball.x, ball.y)
    }

    /**
     * The ball is a tinted disc: its fill defaults to black and the drawer's palette can
     * push any color into the background's tint, leaving the shape and ripple untouched.
     */
    private fun applyColor(hex: String) {
        ball.backgroundTintList = ColorStateList.valueOf(AccentTheme.argbOf(hex))
    }

    private fun positionFromBias() {
        val runInfo = viewModel.pref.runInfo
        place(runInfo.assistBallXBias.fractionOf(spanX()), runInfo.assistBallYBias.fractionOf(spanY()))
    }

    /**
     * Back to the spot a fresh install shows the ball in, from the drawer's restore button.
     *
     * Storing the default pair alone would not be enough: the drawer opens on top of a running
     * session, so the ball is usually on screen while the button is pressed, and a ball that only
     * moved on the next rotation would look like the button did nothing. A ball that is switched
     * off has no position to correct - it reads the restored pair whenever it is next shown.
     */
    fun restoreDefaultPosition() {
        val runInfo = viewModel.pref.runInfo
        runInfo.assistBallXBias = AppPreferences.ASSIST_BALL_X_BIAS_DEFAULT
        runInfo.assistBallYBias = AppPreferences.ASSIST_BALL_Y_BIAS_DEFAULT

        if (ball.isVisible) {
            if (ball.isLaidOut) positionFromBias()
            else ball.doOnNextLayout { positionFromBias() }
        }
    }

    private fun saveBias() {
        val runInfo = viewModel.pref.runInfo
        runInfo.assistBallXBias = bias(ball.x, spanX())
        runInfo.assistBallYBias = bias(ball.y, spanY())
    }

    /** Free travel for the ball inside [parent], in either direction. */
    private fun spanX() = parent.width - ball.width

    private fun spanY() = parent.height - ball.height

    private fun bias(value: Float, span: Int): Float =
            if (span <= 0 || !value.isFinite()) 0f else (value / span).coerceIn(0f, 1f)

    /**
     * A persisted bias, applied to a travel span. Restored settings can hold anything,
     * and a NaN would survive [coerceIn] untouched - leaving the ball off screen with
     * nothing left to drag it back by.
     */
    private fun Float.fractionOf(span: Int): Float =
            if (!isFinite()) 0f else (coerceIn(0f, 1f) * span).toFloat()

    private fun place(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        ball.x = x.coerceIn(0f, spanX().coerceAtLeast(0).toFloat())
        ball.y = y.coerceIn(0f, spanY().coerceAtLeast(0).toFloat())
    }
}
