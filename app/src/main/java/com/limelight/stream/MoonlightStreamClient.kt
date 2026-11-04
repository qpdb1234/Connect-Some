package com.limelight.stream

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.limelight.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.limelight.R
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concrete [StreamClient] implemented on top of Moonlight's real
 * `NvConnection` + `MediaCodecDecoderRenderer` + `AndroidAudioRenderer` stack.
 *
 * Design notes:
 *  - Everything that touches [MoonBridge] is guarded behind [NativeLibraryProbe].
 *    If `libmoonlight-core.so` (built from the moonlight-common-c submodule) is
 *    not present, the client reports a graceful failure instead of crashing on
 *    [UnsatisfiedLinkError].
 *  - [connect] performs the HTTPS pair check via [NvHTTP], then hands the real
 *    work to [NvConnection.start], whose [NvConnectionListener] callbacks are
 *    translated onto [StreamClient.Observer] for the session / UI layers.
 *  - Input methods forward to the corresponding [NvConnection] send* calls.
 */
class MoonlightStreamClient(
    private val appContext: Context,
    private val observer: StreamClient.Observer
) : StreamClient {

    companion object {
        private const val TAG = "MoonlightStreamClient"
        private const val DEFAULT_HTTPS_PORT = 47984
        // Plaintext GameStream HTTP endpoint (serverinfo / pairing). NvConnection and
        // NvHTTP both derive their http base from the address tuple's port, so it must
        // be the HTTP port (47989), NOT the HTTPS port - otherwise the initial
        // serverinfo probe sends plaintext to the TLS listener and the host drops it.
        private const val DEFAULT_HTTP_PORT = 47989
        // Upper bound for a single (async) connection attempt to reach the streaming
        // state before we treat it as failed and try the cancel+relaunch fallback.
        private const val CONNECT_ATTEMPT_TIMEOUT_SEC = 25L
        // How many times the cancel+relaunch fallback repeats after the initial attempt.
        private const val MAX_CONNECT_RETRIES = 3
        // Pause before each retry so the host can finish shutting down the RTSP server of
        // the session it is tearing down. That teardown is asynchronous, and relaunching
        // immediately reuses the dead session's port - the handshake then dies within a few
        // milliseconds with "Failed to decrypt RTSP response". Escalating waits also cover a
        // host that is busy and slow to quit.
        private val RETRY_BACKOFF_MS = longArrayOf(2000L, 4000L, 6000L)
        // How often the wait in [startAttempt] re-checks for a cancellation while the
        // connection thread is blocked on the attempt's latch.
        private const val RETRY_POLL_INTERVAL_MS = 100L
        // Upper bound for how long we wait for the GL layer to deliver its decoded-video
        // render surface before starting the connection. MediaCodec binds its output surface
        // only once at configure(); without a surface it decodes headless (black video).
        private const val VIDEO_SURFACE_TIMEOUT_MS = 3000L
    }

    // Per-attempt gating used by [startAttempt]: the async [NvConnection.start] is awaited
    // through a latch owned by that attempt's own [AttemptListener], [attemptSucceeded]
    // records whether streaming started, and [suppressFailurePropagation] hides
    // *intermediate* setup failures from the observer so a retriable first attempt does not
    // tear down the session layer before we retry.
    @Volatile private var attemptSucceeded = false
    @Volatile private var suppressFailurePropagation = false

    /**
     * Identifies the one connection attempt whose callbacks still mean something.
     * [startAttempt] advances it, [stop] advances it without starting anything else, and that
     * is how a callback which arrives late - from an attempt that timed out and is still
     * running natively, or from the connection the user just disconnected - is recognised as
     * one that must no longer touch [observer], [isConnected] or
     * [suppressFailurePropagation]. With a single shared listener those stale events used to
     * release the *next* attempt's wait early, declare a live stream dead, or bring back a
     * session nobody asked for.
     */
    private val attemptGeneration = AtomicInteger(0)

    /**
     * Set once [stop] runs, so the retry loop on the connection thread gives up instead of
     * fighting over a session the UI has already abandoned.
     */
    @Volatile private var connectCancelled = false

    // ---- internal Moonlight objects ----
    private var nvConnection: NvConnection? = null
    private var videoRenderer: MediaCodecDecoderRenderer? = null

    // Built on the connection thread, touched by the mute button on the main thread.
    @Volatile
    private var audioRenderer: AndroidAudioRenderer? = null

    /**
     * Playback mute requested by the UI, kept here because the renderer comes and goes with
     * every connection attempt (and the retry that follows a failed resume).
     */
    @Volatile
    private var audioMuted = false

    private var config: StreamConfig = StreamConfig()
    private val isConnected = AtomicBoolean(false)
    private val inputDisabled = AtomicBoolean(false)

    /**
     * Guarantees the underlying [NvConnection] is torn down (and, critically, that its
     * process-wide `connectionAllowed` semaphore is released) at most once per client,
     * regardless of how many cleanup passes reach [stop]. See [stop].
     */
    private val connectionStopped = AtomicBoolean(false)

    /**
     * Last button mask seen by [sendPointerEvent]. AVNC reports the *cumulative* pointer
     * button state on every event, whereas moonlight-common-c uses discrete press/release
     * packets - so we diff against this to emit only the transitions. Read as
     * read-modify-write by [applyButtonMaskDiff], hence the atomic: two pointer sources
     * (touch and the mini window's cursor, say) interleaving on a plain field would drop a
     * release and leave a button held down on the host until the next event.
     */
    private val lastPointerButtonMask = AtomicInteger(0)

    private var host: String = ""
    private var httpsPort: Int = DEFAULT_HTTPS_PORT
    private var currentAppId: Int = 0
    private var desktopName: String = ""
    private var fbWidth: Int = 0
    private var fbHeight: Int = 0

    private var renderTarget: SurfaceHolder? = null
    @Volatile
    private var videoSurface: Surface? = null
    private var hostActivity: Activity? = null
    private var serverCertDer: ByteArray? = null
    private var clientUniqueId: String = ""

    // ------------------------------------------------------------------
    // Renderer attachment (set by the session layer before connect)
    // ------------------------------------------------------------------

    fun attachRenderer(activity: Activity?, holder: SurfaceHolder?) {
        hostActivity = activity
        renderTarget = holder
    }

    fun setServerCertificateDer(der: ByteArray?) { serverCertDer = der }

    fun setClientUniqueId(id: String) { clientUniqueId = id }

    /**
     * Silence (or restore) remote audio on this phone only. See
     * [com.limelight.vnc.VncClient.audioMuted] - this is not the media volume, and the host
     * keeps streaming either way.
     */
    fun setAudioMuted(muted: Boolean) {
        audioMuted = muted
        audioRenderer?.setMuted(muted)
    }

    // ------------------------------------------------------------------
    // StreamClient implementation
    // ------------------------------------------------------------------

    override fun configure(config: StreamConfig) {
        this.config = config
        Log.i(TAG, "Configured: ${config.width}x${config.height}@${config.fps} ${config.bitrateKbps}kbps")
    }

    override fun connect(host: String, httpsPort: Int, appId: Int) {
        this.host = host
        this.httpsPort = if (httpsPort > 0) httpsPort else DEFAULT_HTTPS_PORT
        this.currentAppId = appId
        // Fresh session: no buttons are held from a previous connection, and a previous
        // connection's cancellation must not carry over to this one.
        lastPointerButtonMask.set(0)
        connectCancelled = false

        // Native protocol library is a hard requirement for GameStream / Sunshine.
        if (!NativeLibraryProbe.isNativeAvailable()) {
            Thread {
                observer.onDisplayMessage(
                        appContext.getString(R.string.error_native_library_missing))
                observer.onStageFailed("NativeLibrary", 0, -1)
                observer.onConnectionTerminated(-1)
            }.start()
            return
        }

        Thread {
            try {
                runRealConnection()
            } catch (t: Throwable) {
                Log.e(TAG, "Connect failed", t)
                observer.onDisplayMessage(t.message ?: appContext.getString(R.string.error_connect_failed))
                observer.onStageFailed(host, 0, -1)
                observer.onConnectionTerminated(-1)
            }
        }.start()
    }

    private fun runRealConnection() {
        val activity = hostActivity
            ?: throw IllegalStateException("Host activity not attached")
        val holder = renderTarget
        // The decoded-video render target is produced asynchronously by the GL surface
        // (FrameView -> SurfaceTexture -> setVideoSurface). MediaCodec captures its output
        // surface exactly once, at configure() time, and never re-binds it afterwards, so we
        // must not start the connection until that surface exists - otherwise the decoder
        // runs headless (byte-buffer mode): frames decode fine but nothing reaches the
        // screen, i.e. a permanently black stream.
        awaitVideoSurface()
        val surface = videoSurface
        // The render target may still be null here: when video is routed through a GL
        // SurfaceTexture (external OES), the surface is produced on the GL thread and
        // delivered via setVideoSurface() before the decoder is configured. We therefore
        // only require an activity at connect time.

        observer.onStageStarting("Handshake")

        val crypto = AndroidCryptoProvider(appContext)
        val uniqueId = clientUniqueId.ifBlank { crypto.getPemEncodedClientCertificate().hashCode().toString() }
        // Address tuple uses the HTTP port; httpsPort is passed separately so NvHTTP /
        // NvConnection build the correct TLS URL for post-pairing HTTPS calls.
        val address = ComputerDetails.AddressTuple(host, DEFAULT_HTTP_PORT)
        val serverCert = decodeServerCert(serverCertDer)

        // Pairing / server check over HTTPS (this is the "Handshake" stage).
        val http = NvHTTP(address, httpsPort, uniqueId, serverCert, crypto)
        val serverInfo = http.getServerInfo(true)
        if (http.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
            observer.onDisplayMessage(appContext.getString(R.string.error_host_not_paired))
            observer.onStageFailed("Handshake", 0, 0)
            observer.onConnectionTerminated(-1)
            return
        }
        observer.onStageComplete("Handshake")

        // Resolve the app to launch. Sunshine assigns large-integer app ids; appid=0
        // (never picked in the editor) is invalid there and makes the host terminate the
        // session before sending any frame (Server notified termination reason 0x80030023).
        // When no app was chosen, query the host's app list and prefer a "Desktop" entry so
        // that "pair -> connect" works out of the box. We also capture the app's real name:
        // NvConnection uses it verbatim as the launch stage label, so the connecting overlay
        // shows a meaningful title instead of a leftover placeholder (was hardcoded to
        // "Steam").
        val apps = runCatching { http.getAppList() }.getOrDefault(emptyList())
        var effectiveAppId = currentAppId
        var resolvedAppName: String? = null
        if (effectiveAppId == 0) {
            val preferred = apps.firstOrNull {
                it.appName.contains("desktop", ignoreCase = true)
            } ?: apps.firstOrNull()
            effectiveAppId = preferred?.appId ?: 0
            resolvedAppName = preferred?.appName
            if (effectiveAppId == 0) {
                observer.onDisplayMessage(appContext.getString(R.string.error_host_no_apps))
                observer.onStageFailed("Launch", 0, 0)
                observer.onConnectionTerminated(-1)
                return
            }
            Log.i(TAG, "No app selected; auto-launching app id $effectiveAppId (${resolvedAppName ?: "unknown"})")
        } else {
            resolvedAppName = apps.firstOrNull { it.appId == effectiveAppId }?.appName
        }
        val appName = resolvedAppName?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.name_desktop_app)

        // Build Moonlight's StreamConfiguration from our protocol-agnostic config.
        val streamConfig = StreamConfiguration.Builder()
                .setApp(NvApp(appName, effectiveAppId, config.enableHdr))
                .setResolution(config.width, config.height)
                .setRefreshRate(config.fps)
                .setBitrate(config.bitrateKbps)
                .setEnableSops(config.enableSops)
                .enableLocalAudioPlayback(config.playLocalAudio)
                .setMaxPacketSize(config.maxPacketSize)
                .setAudioConfiguration(toMoonAudio(config.audioConfiguration))
                .setSupportedVideoFormats(config.supportedVideoFormats)
                .setRemoteConfiguration(toMoonRemote(config.remoteStreaming))
                .setColorRange(toMoonColorRange(config.colorRange))
                .setColorSpace(toMoonColorSpace(config.colorSpace))
                .setPersistGamepadsAfterDisconnect(config.persistGamepadsAfterDisconnect)
                .build()

        val prefs = PreferenceConfiguration.readPreferences(appContext)
        prefs.width = config.width
        prefs.height = config.height
        prefs.fps = config.fps
        // Always collect per-second performance metrics (network latency, FPS, dropped
        // frames, decode time) so the stats overlay can be toggled on/off instantly
        // without reconnecting. The UI layer decides whether to actually show them; the
        // per-second cost of building this string is negligible. The renderer requires a
        // non-null PerfOverlayListener whenever enablePerfOverlay is set, so both are
        // turned on together here.
        prefs.enablePerfOverlay = true

        // MediaCodecHelper populates the per-device codec whitelist/blacklist and MUST be
        // initialized before MediaCodecDecoderRenderer probes for a decoder, otherwise it
        // throws IllegalStateException("MediaCodecHelper must be initialized before use").
        // Moonlight caches the GL renderer string via GlPreferences; that probe is not part
        // of this port, so an empty renderer is used (it only skips a few device-specific
        // codec quirks and does not affect basic decoder selection). initialize() is idempotent.
        val glRenderer = ""
        MediaCodecHelper.initialize(appContext, glRenderer)

        videoRenderer = MediaCodecDecoderRenderer(
                activity, prefs, /* crashListener = */ null, /* consecutiveCrashCount = */ 0,
                /* meteredData = */ false, config.enableHdr, glRenderer,
                /* perfListener = */ PerfOverlayListener { text -> observer.onPerfOverlayUpdate(text) }).apply {
            // Prefer the direct GL SurfaceTexture surface when one was provided.
            if (surface != null) setRenderTarget(surface) else setRenderTarget(holder)
        }
        audioRenderer = AndroidAudioRenderer(appContext, prefs.enableAudioFx).apply {
            // The button can be pressed before the stream is up, or while a reconnect is
            // rebuilding this renderer - hand it the state the session is already in.
            setMuted(audioMuted)
        }

        // First attempt: let moonlight-common-c launch a new session or resume the one
        // already running on the host. Resuming keeps the live desktop, avoiding a needless
        // cancel which can drop the host onto a lock/black screen.
        //
        // Only if that attempt fails before streaming starts - the resumed RTSP handshake
        // can fail with "Rejecting partial encrypted RTSP message" against a stale/foreign
        // session - do we cancel the host session and relaunch cleanly. Intermediate failures
        // are suppressed so they do not tear down the session layer; an attempt that does
        // reach streaming clears the flag itself (see [connectionStarted]).
        suppressFailurePropagation = true
        var established = startAttempt(address, httpsPort, uniqueId, streamConfig, crypto, serverCert)

        // Quitting alone is not enough: the host answers on the same RTSP port until it has
        // finished tearing the old session down, so each retry waits before relaunching.
        var retry = 0
        while (!established && retry < MAX_CONNECT_RETRIES) {
            retry++
            Log.i(TAG, "Connection attempt $retry did not reach streaming; " +
                    "cancelling host session and relaunching cleanly")
            runCatching { http.quitApp() }
                    .onFailure { Log.w(TAG, "quitApp before retry failed: ${it.message}") }
            if (!awaitHostTeardown(RETRY_BACKOFF_MS[retry - 1])) {
                Log.i(TAG, "Reconnect retries aborted: connection was cancelled")
                break
            }
            established = startAttempt(address, httpsPort, uniqueId, streamConfig, crypto, serverCert)
        }
        suppressFailurePropagation = false

        // Safety net against a permanent "connecting" hang: if no attempt reached the
        // streaming state but the kernel never emitted a terminal event (e.g. every attempt
        // timed out silently because the host is unreachable / wedged), the hosting
        // RemoteSession would keep blocking on its latch until its 1-hour timeout and the UI
        // would sit on the connecting overlay forever. Surface a terminal failure so the
        // session layer unwinds and auto-reconnect can take over. If a real terminal
        // callback already fired, this is a harmless no-op (the completion latch is one-shot).
        if (!established && !isConnected.get()) {
            Log.w(TAG, "No connection attempt reached streaming; surfacing a terminal failure")
            observer.onStageFailed(host, 0, -1)
            observer.onConnectionTerminated(-1)
        }
    }

    /**
     * Pauses the connection thread while the host shuts down the session we just asked it to
     * quit. Returns false when the wait was cut short because [stop] ran (or the thread was
     * interrupted), in which case the caller must not launch another attempt.
     */
    private fun awaitHostTeardown(millis: Long): Boolean {
        var remaining = millis
        while (remaining > 0 && !connectCancelled) {
            val slice = minOf(200L, remaining)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !connectCancelled
    }

    /**
     * Blocks until a decoded-video [videoSurface] has been delivered by the GL layer (see
     * [setVideoSurface]) or [VIDEO_SURFACE_TIMEOUT_MS] elapses. The hosting [FrameView] is a
     * GLSurfaceView whose SurfaceTexture is created asynchronously once the activity is laid
     * out; that can lag behind a fast resume/launch. Waiting here guarantees the decoder is
     * configured with a real output surface so decoded frames are actually shown.
     */
    private fun awaitVideoSurface() {
        if (videoSurface != null) {
            Log.i(TAG, "Video surface already available before connect")
            return
        }
        val start = SystemClock.uptimeMillis()
        val deadline = start + VIDEO_SURFACE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (videoSurface != null) {
                Log.i(TAG, "Video surface delivered after ${SystemClock.uptimeMillis() - start} ms")
                return
            }
            try {
                Thread.sleep(25)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (videoSurface == null) {
            Log.w(TAG, "Timed out waiting for video surface; decoder may render headless (black video)")
        }
    }

    /**
     * Runs one blocking connection attempt through [NvConnection.start] and waits until it
     * either reaches the streaming state (returns true) or fails during setup / times out
     * (returns false). A terminal failure is surfaced to the observer by [AttemptListener]
     * only when [suppressFailurePropagation] is clear, so a retriable attempt stays silent.
     */
    private fun startAttempt(
            address: ComputerDetails.AddressTuple,
            httpsPort: Int,
            uniqueId: String,
            streamConfig: StreamConfiguration,
            crypto: AndroidCryptoProvider,
            serverCert: X509Certificate?,
    ): Boolean {
        val generation = attemptGeneration.incrementAndGet()
        val gate = CountDownLatch(1)
        attemptSucceeded = false

        // A previous attempt that timed out has not gone away: its thread is still working
        // through the HTTP launch and, once it gets there, the RTSP handshake. Interrupting it
        // here does two things that replacing the field alone does not - it stops that thread
        // from taking the process-wide connection permit this attempt needs, and it makes
        // [NvConnection] abandon the start before it touches the shared native bridge, which
        // would otherwise give the host two connections launched one after the other while the
        // UI only knows about the second.
        runCatching { nvConnection?.stop() }

        nvConnection = NvConnection(appContext, address, httpsPort, uniqueId, streamConfig, crypto, serverCert)
        // start() is asynchronous and drives NvConnectionListener callbacks.
        nvConnection?.start(audioRenderer, videoRenderer, AttemptListener(generation, gate))

        // Polled rather than one long await so that a disconnect during the attempt is noticed
        // straight away instead of after the whole timeout budget; the wait also ends as soon
        // as the listener releases [gate].
        val deadline = SystemClock.uptimeMillis() + CONNECT_ATTEMPT_TIMEOUT_SEC * 1000L
        while (SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            if (gate.await(RETRY_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
        }
        return attemptSucceeded
    }

    /**
     * Callbacks of a single connection attempt, identified by [generation]. Every one of them
     * is conditional on that generation still being current: an attempt that timed out keeps
     * running natively for a while longer, and its events landing on top of the next attempt
     * would release that attempt's wait early, flip [isConnected] or
     * [suppressFailurePropagation] from under it, or tell the UI that the stream it just
     * established had ended.
     */
    private inner class AttemptListener(
            private val generation: Int,
            private val gate: CountDownLatch,
    ) : NvConnectionListener {
        private val isCurrent: Boolean get() = generation == attemptGeneration.get()

        override fun stageStarting(stage: String) {
            if (isCurrent) observer.onStageStarting(stage)
        }

        override fun stageComplete(stage: String) {
            if (isCurrent) observer.onStageComplete(stage)
        }

        override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
            // A setup-phase failure. Forward it to the observer (which ends the session) only
            // when this is not a retriable intermediate attempt; either way release the gate
            // so [startAttempt] can return.
            if (!isCurrent) return
            if (!suppressFailurePropagation) observer.onStageFailed(stage, portFlags, errorCode)
            gate.countDown()
        }

        override fun connectionStarted() {
            if (!isCurrent) return
            isConnected.set(true)
            attemptSucceeded = true
            // Streaming is live; from here on, terminations are real and must reach the UI.
            suppressFailurePropagation = false
            desktopName = host
            fbWidth = config.width
            fbHeight = config.height
            observer.onFramebufferSizeChanged(fbWidth, fbHeight)
            observer.onConnectionStarted()
            gate.countDown()
        }

        override fun connectionTerminated(errorCode: Int) {
            if (!isCurrent) return
            isConnected.set(false)
            if (!suppressFailurePropagation) observer.onConnectionTerminated(errorCode)
            gate.countDown()
        }

        override fun connectionStatusUpdate(status: Int) {
            if (isCurrent) observer.onConnectionStatusUpdate(status)
        }

        override fun displayMessage(message: String) {
            if (isCurrent) observer.onDisplayMessage(message)
        }

        override fun displayTransientMessage(message: String) {
            if (isCurrent) observer.onDisplayTransientMessage(message)
        }

        override fun rumble(c: Short, low: Short, high: Short) {
            if (isCurrent) observer.onRumble(c, low, high)
        }

        override fun rumbleTriggers(c: Short, lt: Short, rt: Short) {
            if (isCurrent) observer.onRumbleTriggers(c, lt, rt)
        }

        override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
            if (isCurrent) observer.onHdrModeChanged(enabled, hdrMetadata)
        }

        override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {}
        override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {}
    }

    override fun processServerMessage() {
        if (!isConnected.get()) throw java.io.IOException("Not connected")
        // Moonlight is callback-driven; nothing to poll.
    }

    override fun stop() {
        isConnected.set(false)
        // Also aborts a pending cancel+relaunch retry loop (see [awaitHostTeardown]).
        connectCancelled = true
        // Retires the current attempt's callbacks. Without this, the teardown we are asking
        // for produces a connectionStarted()/connectionTerminated() pair some time later on
        // the native callback thread, which would set [isConnected] back to true on a session
        // the user already left - after that every input call is accepted and quietly thrown
        // away against a bridge that no longer exists.
        attemptGeneration.incrementAndGet()
        // NvConnection.stop() is the ONLY place that releases the process-wide
        // `connectionAllowed` semaphore acquired right before MoonBridge.startConnection().
        // It must run even after a *passive* disconnect: when the host drops the session
        // while we are backgrounded, connectionTerminated has already flipped isConnected
        // to false, so an early return here would leak the permit and every subsequent
        // NvConnection.start() would block forever on acquire() - the UI hangs on the
        // "connecting" stage with no timeout. Guarded to fire at most once so repeated
        // cleanup passes do not stack extra releases.
        if (connectionStopped.compareAndSet(false, true)) {
            runCatching { nvConnection?.stop() }
        }
    }

    override fun cleanup() {
        stop()
        // Halt the renderer's threads before handing back the codec. Native pairs
        // bridgeDrStop() with bridgeDrCleanup() for this reason: releasing the MediaCodec
        // while the render thread is still dequeuing from it makes that thread hit an
        // IllegalStateException and schedule a decoder restart in the middle of teardown.
        // Both calls are idempotent, so native running them again later is harmless.
        runCatching { videoRenderer?.stop() }
        runCatching { videoRenderer?.cleanup() }
        runCatching { audioRenderer?.cleanup() }
        // Deliberately no MoonBridge.cleanupBridge() here: NvConnection.stop() (run by
        // [stop] above, and by the retry loop in [startAttempt]) already performs
        // stopConnection + cleanupBridge as a pair inside a synchronized(MoonBridge.class)
        // block. Calling cleanupBridge again from here would do it *outside* that lock,
        // where it can cut into a still-running connection thread's startConnection
        // critical section and tear down the native bridge mid-handshake.
        nvConnection = null
        videoRenderer = null
        audioRenderer = null
        renderTarget = null
        Log.i(TAG, "Cleaned up")
    }

    override val connected: Boolean
        get() = isConnected.get()

    override fun getDesktopName(): String = desktopName
    override fun getFramebufferWidth(): Int = fbWidth
    override fun getFramebufferHeight(): Int = fbHeight

    // ---- Input ----

    override fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean) {
        if (!connected || inputDisabled.get()) return
        // Legacy RFB-style path. Only usable when a real hardware scan code is available
        // (xtCode != 0); soft keyboards never populate it, so text goes through
        // [sendKeyboardScancode] / [sendUtf8Text] instead. Direction uses Moonlight's
        // KEY_DOWN/KEY_UP constants (1/0 are not valid values here).
        if (xtCode == 0) return
        nvConnection?.sendKeyboardInput(
                xtCode.toShort(),
                if (isDown) com.limelight.nvstream.input.KeyboardPacket.KEY_DOWN
                else com.limelight.nvstream.input.KeyboardPacket.KEY_UP,
                0,
                0)
    }

    override fun sendKeyboardScancode(scancode: Short, keyDirection: Byte, modifier: Byte, flags: Byte) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendKeyboardInput(scancode, keyDirection, modifier, flags)
    }

    override fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (!connected || inputDisabled.get()) return
        moveClientPointer(x, y)
        val w = if (fbWidth > 0) fbWidth else config.width
        val h = if (fbHeight > 0) fbHeight else config.height
        // Absolute pointer position, scaled to the remote resolution.
        nvConnection?.sendMousePosition(x.toShort(), y.toShort(), w.toShort(), h.toShort())
        // AVNC packs the (cumulative) button state into a bitmask on every pointer event,
        // while moonlight-common-c models buttons as discrete press/release packets plus
        // dedicated wheel clicks. Diff against the previous mask and emit the transitions;
        // without this, clicks are silently dropped and touch / touchpad input appears to
        // do nothing (the cursor moves but never presses).
        applyButtonMaskDiff(buttonMask)
    }

    private fun applyButtonMaskDiff(mask: Int) {
        // getAndSet rather than a read and a later write: pointer events do not necessarily
        // all come from one thread, and an interleaving that loses the previous value drops a
        // button release - the host then thinks a button is still held down.
        val prev = lastPointerButtonMask.getAndSet(mask)
        transitionButton(prev, mask, PointerButton.Left, MouseButtonPacket.BUTTON_LEFT)
        transitionButton(prev, mask, PointerButton.Middle, MouseButtonPacket.BUTTON_MIDDLE)
        transitionButton(prev, mask, PointerButton.Right, MouseButtonPacket.BUTTON_RIGHT)
        // Wheel "buttons" are momentary in AVNC (a down immediately followed by an up per
        // notch); translate the rising edge into a single scroll click. Sign matches
        // moonlight-common-c where a positive value scrolls up / right.
        fireWheel(prev, mask, PointerButton.WheelUp) { nvConnection?.sendMouseScroll(1) }
        fireWheel(prev, mask, PointerButton.WheelDown) { nvConnection?.sendMouseScroll(-1) }
        fireWheel(prev, mask, PointerButton.WheelRight) { nvConnection?.sendMouseHScroll(1) }
        fireWheel(prev, mask, PointerButton.WheelLeft) { nvConnection?.sendMouseHScroll(-1) }
    }

    private fun transitionButton(prev: Int, mask: Int, button: PointerButton, moonButton: Byte) {
        val bit = button.bitMask
        val wasDown = prev and bit != 0
        val isDown = mask and bit != 0
        if (!wasDown && isDown) nvConnection?.sendMouseButtonDown(moonButton)
        else if (wasDown && !isDown) nvConnection?.sendMouseButtonUp(moonButton)
    }

    private inline fun fireWheel(prev: Int, mask: Int, button: PointerButton, action: () -> Unit) {
        val bit = button.bitMask
        if (prev and bit == 0 && mask and bit != 0) action()
    }

    override fun sendMouseMove(deltaX: Short, deltaY: Short) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendMouseMove(deltaX, deltaY)
    }

    override fun sendMousePosition(x: Short, y: Short, referenceWidth: Short, referenceHeight: Short) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendMousePosition(x, y, referenceWidth, referenceHeight)
    }

    override fun sendMouseButtonDown(button: Byte) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendMouseButtonDown(button)
    }

    override fun sendMouseButtonUp(button: Byte) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendMouseButtonUp(button)
    }

    override fun sendMouseScroll(scrollClicks: Byte) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendMouseScroll(scrollClicks)
    }

    override fun sendMouseHighResScroll(scrollAmount: Short) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendMouseHighResScroll(scrollAmount)
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
        rightStickY: Short
    ) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendControllerInput(
                controllerNumber, activeGamepadMask, buttonFlags.toInt(),
                leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY)
    }

    override fun sendUtf8Text(text: String) {
        if (!connected || inputDisabled.get()) return
        nvConnection?.sendUtf8Text(text)
    }

    override fun moveClientPointer(x: Int, y: Int) {
        observer.onPointerMoved(x, y)
    }

    override fun setInputDisabled(disabled: Boolean) {
        inputDisabled.set(disabled)
    }

    // ---- Video ----

    override fun setFrameBufferUpdatesPaused(paused: Boolean) {
        // Decoder keeps running; pausing rendering is handled by the renderer foreground flags.
    }

    override fun refreshFrameBuffer() {
        // Moonlight requests an IDR internally on decode errors; no explicit client API.
    }

    override fun setVideoSurface(surface: Surface?) {
        Log.i(TAG, "setVideoSurface(${if (surface != null) "non-null" else "null"}), renderer=${if (videoRenderer != null) "ready" else "pending"}")
        videoSurface = surface
        if (surface != null) videoRenderer?.setRenderTarget(surface)
    }

    /**
     * The mini window brings a GL surface of its own. Rebuilding the GameStream connection
     * for that means a second RTSP handshake seconds after the first one was torn down,
     * which hosts routinely refuse - it ended in a black mini window and a dead session.
     * The decoder can be re-pointed instead, so the stream itself never stops.
     */
    fun swapVideoSurface(surface: Surface): Boolean {
        if (!isConnected.get()) return false
        val swapped = videoRenderer?.swapRenderTarget(surface) == true
        Log.i(TAG, "swapVideoSurface: renderer ${if (swapped) "re-pointed at the new surface" else "not live"}")
        return swapped
    }

    override fun setRenderTarget(holder: SurfaceHolder?) {
        renderTarget = holder
        if (holder != null) videoRenderer?.setRenderTarget(holder)
    }

    // ---- Pairing ----

    override fun getPairState(): PairState {
        if (!NativeLibraryProbe.isNativeAvailable()) return PairState.NOT_PAIRED
        return try {
            val crypto = AndroidCryptoProvider(appContext)
            val uniqueId = clientUniqueId.ifBlank { crypto.getPemEncodedClientCertificate().hashCode().toString() }
            val http = NvHTTP(ComputerDetails.AddressTuple(host, DEFAULT_HTTP_PORT), httpsPort, uniqueId,
                    decodeServerCert(serverCertDer), crypto)
            if (http.getPairState() == PairingManager.PairState.PAIRED) PairState.PAIRED else PairState.NOT_PAIRED
        } catch (t: Throwable) {
            Log.w(TAG, "getPairState failed: ${t.message}")
            PairState.NOT_PAIRED
        }
    }

    override fun startPairing(pin: String) {
        if (!NativeLibraryProbe.isNativeAvailable()) {
            observer.onDisplayMessage(appContext.getString(R.string.error_native_library_missing_pairing))
            return
        }
        Thread {
            try {
                val crypto = AndroidCryptoProvider(appContext)
                val uniqueId = clientUniqueId.ifBlank { crypto.getPemEncodedClientCertificate().hashCode().toString() }
                val http = NvHTTP(ComputerDetails.AddressTuple(host, DEFAULT_HTTP_PORT), httpsPort, uniqueId,
                        decodeServerCert(serverCertDer), crypto)
                val pm = PairingManager(http, crypto)
                val state = pm.pair(http.getServerInfo(true), pin)
                observer.onDisplayMessage(if (state == PairingManager.PairState.PAIRED)
                    appContext.getString(R.string.msg_pairing_success)
                else appContext.getString(R.string.msg_pairing_failed_state, state))
            } catch (t: Throwable) {
                Log.e(TAG, "Pairing failed", t)
                observer.onDisplayMessage(appContext.getString(R.string.msg_pairing_exception, t.message))
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun decodeServerCert(der: ByteArray?): X509Certificate? {
        if (der == null) return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decode pinned server cert", e)
            null
        }
    }

    private fun toMoonAudio(c: StreamConfig.AudioConfig): MoonBridge.AudioConfiguration = when (c) {
        StreamConfig.AudioConfig.STEREO -> MoonBridge.AUDIO_CONFIGURATION_STEREO
        StreamConfig.AudioConfig.SURROUND_5_1 -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
        StreamConfig.AudioConfig.SURROUND_7_1 -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
    }

    private fun toMoonRemote(m: StreamConfig.RemoteMode): Int = when (m) {
        StreamConfig.RemoteMode.LOCAL -> StreamConfiguration.STREAM_CFG_LOCAL
        StreamConfig.RemoteMode.REMOTE -> StreamConfiguration.STREAM_CFG_REMOTE
        StreamConfig.RemoteMode.AUTO -> StreamConfiguration.STREAM_CFG_AUTO
    }

    private fun toMoonColorRange(r: StreamConfig.ColorRange): Int = when (r) {
        StreamConfig.ColorRange.LIMITED -> MoonBridge.COLOR_RANGE_LIMITED
        StreamConfig.ColorRange.FULL -> MoonBridge.COLOR_RANGE_FULL
    }

    private fun toMoonColorSpace(s: StreamConfig.ColorSpace): Int = when (s) {
        StreamConfig.ColorSpace.REC_601 -> MoonBridge.COLORSPACE_REC_601
        StreamConfig.ColorSpace.REC_709 -> MoonBridge.COLORSPACE_REC_709
        StreamConfig.ColorSpace.REC_2020 -> MoonBridge.COLORSPACE_REC_2020
    }
}
