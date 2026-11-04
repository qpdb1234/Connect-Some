/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Represents current 'view' state of the frame.
 *
 * Terminology
 * ===========
 *
 * Framebuffer: This is the buffer holding pixel data. It resides in native memory.
 *
 * Frame: This is the actual content rendered on screen. It can be thought of as
 * 'rendered framebuffer'. Its size changes based on current [scale] and its position
 * is stored in [frameX] & [frameY].
 *
 * Window: Top-level window of the application/activity.
 *
 * Viewport: This is the area of window where frame is rendered. It is denoted by [FrameView].
 *
 * Safe area: Area inside viewport which is safe for interaction with frame, maintained in [safeArea].
 *
 *     Window denotes 'total' area available to our activity, viewport denotes 'visible to user'
 *     area, and safe area denotes 'able to click' area. Most of the time all three will be equal,
 *     but viewport can be smaller than window (e.g. if soft keyboard is visible), and safe area
 *     can be smaller than viewport (e.g. due to display cutout).
 *
 *     +---------------------------+   -   -
 *     |         \Cutout/          |   |   | Viewport
 *     |                           |   |   |   -
 *     |                           |   |   |   | SafeArea
 *     +---------------------------+   |   -   -
 *     |      Soft Keyboard        |   | Window
 *     +---------------------------+   -
 *
 *     Differentiating between these allows us to handle layout changes more easily and cleanly.
 *     We use window size to calculate base scale because we don't want to change scale when
 *     keyboard is shown/hidden. Viewport size is used for rendering the frame, fully immersive.
 *     Safe area is used to coerce frame position so that user can pan every part of frame inside
 *     safe area to interact with it.
 *
 * See [LayoutManager] for more information about these values.
 *
 * State & Coordinates
 * ===================
 *
 * Both frame & viewport are in same coordinate space. Viewport is assumed to be fixed
 * in its place with [0,0] represented by top-left corner. Only frame is scaled/moved.
 * To make sure frame does not move off-screen, after each state change, values are
 * coerced within range by [coerceValues].
 *
 * Rendering is done by [com.limelight.ui.vnc.gl.Renderer] based on these values.
 *
 *
 * Scaling
 * =======
 *
 * Scaling controls the 'size' of rendered frame. It involves multiple factors, like window size,
 * framebuffer size, user choice etc. To achieve best experience, we split scaling in two parts.
 * One automatic, and one user controlled.
 *
 * 1. Base Scale [baseScale] :
 * Motivation behind base scale is to start with the most optimal frame size. It is automatically
 * calculated (and updated) using window size & framebuffer size. When orientation of local
 * device is such that longer edge of the window is aligned with longer edge of the frame,
 * base scale will satisfy following constraints (see [calculateBaseScale]):
 *
 * - Frame is completely visible
 * - Frame's aspect ratio is maintained
 * - Maximum window space is utilized
 *
 * 2. Zoom Scale [zoomScale] :
 * This is the user controlled part. It is updated only in response to pinch gestures.
 * To allow different zoom in different orientations, two separate zoom scales are maintained
 * in [zoomScale1] & [zoomScale2]. Based on user preference and current orientation, [zoomScale]
 * will be delegated to one of these.
 *
 * Conceptually, zoom scale works 'on top of' the base scale.
 * Effective scale [scale] is calculated as the product of these two parts, so:

 *      FrameSize = (FramebufferSize * BaseScale) * ZoomScale
 *
 *
 * Thread safety
 * =============
 *
 * Frame state is accessed from two threads: Its properties are updated in UI thread
 * and consumed by the renderer thread. There is a chance that Renderer thread may see
 * half-updated state (e.g. [frameX] is changed inside [pan] but [coerceValues] is not yet called).
 * This half-updated state can cause flickering issues.
 *
 * To avoid this we use [Snapshot]. All updates to frame state are guarded by [lock].
 * Render thread uses [getSnapshot] to retrieve a consistent state to render the frame.
 */
class FrameState(
        requestedMinZoomScale: Float = DEFAULT_MIN_ZOOM_SCALE,
        requestedMaxZoomScale: Float = DEFAULT_MAX_ZOOM_SCALE,
        private val usePerOrientationZoom: Boolean = false
) {

    private companion object {
        const val DEFAULT_MIN_ZOOM_SCALE = 0.5F
        const val DEFAULT_MAX_ZOOM_SCALE = 5F

        /** Whatever the preferences said, the usable range never leaves this. */
        const val ALLOWED_MIN_ZOOM_SCALE = .1F
        const val ALLOWED_MAX_ZOOM_SCALE = 20F
    }

    /**
     * Bounds applied to the user's zoom in [coerceValues].
     *
     * They are handed over straight from the preferences, which a backup file can have
     * filled with NaN, with a negative, or with the two values the wrong way round.
     * `coerceIn(min, max)` throws on min > max, and that would crash the viewer on the
     * first layout pass; a reversed pair is a bad number rather than a deliberate choice,
     * so it is swapped instead of being discarded.
     */
    private val minZoomScale: Float
    private val maxZoomScale: Float

    init {
        var min = sanitizeZoom(requestedMinZoomScale, DEFAULT_MIN_ZOOM_SCALE)
        var max = sanitizeZoom(requestedMaxZoomScale, DEFAULT_MAX_ZOOM_SCALE)
        if (min > max) {
            val swapped = min
            min = max
            max = swapped
        }
        minZoomScale = min
        maxZoomScale = max
    }

    private fun sanitizeZoom(value: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(ALLOWED_MIN_ZOOM_SCALE, ALLOWED_MAX_ZOOM_SCALE)
            else fallback

    //Frame position, relative to top-left corner (0,0)
    var frameX = 0F; private set
    var frameY = 0F; private set

    //VNC framebuffer size
    var fbWidth = 0F; private set
    var fbHeight = 0F; private set

    //Viewport/FrameView size
    var vpWidth = 0F; private set
    var vpHeight = 0F; private set

    //Size of activity window
    var windowWidth = 0F; private set
    var windowHeight = 0F; private set

    var safeArea = RectF(); private set

    /**
     * Safe area exactly as it was handed to us, before the current viewport clipped it.
     *
     * Kept apart from [safeArea] because that one is narrowed to the viewport on every pass of
     * [coerceValues], and the request must survive to be re-clipped against the next viewport.
     */
    private var requestedSafeArea = RectF()

    //Scaling
    var zoomScale1 = 1F; private set
    var zoomScale2 = 1F; private set
    private val useZoomScale1 get() = (!usePerOrientationZoom || windowHeight > windowWidth)

    var baseScale = 1F; private set
    var zoomScale
        get() = if (useZoomScale1) zoomScale1 else zoomScale2
        private set(value) = if (useZoomScale1) zoomScale1 = value else zoomScale2 = value

    val scale get() = baseScale * zoomScale

    /**
     * Immutable wrapper for frame state
     */
    data class Snapshot(
            val frameX: Float,
            val frameY: Float,
            val fbWidth: Float,
            val fbHeight: Float,
            val vpWidth: Float,
            val vpHeight: Float,
            val scale: Float
    )

    @Synchronized
    fun setFramebufferSize(w: Float, h: Float) {
        fbWidth = w
        fbHeight = h
        calculateBaseScale()
        coerceValues()
    }

    @Synchronized
    fun setViewportSize(w: Float, h: Float) {
        vpWidth = w
        vpHeight = h
        coerceValues()
    }

    @Synchronized
    fun setWindowSize(w: Float, h: Float) {
        windowWidth = w
        windowHeight = h
        calculateBaseScale()
        coerceValues()
    }

    @Synchronized
    fun setSafeArea(rect: RectF) {
        requestedSafeArea = RectF(rect)
        coerceValues()
    }

    /**
     * Adjust zoom scale according to give [scaleFactor].
     *
     * Focus point of zoom can be specified via [fx] & [fy]. If possible,
     * frame will be panned to keep the focus point 'fixed' on screen.
     */
    @Synchronized
    fun updateZoom(scaleFactor: Float, fx: Float = 0f, fy: Float = 0f) {
        // A pinch can hand us 0/0 when the detector sees two pointers exactly on top of each
        // other, and NaN would survive coerceValues() only to divide every later touch by it.
        if (!scaleFactor.isFinite() || scaleFactor <= 0f)
            return

        val oldScale = zoomScale
        val newScale = zoomScale * scaleFactor

        zoomScale = snapZoom(oldScale, newScale)
        coerceValues()

        // How much zoom was actually changed
        val appliedScaleFactor = zoomScale / oldScale

        //Calculate how much the focus would shift after scaling
        val dfx = (fx - frameX) * (appliedScaleFactor - 1)
        val dfy = (fy - frameY) * (appliedScaleFactor - 1)

        //Translate in opposite direction to keep the focus fixed on screen
        pan(-dfx, -dfy)
    }

    @Synchronized
    fun setZoom(zoom1: Float, zoom2: Float) {
        zoomScale1 = if (zoom1.isFinite()) zoom1 else zoomScale1
        zoomScale2 = if (zoom2.isFinite()) zoom2 else zoomScale2
        coerceValues()
    }

    /**
     * Shift frame by given delta.
     */
    @Synchronized
    fun pan(deltaX: Float, deltaY: Float) {
        frameX += deltaX
        frameY += deltaY
        coerceValues()
    }

    /**
     * Move frame to given position.
     */
    @Synchronized
    fun moveTo(x: Float, y: Float) {
        frameX = x
        frameY = y
        coerceValues()
    }

    /**
     * Converts given viewport point to corresponding framebuffer point.
     * Not all viewport points have a corresponding framebuffer point
     * because drawn frame can be smaller than viewport.
     */
    @Synchronized
    fun toFbUnchecked(vpPoint: PointF): PointF {
        val fbX = (vpPoint.x - frameX) / scale
        val fbY = (vpPoint.y - frameY) / scale
        return PointF(fbX, fbY)
    }

    /**
     * Converts given viewport point to corresponding framebuffer point.
     * Returns null if given point lies outside of framebuffer.
     */
    @Synchronized
    fun toFb(vpPoint: PointF): PointF? {
        val fb = toFbUnchecked(vpPoint)
        if (fb.x < 0F || fb.y < 0f || fb.x >= fbWidth || fb.y >= fbHeight)
            return null
        else
            return fb
    }

    /**
     * Converts given framebuffer point to corresponding point in viewport.
     */
    @Synchronized
    fun toVP(fbPoint: PointF): PointF {
        return PointF(fbPoint.x * scale + frameX, fbPoint.y * scale + frameY)
    }

    /**
     * Returns immutable & consistent snapshot of frame state.
     */
    @Synchronized
    fun getSnapshot(): Snapshot {
        return Snapshot(frameX = frameX, frameY = frameY,
                        fbWidth = fbWidth, fbHeight = fbHeight,
                        vpWidth = vpWidth, vpHeight = vpHeight, scale = scale)
    }

    /**
     * Signaled when gesture ends
     */
    fun onGestureStop() {
        resetSnapState()
    }

    private fun calculateBaseScale() {
        if (fbHeight == 0F || fbWidth == 0F || windowWidth == 0F || windowHeight == 0F)
            return  //Not enough info yet

        val s1 = max(windowWidth, windowHeight) / max(fbWidth, fbHeight)
        val s2 = min(windowWidth, windowHeight) / min(fbWidth, fbHeight)

        baseScale = min(s1, s2)
    }

    /**
     * Makes sure state values are within constraints.
     */
    private fun coerceValues() {
        // Everything downstream divides by `baseScale * zoomScale`, so a zero or a
        // non-finite value here is not a wrong zoom level but a viewer where no touch can
        // be mapped any more. coerceIn() cannot catch that: NaN compares false against both
        // bounds and is returned unchanged.
        if (!zoomScale1.isFinite()) zoomScale1 = 1F
        if (!zoomScale2.isFinite()) zoomScale2 = 1F
        if (!baseScale.isFinite() || baseScale <= 0F) baseScale = 1F

        zoomScale1 = zoomScale1.coerceIn(minZoomScale, maxZoomScale)
        zoomScale2 = zoomScale2.coerceIn(minZoomScale, maxZoomScale)

        // Re-clip from the request instead of intersecting in place: the viewport shrinks and
        // grows over the session (keyboard, cutout, mini window), and an intersect() that wrote
        // its result back into safeArea would narrow it permanently. Once the mini window had
        // taken the large-screen viewport hostage at a smaller size, the frame got pinned to one
        // edge on the next rotation and the rest of the card stayed black.
        safeArea.set(requestedSafeArea)
        if (safeArea.isEmpty || !safeArea.intersect(0f, 0f, vpWidth, vpHeight))
            safeArea.set(0f, 0f, vpWidth, vpHeight)

        frameX = coercePosition(frameX, safeArea.left, safeArea.right, fbWidth)
        frameY = coercePosition(frameY, safeArea.top, safeArea.bottom, fbHeight)

        // Align to integral boundary
        frameX = if (frameX.isFinite()) frameX.toInt().toFloat() else 0f
        frameY = if (frameY.isFinite()) frameY.toInt().toFloat() else 0f
    }

    /**
     * Coerce position value in a direction (horizontal/vertical).
     */
    private fun coercePosition(current: Float, safeMin: Float, safeMax: Float, fb: Float): Float {
        val scaledFb = (fb * scale)
        val diff = (safeMax - safeMin) - scaledFb

        return if (diff >= 0) diff / 2 + safeMin       //Frame will be smaller than safe area, so center it
        else current.coerceIn(diff + safeMin, safeMin) //otherwise, make sure safe area is completely filled.
    }


    /**
     * Zoom scale snapping: It temporarily stops the scale at 100% when user is zooming.
     * This way user don't have to reset zoom via "Reset zoom" button in toolbar.
     */
    private enum class Snap { None, Up, Down }

    private var snapMode = Snap.None
    private val snapLimit = 0.3f  // ±30% zoom
    private var snapDelta = 0f

    private fun snapZoom(oldZoom: Float, newZoom: Float): Float {
        val deltaToOne = abs(newZoom - 1)
        var result = newZoom

        if (snapMode == Snap.Up) {
            if (newZoom < 1) {                // Until snap limit is reached, snap up zoom to 1.0
                snapDelta += deltaToOne
                if (snapDelta < snapLimit)
                    result = 1f
            } else                            // Not crossed below 1.0 yet
                snapDelta = 0f
        }

        if (snapMode == Snap.Down) {
            if (newZoom > 1) {                // Until snap limit is reached, snap down zoom to 1.0
                snapDelta += deltaToOne
                if (snapDelta < snapLimit)
                    result = 1f
            } else                            // Not crossed above 1.0 yet
                snapDelta = 0f
        }

        if (oldZoom > 1 && newZoom < oldZoom) // Greater than 1 but decreasing, so snap when going below 1
            snapMode = Snap.Up
        if (oldZoom < 1 && newZoom > oldZoom) // Lower than 1 but increasing, so snap when going above 1
            snapMode = Snap.Down

        return result
    }

    private fun resetSnapState() {
        snapMode = Snap.None
        snapDelta = 0f
    }
}