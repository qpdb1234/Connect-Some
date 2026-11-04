/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.vnc

import com.limelight.stream.PairState
import com.limelight.stream.StreamConfig

/**
 * Capabilities that only the Moonlight / GameStream (NVStream) protocol has.
 *
 * They are deliberately kept *out* of the [VncClient] facade: an RFB server has
 * no notion of pinned certificates, app ids, scancode input or decoder surfaces.
 * Callers obtain them with `client as? GameStreamExtras` and fall back to the
 * generic path (or hide the UI) when the cast fails - so no `if (protocol == ...)`
 * test ever reaches the UI layer.
 *
 * It extends [VideoSink]: the activity / surface hooks are shared with every
 * hardware-decoding protocol, everything below is NVStream-only.
 */
interface GameStreamExtras : VideoSink {

    // ------------------------------------------------------------------
    // Session setup (consumed by RemoteSession before connect)
    // ------------------------------------------------------------------

    /** Resolution / framerate / bitrate / HDR of the video stream. */
    fun setStreamConfig(config: StreamConfig)

    /** NvApp to launch on the host. 0 = the default desktop-ish app. */
    fun setAppId(id: Int)

    /** Stable client identity used for pairing with this host. */
    fun setClientUniqueId(id: String)

    /** Pinned server certificate (DER), or null to trust on first use. */
    fun setServerCertificateDer(der: ByteArray?)

    // attachActivity() / setVideoSurface() come from VideoSink.

    // ------------------------------------------------------------------
    // Input (GameStream wire format)
    // ------------------------------------------------------------------

    /** Keyboard event as translated scancode + modifier bitmask + flags. */
    fun sendKeyboardScancode(scancode: Short, keyDirection: Byte, modifier: Byte, flags: Byte)

    /** Injects text as keystrokes on the host (characters without a scancode). */
    fun sendText(text: String)

    fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short)

    fun sendMouseButtonDown(button: Byte)

    fun sendMouseButtonUp(button: Byte)

    fun sendMouseScroll(scrollClicks: Byte)

    fun sendMouseHighResScroll(scrollAmount: Short)

    fun sendControllerInput(
            controllerNumber: Short,
            activeGamepadMask: Short,
            buttonFlags: Short,
            leftTrigger: Byte,
            rightTrigger: Byte,
            leftStickX: Short,
            leftStickY: Short,
            rightStickX: Short,
            rightStickY: Short)

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    fun getPairState(): PairState

    /** Asynchronous: result is reported through [VncClient.Observer.onStageComplete] style messages. */
    fun startPairing(pin: String)
}
