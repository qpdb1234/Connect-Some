/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.input

import android.graphics.PointF
import android.os.SystemClock
import com.limelight.util.debugCheckNotNull
import com.limelight.viewmodel.VncViewModel
import com.limelight.vnc.PointerButton
import kotlin.math.abs

/**
 * Most actions have the same implementation in both modes, only difference being
 * the point where event is sent. [transformPoint] is used for this mode-specific
 * point selection.
 */
abstract class BasePointerMode(val viewModel: VncViewModel) {

    companion object {
        /** Spacing between the two clicks produced by [doDoubleClick]. */
        private const val DOUBLE_CLICK_GAP = 60L

        /** Only clicks near a previous one, arriving within this time, get snapped onto it. */
        private const val DOUBLE_CLICK_SNAP_WINDOW = 450L

        /** Snap distance, in framebuffer pixels - about half a Windows double-click slop. */
        private const val DOUBLE_CLICK_SNAP_RADIUS = 8f

        /**
         * Upper bound on how many scroll clicks one gesture update may produce. A two-finger
         * fling legitimately builds up a few notches; anything past this is a broken delta,
         * and each notch is a full press+release pair on the wire.
         */
        private const val MAX_SCROLL_STEPS_PER_EVENT = 20
    }

    //Used for remote scrolling
    private var accumulatedDx = 0F
    private var accumulatedDy = 0F
    private val deltaPerScroll = 20F //For how much dx/dy, one scroll event will be sent
    private val yScrollDirection = (if (viewModel.pref.input.gesture.invertVerticalScrolling) -1 else 1)

    abstract fun transformPoint(p: PointF): PointF?
    abstract fun doMovePointer(p: PointF, dx: Float, dy: Float)
    abstract fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float)

    open fun onGestureStart() {}
    open fun onGestureStop(p: PointF) = doButtonRelease(p)

    fun doButtonDown(button: PointerButton, p: PointF) {
        transformPoint(p)?.let { viewModel.messenger?.sendPointerButtonDown(button, it) }
    }

    fun doButtonUp(button: PointerButton, p: PointF) {
        transformPoint(p)?.let { viewModel.messenger?.sendPointerButtonUp(button, it) }
    }

    fun doButtonRelease(p: PointF) {
        transformPoint(p)?.let { viewModel.messenger?.sendPointerButtonRelease(it) }
    }

    open fun doClick(button: PointerButton, p: PointF) {
        val point = if (button == PointerButton.Left) snapLeftClick(p) else p
        doButtonDown(button, point)
        // Some apps (mostly games) seems to ignore click event if button-up is received too early
        if ((button == PointerButton.Left || button == PointerButton.Middle || button == PointerButton.Right)
            && viewModel.profile.fButtonUpDelay)
            viewModel.messenger?.insertButtonUpDelay()
        doButtonUp(button, point)
    }

    fun doDoubleClick(button: PointerButton, p: PointF) {
        doClick(button, p)
        // Press+release pairs sent within the same millisecond can be merged into a
        // single click by the host, so space them out like a real mouse would. The
        // gap stays well inside the double-click time limit on the host.
        viewModel.messenger?.insertDelay(DOUBLE_CLICK_GAP)
        doClick(button, p)
    }

    /**
     * Last left click, in framebuffer coordinates, used as anchor for [snapLeftClick].
     */
    private var lastClickPoint: PointF? = null
    private var lastClickTime = 0L

    /**
     * The host only reports a double-click when both clicks stay within a few pixels
     * of each other (Windows: roughly +-4 desktop pixels) and within its double-click
     * time. Two finger taps never land on the same spot, and scaling the touch point
     * up to the remote resolution turns a small wobble into many desktop pixels - the
     * host then sees two single clicks and merely selects the item. So a left click
     * that follows an earlier one closely in time and stays near it is moved onto the
     * previous point, which makes the pair count as a double-click reliably.
     *
     * Only the position is adjusted - no extra click is synthesized, and clicks that
     * come from a real mouse (which bypass this method) are left untouched.
     */
    private fun snapLeftClick(p: PointF): PointF {
        val fb = transformPoint(p) ?: return p
        val anchor = lastClickPoint
        val now = SystemClock.uptimeMillis()

        if (anchor != null && now - lastClickTime <= DOUBLE_CLICK_SNAP_WINDOW
                && abs(fb.x - anchor.x) <= DOUBLE_CLICK_SNAP_RADIUS
                && abs(fb.y - anchor.y) <= DOUBLE_CLICK_SNAP_RADIUS) {
            lastClickTime = now
            return viewModel.frameState.toVP(anchor)
        }

        lastClickPoint = PointF(fb.x, fb.y)
        lastClickTime = now
        return p
    }

    fun doRemoteScroll(focus: PointF, dx: Float, dy: Float) {
        // Both drains below loop until the accumulator falls back under one notch. A NaN gets
        // there never (`abs(NaN) >= 20` is false, so the loop is skipped and the leftover
        // poison is kept forever), while an infinite one never gets there either - an endless
        // stream of click events on the wire. Neither is worth a guess: drop the residue.
        if (!dx.isFinite() || !dy.isFinite()) {
            accumulatedDx = 0F
            accumulatedDy = 0F
            return
        }

        val limit = deltaPerScroll * MAX_SCROLL_STEPS_PER_EVENT
        accumulatedDx = (accumulatedDx + dx).coerceIn(-limit, limit)
        accumulatedDy = (accumulatedDy + dy * yScrollDirection).coerceIn(-limit, limit)

        //Drain horizontal change
        while (abs(accumulatedDx) >= deltaPerScroll) {
            if (accumulatedDx > 0) {
                doClick(PointerButton.WheelLeft, focus)
                accumulatedDx -= deltaPerScroll
            } else {
                doClick(PointerButton.WheelRight, focus)
                accumulatedDx += deltaPerScroll
            }
        }

        //Drain vertical change
        while (abs(accumulatedDy) >= deltaPerScroll) {
            if (accumulatedDy > 0) {
                doClick(PointerButton.WheelUp, focus)
                accumulatedDy -= deltaPerScroll
            } else {
                doClick(PointerButton.WheelDown, focus)
                accumulatedDy += deltaPerScroll
            }
        }
    }

    /**
     * [hs] Movement of horizontal scroll wheel
     * [vs] Movement of vertical scroll wheel
     */
    fun doRemoteScrollFromMouse(p: PointF, hs: Float, vs: Float) {
        // hs is -ve for for left and +ve for right. But doRemoteScroll() works
        // in terms of finger movement where -ve is right and +ve is left
        // So we have to invert the sign of hs for doRemoteScroll()
        doRemoteScroll(p, -1 * hs * deltaPerScroll, vs * deltaPerScroll)
    }
}

/**
 * Actions happen at touch-point, which is simply transformed from
 * viewport coordinates into corresponding position in framebuffer.
 */
class DirectPointerMode(viewModel: VncViewModel) : BasePointerMode(viewModel) {
    override fun transformPoint(p: PointF) = viewModel.frameState.toFb(p)
    override fun doMovePointer(p: PointF, dx: Float, dy: Float) = doButtonDown(PointerButton.None, p)
    override fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float) = doButtonDown(button, p)
    override fun doClick(button: PointerButton, p: PointF) {
        if (transformPoint(p) != null)
            super.doClick(button, p)
        else if (button == PointerButton.Left)
            coerceToFbEdge(p)?.let { doMovePointer(it, 0f, 0f) }
    }

    // When user taps outside the frame, move the pointer to edge of the frame
    // It allows opening of taskbar/panels when they are set to auto-hide.
    // It can also be used for previewing taskbar items.
    private fun coerceToFbEdge(p: PointF): PointF? {
        val fs = viewModel.frameState
        if (fs.fbWidth < 1 || fs.fbHeight < 1)
            return null

        return fs.toVP(
                fs.toFbUnchecked(p).apply {
                    x = x.coerceIn(0f, fs.fbWidth - 1)
                    y = y.coerceIn(0f, fs.fbHeight - 1)
                }
        )
    }
}

/**
 * Actions happen at [pointerPosition], which is updated by [doMovePointer].
 */
class RelativePointerMode(viewModel: VncViewModel, private val accelerator: PointerAcceleration) : BasePointerMode(viewModel) {
    private val pointerPosition = PointF(0f, 0f)

    override fun onGestureStart() {
        super.onGestureStart()
        debugCheckNotNull(viewModel.client)

        // Initialize with the latest pointer position - but only if there is one.
        //
        // Right after a reconnect the fresh client has never been told where the remote
        // pointer sits, so its coordinates still read (0, 0): seeding from them puts the
        // cursor in the top-left corner of the desktop and the centering at the end of the
        // gesture drags the picture there with it. [pointerPosition] survives a reconnect
        // (this mode belongs to the activity, not to a session), so keeping the old value is
        // both possible and the position the user last saw.
        viewModel.client?.let {
            if (it.pointerPositionKnown) {
                pointerPosition.x = it.pointerX.toFloat()
                pointerPosition.y = it.pointerY.toFloat()
            }
            it.ignorePointerMovesByServer = true
        }
    }

    override fun onGestureStop(p: PointF) {
        super.onGestureStop(p)
        viewModel.client?.ignorePointerMovesByServer = false
    }

    override fun transformPoint(p: PointF) = pointerPosition

    override fun doMovePointer(p: PointF, dx: Float, dy: Float) = doMovePointer(dx, dy, true)

    fun doMovePointer(dx: Float, dy: Float, accelerate: Boolean) {
        val xLimit = viewModel.frameState.fbWidth - 1
        val yLimit = viewModel.frameState.fbHeight - 1
        if (xLimit < 0 || yLimit < 0)
            return

        // `offset()` on a NaN moves the pointer onto a coordinate that no `coerceIn` can
        // bring back, and every later event is then computed from that poisoned position.
        if (!dx.isFinite() || !dy.isFinite())
            return

        var adx = dx
        var ady = dy
        if (accelerate) {
            accelerator.compute()
            adx = accelerator.updateDx(dx)
            ady = accelerator.updateDy(dy)
        }

        pointerPosition.apply {
            offset(adx, ady)
            x = x.coerceIn(0f, xLimit)
            y = y.coerceIn(0f, yLimit)
        }
        doButtonDown(PointerButton.None, pointerPosition)

        //Try to keep the pointer centered on screen
        val vp = viewModel.frameState.toVP(pointerPosition)
        val centerDiffX = viewModel.frameState.safeArea.centerX() - vp.x
        val centerDiffY = viewModel.frameState.safeArea.centerY() - vp.y
        viewModel.panFrame(centerDiffX, centerDiffY)
    }

    override fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float) {
        doButtonDown(button, p)
        doMovePointer(p, dx, dy)
    }
}