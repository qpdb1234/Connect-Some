/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.gl

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLES20.GL_BLEND
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA
import android.opengl.GLES20.GL_SRC_ALPHA
import android.opengl.GLES20.glBlendFunc
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glDisable
import android.opengl.GLES20.glEnable
import android.opengl.GLES20.glViewport
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.limelight.util.AccentTheme
import com.limelight.vnc.RenderMode
import com.limelight.viewmodel.VncViewModel
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Frame renderer.
 *
 * There are two ways the image can reach this renderer, and the protocol (not
 * the UI) decides which one is used - see [RenderMode]:
 *
 *  - [RenderMode.SURFACE][com.limelight.vnc.RenderMode.SURFACE]: MediaCodec
 *    decodes into a [SurfaceTexture] (external OES texture) owned by [Frame];
 *    each draw pulls the newest frame via [Frame.updateTexImage]. The remote
 *    cursor is embedded in the video, so no local cursor overlay is drawn.
 *  - [RenderMode.PIXEL_BUFFER][com.limelight.vnc.RenderMode.PIXEL_BUFFER]: the
 *    client keeps the framebuffer in memory and the GL thread uploads it into a
 *    plain 2D texture with `client.uploadFrameTexture()`. The remote cursor is a
 *    separate shape update, so it is composited locally on top of the frame.
 *
 * Assembly is deferred until [VncViewModel.hasProfile] becomes true: the EGL
 * surface can be created before the profile (and thus the protocol) has been
 * loaded from the database. The ViewModel asks for a redraw once it knows.
 */
class Renderer(val viewModel: VncViewModel) : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)

    private var assembled = false
    private var drawCursor = false
    private var renderMode = RenderMode.SURFACE
    private lateinit var program: Program
    private lateinit var cursorProgram: Program
    private lateinit var frame: Frame
    private lateinit var cursor: Cursor

    /**
     * ARGB the letterbox is cleared to. Written from the UI thread through
     * [setLetterboxColor] and read on the GL thread in [onDrawFrame]; volatile so a change is
     * seen there. Seeded from the persisted choice in [onSurfaceCreated] so a surface that is
     * (re)created before the activity's next notification still comes up in the right color.
     */
    @Volatile private var letterboxColor = Color.BLACK

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        letterboxColor = AccentTheme.argbOf(viewModel.pref.viewer.streamBarColor)

        // A (possibly lost) GL context means the old textures/programs are gone
        assembled = false
        assembleIfNeeded()
    }

    /**
     * Change the letterbox fill at runtime. Reached on the GL thread via
     * [FrameView.setLetterboxColor]'s queueEvent.
     */
    fun setLetterboxColor(argb: Int) {
        letterboxColor = argb
    }

    /**
     * Creates the GL objects for the render path selected by the profile.
     *
     * Called from [onSurfaceCreated] and from [onDrawFrame] (the latter because
     * the profile may arrive only after the surface exists). Runs on the GL
     * thread only. Does nothing until the protocol is known.
     */
    private fun assembleIfNeeded() {
        if (assembled || !viewModel.hasProfile)
            return

        renderMode = viewModel.renderMode
        val useSurface = renderMode == RenderMode.SURFACE

        frame = Frame(external = useSurface)
        cursor = Cursor()
        program = Program(external = useSurface)
        // The cursor is always a 2D texture; with a pixel-buffer frame both use
        // the same program (which is what AVNC originally did).
        cursorProgram = if (useSurface) Program(external = false) else program

        drawCursor = !useSurface && !viewModel.pref.input.hideRemoteCursor

        if (useSurface) {
            // Wire the video surface into the streaming client and request a redraw
            // whenever the decoder produces a new frame. With a pixel buffer the
            // client itself notifies us via `onFramebufferUpdated`, so nothing is
            // wired here.
            frame.surfaceTexture?.setOnFrameAvailableListener(
                    SurfaceTexture.OnFrameAvailableListener { viewModel.frameViewRef.get()?.refreshFrame() })
            viewModel.onVideoSurfaceAvailable(frame.surface)
        } else {
            // This just made a fresh 2D texture that has no storage yet. The client
            // uploads into it lazily and remembers the size it allocated at; tell it that
            // memory is gone so the next upload re-allocates rather than patching an empty
            // texture - the fix for a GL context that was lost and restored mid-stream.
            viewModel.client?.invalidateFrameTexture()
        }

        assembled = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
    }

    /**
     * Re-runs the surface hand-over for a renderer that has already been assembled.
     *
     * The mini window runs its own renderer with its own surface, and a hardware decoder
     * binds its output exactly once. So when that window goes away and this view becomes
     * the picture again, nothing would point the decoder back here - the surface this
     * renderer created is still alive, it just stopped being the current one. Handing it
     * over again is what makes [VncViewModel.onVideoSurfaceAvailable] restart the
     * connection onto it. Runs on the GL thread, which is what owns [frame].
     */
    fun rebindSurface() {
        if (!assembled || renderMode != RenderMode.SURFACE)
            return // Pixel-buffer protocols never bound a surface in the first place

        viewModel.onVideoSurfaceAvailable(frame.surface)
    }

    /**
     * Draws frame on screen according to current state.
     *
     * Y-axis of coordinate system used by OpenGL is in opposite direction (upwards)
     * relative to Y-axis in screen coordinates (downwards).
     *
     * To compensate for this, we invert the Y-coordinates of drawn frame. This is
     * achieved by:
     *              1. Using clipping region [-height, 0] instead of [0, height] for Y-axis
     *              2. Inverting sign of Y-axis position
     *              3. Inverting sign of Y-axis scale (to flip the frame)
     *
     * So the frame is drawn as follows in OpenGL:
     *
     *              +Y ^
     *                 |
     *                 |[0,0]            [width,0]
     *        -X <-----|---------------------+-------------> +X
     *                 |                     |
     *                 |                     |
     *                 |       Frame         |
     *                 |                     |
     *                 |                     |
     *                 +---------------------+
     *                 |[0,-height]    [width,-height]
     *                 |
     *              -Y v
     *
     * Doing this inversion here allows rest of the code to not worry about difference
     * in Y-axis direction.
     *
     */
    override fun onDrawFrame(gl: GL10?) {
        // Paint the band the frame does not cover (the letterbox) with the user's chosen border
        // color instead of black. Applied every frame so a live toolbar change lands on the next
        // draw, and set before glClear - which runs ahead of the paused / no-profile returns
        // below, so even a frozen picture is repainted when only the color changed.
        val c = letterboxColor
        glClearColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        glDisable(GL_BLEND)

        // Might be the first draw after the profile (and its render path) arrived
        assembleIfNeeded()
        if (!assembled) return // Still no profile - there is nothing to draw yet

        val client = viewModel.client ?: return
        if (!client.connected || client.frameBufferUpdatesPaused.get())
            return

        val state = viewModel.frameState.getSnapshot()
        if (state.vpWidth == 0f || state.vpHeight == 0f)
            return

        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.orthoM(projectionMatrix, 0, 0f, state.vpWidth, -state.vpHeight, 0f, -1f, 1f)
        Matrix.translateM(projectionMatrix, 0, state.frameX, -state.frameY, 0f)
        Matrix.scaleM(projectionMatrix, 0, state.scale, -state.scale, 1f)

        program.useProgram()
        program.setUniforms(projectionMatrix)

        frame.updateFbSize(state.fbWidth, state.fbHeight)
        frame.setVideoBufferSize(state.fbWidth.toInt(), state.fbHeight.toInt())
        frame.bind(program)
        if (renderMode == RenderMode.SURFACE) frame.updateTexImage() else client.uploadFrameTexture()
        frame.draw()

        program.validate()

        // The composited remote cursor lives on the pixel-buffer path only (drawCursor). The
        // local dot marker now sits on top of it rather than replacing it, so the desktop's own
        // pointer keeps showing even when the marker is a dot - the two read as a highlighted
        // cursor instead of one hiding the other.
        if (drawCursor) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            val ci = client.cursorInfo

            cursor.update(client.pointerX.toFloat(), client.pointerY.toFloat(), ci, frame)
            cursorProgram.useProgram()
            cursorProgram.setUniforms(projectionMatrix)
            cursor.bind(cursorProgram)
            client.uploadCursorTexture()
            cursor.draw()

            glDisable(GL_BLEND)
        }
    }
}