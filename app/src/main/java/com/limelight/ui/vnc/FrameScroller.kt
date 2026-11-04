/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import com.limelight.viewmodel.VncViewModel

/**
 * Implements fling animation for the frame.
 */
class FrameScroller(val viewModel: VncViewModel) {

    private val fs = viewModel.frameState
    private val xAnimator = FlingAnimation(FloatValueHolder())
    private val yAnimator = FlingAnimation(FloatValueHolder())

    init {
        xAnimator.addUpdateListener { _, x, _ ->
            viewModel.moveFrameTo(x, fs.frameY)
        }

        yAnimator.addUpdateListener { _, y, _ ->
            viewModel.moveFrameTo(fs.frameX, y)
        }
    }

    /**
     * Stop current animation
     */
    fun stop() {
        xAnimator.cancel()
        yAnimator.cancel()
    }

    /**
     * Starts fling animation according to given velocities
     */
    fun fling(vx: Float, vy: Float) {
        stop()

        val x = fs.frameX
        val y = fs.frameY
        val safe = fs.safeArea

        /**
         * Fling limits.
         *
         * There are two cases:
         *
         * 1) x >= safeLeft : It means frame is completely visible and centered horizontally.
         *                    In this case both minX,maxX = x (ie. no movement possible).

         * 2) x < safeLeft  : Here, 'scaled frame width' > 'safe width'. In this case
         *                    minX is negative and maxX = safeLeft.
         *
         * minY,maxY are calculated similarly.
         */
        val minX = if (x >= safe.left) x else (safe.width()) - (fs.fbWidth * fs.scale)
        val maxX = if (x >= safe.left) x else safe.left
        val minY = if (y >= safe.top) y else (safe.height()) - (fs.fbHeight * fs.scale)
        val maxY = if (y >= safe.top) y else safe.top

        flingAxis(xAnimator, x, vx, minX, maxX)
        flingAxis(yAnimator, y, vy, minY, maxY)
    }

    /**
     * Starts one axis of the fling, if there is anything to fling over.
     *
     * In the two cases described above, the range is either a single point (frame already
     * fits, so the axis cannot move) or, before the framebuffer size is known, inverted
     * (`safe.width() - 0` > `safe.left`). DynamicAnimation rejects a range whose lower
     * bound is not strictly below its upper bound, and a NaN bound is rejected as well -
     * both surface as an exception from a touch callback, which takes the viewer down.
     *
     * Skipping the animation is not a change in behaviour: with such a range the frame
     * either is already where it belongs, or gets re-centered by the next layout pass.
     */
    private fun flingAxis(animator: FlingAnimation, start: Float, velocity: Float, min: Float, max: Float) {
        if (!start.isFinite() || !velocity.isFinite() || !min.isFinite() || !max.isFinite())
            return
        if (min >= max)
            return

        animator.apply {
            setStartValue(start)
            setStartVelocity(velocity)
            setMinValue(min)
            setMaxValue(max)
            start()
        }
    }
}