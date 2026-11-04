/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.limelight.viewmodel.VncViewModel
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Low-latency ("direct scan") render path for the surface protocols - GameStream and the
 * Android device.
 *
 * The whole point of this view is what it does *not* do: there is no GL `SurfaceTexture`, no
 * shader sampling and no extra composite. The hardware decoder writes straight into this
 * `SurfaceView`'s surface, and the system scans it out - exactly the path upstream
 * moonlight-android uses, and a few milliseconds shorter than routing the same frames back
 * through the [FrameView] GL pipeline.
 *
 * The trade-off is that a `SurfaceView` composites its surface in a window of its own, so its
 * content does not follow ordinary view transforms (`scaleX`, `translationX`). Scaling and
 * panning are therefore expressed as the view's *layout*: the frame the user zooms and drags
 * becomes this view's layout size and margins, computed from the same [FrameState] the GL
 * renderer reads, while the buffer itself stays at the video's native resolution - sized by
 * MediaCodec, deliberately *not* pinned via `setFixedSize`, so a layout resize only re-scales
 * it. The two paths thus stay pixel-for-pixel identical to the input layer, which maps touches
 * through [FrameState.toFb] regardless of who is drawing.
 *
 * On a background round-trip the `SurfaceView`'s surface is destroyed and re-created (reusing
 * the same `Surface` object), so the running decoder would otherwise be left pointing at an
 * abandoned buffer queue. [surfaceCreated] therefore forces a re-point via
 * [VncViewModel.onVideoSurfaceAvailable] whenever it fires after the first time, which is what
 * keeps the picture alive across pause/resume.
 */
class StreamSurfaceView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, FramePresenter {

    private var viewModel: VncViewModel? = null

    /** Whether [surfaceCreated] has fired and the surface has not been destroyed since. */
    private var surfaceAlive = false

    /**
     * Whether a surface has ever been handed to the decoder. The *first* [surfaceCreated]
     * only needs to make the surface available for `configure()`; every later one is a
     * re-creation (the activity was backgrounded, or the view was detached) whose buffer
     * queue the running decoder is no longer connected to, so it must force a re-point.
     */
    private var handedOffOnce = false

    init {
        holder.addCallback(this)
    }

    override val view: View get() = this

    override fun attach(viewModel: VncViewModel) {
        this.viewModel = viewModel
        viewModel.frameViewRef = WeakReference<FramePresenter>(this)

        // Become the frame presenter immediately so the ViewModel routes state changes here
        // even before the first surface callback. Visibility is managed by the activity.
        requestLayout()
    }

    // ------------------------------------------------------------------
    // Surface lifecycle
    // ------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAlive = true
        // Hand the freshly created surface to the decoder through the shared pipeline. The
        // connect thread blocks in awaitVideoSurface() until it lands, so the decoder is
        // configured with a real output surface and never runs headless.
        //
        // We deliberately do NOT call SurfaceHolder.setFixedSize here. Pinning the buffer size
        // makes the *view* own the BufferQueue geometry, so every later layout resize (zoom,
        // pan, or the framebuffer-size callback) re-negotiates the buffers under the running
        // codec - its output no longer matches the queue, frames are dropped (the displayed
        // layer reports zero queued buffers) and the stream goes black. Left to itself,
        // MediaCodec sizes the buffers to the video resolution once at configure(); a view
        // resize then only changes how SurfaceFlinger scales that fixed buffer, never the queue
        // geometry, so the codec's connection stays valid for the whole session.
        //
        // A SurfaceView reuses one Surface object across destroy/create, so simply re-delivering
        // it would look identical to the decoder's current target and be dropped. Every create
        // after the first is a genuine re-creation (background round-trip, detach) with a new
        // buffer queue, so it forces a re-point via `rebind`.
        val rebind = handedOffOnce
        handedOffOnce = true
        viewModel?.onVideoSurfaceAvailable(holder.surface, rebind = rebind)
        // Place and size the view rect for the current zoom. This is layout only - the buffer
        // geometry is owned by the codec, so resizing the view never touches the BufferQueue.
        post { applyFrame() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Nothing to do: the visible size is driven by layout, the buffer size by the codec.
        // A resize only re-scales the codec's fixed buffer; it does not recycle the surface.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The decoder's bound surface is about to become invalid. We deliberately keep the
        // session alive rather than nulling the surface (which would tear the stream down);
        // `handedOffOnce` stays true so the next [surfaceCreated] forces a re-point at the
        // rebuilt buffer queue. Nothing to do here beyond recording that the surface is gone.
        surfaceAlive = false
    }

    override fun rebindSurface() {
        if (!surfaceAlive) return
        viewModel?.onVideoSurfaceAvailable(holder.surface)
    }

    /**
     * Nothing to do here: this view is punched out of the window, so the band around the video
     * shows the container behind it, which [VncActivity] paints with the chosen color directly.
     * Putting an opaque fill on this SurfaceView itself is exactly what breaks hardware
     * composition on some OEM devices, so the color must stay off the view.
     */
    override fun setLetterboxColor(argb: Int) {
}

    // ------------------------------------------------------------------
    // Frame state -> layout (scaling + panning)
    // ------------------------------------------------------------------

    override fun refreshFrame() {
        // Layout must run on the UI thread; callers may be on the GL, session or input thread.
        post { applyFrame() }
    }

    private fun applyFrame() {
        val vm = viewModel ?: return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return

        val state = vm.frameState.getSnapshot()
        val fbW = state.fbWidth.roundToInt()
        val fbH = state.fbHeight.roundToInt()
        val scale = state.scale

        val targetW: Int
        val targetH: Int
        val marginL: Int
        val marginT: Int

        if (fbW <= 0 || fbH <= 0 || !scale.isFinite() || scale <= 0f) {
            // No video shape yet: fill the viewport so a real surface exists for the decoder
            // to bind to at configure() time. The opaque "connecting" overlay covers this.
            targetW = ViewGroup.LayoutParams.MATCH_PARENT
            targetH = ViewGroup.LayoutParams.MATCH_PARENT
            marginL = 0
            marginT = 0
        } else {
            targetW = (state.fbWidth * scale).roundToInt().coerceAtLeast(1)
            targetH = (state.fbHeight * scale).roundToInt().coerceAtLeast(1)
            marginL = state.frameX.roundToInt()
            marginT = state.frameY.roundToInt()
        }

        var changed = false
        if (lp.gravity != (Gravity.TOP or Gravity.START)) {
            lp.gravity = Gravity.TOP or Gravity.START
            changed = true
        }
        if (lp.width != targetW) { lp.width = targetW; changed = true }
        if (lp.height != targetH) { lp.height = targetH; changed = true }
        if (lp.leftMargin != marginL) { lp.leftMargin = marginL; changed = true }
        if (lp.topMargin != marginT) { lp.topMargin = marginT; changed = true }

        if (changed) layoutParams = lp
    }
}
