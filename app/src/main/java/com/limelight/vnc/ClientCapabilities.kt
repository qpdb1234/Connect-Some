/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.vnc

import android.app.Activity
import android.view.Surface

/**
 * How the received image reaches the screen.
 *
 * This is a property of the *protocol*, not of the UI: the two values need
 * different assembly in [com.limelight.ui.vnc.gl.Renderer] /
 * [com.limelight.ui.vnc.gl.Frame], and the renderer asks the live client for
 * its mode instead of guessing (or checking which protocol was selected).
 */
enum class RenderMode {

    /**
     * The decoder writes into a `SurfaceTexture` which GL samples as an
     * external (OES) texture. Used by GameStream: frames never touch CPU
     * memory on the client side.
     */
    SURFACE,

    /**
     * The client owns a pixel buffer in memory; the GL thread uploads it with
     * `glTexImage2D` for every framebuffer update. Used by RFB/VNC.
     */
    PIXEL_BUFFER,
}

/**
 * Which keyboard transport the connected protocol actually understands.
 *
 * RFB speaks X keysyms (what AVNC's original `KeyHandler` produces), GameStream
 * speaks XT scancodes + modifier bitmasks (what
 * [com.limelight.ui.vnc.input.MoonlightKeyHandler] produces), and the
 * Android-device protocol speaks raw `KeyEvent` key codes - no translation table
 * at all, so it needs its own handler
 * ([com.limelight.ui.vnc.input.AndroidKeyHandler]). The input layer picks its
 * `KeyHandler` implementation based on this value.
 */
enum class InputPath {
    RFB_KEYSYM,
    GS_SCANCODE,
    ANDROID_KEYCODE,
}

/**
 * How touch input travels from the screen to the remote machine.
 *
 * RFB and GameStream have no multi-touch concept, so AVNC's gesture system
 * (mouse emulation, two-finger scroll, pinch-to-zoom mapping) translates finger
 * chatter into pointer events - [GESTURE]. A remote *Android* device instead has
 * a real touchscreen and the protocol carries up to 10 independent pointers, so
 * raw touch events are forwarded verbatim by
 * [com.limelight.ui.vnc.input.DirectTouchHandler] - [DIRECT], which bypasses the
 * gesture layer (and the mouse/trackpad helpers that would otherwise fight it).
 */
enum class TouchPath {
    GESTURE,
    DIRECT,
}

/**
 * The two hooks a protocol needs when the *UI* owns the render target: the host
 * activity (for MediaCodec) and the decoded-video surface coming out of the GL
 * `SurfaceTexture`.
 *
 * Both hardware-decoding protocols implement it - [GameStreamExtras] narrows it
 * further - while the pixel-buffer RFB client does not, so the session layer uses
 * `client as? VideoSink` instead of testing which protocol was selected.
 */
interface VideoSink {
    fun attachActivity(activity: Activity?)
    fun setVideoSurface(surface: Surface?)

    /**
     * Re-point a live decoder at [surface] rather than the one it was configured with.
     *
     * Returning true means the running stream will paint onto the new surface, so the
     * session layer must not tear the connection down. The default false says this
     * protocol cannot do it, and changing surfaces costs a reconnect.
     */
    fun swapVideoSurface(surface: Surface): Boolean = false
}

/**
 * Thrown by [VncClientFactory] when a profile requests a protocol that this
 * build cannot serve - typically because the corresponding optional native
 * library was not compiled (see [com.limelight.stream.NativeLibraryProbe]).
 *
 * Only ever thrown for the protocol that is missing: the other protocols stay
 * fully usable.
 */
class UnsupportedProtocolException(message: String) : Exception(message)
