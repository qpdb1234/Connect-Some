/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.view.View
import com.limelight.viewmodel.VncViewModel

/**
 * Anything the viewer can paint the remote frame with.
 *
 * There are two implementations, chosen by the protocol's [com.limelight.vnc.RenderMode]:
 *
 *  - [FrameView]: a [android.opengl.GLSurfaceView] that samples the decoded image through
 *    a GL `SurfaceTexture`. Used by pixel-buffer protocols (RFB/VNC), where a remote cursor
 *    has to be composited locally on top of the frame.
 *  - [StreamSurfaceView]: a plain [android.view.SurfaceView] the hardware decoder writes
 *    into directly. Used by surface protocols (GameStream, Android device) on the low-latency
 *    "direct scan" path, where skipping the GL round trip saves a few milliseconds.
 *
 * The ViewModel holds the current one through a weak reference and only ever talks to it
 * through this interface, so switching paths does not touch the zoom / pan / input layers.
 */
interface FramePresenter {

    /** The underlying view, used for visibility, insets and screen-on handling. */
    val view: View

    /**
     * Wire this presenter to [viewModel] and make it the active one. Called by the activity
     * once the protocol (and therefore the render path) is known.
     */
    fun attach(viewModel: VncViewModel)

    /**
     * Ask the presenter to bring itself up to date with the current [FrameState]
     * (scale / translation / framebuffer size). Safe to call from any thread.
     *
     * Replaces the old direct call to `GLSurfaceView.requestRender()`.
     */
    fun refreshFrame()

    /**
     * Offer this presenter's decode surface to the session again.
     *
     * Only meaningful once the surface was handed to somebody else - which is exactly what
     * the mini window does while it is showing.
     */
    fun rebindSurface()

    /**
     * Repaint the letterbox fill - the band left around the picture when its aspect does not
     * match the viewport - with [argb]. Called on the UI thread whenever the user picks a new
     * border color.
     *
     * The two paths realize it differently: the GL [FrameView] clears its framebuffer to this
     * color (it covers the whole container, so nothing behind it could show through), while the
     * direct-scan [StreamSurfaceView] leaves the band to the container painted by the activity
     * and ignores it here.
     */
    fun setLetterboxColor(argb: Int)
}
