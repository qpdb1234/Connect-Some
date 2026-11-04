/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.gl

import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.GL_CLAMP_TO_EDGE
import android.opengl.GLES20.GL_FLOAT
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_TEXTURE_2D
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TEXTURE_WRAP_S
import android.opengl.GLES20.GL_TEXTURE_WRAP_T
import android.opengl.GLES20.GL_TRIANGLES
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glDrawArrays
import android.opengl.GLES20.glEnableVertexAttribArray
import android.opengl.GLES20.glGenTextures
import android.opengl.GLES20.glTexParameteri
import android.opengl.GLES20.glVertexAttribPointer
import com.limelight.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * This class handles rendering of a 2D image.
 *
 * The image is represented with two side-by-side triangles:
 *
 *       [0, height]  +-----------+  [width, height]
 *                    |  T2      /|
 *                    |       /   |
 *                    |    /      |
 *                    | /     T1  |
 *            [0, 0]  +-----------+  [width, 0]
 *
 * Image texture is mapped onto these two triangles.
 *
 * When [external] is true, the texture target is `GL_TEXTURE_EXTERNAL_OES` and a
 * [SurfaceTexture] (+ [Surface]) is created on top of it. Decoded video is fed
 * into that surface by MediaCodec and pulled during rendering via [updateTexImage].
 */
open class Image(private val external: Boolean = false) {

    companion object {
        const val FLOAT_SIZE = 4
        const val TRIANGLE_COMPONENTS = 2    //[x,y]
        const val TEXTURE_COMPONENTS = 2     //[x,y]
        const val VERTEX_COUNT = 6
    }

    /** The texture target used for allocation & binding. */
    private val textureTarget = if (external) GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D

    /** For external images: the SurfaceTexture backing the video and its Surface. */
    var surfaceTexture: SurfaceTexture? = null
        private set
    var surface: Surface? = null
        private set

    private val drawVertexBuffer = allocateVertexBuffer(VERTEX_COUNT, TRIANGLE_COMPONENTS)
    private val textureVertexBuffer = allocateVertexBuffer(VERTEX_COUNT, TEXTURE_COMPONENTS)
    private val texture = allocateTexture()

    private fun allocateVertexBuffer(vertexCount: Int, vertexComponents: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(vertexCount * vertexComponents * FLOAT_SIZE)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
    }

    private fun allocateTexture(): Int {
        val texturesObjects = intArrayOf(0)
        glGenTextures(1, texturesObjects, 0)
        if (texturesObjects[0] == 0) {
            Log.e("Texture", "Could not generate texture.")
            return 0
        }

        glBindTexture(textureTarget, texturesObjects[0])
        glTexParameteri(textureTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(textureTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(textureTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(textureTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(textureTarget, 0)

        if (external) {
            // Wrap the freshly created OES texture in a SurfaceTexture and expose a
            // Surface that MediaCodec can render into. Must run with the GL context
            // current (which is the case inside Renderer.onSurfaceCreated). The
            // texture-arg constructor already attaches the texture to this context.
            val st = SurfaceTexture(texturesObjects[0])
            surfaceTexture = st
            surface = Surface(st)
        }
        return texturesObjects[0]
    }

    /**
     * Pull the newest decoded frame into the external texture. No-op for 2D images.
     * Must be called on the GL thread with the image's texture bound.
     */
    fun updateTexImage() {
        if (external) surfaceTexture?.updateTexImage()
    }

    /** Adjust the buffer size of the video surface to match the framebuffer. */
    fun setVideoBufferSize(width: Int, height: Int) {
        if (external && width > 0 && height > 0) {
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    private fun updateVertexBuffer(buffer: FloatBuffer, vararg data: Float) {
        check(data.size == buffer.capacity())
        buffer.rewind()
        buffer.put(data)
        buffer.rewind()
    }

    private fun updateVertexBuffer(buffer: FloatBuffer, rect: RectF) {
        val l = rect.left
        val r = rect.right
        // Y-axis in GL is inverted w.r.t. Y-axis in UI
        val t = rect.bottom
        val b = rect.top

        updateVertexBuffer(
                buffer,

                // Triangle 1
                l, b,
                r, b,
                r, t,

                // Triangle 2
                l, b,
                r, t,
                l, t,
        )
    }

    /**
     * Updates drawing coordinates of the image
     */
    fun updateDrawRect(rect: RectF) {
        updateVertexBuffer(drawVertexBuffer, rect)
    }

    /**
     * Updates sampling area of the texture.
     * Values in [rect] must be in texture coordinates (in range [0, 1])
     */
    fun updateTextureRect(rect: RectF) {
        updateVertexBuffer(textureVertexBuffer, rect)
    }

    /**
     * Updates program attributes according to this image
     */
    fun bind(program: Program) {
        setVertexAttributePointer(program.aPositionLocation, TRIANGLE_COMPONENTS, drawVertexBuffer)
        setVertexAttributePointer(program.aTextureCoordinatesLocation, TEXTURE_COMPONENTS, textureVertexBuffer)
        glBindTexture(textureTarget, texture)
        // For external images the pixel data is supplied by the decoder via the
        // SurfaceTexture; for 2D images it is uploaded from native code.
    }

    private fun setVertexAttributePointer(attributeLocation: Int, componentCount: Int, buffer: FloatBuffer) {
        buffer.rewind()
        glVertexAttribPointer(attributeLocation, componentCount, GL_FLOAT, false, componentCount * FLOAT_SIZE, buffer)
        glEnableVertexAttribArray(attributeLocation)
    }

    fun draw() {
        glDrawArrays(GL_TRIANGLES, 0, VERTEX_COUNT)
    }
}