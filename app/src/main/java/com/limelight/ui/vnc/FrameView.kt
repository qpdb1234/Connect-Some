/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.View
import com.limelight.ui.vnc.gl.Renderer
import com.limelight.viewmodel.VncViewModel
import com.limelight.vnc.VncClient
import java.lang.ref.WeakReference

/**
 * This class renders the VNC framebuffer on screen.
 *
 * It derives from [GLSurfaceView], which creates an EGL Display, where we can
 * render the framebuffer using OpenGL ES. See [GLSurfaceView] for more details.
 *
 * Actual rendering is done by [Renderer], which is executed on a dedicated
 * thread by [GLSurfaceView].
 *
 *
 *-   +-------------------+          +--------------------+         +--------------------+
 *-   |   [FrameView]     |          |  [VncViewModel]    |         |   [VncClient]      |
 *-   +--------+----------+          +----------+---------+         +----------+---------+
 *-            |                                |                              |
 *-            |                                |                              |
 *-            | Render Request                 | [FrameState]                 | Framebuffer
 *-            |                                v                              |
 *-            |                     +----------+---------+                    |
 *-            +-------------------> |     [Renderer]     | <------------------+
 *-                                  +--------------------+

 */
class FrameView(context: Context?, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs), FramePresenter {

    // Spelled out in full because this class inherits a nested `Renderer` interface from
    // GLSurfaceView, which inside the class body would otherwise win over the import.
    private var renderer: com.limelight.ui.vnc.gl.Renderer? = null

    override val view: View get() = this

    /**
     * Set up the GL pipeline and become the ViewModel's active presenter. This is the
     * pixel-buffer render path: the decoder is never pointed here directly, instead the
     * client uploads frames and [Renderer] composites them (and the remote cursor).
     */
    override fun attach(viewModel: VncViewModel) {
        initialize(viewModel)
        viewModel.frameViewRef = WeakReference<FramePresenter>(this)
    }

    /**
     * GL frames are pulled on demand, so a state change just means "draw again".
     */
    override fun refreshFrame() {
        requestRender()
    }

    /**
     * Should be called from [VncActivity.onCreate].
     */
    fun initialize(viewModel: VncViewModel) {
        setEGLContextClientVersion(2)
        // Keep the EGL context (and with it the video-decode SurfaceTexture created in
        // Renderer.onSurfaceCreated) alive while the activity is paused, so a short
        // background->foreground round-trip does NOT recreate the SurfaceTexture. The
        // MediaCodec decoder binds its output Surface exactly once at configure() and can
        // never be re-pointed at a new one, so if the surface were rebuilt the live decoder
        // would keep writing into the now-dead old SurfaceTexture and the screen would go
        // black (a long background hides this because the session has dropped and the whole
        // decoder is rebuilt on reconnect). Preserving the context keeps the same valid
        // surface bound.
        setPreserveEGLContextOnPause(true)
        renderer = Renderer(viewModel)
        setRenderer(renderer!!)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * Offers this view's decode surface to the session again.
     *
     * Only meaningful after the surface was handed to somebody else - which is exactly
     * what the mini window does while it is showing. See [Renderer.rebindSurface].
     */
    override fun rebindSurface() {
        queueEvent { renderer?.rebindSurface() }
    }

    /**
     * The GL path fills the whole container, so its letterbox is whatever the framebuffer was
     * cleared to. Hand the chosen color to the renderer on the GL thread; [queueEvent] also
     * schedules a draw, so the change shows even on a paused picture.
     */
    override fun setLetterboxColor(argb: Int) {
        queueEvent { renderer?.setLetterboxColor(argb) }
    }
}