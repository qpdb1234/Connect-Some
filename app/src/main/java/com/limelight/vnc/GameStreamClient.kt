/*
 * GameStream / Sunshine implementation of the [VncClient] facade.
 *
 * This is the class that used to *be* `VncClient`: it keeps AVNC's original
 * public surface while internally delegating to the Moonlight kernel
 * ([com.limelight.stream.MoonlightStreamClient]). Everything the RFB protocol
 * has natively (keysyms, cut-text, bell, framebuffer resize, receive loop, GL
 * pixel uploads) is either adapted here or a no-op, and everything only
 * GameStream can do (scancode input, app ids, pairing, decoder surfaces) is
 * exposed through [GameStreamExtras].
 *
 * The one structural difference vs. the native-libvncclient original: Moonlight's
 * `NvConnection` is fully callback-driven, so there is no RFB receive loop. The
 * hosting [com.limelight.session.RemoteSession] is notified about the connection
 * lifecycle through the [onConnectionStarted] / [onConnectionTerminated] hooks
 * passed to the constructor.
 */
package com.limelight.vnc

import android.app.Activity
import android.view.Surface
import com.limelight.App
import com.limelight.model.ServerProfile
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.stream.MoonlightStreamClient
import com.limelight.stream.StreamClient
import com.limelight.stream.StreamConfig
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

class GameStreamClient(
        private val observer: VncClient.Observer,
        private val onConnectionStarted: () -> Unit,
        private val onConnectionTerminated: (errorCode: Int) -> Unit,
) : VncClient, GameStreamExtras {

    override val renderMode = RenderMode.SURFACE
    override val ownsReceiveLoop = false
    override val inputPath = InputPath.GS_SCANCODE

    // NVStream has a mouse, not a touchscreen: gestures stay translated by AVNC.
    override val touchPath = TouchPath.GESTURE

    // The Moonlight decoder reports latency / FPS / dropped frames every second
    // (see [bridging].onPerfOverlayUpdate), so the overlay toggle has a real source.
    override val supportsPerfOverlay = true

    // GameStream multiplexes audio into the stream, so the mute toggle has something to
    // silence. The flag is delegated to the kernel: it owns the AudioTrack.
    override val supportsAudio = true

    override var audioMuted = false
        set(value) {
            field = value
            kernel.setAudioMuted(value)
        }

    private val connectedFlag = AtomicBoolean(false)
    private val inputDisabled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    override var connected: Boolean = false
        private set(value) {
            field = value
            connectedFlag.set(value)
        }

    /**
     * True once streaming has actually started (onConnectionStarted fired). Used to
     * distinguish a *setup-phase* failure (RTSP handshake, launch, ...) from a normal
     * post-connect disconnect: moonlight-common-c reports setup failures only through
     * `stageFailed` and never fires `connectionTerminated` (see Limelight.h), so the
     * session layer must treat an early `stageFailed` as terminal or it blocks forever.
     */
    @Volatile
    private var established = false
    override val connectionStarted: Boolean get() = established

    /** Human-readable reason captured when a connection attempt fails before streaming. */
    @Volatile
    override var failureReason: String? = null
        private set

    override var cursorInfo = CursorInfo()

    override var pointerX = 0
        private set
    override var pointerY = 0
        private set

    /** Flipped by the first position the host reports, or by one of our own gestures. */
    override var pointerPositionKnown = false
        private set

    override var ignorePointerMovesByServer = false

    override val frameBufferUpdatesPaused: AtomicBoolean get() = paused

    /** Video render target + host activity, supplied by the UI layer. */
    private var videoSurface: Surface? = null
    private var hostActivity: Activity? = null

    private var config: StreamConfig = StreamConfig()
    private var appId: Int = 0

    // ------------------------------------------------------------------
    // Kernel bridging: StreamClient.Observer -> VncClient.Observer
    // ------------------------------------------------------------------
    private val bridging = object : StreamClient.Observer {
        override fun getPassword(): String = observer.getVncPassword()
        override fun getCredentials(): com.limelight.stream.UserCredential {
            val c = observer.getVncCredentials()
            return com.limelight.stream.UserCredential(c.username, c.password)
        }

        override fun verifyServerCertificate(certificate: X509Certificate): Boolean =
                observer.verifyVncServerCertificate(certificate)

        override fun onCutTextReceived(text: String) = observer.onCutTextReceived(text)
        override fun onFramebufferUpdated() = observer.onFramebufferUpdated()

        override fun onFramebufferSizeChanged(width: Int, height: Int) {
            observer.onFramebufferSizeChanged(width, height)
        }

        override fun onPointerMoved(x: Int, y: Int) {
            pointerX = x
            pointerY = y
            pointerPositionKnown = true
            if (!ignorePointerMovesByServer) observer.onPointerMoved(x, y)
        }

        override fun onBell() = observer.onBell()

        override fun onConnectionStarted() {
            established = true
            connected = true
            // Any notice captured during setup is stale from now on: the session is
            // live, so RemoteSession must not report it as a disconnect reason later.
            failureReason = null
            // Must qualify with this@GameStreamClient: the constructor lambda property
            // shares its name with this override, so a bare call would resolve to
            // the object's own method and recurse infinitely (StackOverflowError).
            this@GameStreamClient.onConnectionStarted()
        }

        override fun onConnectionTerminated(errorCode: Int) {
            connected = false
            // See note above: qualify to avoid resolving to this same override.
            this@GameStreamClient.onConnectionTerminated(errorCode)
        }

        override fun onStageStarting(stage: String) = observer.onStageStarting(stage)
        override fun onStageComplete(stage: String) = observer.onStageComplete(stage)

        override fun onStageFailed(stage: String, portFlags: Int, errorCode: Int) {
            // A stage failure before streaming started is terminal and, unlike a normal
            // disconnect, is NOT followed by connectionTerminated. End the session here so
            // the hosting RemoteSession stops blocking on its latch ("connecting" spinner
            // would otherwise hang until its timeout).
            if (!established) {
                connected = false
                this@GameStreamClient.onConnectionTerminated(errorCode)
            }
        }

        override fun onConnectionStatusUpdate(status: Int) {}
        override fun onRumble(c: Short, low: Short, high: Short) {}
        override fun onRumbleTriggers(c: Short, lt: Short, rt: Short) {}
        override fun onHdrModeChanged(enabled: Boolean, hdrMetadata: ByteArray?) {}
        override fun onDisplayMessage(message: String) {
            // Keep the last message seen before streaming so the UI can explain why a
            // connection attempt failed (e.g. "Failed to resume existing session").
            if (!established) failureReason = message
        }
        override fun onDisplayTransientMessage(message: String) {}
        override fun onPerfOverlayUpdate(text: String) = observer.onPerfOverlayUpdate(text)
    }

    // The Moonlight kernel. Declared after [bridging] so the observer is initialized first.
    private val kernel = MoonlightStreamClient(App.appContext, bridging)

    // ------------------------------------------------------------------
    // GameStreamExtras: Moonlight-specific configuration
    // ------------------------------------------------------------------
    override fun setStreamConfig(config: StreamConfig) {
        this.config = config
        kernel.configure(config)
    }

    override fun setAppId(id: Int) { this.appId = id }

    // These must be forwarded to the kernel: MoonlightStreamClient reads its own
    // serverCertDer / clientUniqueId inside runRealConnection() to build NvHTTP /
    // NvConnection. Storing them only here would leave the pinned server cert and the
    // pairing unique-id unset on the kernel, so HTTPS after pairing would fail.
    override fun setServerCertificateDer(der: ByteArray?) { kernel.setServerCertificateDer(der) }
    override fun setClientUniqueId(id: String) { kernel.setClientUniqueId(id) }

    override fun attachActivity(activity: Activity?) {
        hostActivity = activity
        kernel.attachRenderer(activity, null)
    }

    /** Provide the decoded-video render surface (from the GL SurfaceTexture). */
    override fun setVideoSurface(surface: Surface?) {
        videoSurface = surface
        kernel.setVideoSurface(surface)
    }

    /**
     * Switching to or from the mini window brings a surface of its own. Let the kernel
     * re-point the running decoder at it rather than paying for a second RTSP handshake.
     * The surface itself was already handed over by setVideoSurface().
     */
    override fun swapVideoSurface(surface: Surface): Boolean = kernel.swapVideoSurface(surface)

    override fun sendKeyboardScancode(scancode: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
        if (canSendInput()) kernel.sendKeyboardScancode(scancode, keyDirection, modifier, flags)
    }

    override fun sendText(text: String) {
        if (canSendInput()) sendUtf8TextWithNewlines(text)
    }

    override fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
        if (canSendInput()) kernel.sendMousePosition(x, y, referenceWidth, referenceHeight)
    }

    override fun sendMouseButtonDown(button: Byte) {
        if (canSendInput()) kernel.sendMouseButtonDown(button)
    }

    override fun sendMouseButtonUp(button: Byte) {
        if (canSendInput()) kernel.sendMouseButtonUp(button)
    }

    override fun sendMouseScroll(scrollClicks: Byte) {
        if (canSendInput()) kernel.sendMouseScroll(scrollClicks)
    }

    override fun sendMouseHighResScroll(scrollAmount: Short) {
        if (canSendInput()) kernel.sendMouseHighResScroll(scrollAmount)
    }

    override fun sendControllerInput(
            controllerNumber: Short,
            activeGamepadMask: Short,
            buttonFlags: Short,
            leftTrigger: Byte,
            rightTrigger: Byte,
            leftStickX: Short,
            leftStickY: Short,
            rightStickX: Short,
            rightStickY: Short) {
        if (canSendInput()) kernel.sendControllerInput(controllerNumber, activeGamepadMask, buttonFlags,
                leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY)
    }

    override fun getPairState() = kernel.getPairState()

    override fun startPairing(pin: String) = kernel.startPairing(pin)

    // ------------------------------------------------------------------
    // VncClient API
    // ------------------------------------------------------------------

    /** No-op: RFB security/encoding knobs have no Moonlight equivalent. */
    override fun configure(securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean) {}

    /** No-op: UltraVNC repeater is VNC-only. */
    override fun setupRepeater(serverId: Int) {}

    override fun connect(host: String, port: Int) {
        // [ServerProfile.port] is one column shared by both protocols, so rows
        // written before the protocol existed can still carry VNC's 5900 default.
        // For a GameStream host that value never meant anything but "the default
        // HTTPS port" - remapping it here keeps the interpretation inside the
        // protocol that needs it (an RFB client must see 5900 untouched).
        val httpsPort = when (port) {
            5900, 5901, 0 -> ServerProfile.DEFAULT_GAME_STREAM_PORT
            else -> port
        }
        kernel.connect(host, httpsPort, appId)
    }

    /** Callback-driven: nothing to poll. Present for API compatibility. */
    override fun processServerMessage(): Boolean = false

    override fun getDesktopName(): String = kernel.getDesktopName()
    override fun getFramebufferWidth(): Int = kernel.getFramebufferWidth()
    override fun getFramebufferHeight(): Int = kernel.getFramebufferHeight()

    override fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean) {
        if (canSendInput()) kernel.sendKeyEvent(keySym, xtCode, isDown)
    }

    override fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        if (canSendInput()) kernel.sendPointerEvent(x, y, mask)
    }

    override fun moveClientPointer(x: Int, y: Int) {
        pointerX = x
        pointerY = y
        pointerPositionKnown = true
        observer.onPointerMoved(x, y)
    }

    override fun sendCutText(text: String) {
        if (canSendInput()) sendUtf8TextWithNewlines(text)
    }

    /**
     * Injects [text] as keystrokes through Moonlight's UTF-8 text event. That event only
     * types printable characters: line breaks are silently dropped by the host, so a
     * multiline clipboard payload arrived on the PC as one long line. Send the printable
     * runs as text events and turn every newline / carriage return (and a CRLF pair) into
     * a real Enter keypress in between.
     */
    private fun sendUtf8TextWithNewlines(text: String) {
        var i = 0
        var runStart = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                if (runStart < i) kernel.sendUtf8Text(text.substring(runStart, i))
                kernel.sendKeyboardScancode(SCANCODE_RETURN, KeyboardPacket.KEY_DOWN, 0, 0)
                kernel.sendKeyboardScancode(SCANCODE_RETURN, KeyboardPacket.KEY_UP, 0, 0)
                // A CRLF pair is a single Enter
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                i++
                runStart = i
            } else {
                i++
            }
        }
        if (runStart < text.length) kernel.sendUtf8Text(text.substring(runStart))
    }

    override fun setDesktopSize(width: Int, height: Int) {
        // Moonlight streams at a fixed configured resolution; ignore dynamic resize.
    }

    override fun setInputDisabled(disabled: Boolean) {
        inputDisabled.set(disabled)
    }

    override fun refreshFrameBuffer() {}

    override fun setFrameBufferUpdatesPaused(pause: Boolean) {
        paused.set(pause)
    }

    /** Video arrives through the OES SurfaceTexture; no GL upload from client. */
    override fun uploadFrameTexture() {}
    override fun uploadCursorTexture() {}

    override fun cleanup() {
        kernel.cleanup()
        connected = false
    }

    private fun canSendInput(): Boolean = connected && !inputDisabled.get()

    private companion object {
        /**
         * Enter as the GameStream key map wants it: the same value
         * KeyboardTranslator produces for KEYCODE_ENTER ((0x80 << 8) | 0x0D).
         * 0x800D is past Short.MAX_VALUE, hence the wrap-around conversion.
         */
        val SCANCODE_RETURN: Short = 0x800D.toShort()
    }
}
