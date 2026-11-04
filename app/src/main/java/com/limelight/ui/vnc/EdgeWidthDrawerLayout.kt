/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.limelight.util.AppPreferences
import kotlin.math.abs

/**
 * A [DrawerLayout] that lets the app widen or narrow the strip along the toolbar's edge from
 * which a horizontal swipe opens the drawer.
 *
 * DrawerLayout hands that decision to ViewDragHelper, whose edge size is private and has no
 * setter reachable without reflection - and reflection is deliberately not used here. Instead
 * this subclass keeps the framework's own handling wherever it already works, and adds a layer
 * of its own around it:
 *
 *  - Inside the framework's native edge strip the original view-drag handling is passed through
 *    untouched, so the panel still follows the finger exactly as it did before. That covers the
 *    default width, which is about the same as the framework's own.
 *  - Widening past it: a swipe that starts beyond the native strip but inside the configured
 *    width is caught here and, once it is clearly a horizontal pull outward, handed to
 *    [openDrawer]. The extra strip does not get the live follow (1.2.0 exposes no public way to
 *    move a drawer to a partial offset), but it does get the adjustable reach, which is what the
 *    trigger-width setting asks for.
 *  - Narrowing below the default: a touch that starts past the configured width never reaches the
 *    framework as an edge candidate, so the thinner edge is respected and the content underneath
 *    keeps the tap.
 *
 * Only opening is touched. Closing the drawer - by tapping the scrim, dragging the panel back, or
 * the swipe-to-close the toolbar installs - goes through the framework exactly as before, because
 * everything here bails out to `super` as soon as a drawer is visible.
 */
class EdgeWidthDrawerLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : DrawerLayout(context, attrs, defStyleAttr) {

    private companion object {
        const val NO_POINTER = -1

        // A pull counts as an intent to open only when it is clearly sideways: this is how much
        // more horizontal than vertical it has to be before the drawer is claimed.
        const val HORIZONTAL_DOMINANCE = 1.2f
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f

    /** Half-width of the strip the swipe may start in, in px, measured from the toolbar's edge. */
    private var edgeWidthPx = AppPreferences.DRAWER_EDGE_WIDTH_DEFAULT * density

    private enum class Gesture { NONE, CANDIDATE, FOLLOW, CUSTOM, SWALLOW }

    private var gesture = Gesture.NONE
    private var edgeDrawer: View? = null
    private var fromRightEdge = false
    private var downX = 0f
    private var downY = 0f
    private var downPointerId = NO_POINTER
    private var velocity: VelocityTracker? = null

    /**
     * Set the trigger strip, in dp. Out-of-range values (a hand-edited or imported preference)
     * are clamped to the range the settings offer, so a wild number can never make the whole
     * screen an edge or none of it one.
     */
    fun setEdgeWidthDp(dp: Int) {
        val clamped = dp.coerceIn(AppPreferences.DRAWER_EDGE_WIDTH_MIN, AppPreferences.DRAWER_EDGE_WIDTH_MAX)
        edgeWidthPx = clamped * density
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseVelocity()
                edgeDrawer = null
                gesture = Gesture.NONE

                val drawer = edgeDrawerFor(ev.getX(0))
                if (drawer != null) {
                    // A fresh, closed drawer whose edge this touch is inside: start watching.
                    edgeDrawer = drawer
                    fromRightEdge = isRightEdge(drawer)
                    downX = ev.getX(0)
                    downY = ev.getY(0)
                    downPointerId = ev.getPointerId(0)
                    gesture = Gesture.CANDIDATE

                    // Feed the framework its chance to follow within its native strip; if it
                    // takes the gesture it will report it on the moves that follow.
                    return super.onInterceptTouchEvent(ev)
                }

                if (!anyDrawerVisible()) {
                    // Closed drawer, touch past the configured strip: keep it away from the
                    // framework's edge so a narrowed trigger cannot be opened by accident, and
                    // let the content underneath have the touch.
                    gesture = Gesture.SWALLOW
                    return false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is nobody's edge swipe; step out of the way.
                if (gesture == Gesture.CANDIDATE || gesture == Gesture.SWALLOW) {
                    gesture = Gesture.NONE
                    edgeDrawer = null
                    releaseVelocity()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.CANDIDATE) {
                    if (super.onInterceptTouchEvent(ev)) {
                        gesture = Gesture.FOLLOW
                        return true
                    }
                    if (isOutwardDrag(ev)) {
                        gesture = Gesture.CUSTOM
                        initVelocity(ev)
                        return true
                    }
                    return false
                }
                if (gesture == Gesture.SWALLOW) return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val opening = gesture == Gesture.CUSTOM
                gesture = Gesture.NONE
                edgeDrawer = null
                releaseVelocity()
                if (opening) return false // settled in onTouchEvent's UP; nothing to intercept
            }
        }

        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (gesture) {
            Gesture.FOLLOW -> {
                val handled = super.onTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
                    endGesture()
                return handled
            }

            Gesture.CUSTOM -> {
                velocity?.addMovement(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    commitOpen(ev)
                    endGesture()
                }
                return true
            }

            Gesture.SWALLOW -> return false

            else -> return super.onTouchEvent(ev)
        }
    }

    /** Drop the per-gesture state once the sequence is over, wherever it ends. */
    private fun endGesture() {
        gesture = Gesture.NONE
        edgeDrawer = null
        releaseVelocity()
    }

    /**
     * The drawer, if any, that a touch at [x] should open by an edge swipe: one of the drawer
     * children that is fully closed, whose edge [x] falls inside the configured strip, and whose
     * lock mode lets the user open it (so a locked or not-yet-connected viewer stays shut).
     */
    private fun edgeDrawerFor(x: Float): View? {
        val w = width
        if (w == 0) return null
        val fromLeft = x <= edgeWidthPx
        val fromRight = x >= w - edgeWidthPx
        if (!fromLeft && !fromRight) return null

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (!isClosedAndUnlocked(child, lp)) continue
            val edge = absoluteHorizontalGravity(lp)
            if (edge == Gravity.LEFT && fromLeft) return child
            if (edge == Gravity.RIGHT && fromRight) return child
        }
        return null
    }

    private fun isClosedAndUnlocked(child: View, lp: LayoutParams): Boolean {
        if (lp.gravity == Gravity.NO_GRAVITY) return false
        if (isDrawerVisible(child)) return false
        val mode = getDrawerLockMode(child)
        return mode == LOCK_MODE_UNLOCKED || mode == LOCK_MODE_UNDEFINED
    }

    private fun absoluteHorizontalGravity(lp: LayoutParams): Int =
            GravityCompat.getAbsoluteGravity(lp.gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK

    private fun isRightEdge(child: View): Boolean {
        val lp = child.layoutParams as? LayoutParams ?: return false
        return absoluteHorizontalGravity(lp) == Gravity.RIGHT
    }

    private fun anyDrawerVisible(): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            // Only real drawers may be queried here. The content view (frame_container) carries no
            // drawer gravity, and DrawerLayout.isDrawerVisible() throws IllegalArgumentException for
            // anything that is not a drawer - so skip it the same way isClosedAndUnlocked does.
            if (lp.gravity == Gravity.NO_GRAVITY) continue
            if (isDrawerVisible(child)) return true
        }
        return false
    }

    /** A pull far enough sideways, and clearly more sideways than up/down, to mean "open". */
    private fun isOutwardDrag(ev: MotionEvent): Boolean {
        val idx = ev.findPointerIndex(downPointerId)
        if (idx < 0) return false
        val dx = ev.getX(idx) - downX
        val dy = ev.getY(idx) - downY
        val outward = if (fromRightEdge) -dx else dx
        return outward > touchSlop && abs(outward) > abs(dy) * HORIZONTAL_DOMINANCE
    }

    /**
     * Finish a custom (beyond-the-native-strip) swipe. The drawer was never moved during the
     * drag, so this is the single decision that opens it - unless the finger came back the way it
     * went, or was flung inward, in which case the drawer stays shut.
     */
    private fun commitOpen(ev: MotionEvent) {
        val drawer = edgeDrawer ?: return
        velocity?.computeCurrentVelocity(1000)
        val rawVx = velocity?.xVelocity ?: 0f
        val outwardVx = if (fromRightEdge) -rawVx else rawVx

        val idx = ev.findPointerIndex(downPointerId)
        val endX = if (idx >= 0) ev.getX(idx) else downX
        val outward = if (fromRightEdge) downX - endX else endX - downX

        val flungInward = outwardVx < -minFlingVelocity
        if (!flungInward && ev.actionMasked == MotionEvent.ACTION_UP && outward > touchSlop) {
            openDrawer(drawer, true)
        }
    }

    private fun initVelocity(ev: MotionEvent) {
        releaseVelocity()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun releaseVelocity() {
        velocity?.recycle()
        velocity = null
    }
}
