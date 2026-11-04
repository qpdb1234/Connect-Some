/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.gl

import android.graphics.RectF

/**
 * This class manges rendering of the framebuffer.
 *
 * Two image sources are supported, selected by the connected protocol (see
 * [com.limelight.vnc.RenderMode]):
 *
 *  - `external = true`: the pixels come from MediaCodec, which decodes into the
 *    [android.graphics.SurfaceTexture] created by [Image]. This is the GameStream
 *    path - the frame never passes through CPU memory.
 *  - `external = false`: a plain `GL_TEXTURE_2D` whose contents are uploaded by
 *    the client itself (`uploadFrameTexture`) on the GL thread. This is the
 *    RFB/VNC path, where the native library keeps the framebuffer in memory.
 */
class Frame(external: Boolean = true) : Image(external = external) {

    private val frameRect = RectF()

    init {
        // Texture rectangle is fixed and covers the entire texture
        updateTextureRect(RectF(0f, 0f, 1f, 1f))
    }

    fun getFrameRect() = RectF(frameRect)

    /**
     * Should be called whenever the size of framebuffer is changed.
     * This size will be used to calculate frame vertices.
     */
    fun updateFbSize(width: Float, height: Float) {
        if (frameRect.width() == width && frameRect.height() == height)
            return //Nothing to do

        frameRect.set(0f, 0f, width, height)
        updateDrawRect(frameRect)
    }
}