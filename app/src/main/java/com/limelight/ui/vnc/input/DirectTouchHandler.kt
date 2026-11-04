/*
 * Touch passthrough for a remote Android device.
 *
 * The other protocols drive a *desktop*: one pointer, button masks, and a gesture
 * vocabulary ([TouchHandler]) that turns two fingers into right-clicks, scrolls and
 * pans. A phone has none of that - it has up to ten fingers and a touchscreen that
 * understands exactly this kind of event - so when the connected client declares
 * [com.limelight.vnc.TouchPath.DIRECT], [InputHandler] hands the raw stream to this
 * class instead.
 *
 * Two translations are still needed:
 *
 *  1. Screen position -> position inside the remote frame. A finger on the black bar
 *     beside the image must not land on the remote status bar, so the point goes
 *     through [com.limelight.ui.vnc.FrameState] (zoom and pan included) and is then
 *     normalized, because the wire carries floats in `[0, 1]` rather than pixels.
 *  2. Event actions -> one packet *per pointer*. The helper keeps its own pointer
 *     table and derives `ACTION_POINTER_DOWN/UP` itself, so a second finger must
 *     arrive as a plain `ACTION_DOWN` for its own id or it is dropped.
 *
 * Press duration travels with it: every packet carries the time since *its* pointer went
 * down, and a finger held without moving keeps saying so on a beat, so that a target which
 * reads the arriving stream instead of the timestamps still sees a long press and not a tap.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */
package com.limelight.ui.vnc.input

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.limelight.viewmodel.VncViewModel

/**
 * @param dispatcher session dispatcher, which reaches the live client
 * @param viewModel  source of the frame transform (zoom, pan, frame size)
 */
class DirectTouchHandler(private val dispatcher: Dispatcher, private val viewModel: VncViewModel) {

    private companion object {
        /**
         * Jitter below this many screen pixels is not sent. Without it, a resting
         * thumb floods the wire with move packets; the reference client uses the
         * same cutoff.
         */
        const val MOVE_DEAD_ZONE_PX = 4

        /**
         * How often a finger that is held but not moving checks in on the wire. A long
         * press is a *sequence* that stays alive, not only a pair of timestamps: a target
         * that rebuilds its input stream from packet arrival (an Android VM whose injector
         * re-stamps events) sees a motionless DOWN ... UP as one instant tap and never waits
         * for the long-press timeout. Well under that timeout, and cheap next to a drag.
         */
        const val KEEP_ALIVE_MS = 100L
    }

    /**
     * One finger, from its own down time to its release.
     *
     * [downAt] is this view's own [SystemClock.uptimeMillis] at the press rather than the
     * [MotionEvent.getDownTime] of the gesture: every packet then carries the elapsed time
     * measured when it leaves, so the sequence keeps counting even while the finger is held
     * still and no further motion event arrives.
     */
    private class Finger(val downAt: Long, var x: Int, var y: Int)

    private val fingers = HashMap<Int, Finger>()

    /** Sends a still-held finger in on its own, until the last one is lifted. */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val keepAlive = object : Runnable {
        override fun run() {
            if (fingers.isEmpty()) return
            for ((id, finger) in fingers)
                send(id, MotionEvent.ACTION_MOVE, finger.x, finger.y, finger)
            uiHandler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    /** Viewport (screen) coordinates of a touch, which is what the frame transform eats. */
    private val point = PointF()

    fun onTouchEvent(event: MotionEvent): Boolean {
        val fs = viewModel.frameState
        if (fs.fbWidth < 1f || fs.fbHeight < 1f)
            return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                press(event, event.actionIndex)

            MotionEvent.ACTION_MOVE ->
                // Every pointer of a multi-touch drag moves in the same event.
                for (i in 0 until event.pointerCount) move(event, i)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                release(event, event.actionIndex)

            MotionEvent.ACTION_CANCEL ->
                // The gesture was stolen (a dialog, a notification shade): close every
                // finger we opened, or the device keeps a phantom touch forever.
                cancelAll()

            else -> return false
        }
        return true
    }

    /** Drops all tracked fingers, e.g. when the session ends mid-gesture. */
    fun reset() {
        uiHandler.removeCallbacks(keepAlive)
        fingers.clear()
    }

    private fun press(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val x = event.getX(index).toInt()
        val y = event.getY(index).toInt()
        // Every pointer owns its down time: MotionEvent only carries one for the whole
        // gesture, and the helper turns the offset straight back into an eventTime, so a
        // second finger borrowing the first finger's clock would arrive already stale.
        fingers[id] = Finger(SystemClock.uptimeMillis(), x, y)
        send(id, MotionEvent.ACTION_DOWN, x, y, fingers[id])
        uiHandler.removeCallbacks(keepAlive)
        uiHandler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    private fun move(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val finger = fingers[id] ?: return press(event, index)

        val x = event.getX(index).toInt()
        val y = event.getY(index).toInt()
        if (kotlin.math.abs(x - finger.x) < MOVE_DEAD_ZONE_PX &&
            kotlin.math.abs(y - finger.y) < MOVE_DEAD_ZONE_PX)
            return

        finger.x = x
        finger.y = y
        send(id, MotionEvent.ACTION_MOVE, x, y, finger)
    }

    private fun release(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val finger = fingers.remove(id) ?: return
        // The finger is already off the table, so it has to travel to [send] by hand: without
        // it the release would fall back to *this* event's time as the down time, making every
        // lift look instantaneous (offset 0) and breaking anything that waits on a long press.
        send(id, MotionEvent.ACTION_UP, finger.x, finger.y, finger)
        stopKeepAliveIfIdle()
    }

    private fun cancelAll() {
        if (fingers.isEmpty()) return
        for ((id, finger) in fingers.toList())
            send(id, MotionEvent.ACTION_UP, finger.x, finger.y, finger)
        fingers.clear()
        stopKeepAliveIfIdle()
    }

    /** The last finger's release has to cancel the beat, or it would outlive the gesture. */
    private fun stopKeepAliveIfIdle() {
        if (fingers.isEmpty()) uiHandler.removeCallbacks(keepAlive)
    }

    private fun send(pointerId: Int, action: Int, viewX: Int, viewY: Int, finger: Finger?) {
        val fs = viewModel.frameState
        val downAt = finger?.downAt ?: SystemClock.uptimeMillis()

        point.set(viewX.toFloat(), viewY.toFloat())
        // Unchecked on purpose: a finger that slid past the frame edge keeps the
        // gesture alive, so the coordinate is clamped into the frame instead of
        // dropping the event. The wire clamps too, but only after normalization.
        val fb = fs.toFbUnchecked(point)
        val xNorm = (fb.x / fs.fbWidth).coerceIn(0f, 1f)
        val yNorm = (fb.y / fs.fbHeight).coerceIn(0f, 1f)

        // A blown-up transform leaks NaN through the clamp, and the helper would turn it
        // into a coordinate of 0 - a touch in the corner nobody asked for.
        if (!xNorm.isFinite() || !yNorm.isFinite()) return

        val offsetTime = (SystemClock.uptimeMillis() - downAt).coerceAtLeast(0L).toInt()
        // The helper's pointer ids go into a single byte.
        dispatcher.sendAndroidTouch(action, pointerId.coerceIn(0, 255), xNorm, yNorm, offsetTime)
    }
}
