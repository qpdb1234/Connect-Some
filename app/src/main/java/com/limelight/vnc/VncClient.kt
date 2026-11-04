/*
 * Protocol facade shared by every remote-desktop protocol this app speaks.
 *
 * The shape of this interface is deliberately RFB's (it is AVNC's original
 * `VncClient` contract, copied verbatim: same method names, same [Observer],
 * same `connected` / `cursorInfo` / `pointerX` / `pointerY` /
 * `frameBufferUpdatesPaused` / texture hooks). The whole UI, input and
 * messenger stack was written against those semantics - keysyms, pointer
 * button masks, cut-text, bell, framebuffer resize - so an RFB connection is a
 * straight pass-through while the Moonlight / GameStream kernel
 * ([GameStreamClient]) is the adapter that translates them.
 *
 * Protocol-specific features are *not* part of this interface. GameStream-only
 * abilities live in [GameStreamExtras], Android-device-only abilities in
 * [AndroidControlExtras]; callers use `client as? GameStreamExtras` (or
 * `as? AndroidControlExtras`) and fall back to the generic path when the cast
 * fails. The same applies to the structural differences between the protocols,
 * which are exposed as data ([renderMode], [ownsReceiveLoop], [inputPath],
 * [touchPath]) instead of being inferred from a protocol enum scattered across
 * the code.
 *
 * Instances are created by [VncClientFactory]; that is the single place where
 * a profile's protocol is turned into a concrete client.
 */
package com.limelight.vnc

import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

interface VncClient {

    /** Interface for event observer (identical to AVNC's original contract). */
    interface Observer {
        fun getVncPassword(): String
        fun getVncCredentials(): UserCredential
        fun verifyVncServerCertificate(certificate: X509Certificate): Boolean
        fun onCutTextReceived(text: String)
        fun onFramebufferUpdated()
        fun onFramebufferSizeChanged(width: Int, height: Int)
        fun onPointerMoved(x: Int, y: Int)
        fun onBell()

        /**
         * Connection progress hooks. The Moonlight kernel reports each setup
         * stage (handshake, RTSP handshake, video stream establishment, ...);
         * RFB reports the RFB handshake stages. The UI can use these to show a
         * live "connecting" progress instead of a bare spinner. Default
         * implementations are no-ops so non-streaming observers need not
         * implement them.
         */
        fun onStageStarting(stage: String) {}
        fun onStageComplete(stage: String) {}

        /** Periodic (~1s) performance snapshot (latency/FPS/drops/decode time). */
        fun onPerfOverlayUpdate(text: String) {}
    }

    // ------------------------------------------------------------------
    // Path differences between the protocols, declared once per client
    // ------------------------------------------------------------------

    /** How this client's image reaches the screen. Read by the GL renderer. */
    val renderMode: RenderMode

    /**
     * Whether this client needs someone to run its receive loop.
     *
     * RFB is polling-driven: [com.limelight.session.RemoteSession] must keep
     * calling [processServerMessage] until it reports otherwise. GameStream is
     * fully callback-driven, so it sets this to false and the session simply
     * waits for the connection to end.
     */
    val ownsReceiveLoop: Boolean

    /** Keyboard transport the input layer should feed this client with. */
    val inputPath: InputPath

    /**
     * Whether touch is translated by AVNC's gesture system or handed to the
     * remote device as-is. Read by [com.limelight.ui.vnc.input.InputHandler].
     */
    val touchPath: TouchPath

    /**
     * Whether this protocol produces [Observer.onPerfOverlayUpdate] snapshots.
     *
     * GameStream measures latency/FPS/drops inside its video decoder. RFB has no such
     * source on the wire, so its client counts what reaches the viewer itself - updates
     * per second, the gap between them, the picture size and how long the host took to
     * answer the last input (see RfbClientNative.publishPerf). Read by
     * [com.limelight.ui.vnc.Toolbar].
     */
    val supportsPerfOverlay: Boolean

    /**
     * Whether this protocol plays remote audio on the phone. RFB (VNC) has no audio
     * channel at all, so the UI hides [audioMuted]'s control instead of offering a switch
     * that can do nothing. Read by [com.limelight.ui.vnc.Toolbar].
     */
    val supportsAudio: Boolean

    /**
     * Silence the remote audio *on this phone*, leaving the host untouched.
     *
     * This is not the phone's media volume and not a host-side mute: no protocol reports
     * the host's volume or mute state, so a muted host can still push a full-level stream.
     * The flag is applied to the local playback device (an `AudioTrack` volume of 0) by
     * [com.limelight.stream.MoonlightStreamClient] and by [AndroidClient]. Protocols
     * without audio keep it as a plain field that does nothing.
     *
     * The choice is not stored here: it is a persisted viewer preference
     * ([com.limelight.util.AppPreferences.Viewer.audioMuted]) which
     * [com.limelight.session.RemoteSession] writes onto every client in
     * [com.limelight.session.RemoteSession.configureClient]. Before the connection starts,
     * because the playback device is built during setup and must be born silent.
     */
    var audioMuted: Boolean

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    val connected: Boolean

    /**
     * True once the connection has actually been established. Used to tell a
     * *setup-phase* failure apart from a normal post-connect disconnect.
     */
    val connectionStarted: Boolean

    /** Human-readable reason captured when a connection attempt fails. */
    val failureReason: String?

    var cursorInfo: CursorInfo

    val pointerX: Int
    val pointerY: Int

    /**
     * Whether this session has been told where the remote pointer is at all.
     *
     * Both coordinates start at 0, which on every desktop is a real spot rather than an
     * unknown one - and a server only reports the position once something moves it. Callers
     * that show or reuse the pointer have to check this flag instead of trusting the numbers.
     */
    val pointerPositionKnown: Boolean

    /**
     * While true, pointer positions reported by the server are ignored (used by
     * relative-pointer mode while a local gesture is in progress).
     */
    var ignorePointerMovesByServer: Boolean

    val frameBufferUpdatesPaused: AtomicBoolean

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /**
     * Protocol-level connection knobs. For RFB these select the authentication
     * scheme, cursor rendering, compression level and encoding; GameStream has
     * no equivalent and ignores them.
     *
     * @param imageQuality RFB quality level in `[0, 9]`, already quantized by the
     *                     caller from the percentage stored on the profile.
     */
    fun configure(securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean)

    /** UltraVNC repeater support (VNC only; other protocols ignore it). */
    fun setupRepeater(serverId: Int)

    /** Establish the connection. Throws [java.io.IOException] on immediate failure. */
    fun connect(host: String, port: Int)

    /**
     * Waits for one incoming server message, parses it and invokes the
     * appropriate callbacks.
     *
     * @return true while the receive loop should keep running. Only meaningful
     *         when [ownsReceiveLoop] is true.
     */
    fun processServerMessage(): Boolean

    /** Release all resources. The client must not be used afterwards. */
    fun cleanup()

    // ------------------------------------------------------------------
    // Server info
    // ------------------------------------------------------------------

    fun getDesktopName(): String
    fun getFramebufferWidth(): Int
    fun getFramebufferHeight(): Int

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Sends a key event.
     *
     * @param keySym X keysym; protocols that need scan codes derive them from it
     * @param xtCode [com.limelight.vnc.XTKeyCode] value (0 if unknown)
     * @param isDown true for key down, false for key up
     */
    fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean)

    fun sendPointerEvent(x: Int, y: Int, mask: Int)

    /**
     * Updates the client-side pointer position without sending anything to the
     * server, so the local cursor can be rendered immediately.
     */
    fun moveClientPointer(x: Int, y: Int)

    /** Sends text to the remote desktop's clipboard. */
    fun sendCutText(text: String)

    /** Set remote desktop size. Needs server support; non-positive sizes ignored. */
    fun setDesktopSize(width: Int, height: Int)

    /**
     * Gives up on a [setDesktopSize] request which the host has not answered.
     *
     * RFB keeps a "resize in progress" guard raised until the server replies, and that
     * guard swallows every framebuffer update request - so a host which ignores the
     * message would leave the picture frozen. Protocols which size their own stream
     * have nothing to abandon and ignore this.
     */
    fun cancelDesktopSizeChange() {}

    fun setInputDisabled(disabled: Boolean)

    // ------------------------------------------------------------------
    // Framebuffer
    // ------------------------------------------------------------------

    /** Ask the server for a full framebuffer update. */
    fun refreshFrameBuffer()

    fun setFrameBufferUpdatesPaused(pause: Boolean)

    /**
     * Puts framebuffer contents into the currently active OpenGL texture.
     * Must be called from the GL thread, and only when
     * [renderMode] is [RenderMode.PIXEL_BUFFER].
     */
    fun uploadFrameTexture()

    /**
     * Tell the client that the OpenGL texture it uploads into was just recreated - the GL
     * context was lost and restored - so any memory of the size it allocated that texture
     * for is stale and the next [uploadFrameTexture] must re-specify it in full instead of
     * patching storage that no longer exists. Only the pixel-buffer path keeps such a
     * texture; surface protocols decode straight into it and ignore this.
     */
    fun invalidateFrameTexture() {}

    /** Uploads the remote cursor image into the active OpenGL texture. */
    fun uploadCursorTexture()
}
