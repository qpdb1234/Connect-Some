/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.gl

/**
 * Shaders used for rendering framebuffer
 */
object Shaders {
    //language=GLSL
    const val VERTEX_SHADER = """
            uniform mat4 u_Projection;
            attribute vec2 a_Position;
            attribute vec2 a_TextureCoordinates;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               v_TextureCoordinates = a_TextureCoordinates;
               gl_Position = u_Projection * vec4(a_Position, 0, 1);
            }"""

    //language=GLSL
    const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_TextureUnit;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               gl_FragColor = texture2D(u_TextureUnit, v_TextureCoordinates).bgra;
            }"""

    /**
     * Fragment shader for the video framebuffer when it is delivered through a
     * MediaCodec-backed [android.graphics.SurfaceTexture] (external OES texture).
     * The decoder already produces correctly ordered RGBA, so no swizzle is applied.
     */
    //language=GLSL
    const val FRAGMENT_SHADER_EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_TextureUnit;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               gl_FragColor = texture2D(u_TextureUnit, v_TextureCoordinates);
            }"""
}