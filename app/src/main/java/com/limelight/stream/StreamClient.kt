package com.limelight.stream

import java.security.cert.X509Certificate

/**
 * Unified streaming client interface.
 *
 * Designed as a drop-in conceptual replacement for AVNC's original VncClient.
 * All UI / ViewModel / Session / Messenger layers talk only to this interface.
 *
 * Implementations:
 * - MoonlightStreamClient  (GameStream + Sunshine)
 * - (future) VncStreamClient if dual-protocol is desired
 */
interface StreamClient {

    /**
     * Observer for connection & media events.
     * Extends the original VncClient.Observer contract so existing AVNC code
     * needs only minimal changes.
     */
    interface Observer {
        // ---- original VncClient.Observer ----
        fun getPassword(): String
        fun getCredentials(): UserCredential
        fun verifyServerCertificate(certificate: X509Certificate): Boolean
        fun onCutTextReceived(text: String)
        fun onFramebufferUpdated()
        fun onFramebufferSizeChanged(width: Int, height: Int)
        fun onPointerMoved(x: Int, y: Int)
        fun onBell()

        // ---- Moonlight / GameStream additions ----
        fun onStageStarting(stage: String)
        fun onStageComplete(stage: String)
        fun onStageFailed(stage: String, portFlags: Int, errorCode: Int)
        fun onConnectionStarted()
        fun onConnectionTerminated(errorCode: Int)
        fun onConnectionStatusUpdate(status: Int)
        fun onRumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short)
        fun onRumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short)
        fun onHdrModeChanged(enabled: Boolean, hdrMetadata: ByteArray?)
        fun onDisplayMessage(message: String)
        fun onDisplayTransientMessage(message: String)

        /**
         * Periodic (~1s) performance snapshot produced by the video decoder while
         * streaming: network latency, incoming/rendering FPS, dropped frames and
         * decode time. The text is already localized/formatted by the renderer.
         */
        fun onPerfOverlayUpdate(text: String)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Configure stream parameters before connect(). */
    fun configure(config: StreamConfig)

    /**
     * Start connection.
     * @param host          hostname or IP
     * @param httpsPort     HTTPS port (usually 47984)
     * @param appId         NvApp ID to launch (0 / INVALID = desktop / Steam Big Picture etc.)
     */
    fun connect(host: String, httpsPort: Int = 47984, appId: Int = 0)

    /**
     * For compatibility with original receive loop.
     * Moonlight is callback-driven; this can be a no-op or status poll.
     */
    fun processServerMessage()

    /** Graceful stop + resource cleanup. Must be called. */
    fun cleanup()

    fun stop()

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    val connected: Boolean

    fun getDesktopName(): String

    fun getFramebufferWidth(): Int
    fun getFramebufferHeight(): Int

    // ------------------------------------------------------------------
    // Input – aligned with original VncClient + Moonlight extensions
    // ------------------------------------------------------------------

    fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean)

    /**
     * Moonlight/GameStream native keyboard input: a translated scancode ("key map") plus
     * key direction, a modifier bitmask and protocol flags. This is the path that actually
     * types characters on the host (soft keyboards report no hardware scan code, so the RFB
     * [sendKeyEvent] path is inert for them).
     */
    fun sendKeyboardScancode(scancode: Short, keyDirection: Byte, modifier: Byte, flags: Byte)

    /** Absolute pointer (VNC style). */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int)

    /** Relative mouse move (preferred for games). */
    fun sendMouseMove(deltaX: Short, deltaY: Short)

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
        rightStickY: Short
    )

    fun sendUtf8Text(text: String)

    /** Local cursor prediction (no network). */
    fun moveClientPointer(x: Int, y: Int)

    fun setInputDisabled(disabled: Boolean)

    // ------------------------------------------------------------------
    // Framebuffer / Video control
    // ------------------------------------------------------------------

    fun setFrameBufferUpdatesPaused(paused: Boolean)
    fun refreshFrameBuffer()          // request IDR frame

    /** Provide Surface for MediaCodec output (Moonlight path). */
    fun setVideoSurface(surface: android.view.Surface?)   // fully-qualified to avoid extra import in interface file

    /** Provide the render target SurfaceHolder backing the video surface (Moonlight path). */
    fun setRenderTarget(holder: android.view.SurfaceHolder?)

    // ------------------------------------------------------------------
    // Moonlight specific helpers
    // ------------------------------------------------------------------

    fun getPairState(): PairState
    fun startPairing(pin: String)
}

/** Simple credential holder (kept for AVNC compatibility). */
data class UserCredential(
    val username: String = "",
    val password: String = ""
)

enum class PairState {
    NOT_PAIRED,
    PAIRED,
    PIN_WRONG,
    FAILED
}
