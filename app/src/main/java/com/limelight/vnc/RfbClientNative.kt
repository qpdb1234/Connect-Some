/*
 * RFB / VNC implementation of the [VncClient] facade, on top of libvncclient.
 *
 * This is AVNC's original native wrapper (same JNI surface, same callbacks,
 * same locking rules), renamed and re-plumbed so that it *implements* the
 * protocol facade instead of being the only client that exists:
 *
 * -       +------------+                                    +----------+
 * -       | Public API |                                    | Observer |
 * -       +------------+                                    +-----A----+
 * -              |                                                |
 * -              |                                                |
 * -   JNI -------|------------------------------------------------|-----------
 * -              |                                                |
 * -              |                                                |
 * -      +-------v--------+       +--------------+       +--------v---------+
 * -      | Native Methods |------>| LibVNCClient |<----->| Native Callbacks |
 * -      +----------------+       +--------------+       +------------------+
 *
 * Two things differ from the GameStream side, and they are the reason the
 * facade declares [renderMode] / [ownsReceiveLoop] instead of letting the UI
 * guess:
 *   - frames land in a CPU pixel buffer that the GL thread uploads itself
 *     ([RenderMode.PIXEL_BUFFER]), not in a decoder `Surface`;
 *   - the protocol is polled, so somebody has to keep calling
 *     [processServerMessage] ([ownsReceiveLoop] = true). That somebody is
 *     [com.limelight.session.RemoteSession].
 *
 * Every method is only reachable after the native library has been loaded
 * successfully; [com.limelight.stream.NativeLibraryProbe.isVncAvailable]
 * touches this class once and caches the outcome, so a build without
 * libnative-vnc.so simply never constructs a client.
 */
package com.limelight.vnc

import com.limelight.util.AppLogging
import com.limelight.util.Log
import androidx.annotation.Keep
import com.limelight.App
import com.limelight.R
import com.limelight.util.AppPreferences
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class RfbClientNative(
        private val observer: VncClient.Observer,
        private val onConnectionStarted: () -> Unit,
        private val onConnectionTerminated: (errorCode: Int) -> Unit,
) : VncClient {

    override val renderMode = RenderMode.PIXEL_BUFFER
    override val ownsReceiveLoop = true
    override val inputPath = InputPath.RFB_KEYSYM

    // RFB's pointer event is a single coordinate + button mask: multi-touch would
    // have to be squeezed into that, so AVNC's gesture system stays in charge.
    override val touchPath = TouchPath.GESTURE

    // RFB carries no timing information on the wire, but a viewer can still count what
    // reaches it: how often a framebuffer update lands, how big the picture is, and how
    // long the host took to answer the last input. The arrivals are collected here and
    // turned into numbers by [RfbPerfWindow]; nothing is asked of the server.
    override val supportsPerfOverlay = true

    // RFB carries no audio channel - just a one-bit Bell, which is a local tone rather than
    // streamed sound - so there is nothing for the mute toggle to silence and the UI hides it.
    // The setter still accepts assignments: the session layer writes the persisted choice onto
    // whichever client it has just created, without asking which protocol that client speaks.
    override val supportsAudio = false
    override var audioMuted = false

    /**
     * Update rate and picture gap, measured over the arrivals themselves rather than over
     * what a counter collected between two reports - which is what keeps the overlay from
     * swinging with the scheduler (see [RfbPerfWindow]).
     */
    private val perfWindow = RfbPerfWindow()

    /** When the last input was handed to the wire, in nanoseconds; 0 = nothing pending. */
    @Volatile
    private var perfLastInput = 0L

    /**
     * Milliseconds the picture took to answer the last input, blended over the answers so
     * far; 0 when nothing has answered yet.
     *
     * Blended rather than replaced, because two answers are not two measurements of the
     * same quantity: whether a keystroke is answered by a 16x16 rectangle or by the whole
     * desktop is the host's choice, and consecutive raw readings on one unchanged host
     * differed by a factor of ten - which read back as the connection being unstable.
     */
    @Volatile
    private var perfInputLagMs = 0f

    /**
     * When [perfInputLagMs] was last measured, in nanoseconds; 0 = no reading. The value is held
     * for a few reports rather than shown once and dropped: at the roughly one update per
     * second a still RFB screen produces, a reading that vanished on the next tick would be
     * unreadable.
     */
    @Volatile
    private var perfInputLagAt = 0L

    /**
     * Picture size as last described by the server. Once known it is only ever replaced by
     * another real size, never by the 0 that [getFramebufferWidth] answers while the state
     * lock is busy - a resolution that blinked to 0x0 between reports was another thing the
     * host had not done.
     */
    @Volatile
    private var perfWidth = 0
    @Volatile
    private var perfHeight = 0

    private var perfTimer: Timer? = null

    /**
     * Value of the pointer to native 'rfbClient'. This is passed to all native methods.
     */
    private val nativePtr: Long

    init {
        nativePtr = nativeClientCreate()
        if (nativePtr == 0L)
            throw RuntimeException("Could not create native rfbClient!")
    }

    override var connected = false
        private set

    /**
     * True once the RFB handshake completed. A failure before that point is
     * reported through [failureReason] so the session layer can show it as a
     * connection error rather than as a disconnect.
     */
    @Volatile
    private var established = false
    override val connectionStarted: Boolean get() = established

    @Volatile
    override var failureReason: String? = null
        private set

    private var destroyed = false

    /**
     * Lock protecting access to [connected] & [destroyed] state.
     */
    private val stateLock = ReentrantReadWriteLock()

    /**
     * If true, all input to remote server is disabled
     */
    private val inputDisabled = AtomicBoolean(false)

    /**
     * If true, client stops sending framebuffer update requests to server
     */
    override val frameBufferUpdatesPaused = AtomicBoolean(false)

    /**
     * Cursor geometry, replaced by [cbHandleCursorInfo] on the receiver thread and read
     * while drawing on the GL thread. Written through a volatile field so the renderer
     * cannot pair a new width/height with an old texture (or the other way round), which
     * is what draws a stretched or torn cursor.
     */
    @Volatile
    private var cursorInfoField = CursorInfo()
    override var cursorInfo: CursorInfo
        get() = cursorInfoField
        set(value) {
            cursorInfoField = value
        }

    /**
     * Latest pointer position. See [moveClientPointer].
     */
    override var pointerX = 0
        private set
    override var pointerY = 0
        private set

    /** Flipped by the first position we learn, from the server or from our own gesture. */
    override var pointerPositionKnown = false
        private set

    override var ignorePointerMovesByServer = false

    /**
     * Value of the most recent cut text sent/received from server
     */
    @Volatile
    private var lastCutText: String? = null

    /**
     * The `Strict Latin-1 clipboard` switch, read once per connection: flipping it while
     * bytes are already in flight would give the two halves of one payload different
     * meanings. The rules it toggles live in [ClipboardTextCodec], which the
     * clipboard-only [RfbClipboardBridge] shares.
     */
    private val strictCutText by lazy { AppPreferences(App.appContext).server.clipboardStrictLatin1 }

    /**
     * Setup different properties for this client.
     *
     * @param securityType RFB security type to use.
     */
    override fun configure(securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean) {
        stateLock.read {
            if (!connected && !destroyed) {
                nativeConfigure(nativePtr, securityType, useLocalCursor, imageQuality, useRawEncoding)
            }
        }
    }

    /**
     * UltraVNC repeater: [serverId] is the id of the destination machine, which
     * the repeater resolves instead of a host name.
     */
    override fun setupRepeater(serverId: Int) {
        stateLock.read {
            if (!connected && !destroyed)
                nativeSetDest(nativePtr, "ID", serverId)
        }
    }

    /**
     * Initializes VNC connection.
     */
    override fun connect(host: String, port: Int) {
        stateLock.read {
            check(!connected) { "Already connected" }
            check(!destroyed) { "Client has been destroyed" }

            if (!nativeInit(nativePtr, host, port)) {
                failureReason = nativeGetLastErrorStr().ifBlank { CONNECT_FAILURE_HINT }
                throw IOException(failureReason)
            }
        }
        stateLock.write {
            if (!destroyed) {
                connected = true
                established = true
                failureReason = null
            }
        }

        startPerfTimer()

        // The facade's lifecycle hooks are how the hosting session learns that it may
        // publish the client + messenger to the UI. GameStream gets these from the
        // Moonlight kernel's callbacks; here the handshake is synchronous, so the
        // thread that ran `connect` reports it itself.
        onConnectionStarted()
    }

    /**
     * Waits for one incoming server message, parses it and invokes the
     * appropriate callbacks.
     *
     * @return true while the receive loop should keep running. A finished
     *         connection is reported by returning false (and not by throwing),
     *         so [com.limelight.session.RemoteSession] can shut the session down
     *         through its normal path.
     */
    override fun processServerMessage(): Boolean {
        stateLock.read {
            if (connected && nativeProcessServerMessage(nativePtr))
                return true
        }

        // Either not connected or an error occurred when processing message
        stateLock.write {
            connected = false
        }
        nativeGetLastErrorStr().let {
            // errno only describes socket-level problems. If the handshake succeeded
            // and the very first server message still could not be handled, the usual
            // reason is a framebuffer encoding this build has no decoder for: Tight
            // embeds JPEG and libjpeg is deliberately not linked (see CMakeLists.txt).
            failureReason = it.ifBlank {
                if (established) ENCODING_FAILURE_HINT else CONNECT_FAILURE_HINT
            }
        }
        onConnectionTerminated(0)
        return false
    }

    /**
     * Name of remote desktop
     */
    override fun getDesktopName(): String = ifConnected {
        nativeGetDesktopName(nativePtr)
    } ?: ""

    override fun getFramebufferWidth(): Int = ifConnected {
        nativeGetWidth(nativePtr)
    } ?: 0

    override fun getFramebufferHeight(): Int = ifConnected {
        nativeGetHeight(nativePtr)
    } ?: 0

    /**
     * Sends Key event to remote server.
     *
     * @param keySym    Key symbol
     * @param xtCode    Key code from [XTKeyCode]
     * @param isDown    true for key down, false for key up
     */
    override fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean) {
        ifConnectedAndInteractive {
            perfLastInput = System.nanoTime()
            nativeSendKeyEvent(nativePtr, keySym, xtCode, isDown)
        }
    }

    /**
     * Sends pointer event to remote server.
     *
     * @param x    Horizontal pointer coordinate
     * @param y    Vertical pointer coordinate
     * @param mask Button mask to identify which button was pressed.
     */
    override fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        ifConnectedAndInteractive {
            perfLastInput = System.nanoTime()
            nativeSendPointerEvent(nativePtr, x, y, mask)
        }
    }

    /**
     * Updates client-side pointer position.
     * No event is sent to server.
     *
     * Primary use-case is to update pointer position during gestures.
     * This way we can immediately render the cursor on new position without
     * waiting for Network IO.
     *
     * It also helps with servers which don't send pointer-position updates
     * if pointer was moved by the client.
     */
    override fun moveClientPointer(x: Int, y: Int) {
        ifConnected {
            pointerX = x
            pointerY = y
            pointerPositionKnown = true
            observer.onPointerMoved(x, y)
        }
    }

    /**
     * Sends text to remote desktop's clipboard.
     */
    override fun sendCutText(text: String) {
        ifConnectedAndInteractive {
            if (text != lastCutText) {
                val sent = if (nativeIsUTF8CutTextSupported(nativePtr))
                    nativeSendCutText(nativePtr, text.toByteArray(StandardCharsets.UTF_8), true)
                else
                    nativeSendCutText(nativePtr, ClipboardTextCodec.encode(text, strictCutText), false)
                if (sent)
                    lastCutText = text
            }
        }
    }

    /**
     * Set remote desktop size to given dimensions.
     * This needs server support to actually work.
     * Non-positive [width] & [height] are ignored.
     */
    override fun setDesktopSize(width: Int, height: Int) {
        ifConnected {
            if (width > 0 && height > 0)
                nativeSetDesktopSize(nativePtr, width, height)
        }
    }

    /**
     * Abandons the desktop size asked for through [setDesktopSize]: lowers the library's
     * pending-resize guard and requests a full frame of whatever the server really has.
     * Without this, a server which ignored the request leaves the viewer showing a
     * picture which never updates again.
     */
    override fun cancelDesktopSizeChange() {
        ifConnected { nativeCancelDesktopSizeChange(nativePtr) }
    }

    override fun setInputDisabled(disabled: Boolean) {
        inputDisabled.set(disabled)
    }

    /**
     * Sends frame buffer update request to remote server.
     */
    override fun refreshFrameBuffer() {
        ifConnected {
            if (!frameBufferUpdatesPaused.get())
                nativeRefreshFrameBuffer(nativePtr)
        }
    }

    /**
     * Change framebuffer update status.
     * If paused, client will effectively stop asking for framebuffer updates from server.
     * This will do network IO when resuming, so must not be called from Main thread.
     */
    override fun setFrameBufferUpdatesPaused(pause: Boolean) {
        stateLock.read {
            if (destroyed || !frameBufferUpdatesPaused.compareAndSet(!pause, pause))
                return

            nativePauseFramebufferUpdates(nativePtr, pause)
            refreshFrameBuffer()
        }
    }

    /**
     * Puts framebuffer contents in currently active OpenGL texture.
     * Must be called from an OpenGL ES context (i.e. from renderer thread).
     */
    override fun uploadFrameTexture() {
        ifConnected { nativeUploadFrameTexture(nativePtr) }
    }

    /**
     * Marks the uploaded texture as unallocated so the next [uploadFrameTexture] re-specifies
     * it in full. Called by the renderer when the GL context has been recreated.
     */
    override fun invalidateFrameTexture() {
        ifConnected { nativeInvalidateFrameTexture(nativePtr) }
    }

    /**
     * Upload cursor contents in currently active OpenGL texture
     */
    override fun uploadCursorTexture() {
        ifConnected { nativeUploadCursorTexture(nativePtr) }
    }

    /**
     * Release all resources allocated by the client.
     * DO NOT use this client after [cleanup].
     */
    override fun cleanup() {
        stopPerfTimer()
        stateLock.write {
            if (!destroyed) {
                nativeCleanup(nativePtr)
                connected = false
                destroyed = true
            }
        }
    }

    // ------------------------------------------------------------------
    // Performance overlay
    // ------------------------------------------------------------------

    /**
     * Starts the once-a-second report. A timer rather than a counter on the frame path,
     * because an RFB host sends nothing at all while its screen is still - a rate that
     * only moves when something moves could never show that the stream has gone quiet.
     */
    private fun startPerfTimer() {
        stopPerfTimer()
        perfWindow.reset(System.nanoTime())
        perfInputLagMs = 0f
        perfInputLagAt = 0L
        perfLastInput = 0L

        val timer = Timer("rfb-perf", true)
        // The tick's punctuality no longer reaches the numbers at all: they are measured over
        // a window of arrival times, so a late report shows the same reading late, and the
        // catch-up firing that a fixed rate does after a stalled tick only refreshes the line
        // twice in quick succession. Android's Timer has no fixed-delay variant to choose
        // instead, and it no longer matters.
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // An exception thrown out of a TimerTask cancels the whole timer, and the
                // overlay would then freeze on its last reading for the rest of the session.
                try {
                    publishPerf()
                } catch (t: Throwable) {
                    Log.w("RfbClientNative", "performance report failed", t)
                }
            }
        }, PERF_REPORT_INTERVAL_MS, PERF_REPORT_INTERVAL_MS)
        perfTimer = timer
    }

    private fun stopPerfTimer() {
        perfTimer?.cancel()
        perfTimer = null
    }

    /**
     * Formats one report out of three numbers, all measured on this side:
     *
     *  - updates per second, i.e. how often the host redrew the picture;
     *  - the gap between them, so a stall reads as a low rate *and* a long gap rather than
     *    disappearing into a smoothed average;
     *  - how long the picture took to answer the last input. That is the only end-to-end
     *    latency RFB offers: the protocol has no echo of its own, and an incremental update
     *    request is deliberately left unanswered while the remote screen is still.
     */
    private fun publishPerf() {
        if (!connected) return

        val now = System.nanoTime()
        val window = perfWindow.measure(now)

        if (perfWidth <= 0 || perfHeight <= 0) {
            val width = getFramebufferWidth()
            val height = getFramebufferHeight()
            if (width > 0 && height > 0) {
                perfWidth = width
                perfHeight = height
            }
        }

        val lagAt = perfInputLagAt
        val lag = if (lagAt != 0L && now - lagAt <= PERF_INPUT_LAG_HOLD_MS * NANOS_PER_MS)
            perfInputLagMs else 0f

        // A blend of measurements can land under a millisecond, and "input 0 ms" reads as a
        // broken meter rather than as an answered keystroke.
        val text = if (lag > 0f)
            App.appContext.getString(R.string.label_perf_overlay_rfb_lag, window.fps, window.gapMs,
                    perfWidth, perfHeight, lag.toInt().coerceAtLeast(1))
        else
            App.appContext.getString(R.string.label_perf_overlay_rfb, window.fps, window.gapMs,
                    perfWidth, perfHeight)
        observer.onPerfOverlayUpdate(text)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private inline fun <T> ifConnected(block: () -> T): T? = stateLock.tryRead {
        if (connected && !destroyed)
            block()
        else
            null
    }

    private inline fun ifConnectedAndInteractive(block: () -> Unit) {
        ifConnected {
            if (!inputDisabled.get())
                block()
        }
    }

    /**
     * Non-blocking variant of [ReentrantReadWriteLock.read].
     *
     * In normal situation, we will always be able to acquire read-lock immediately
     * because write-lock is only used to update the state. Moreover, write-lock is
     * only held for very small duration.
     *
     * But it is possible for the following situation:
     * 1. Thread A is holding read-lock to do a long running network IO (e.g. [sendCutText])
     * 2. Thread B want to acquire write-lock (most probably in [processServerMessage])
     * 3. Thread C, which can be the Main thread, wants to acquire read-lock (e.g. [moveClientPointer])
     *
     * Thread C can't acquire read-lock until both A & B are done. This can lead to
     * ANRs. [tryRead] can be used in such scenarios because if Thread B is waiting for
     * write-lock that most likely means connection has been closed, and action of
     * thread C will fail anyway.
     */
    private inline fun <T> ReentrantReadWriteLock.tryRead(block: () -> T): T? {
        if (this.readLock().tryLock(0, TimeUnit.SECONDS)) {
            try {
                return block()
            } finally {
                this.readLock().unlock()
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // JNI
    // ------------------------------------------------------------------

    private external fun nativeClientCreate(): Long
    private external fun nativeConfigure(clientPtr: Long, securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean)
    private external fun nativeInit(clientPtr: Long, host: String, port: Int): Boolean
    private external fun nativeSetDest(clientPtr: Long, host: String, port: Int)
    private external fun nativeProcessServerMessage(clientPtr: Long): Boolean
    private external fun nativeSendKeyEvent(clientPtr: Long, keySym: Int, xtCode: Int, isDown: Boolean): Boolean
    private external fun nativeSendPointerEvent(clientPtr: Long, x: Int, y: Int, mask: Int): Boolean
    private external fun nativeSendCutText(clientPtr: Long, bytes: ByteArray, isUTF8: Boolean): Boolean
    private external fun nativeIsUTF8CutTextSupported(clientPtr: Long): Boolean
    private external fun nativeSetDesktopSize(clientPtr: Long, width: Int, height: Int): Boolean
    private external fun nativeCancelDesktopSizeChange(clientPtr: Long)
    private external fun nativeRefreshFrameBuffer(clientPtr: Long): Boolean
    private external fun nativePauseFramebufferUpdates(clientPtr: Long, pause: Boolean)
    private external fun nativeGetDesktopName(clientPtr: Long): String
    private external fun nativeGetWidth(clientPtr: Long): Int
    private external fun nativeGetHeight(clientPtr: Long): Int
    private external fun nativeUploadFrameTexture(clientPtr: Long)
    private external fun nativeInvalidateFrameTexture(clientPtr: Long)
    private external fun nativeUploadCursorTexture(clientPtr: Long)
    private external fun nativeGetLastErrorStr(): String
    private external fun nativeCleanup(clientPtr: Long)

    // ------------------------------------------------------------------
    // Native callbacks (invoked from JNI - keep names & signatures in sync
    // with the GetMethodID calls in native-vnc.cpp)
    // ------------------------------------------------------------------

    @Keep
    private fun cbGetPassword() = observer.getVncPassword()

    @Keep
    private fun cbGetCredential() = observer.getVncCredentials()

    @Keep
    private fun cbVerifyServerCertificate(der: ByteArray): Boolean = try {
        val cert = ByteArrayInputStream(der).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        observer.verifyVncServerCertificate(cert as X509Certificate)
    } catch (e: Exception) {
        // Called straight from native code: a Java exception left pending here would
        // surface inside libvncclient's handshake, where it at best aborts the call with
        // an unhelpful JNI error. An unparsable certificate is simply not trusted.
        Log.w("RfbClientNative", "Server certificate could not be parsed", e)
        false
    }

    @Keep
    private fun cbGotXCutText(bytes: ByteArray, isUTF8: Boolean) {
        val cutText = ClipboardTextCodec.decode(bytes, isUTF8, strictCutText)
        if (cutText != lastCutText) {
            lastCutText = cutText
            observer.onCutTextReceived(cutText)
        }
    }

    @Keep
    private fun cbFinishedFrameBufferUpdate() {
        val arrived = System.nanoTime()
        // Stamped rather than counted, so the shape in which the updates arrived survives to
        // be measured later instead of being flattened into a total over an arbitrary span.
        perfWindow.record(arrived)

        // An update that follows an input is that input being answered. The stamp is taken
        // once, so a still picture does not keep re-reporting the same keystroke.
        val input = perfLastInput
        if (input != 0L) {
            perfLastInput = 0L
            val measured = (arrived - input).toFloat() / NANOS_PER_MS
            perfInputLagMs = if (perfInputLagMs > 0f)
                perfInputLagMs * PERF_INPUT_LAG_KEEP + measured * (1f - PERF_INPUT_LAG_KEEP)
            else
                measured
            perfInputLagAt = arrived
        }
        observer.onFramebufferUpdated()
    }

    @Keep
    private fun cbFramebufferSizeChanged(w: Int, h: Int) {
        perfWidth = w
        perfHeight = h
        observer.onFramebufferSizeChanged(w, h)
    }

    @Keep
    private fun cbBell() = observer.onBell()

    @Keep
    private fun cbHandleCursorPos(x: Int, y: Int) {
        if (!ignorePointerMovesByServer)
            moveClientPointer(x, y)
    }

    @Keep
    private fun cbHandleCursorInfo(width: Int, height: Int, xHot: Int, yHot: Int) {
        cursorInfo = CursorInfo(width, height, xHot, yHot)
        // Redraw, but do not count the redraw as a picture update. A cursor *shape* is local
        // ornament: hovering over a taskbar changes shapes several times a second while the
        // remote desktop itself stays put, and those changes used to be added to the update
        // rate, where they read as the stream having sped up.
        observer.onFramebufferUpdated()
    }

    /**
     * Native library initialization.
     *
     * Touching this class from a build without the real library (see
     * cpp/vnc_placeholder.cpp) throws right here, which is exactly what
     * [com.limelight.stream.NativeLibraryProbe] catches to disable VNC.
     */
    companion object {
        /** How often the RFB performance overlay refreshes. */
        private const val PERF_REPORT_INTERVAL_MS = 1000L

        /** How long a measured input delay stays on screen. */
        private const val PERF_INPUT_LAG_HOLD_MS = 15_000L

        /** Weight of the earlier readings in the input delay, per new measurement. */
        private const val PERF_INPUT_LAG_KEEP = 0.4f

        private const val NANOS_PER_MS = 1_000_000L

        /** Socket/handshake level failure, reported when errno says nothing. */
        private val CONNECT_FAILURE_HINT: String
            get() = App.appContext.getString(R.string.error_vnc_connect_failed)

        /**
         * Handshake worked but a server message could not be handled. Without
         * libjpeg this build cannot decode the Tight encoding, so a server that
         * only offers Tight ends up here - say so instead of showing a black screen.
         */
        private val ENCODING_FAILURE_HINT: String
            get() = App.appContext.getString(R.string.error_vnc_encoding_unsupported)

        fun loadLibrary() {
            System.loadLibrary("native-vnc")
        }

        @JvmStatic
        external fun initLibrary()

        /** Parks the C-side log switch; named exactly as the C side expects. */
        @JvmStatic
        external fun setLogEnabled(enabled: Boolean)

        init {
            loadLibrary()
            initLibrary()
            // Give the native side the current recording switch and every later one,
            // so a session on an install with logging off stays silent (see AppLogging).
            AppLogging.registerNative { setLogEnabled(it) }
        }
    }
}
