/*
 * Android-device protocol: implementation of the [VncClient] facade against a
 * phone/tablet driven over ADB.
 *
 * The wire is not RFB at all - it is the "scrcpy" control protocol spoken by
 * [com.limelight.androidserver], a helper this app pushes to the target device
 * and starts with `app_process`. What makes it fit the existing architecture
 * without a single `if (protocol == ...)` in the UI is that the facade's
 * *shape* is broad enough: video arrives as H.264/H.265 into a [Surface]
 * (exactly like GameStream), so [RenderMode.SURFACE] and the whole GL path are
 * reused untouched, and the input methods are simply the ones a touchscreen
 * device understands ([AndroidControlExtras]).
 *
 * Structure of a session, top to bottom:
 *
 *  1. [connect] authenticates over ADB (USB-OTG or TCP), pushes the helper jar
 *     if needed, starts the resident RPC process and one encoder process, then
 *     opens *two* `localabstract:connectsome_scrcpy` channels - the helper
 *     accepts them in order: main first, video second.
 *  2. Three threads carry the session: [videoLoop] (header + frames into
 *     MediaCodec), [mainLoop] (audio, clipboard, size changes, heartbeats) and
 *     [heartbeatLoop] (keep-alive + death detection).
 *  3. Everything the user does goes out over the main channel as one of the
 *     big-endian control packets described in [writeControl].
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */
package com.limelight.vnc

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.limelight.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.StringRes
import com.limelight.App
import com.limelight.R
import com.limelight.adb.Adb
import com.limelight.adb.AdbManager
import com.limelight.adb.AdbTransport
import com.limelight.adb.BufferStream
import com.limelight.model.ServerProfile
import com.limelight.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ceiling for one frame dimension, matching the native side's framebuffer bound.
 *
 * The header's width and height are handed straight to MediaFormat, which multiplies
 * them into buffer sizes and (here) into the `MAX_WIDTH`/`MAX_HEIGHT` capacity. No
 * phone screen approaches this number, so accepting a larger one only means trusting
 * a length prefix from the wire with an allocator behind it.
 *
 * File-level because it is needed by [AndroidVideoDecoder] as well, and the companion
 * of [AndroidClient] is private to that class - not merely to the file.
 */
private const val MAX_VIDEO_DIMENSION = 16384

class AndroidClient(
        private val profile: ServerProfile,
        private val observer: VncClient.Observer,
        private val onConnectionStarted: () -> Unit,
        private val onConnectionTerminated: (errorCode: Int) -> Unit,
) : VncClient, VideoSink, AndroidControlExtras {

    private companion object {
        const val TAG = "ConnectSomeAndroid"

        /** Control packet types, outbound (us -> helper). */
        const val PACKET_TOUCH = 1
        const val PACKET_KEY = 2
        const val PACKET_CLIPBOARD = 3
        const val PACKET_KEEP_ALIVE = 4
        const val PACKET_CONFIG = 5
        const val PACKET_ROTATE = 6
        const val PACKET_LIGHT = 7
        const val PACKET_POWER = 8
        const val PACKET_NIGHT = 9

        /** Event types, inbound on the main channel. */
        const val EVENT_AUDIO = 2
        const val EVENT_CLIPBOARD = 3
        const val EVENT_SIZE = 4
        const val EVENT_KEEP_ALIVE = 5

        /** The helper answers a keep-alive every so often; silence for this long is death. */
        const val HEARTBEAT_INTERVAL_MS = 1500L
        const val TIMEOUT_MS = 5_000L

        /**
         * The encoder process needs a moment to boot before its socket exists, so the two
         * channels are opened against a deadline rather than a fixed number of tries.
         * Generous enough for a cold `app_process` start on a slow vendor ROM, short enough
         * that a device which is never going to answer gets reported instead of waited on.
         *
         * It is deliberately much longer than the helper's own five second patience, which
         * is not a contradiction: that one is survived by starting another helper (see
         * [ENCODER_DEATH_MARKERS]) rather than by waiting for a socket nobody binds.
         */
        const val SOCKET_WAIT_BUDGET_MS = 25_000L

        /**
         * Patience for one OPEN. adbd answers or refuses promptly, so this only covers a
         * wire that went quiet - and it has to stay small, because it is spent once per
         * attempt while the loop above is what the total is shared between.
         */
        const val SOCKET_ATTEMPT_TIMEOUT_MS = 2_000L

        const val SOCKET_RETRY_DELAY_MS = 150L

        /**
         * Consecutive OPENs that adbd never answered. Two is enough to tell a slow encoder
         * (which is refused, not ignored) apart from a wire that has died under us, and the
         * difference is worth naming: nothing we send from here on will be answered either.
         */
        const val SILENT_OPENS_BEFORE_GIVING_UP = 2

        /**
         * Lines the helper prints when it is abandoning this session. Its `main` keeps a
         * five second deadline of its own: if both channels are not paired by then it shuts
         * itself down, so a socket that is refusing connections for that long has stopped
         * being a slow boot and became a dead process - which no amount of waiting fixes.
         */
        val ENCODER_DEATH_MARKERS = arrayOf("startScrcpy error", "System.exit called", "errorClose")

        /** How often a self-terminated encoder is replaced within one connect attempt. */
        const val MAX_ENCODER_RESTARTS = 2

        /** Length of encoder output kept for the failure log. */
        const val ENCODER_LOG_KEEP = 600

        /** Poll slice while waiting for the render surface to appear. */
        const val SURFACE_POLL_MS = 200L

        /**
         * Fallback ceiling for inbound clipboard packets. A helper built before `clipMax`
         * existed always trims itself to the protocol default and ignores the argument,
         * so a session must still accept that much even when the user has since set a
         * lower limit - rejecting it would drop the picture over a piece of text.
         */
        val CLIPBOARD_LEGACY_MAX = AppPreferences.CLIPBOARD_LIMIT_DEFAULT

        /**
         * Ceiling for one inbound frame on the video and audio channels.
         *
         * Every frame is `int length` followed by that many bytes, and the length is read
         * from the wire before any of it exists. A megabit-per-second encoder at 60 fps
         * produces frames of a few hundred KB, so this bound is not in the same conversation
         * as real traffic - but without it, a helper that fell out of step (or a device that
         * was never the helper at all) can announce two gigabytes, and the allocation that
         * follows takes the whole process down.
         */
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024

        const val AUDIO_SAMPLE_RATE = 48000
        const val AUDIO_CHANNELS = 2
        const val AUDIO_BIT_RATE = 96000

        const val MIME_H265 = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Performance overlay period. */
        const val PERF_REPORT_INTERVAL_MS = 1000L

        /**
         * Wakefulness, asked of the helper's shell RPC rather than of the stream: the
         * wire has no message for it, but the resident process can run a command, which
         * keeps this readable without a new packet type (and without an updated helper).
         */
        const val WAKEFULNESS_QUERY = "dumpsys power | grep -m1 mWakefulness"
    }

    // ------------------------------------------------------------------
    // Facade: declared path differences
    // ------------------------------------------------------------------

    override val renderMode = RenderMode.SURFACE
    override val ownsReceiveLoop = false
    override val inputPath = InputPath.ANDROID_KEYCODE

    // The device has a real touchscreen and the protocol carries up to ten
    // pointers, so fingers are forwarded instead of emulated as mouse moves.
    override val touchPath = TouchPath.DIRECT

    override val supportsPerfOverlay = true

    // The device mirrors its audio into the stream, so there is something to silence - and
    // no way to ask it to be quiet, which is exactly what the toolbar button is for.
    override val supportsAudio = true

    override var audioMuted = false
        set(value) {
            field = value
            audioDecoder?.muted = value
        }

    // ------------------------------------------------------------------
    // Facade: state
    // ------------------------------------------------------------------

    override var connected = false
        private set

    @Volatile
    private var established = false
    override val connectionStarted: Boolean get() = established

    @Volatile
    override var failureReason: String? = null
        private set

    // There is no remote cursor on a touchscreen: an empty local one keeps the
    // renderer's cursor branch happy without drawing anything meaningful.
    override var cursorInfo = CursorInfo(1, 1, 0, 0)
    override var pointerX = 0
        private set
    override var pointerY = 0
        private set

    /**
     * There is nothing to know: touches go to a touchscreen, the device has no pointer to
     * report back, and both coordinates above stay where they were initialised.
     */
    override val pointerPositionKnown = false

    override var ignorePointerMovesByServer = false
    override val frameBufferUpdatesPaused = AtomicBoolean(false)

    /** Frame dimensions as announced by the encoder header / size events. */
    @Volatile
    private var framebufferWidth = 0

    @Volatile
    private var framebufferHeight = 0

    override fun getDesktopName() = profile.name
    override fun getFramebufferWidth() = framebufferWidth
    override fun getFramebufferHeight() = framebufferHeight

    // ------------------------------------------------------------------
    // Session state
    // ------------------------------------------------------------------

    private var adb: Adb? = null
    private var sessionKey: String? = null

    /** Event channel: control packets out, audio/clipboard/size/heartbeat in. */
    private var mainStream: BufferStream? = null

    /** Video channel: one-way, `length + bytes + pts` frames after a 9-byte header. */
    private var videoStream: BufferStream? = null

    private val writeLock = Any()
    private val inputDisabled = AtomicBoolean(false)

    /**
     * Set true by [teardown] so every loop and callback stops touching the wire - and so
     * that a setup still in progress gives up at its next stage boundary.
     */
    @Volatile
    private var shuttingDown = false

    @Volatile
    private var lastKeepAliveReply = 0L

    private var config = AndroidStreamConfig.forProfile(profile)

    /** Render target, supplied by the UI - and possibly later than [connect]. */
    @Volatile
    private var videoSurface: Surface? = null

    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null
    private var videoThread: Thread? = null
    private var mainThread: Thread? = null
    private var heartbeatThread: Thread? = null

    private var videoDecoder: AndroidVideoDecoder? = null
    private var audioDecoder: AndroidAudioDecoder? = null

    // Only ever touched from the video loop.
    private var perfFrames = 0
    private var perfLastReport = 0L

    // Button-mask emulation state for [sendPointerEvent] (single pointer).
    private var virtualPointerDown = false

    /**
     * When that emulated pointer went down, in [android.os.SystemClock.uptimeMillis]. The
     * button-mask model carries no timing at all, but the wire does: the helper rebuilds the
     * press as `downTime + offsetTime`, so without this the offset is 0 on every packet and
     * even a five-second hold reaches the device as an instantaneous tap - which is why a
     * long press (text selection, app context menus, desktop icons) never worked over this
     * path.
     */
    private var virtualPointerDownAt = 0L

    /** Whether the current session already reported a non-finite touch coordinate. */
    private var nonFiniteTouchLogged = false

    /**
     * Set when this connect attempt saw adbd stop answering channel requests. A half-dead
     * TCP wire keeps [Adb.isAlive] true - both pump threads simply stay parked on the
     * socket - so [cleanup] needs this independent witness to take the session out of
     * [AdbManager]'s cache; otherwise every retry reuses the same silent line and the
     * device reads as "cannot connect" until the app restarts.
     */
    private var wireUnresponsive = false

    // ------------------------------------------------------------------
    // Facade: RFB-shaped configuration this protocol has no use for
    // ------------------------------------------------------------------

    /**
     * Security type, local cursor, image quality, raw encoding: all RFB
     * handshakes knobs. The video quality of this protocol is set through
     * [AndroidControlExtras.setAndroidStreamConfig] instead.
     */
    override fun configure(securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean) {}

    override fun setupRepeater(serverId: Int) {}

    /** No desktop resize request exists on a phone - the screen is what it is. */
    override fun setDesktopSize(width: Int, height: Int) {}

    /** No framebuffer to request: the encoder streams continuously. */
    override fun refreshFrameBuffer() {}

    /**
     * Pausing is expressed by dropping the decoder's output, which the surface
     * holder cannot do selectively; the stream keeps running and the UI simply
     * stops seeing new frames, so this is accepted and ignored.
     */
    override fun setFrameBufferUpdatesPaused(pause: Boolean) {
        frameBufferUpdatesPaused.set(pause)
    }

    // Pixel-buffer hooks - never reached while [renderMode] is SURFACE.
    override fun uploadFrameTexture() {}
    override fun uploadCursorTexture() {}

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    @Throws(IOException::class)
    override fun connect(host: String, port: Int) {
        check(!connected) { "Already connected" }

        wireUnresponsive = false
        try {
            stage("android_adb")

            val transport = transport(host, port)
            sessionKey = sessionKeyFor(transport)
            val session = AdbManager.acquire(sessionKey!!, transport)
            adb = session

            // The resident RPC process is per device, not per session: a live one
            // from an earlier connection is reused (that is what makes a quick
            // reconnect fast), a dead one is started fresh.
            stage("android_server")
            checkNotCancelled()
            val serverShell = session.serverShell
            if (serverShell == null || serverShell.isClosed) session.startServer()

            stage("android_encoder")
            checkNotCancelled()
            // A previous session may have left its encoder running (the "keep the helper"
            // preference), and that process owns the socket we are about to ask for. The
            // new one would fail to bind and exit, and we would be pairing channels with a
            // helper that has no client - so the old encoder goes before the new starts.
            session.clearStaleEncoder()
            session.startScrcpyServer(buildEncoderOptions())

            stage("android_socket")
            checkNotCancelled()
            openChannels(session)

            // Everything is up: publish the session before the loops can deliver
            // their first frame, so the UI already has a client to look at.
            established = true
            connected = true
            failureReason = null
            lastKeepAliveReply = System.currentTimeMillis()
            onConnectionStarted()

            startLoops()
        } catch (e: Exception) {
            // An OPEN nobody answered is the classic symptom of a wire that is gone
            // without having said so; [startServer] wraps the timeout, so check the cause.
            if (e is Adb.OpenTimedOut || e.cause is Adb.OpenTimedOut) wireUnresponsive = true
            teardown(interruptLoops = true)
            val reason = e.message ?: e.javaClass.simpleName
            failureReason = reason
            Log.w(TAG, "android connect failed", e)
            if (e is IOException) throw e
            throw IOException(reason, e)
        }
    }

    /**
     * Announces one step of the setup to the UI and to logcat with the same call. The
     * progress line can be replaced by the next stage before the user reads it, so the
     * log is what survives a stuck connection: it says which step was entered and, from
     * the timestamps, how long each of them took.
     */
    private fun stage(name: String) {
        Log.i(TAG, "connect stage: $name")
        observer.onStageStarting(name)
    }

    /**
     * Aborts the setup at the boundary between two stages, once the viewer is gone.
     *
     * [teardown] - and with it [shuttingDown] - is what [cleanup] leaves behind, and closing
     * the streams can only interrupt a read that is already underway. The stages themselves
     * are a chain of calls that each run to completion: the handshake, the jar push (up to
     * two minutes), the encoder launch, the channel pairing. Without this gate, walking back
     * to the server list merely skipped the progress line, while the connection kept walking
     * the rest of that chain - and a device whose screen still says "被 connectsome 控制" is a
     * rather loud way of finding out that the session did not stop.
     *
     * Checked after [adb] is assigned, so the caller's `catch` and the eventual
     * [cleanup] still have a session to hand back to [com.limelight.adb.AdbManager].
     */
    @Throws(IOException::class)
    private fun checkNotCancelled() {
        if (shuttingDown) throw IOException(App.appContext.getString(R.string.error_connect_cancelled))
    }

    private fun transport(host: String, port: Int): AdbTransport {
        val deviceName = profile.androidUsbDeviceName
        return if (deviceName.isNotBlank()) AdbTransport.Usb(deviceName)
        else AdbTransport.Tcp(host, port)
    }

    /** Identity of a wire in [AdbManager]: the cable or the address, never both. */
    private fun sessionKeyFor(transport: AdbTransport): String = when (transport) {
        is AdbTransport.Usb -> "usb:${transport.deviceName}"
        is AdbTransport.Tcp -> "tcp:${transport.host}:${transport.port}"
    }

    /**
     * Opens both channels of the encoder.
     *
     * The helper binds its local socket only once the process is up, and the two
     * accepts are ordered (main, then video) - opening them in the other order
     * would hand the video frames to the control reader.
     *
     * That order is also *spent*: an OPEN that got nowhere, or a pair of which only the
     * first channel survived, leaves the helper holding a control channel we no longer
     * have, and the next attempt would then be accepted as its video channel. Retrying
     * the open alone cannot undo that, so such an attempt is answered with a fresh
     * encoder - and the whole wait stays inside [SOCKET_WAIT_BUDGET_MS], because a retry
     * loop with an unbounded per-attempt timeout is how a connection ends up silent for
     * a quarter of an hour on one progress line.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun openChannels(session: Adb) {
        // Bare socket name: localSocketForward owns the `localabstract:` prefix. Passing a
        // prefixed name here used to make adbd look for a socket literally called
        // "localabstract:connectsome_scrcpy", which it reports as an unfathomable address
        // instead of a mistake - and so every attempt failed, forever, with no hint.
        val socketName = Adb.SCRCPY_SOCKET_NAME
        val startedAt = System.currentTimeMillis()
        val encoderLog = StringBuilder()
        var lastError: Exception? = null
        var attempts = 0
        var encoderRestarts = 0
        var silentOpens = 0

        while (!shuttingDown) {
            attempts++
            if (!session.isAlive) {
                // The device hung up on us mid-setup. Whatever is on the other end of that
                // address now, it is not this session, so asking it for channels is worse
                // than useless: it just delays the one honest answer.
                lastError = IOException(session.failureReason
                        ?: App.appContext.getString(R.string.error_adb_interrupted))
                break
            }
            try {
                val main = session.localSocketForward(socketName, SOCKET_ATTEMPT_TIMEOUT_MS)
                silentOpens = 0
                try {
                    val video = session.localSocketForward(socketName, SOCKET_ATTEMPT_TIMEOUT_MS)
                    mainStream = main
                    videoStream = video
                    Log.i(TAG, "Encoder channels paired: attempt $attempts, " +
                            "$encoderRestarts encoder restart(s), " +
                            "${System.currentTimeMillis() - startedAt}ms")
                    return
                } catch (e: Exception) {
                    main.close()
                    // Half a pair is worse than no pair: see the note above. The wire is
                    // provably answering, so paying a shell round trip for a clean encoder
                    // is both possible and worth it.
                    relaunchEncoder(session)
                    encoderRestarts++
                    lastError = e
                }
            } catch (e: Exception) {
                // A refused OPEN (nothing listening yet) is the encoder still booting, which
                // is normal for the first seconds. An OPEN nobody answered is not: the wire
                // is gone, and so is any command we would send over it.
                if (e is Adb.OpenTimedOut) silentOpens++
                lastError = e
            }

            if (silentOpens >= SILENT_OPENS_BEFORE_GIVING_UP) {
                Log.w(TAG, "adbd stopped answering channel requests after $attempts attempt(s)")
                wireUnresponsive = true
                break
            }

            // Is there still someone to open against? The helper prints its own parting
            // words, and one of them means the listener is gone for good - so collecting
            // the output serves twice over, as the diagnosis and as the trigger.
            val fresh = encoderOutput(session)
            if (fresh.length > 0) {
                if (encoderLog.length > 0) encoderLog.append(' ')
                encoderLog.append(fresh)
                if (encoderLog.length > ENCODER_LOG_KEEP) encoderLog.delete(0, encoderLog.length - ENCODER_LOG_KEEP)
            }
            if (ENCODER_DEATH_MARKERS.any { fresh.contains(it) } && encoderRestarts < MAX_ENCODER_RESTARTS) {
                Log.w(TAG, "Encoder process gave up on its own after $attempts attempt(s); " +
                        "starting a fresh one: $fresh")
                relaunchEncoder(session)
                encoderRestarts++
            }

            val left = SOCKET_WAIT_BUDGET_MS - (System.currentTimeMillis() - startedAt)
            if (left < SOCKET_ATTEMPT_TIMEOUT_MS + SOCKET_RETRY_DELAY_MS) break
            Log.d(TAG, "No channel pair yet after ${SOCKET_WAIT_BUDGET_MS - left}ms " +
                    "(attempt $attempts): ${lastError?.message}")
            Thread.sleep(SOCKET_RETRY_DELAY_MS)
        }

        val reason = when {
            !session.isAlive -> App.appContext.getString(R.string.error_android_wire_dropped)
            silentOpens >= SILENT_OPENS_BEFORE_GIVING_UP ->
                App.appContext.getString(R.string.error_android_wire_silent)
            else -> App.appContext.getString(R.string.error_android_socket_timeout)
        }
        val detail = lastError?.message ?: lastError?.javaClass?.simpleName ?: ""
        Log.w(TAG, "Giving up on the encoder socket after $attempts attempt(s) and " +
                "$encoderRestarts encoder restart(s): $detail", lastError)
        val output = encoderLog.toString()
        // Whether the helper stayed silent or complained is the difference between a
        // wire problem and a helper that cannot start at all, and only the log outlives
        // the dialog.
        if (output.length == 0) Log.i(TAG, "encoder printed nothing")
        else Log.w(TAG, "encoder output: $output")
        throw IOException("$reason [$detail]", lastError)
    }

    /**
     * Starts the encoder over, for the cases where the running one can no longer be
     * paired. Waiting is not an option: a helper that has already spent its first accept
     * on a channel we threw away will never hand out that seat again.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun relaunchEncoder(session: Adb) {
        session.clearStaleEncoder()
        session.startScrcpyServer(buildEncoderOptions())
    }

    /**
     * Whatever the encoder process has printed so far, drained without blocking.
     *
     * Its stdout lands in the shell channel it was launched through, which is otherwise
     * never read - so a `ClassNotFoundException`, a refused screen capture or a stack
     * trace from the helper would sit there unnoticed while the session waits for a socket
     * that the dead process was never going to bind.
     */
    private fun encoderOutput(session: Adb): String = session.scrcpyShell?.let { shell ->
        runCatching { String(shell.readAllBytes().array(), StandardCharsets.UTF_8) }.getOrNull()
    }.orEmpty().trim().take(ENCODER_LOG_KEEP)

    /**
     * Renders [AndroidStreamConfig] as the helper's `key=value` argv.
     *
     * Only deviations from the helper's own defaults are sent - its parser
     * throws on an argument without `=`, and a shorter command is easier to
     * read in `ps` output. The codec flags additionally require that *this*
     * device can decode what the target would encode.
     */
    private fun buildEncoderOptions(): String {
        val c = config
        val parts = mutableListOf<String>()
        if (!c.audioEnabled) parts += "isAudio=0"
        if (c.maxSize != 1600) parts += "maxSize=${c.maxSize}"
        if (c.maxFps != 60) parts += "maxFps=${c.maxFps}"
        if (c.bitrateMbps != 4) parts += "maxVideoBit=${c.bitrateMbps}"
        if (!c.keepAwake) parts += "keepAwake=0"
        if (c.screenMode != 1001) parts += "ScreenMode=${c.screenMode}"
        if (!(c.useH265 && hasDecoder(MIME_H265))) parts += "useH265=0"
        if (!(c.useOpus && hasDecoder(MediaFormat.MIMETYPE_AUDIO_OPUS))) parts += "useOpus=0"
        if (c.useMirrorMode) parts += "mirrorMode=1"
        if (c.clipboardMaxBytes != AppPreferences.CLIPBOARD_LIMIT_DEFAULT) parts += "clipMax=${c.clipboardMaxBytes}"
        return parts.joinToString(" ")
    }

    private fun hasDecoder(mime: String): Boolean {
        // MediaCodecInfo.isDecoder() is API 29 while this app runs from 26 up, so a
        // decoder is whatever the (regular) codec list reports as not-an-encoder.
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.any { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
    }

    private fun startLoops() {
        val handlerThread = HandlerThread("connectsome-mediacodec").also { it.start() }
        codecThread = handlerThread
        codecHandler = Handler(handlerThread.looper)

        videoThread = thread(name = "connectsome-android-video", isDaemon = true) { videoLoop() }
        mainThread = thread(name = "connectsome-android-main", isDaemon = true) { mainLoop() }
        heartbeatThread = thread(name = "connectsome-android-heartbeat", isDaemon = true) { heartbeatLoop() }
    }

    // ------------------------------------------------------------------
    // Video channel
    // ------------------------------------------------------------------

    /**
     * Header: `useH265(1B) + width(int) + height(int)`, then frames of
     * `length(int) + bytes + pts(long)`. The first frame(s) are the codec
     * description and are handed to [MediaFormat] instead of the queue.
     */
    private fun videoLoop() {
        val stream = videoStream ?: return
        try {
            val useH265 = stream.readByte().toInt() == 1
            val width = stream.readInt()
            val height = stream.readInt()
            require(width in 1..MAX_VIDEO_DIMENSION && height in 1..MAX_VIDEO_DIMENSION) {
                "invalid video header: ${width}x$height"
            }

            framebufferWidth = width
            framebufferHeight = height
            observer.onFramebufferSizeChanged(width, height)
            observer.onStageComplete("android_socket")

            // The codec description arrives as ordinary frames; H.265 folds it
            // into a single buffer, H.264 splits SPS and PPS.
            val csd0 = readFrame(stream)
            val csd0Pts = stream.readLong()
            val csd1 = if (useH265) null else run {
                val data = readFrame(stream)
                val pts = stream.readLong()
                data to pts
            }

            val mime = if (useH265) MIME_H265 else MIME_H264
            val surface = awaitSurface() ?: return

            var decoder = AndroidVideoDecoder(mime, width, height, surface, csd0, csd0Pts, handler = codecHandler!!)
            videoDecoder = decoder
            csd1?.let { (data, pts) -> decoder.decodeIn(data, pts) }

            while (!Thread.interrupted() && !shuttingDown) {
                val frame = readFrame(stream)
                val pts = stream.readLong()
                decoder.decodeIn(frame, pts)
                if (stream.isClosed) break
                trackFrameTiming()
            }
        } catch (e: Exception) {
            onStreamBroken(e, R.string.error_video_stream_broken)
        }
    }

    /**
     * Reads one `length + bytes` frame. An implausible length means this reader is no
     * longer aligned with the stream; carrying on would either allocate on trust or block
     * forever on bytes that were never sent, so the session ends instead.
     */
    private fun readFrame(stream: BufferStream): ByteArray {
        val length = stream.readInt()
        if (length !in 1..MAX_FRAME_BYTES) {
            Log.w(TAG, "frame with implausible length $length")
            throw IOException(App.appContext.getString(R.string.error_android_frame_length_bad))
        }
        return stream.readByteArray(length).array()
    }

    /**
     * The GL surface normally exists before a session starts, but it is created
     * asynchronously - and [ServerProfile.VIEW_MODE_NO_VIDEO] sessions never get
     * one at all, in which case the loop simply ends without decoding.
     */
    private fun awaitSurface(): Surface? {
        videoSurface?.let { return it }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS * 6
        // Polled rather than signalled: the surface is published by the UI thread while
        // this loop runs on the video thread, and @Volatile plus a slice of sleep gives
        // the same guarantee without a monitor (Kotlin cannot wait() on an Any receiver).
        while (videoSurface == null && !shuttingDown && System.currentTimeMillis() < deadline)
            Thread.sleep(SURFACE_POLL_MS)

        return videoSurface
    }

    /** FPS + inter-frame interval, measured on arrival: all this protocol can know. */
    private fun trackFrameTiming() {
        val now = System.currentTimeMillis()
        if (perfLastReport == 0L) {
            perfLastReport = now
            return
        }
        perfFrames++

        val elapsed = now - perfLastReport
        if (elapsed >= PERF_REPORT_INTERVAL_MS) {
            perfLastReport = now
            // One window answers both questions, so the two numbers can never disagree:
            // a stalled stream reads as a low frame rate *and* a long frame interval,
            // where an interval smoothed over earlier frames used to hide the stall.
            val fps = perfFrames * 1000f / elapsed
            val intervalMs = elapsed.toFloat() / perfFrames
            observer.onPerfOverlayUpdate(
                    App.appContext.getString(R.string.label_perf_overlay, fps, intervalMs))
            perfFrames = 0
        }
    }

    // ------------------------------------------------------------------
    // Main (event) channel
    // ------------------------------------------------------------------

    /**
     * Header: `hasAudio(1B)`, followed by `useOpus(1B)` only when audio was
     * actually started. Then a byte-sized event type per message.
     */
    private fun mainLoop() {
        val stream = mainStream ?: return
        try {
            var useOpus = false
            if (stream.readByte().toInt() == 1) {
                useOpus = stream.readByte().toInt() == 1
            } else if (config.audioEnabled) {
                Log.i(TAG, "helper did not start audio capture")
            }

            while (!Thread.interrupted() && !shuttingDown) {
                when (stream.readByte().toInt()) {
                    EVENT_AUDIO -> {
                        // The very first audio packet is the codec description: it goes
                        // into the MediaFormat and is *not* fed to the decoder as data.
                        // The frame is always read, or the stream would fall out of step.
                        val frame = readFrame(stream)
                        if (config.audioEnabled && config.playAudioLocally) {
                            val decoder = audioDecoder
                            if (decoder != null) decoder.decodeIn(frame)
                            else audioDecoder = AndroidAudioDecoder(useOpus, frame, handler = codecHandler!!)
                                    .also { it.muted = audioMuted }
                        }
                    }

                    EVENT_CLIPBOARD -> {
                        // Both ends trim to the configured limit, but a helper from before
                        // `clipMax` existed sticks to the protocol default, so accept the
                        // larger of the two. Anything beyond that means this reader is no
                        // longer aligned with the stream: reading it anyway would allocate
                        // on trust (or block forever waiting for bytes that were never
                        // sent), so end the session instead.
                        val length = stream.readInt()
                        val bound = maxOf(config.clipboardMaxBytes, CLIPBOARD_LEGACY_MAX)
                        if (length !in 1..bound) {
                            Log.w(TAG, "clipboard event with implausible length $length")
                            terminateWith(App.appContext.getString(R.string.error_android_clipboard_garbled))
                            return
                        }
                        observer.onCutTextReceived(
                                stream.readByteArray(length).array().toString(StandardCharsets.UTF_8))
                    }

                    EVENT_SIZE -> {
                        val width = stream.readInt()
                        val height = stream.readInt()
                        if (width in 1..MAX_VIDEO_DIMENSION && height in 1..MAX_VIDEO_DIMENSION) {
                            framebufferWidth = width
                            framebufferHeight = height
                            observer.onFramebufferSizeChanged(width, height)
                        } else if (width > 0 && height > 0) {
                            // Implies a stream we are no longer reading in step; the picture
                            // must not be re-laid out on a size no decoder could ever use.
                            Log.w(TAG, "size event with implausible dimensions ${width}x$height")
                        }
                    }

                    EVENT_KEEP_ALIVE -> lastKeepAliveReply = System.currentTimeMillis()

                    else -> { /* unknown type: ignore rather than kill the session */ }
                }
            }
        } catch (e: Exception) {
            onStreamBroken(e, R.string.error_event_stream_broken)
        }
    }

    private fun heartbeatLoop() {
        try {
            while (!Thread.interrupted() && !shuttingDown) {
                if (System.currentTimeMillis() - lastKeepAliveReply > TIMEOUT_MS) {
                    terminateWith(App.appContext.getString(R.string.error_android_timeout))
                    return
                }
                writeMain(ByteBuffer.wrap(byteArrayOf(PACKET_KEEP_ALIVE.toByte())))
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            }
        } catch (e: Exception) {
            onStreamBroken(e, R.string.error_heartbeat_broken)
        }
    }

    // ------------------------------------------------------------------
    // Outbound packets
    // ------------------------------------------------------------------

    /**
     * Control packets are big-endian and self-describing:
     *
     *  - `1` TOUCH: action, pointerId, x(float), y(float), offsetTime(int)
     *  - `2` KEY: keyCode, meta, displayId (-1 = the mirrored one)
     *  - `3` CLIPBOARD: length + UTF-8
     *  - `4` KEEP ALIVE / `8` POWER: no payload
     *  - `5` CONFIG, `6` ROTATE: int
     *  - `7` LIGHT, `9` NIGHT: byte
     */
    private fun writeControl(packet: ByteBuffer) {
        if (shuttingDown) return
        synchronized(writeLock) {
            try {
                mainStream?.write(packet)
            } catch (e: Exception) {
                onStreamBroken(e, R.string.error_control_channel_unavailable)
            }
        }
    }

    private fun writeMain(packet: ByteBuffer) = writeControl(packet)

    /** Input is dropped wholesale while the session is in view-only mode. */
    private fun sendInput(packet: ByteBuffer) {
        if (inputDisabled.get()) return
        writeControl(packet)
    }

    override fun sendMultiTouch(
            action: Int, pointerId: Int, xNorm: Float, yNorm: Float, offsetTimeMs: Int) {
        // A coordinate that is not a number is dropped, not clamped: [coerceIn] lets NaN
        // through (both comparisons are false), and the helper turns it into pixel zero -
        // a tap in the top left corner that nobody made. Dropping costs one move event.
        if (!xNorm.isFinite() || !yNorm.isFinite()) {
            // Once per session: the input path runs at touch rate, and a systematic
            // producer of NaN would otherwise fill logcat faster than it can be read.
            if (!nonFiniteTouchLogged) {
                nonFiniteTouchLogged = true
                Log.w(TAG, "dropped touch with non-finite coordinates: $xNorm,$yNorm")
            }
            return
        }
        // Out-of-range coordinates are *not* clamped by the sender-only side of the
        // helper's contract: it would reinterpret them as a release. Clamp here so a
        // finger dragged past the edge keeps drawing.
        val x = xNorm.coerceIn(0f, 1f)
        val y = yNorm.coerceIn(0f, 1f)
        val packet = ByteBuffer.allocate(15)
        packet.put(PACKET_TOUCH.toByte())
        packet.put(action.toByte())
        packet.put(pointerId.coerceIn(0, 255).toByte())
        packet.putFloat(x)
        packet.putFloat(y)
        packet.putInt(offsetTimeMs)
        packet.flip()
        sendInput(packet)
    }

    override fun sendAndroidKey(keyCode: Int, metaState: Int) {
        val packet = ByteBuffer.allocate(13)
        packet.put(PACKET_KEY.toByte())
        packet.putInt(keyCode)
        packet.putInt(metaState)
        packet.putInt(-1)
        packet.flip()
        sendInput(packet)
    }

    override fun sendRotate(rotation: Int) = sendInput(intPacket(PACKET_ROTATE, rotation))

    override fun sendConfigChange(mode: Int) = sendInput(intPacket(PACKET_CONFIG, mode))

    override fun sendScreenPower(state: Int) {
        sendInput(ByteBuffer.wrap(byteArrayOf(PACKET_LIGHT.toByte(), state.toByte())))
    }

    override fun sendNightMode(mode: Int) {
        sendInput(ByteBuffer.wrap(byteArrayOf(PACKET_NIGHT.toByte(), mode.toByte())))
    }

    /**
     * Read back through the resident RPC process - see [AndroidControlExtras.queryNightMode].
     * Anything but a plain number comes back as null: an unknown request, a helper that
     * answered with an error message, and a reply that arrived as a fragment all fail the
     * same way, which is what the caller wants (it keeps its own guess).
     */
    override suspend fun queryNightMode(): Int? {
        val session = adb ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { session.getStringResponse("getNightMode").trim().toIntOrNull() }.getOrNull()
        }
    }

    /**
     * Whether the device's screen is lit, from its current wakefulness. `Dozing` (always-on
     * display) counts as off, since that is the state a [android.app.PowerManager.isInteractive]
     * reader means by it and the answer is what the caller acts on.
     *
     * A reply we cannot parse stays null: the caller then keeps its own guess instead of
     * being talked into a wake that the device may not need.
     */
    override suspend fun queryScreenOn(): Boolean? {
        val session = adb ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val reply = session.getStringResponse("runShell", "cmd=$WAKEFULNESS_QUERY")
                val state = Regex("mWakefulness=(\\w+)").find(reply)?.groupValues?.get(1)
                        ?: return@runCatching null
                state == "Awake"
            }.getOrNull()
        }
    }

    override fun sendPower() = sendInput(ByteBuffer.wrap(byteArrayOf(PACKET_POWER.toByte())))

    private fun intPacket(type: Int, value: Int): ByteBuffer {
        val packet = ByteBuffer.allocate(5)
        packet.put(type.toByte())
        packet.putInt(value)
        packet.flip()
        return packet
    }

    /**
     * The keysym-based input path of the facade. This protocol takes
     * [android.view.KeyEvent] codes through [sendAndroidKey] - see
     * [InputPath.ANDROID_KEYCODE] - and a keysym carries no code we could send,
     * so there is deliberately nothing to translate here.
     */
    override fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean) {}

    /**
     * Single-pointer compatibility path for callers that still speak the RFB
     * pointer model (button mask). Multi-touch goes through [sendMultiTouch].
     */
    override fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        val pressed = mask != 0
        val action = when {
            pressed && !virtualPointerDown -> MotionEvent.ACTION_DOWN
            pressed -> MotionEvent.ACTION_MOVE
            virtualPointerDown -> MotionEvent.ACTION_UP
            else -> return
        }
        // Measured on the viewer's clock and sent as an offset: the helper adds it to the
        // down time of *its* clock, so only the difference between two packets matters. A
        // release therefore still says how long the finger really waited, and a click - whose
        // down and up are separated by the messenger's own delay - keeps its short duration.
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) virtualPointerDownAt = now
        val offsetTime = (now - virtualPointerDownAt).coerceAtLeast(0L).toInt()
        virtualPointerDown = pressed
        sendMultiTouch(action, 0, normalizeX(x), normalizeY(y), offsetTime)
    }

    private fun normalizeX(x: Int): Float =
            if (framebufferWidth <= 0) 0f else (x.toFloat() / framebufferWidth).coerceIn(0f, 1f)

    private fun normalizeY(y: Int): Float =
            if (framebufferHeight <= 0) 0f else (y.toFloat() / framebufferHeight).coerceIn(0f, 1f)

    /** No remote cursor to place. */
    override fun moveClientPointer(x: Int, y: Int) {}

    override fun setInputDisabled(disabled: Boolean) {
        inputDisabled.set(disabled)
    }

    /**
     * Sends text to the device's clipboard. Anything longer than the agreed limit is cut
     * at a code-point boundary rather than dropped, because silently losing a URL would
     * look like a bug.
     */
    override fun sendCutText(text: String) {
        val bytes = clipToUtf8Limit(text)
        if (bytes.isEmpty()) return
        val packet = ByteBuffer.allocate(5 + bytes.size)
        packet.put(PACKET_CLIPBOARD.toByte())
        packet.putInt(bytes.size)
        packet.put(bytes)
        packet.flip()
        sendInput(packet)
    }

    private fun clipToUtf8Limit(text: String): ByteArray {
        val limit = config.clipboardMaxBytes
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        if (raw.size <= limit) return raw

        // Walk code points so a truncated surrogate pair cannot corrupt the tail.
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + width > limit) break
            bytes += width
            index += Character.charCount(codePoint)
        }
        return text.substring(0, index).toByteArray(StandardCharsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // Render target
    // ------------------------------------------------------------------

    /**
     * Required by [VideoSink], but this protocol never talks to the Activity:
     * input goes over the wire, not through a system service that needs a context
     * (GameStream keeps the reference for its native renderer callbacks).
     */
    override fun attachActivity(activity: Activity?) {}

    override fun setVideoSurface(surface: Surface?) {
        videoSurface = surface
    }

    // ------------------------------------------------------------------
    // AndroidControlExtras
    // ------------------------------------------------------------------

    override fun setAndroidStreamConfig(config: AndroidStreamConfig) {
        this.config = config
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    override fun processServerMessage(): Boolean {
        // Callback-driven: [RemoteSession] never asks for this.
        throw UnsupportedOperationException(
                "AndroidClient runs its own receive loops (ownsReceiveLoop = false)")
    }

    private fun onStreamBroken(e: Exception, @StringRes what: Int) {
        if (shuttingDown) return
        val label = App.appContext.getString(what)
        Log.w(TAG, "$label: $e")
        terminateWith(App.appContext.getString(R.string.error_stream_broken_detail,
                label, e.message ?: e.javaClass.simpleName))
    }

    private fun terminateWith(reason: String) {
        val wasEstablished = established
        failureReason = reason
        connected = false
        teardown(interruptLoops = true)
        if (wasEstablished) onConnectionTerminated(0)
    }

    /**
     * Ends a session but keeps the ADB wire and the resident RPC process, which
     * is what makes reconnecting cheap. The encoder process is per-session and
     * dies on its own a few seconds after its sockets close - it is only killed
     * explicitly when the user asked for it.
     */
    override fun cleanup() {
        val keepEncoder = AppPreferences(App.appContext).server.androidKeepServerOnDisconnect
        val session = adb
        val key = sessionKey

        teardown(interruptLoops = true)

        adb = null
        sessionKey = null

        if (session == null || key == null) return

        runCatching { AdbManager.release(key) }

        // A wire that died under us - or one that stopped answering channel requests
        // without ever noticing it was gone - must not be reused for the next connect.
        // Killing the encoder over such a wire is both pointless and slow (its OPEN
        // would wait out the full channel timeout), so the session goes instead.
        if (!session.isAlive || wireUnresponsive) runCatching { AdbManager.invalidate(key) }
        else if (!keepEncoder) runCatching { session.killScrcpyProcesses() }
    }

    @Synchronized
    private fun teardown(interruptLoops: Boolean) {
        if (shuttingDown) return
        shuttingDown = true

        if (interruptLoops) {
            // Closing the streams unblocks their readers, which is both faster and
            // safer than interrupting a thread parked on a socket. [awaitSurface] polls
            // [shuttingDown], so it leaves on its own.
            runCatching { mainStream?.close() }
            runCatching { videoStream?.close() }
            mainStream = null
            videoStream = null
        }

        runCatching { adb?.scrcpyShell?.close() }

        runCatching { videoDecoder?.release() }
        videoDecoder = null
        runCatching { audioDecoder?.release() }
        audioDecoder = null

        videoThread?.interrupt()
        mainThread?.interrupt()
        heartbeatThread?.interrupt()

        runCatching { codecThread?.quitSafely() }
        codecThread = null
        codecHandler = null

        connected = false
    }
}

/**
 * H.264/H.265 decoder into a [Surface], async (callback) mode.
 *
 * Frames are queued through two queues because MediaCodec hands out input
 * buffers when it is ready, while the network delivers them when the encoder
 * feels like it - draining both as soon as either has something keeps latency
 * at one buffer pair instead of a stalled pipeline.
 */
private class AndroidVideoDecoder(
        mime: String,
        width: Int,
        height: Int,
        surface: Surface,
        csd0: ByteArray,
        csd0Pts: Long,
        handler: Handler,
) {
    private var codec = MediaCodec.createDecoderByType(mime)
    private val pendingData = java.util.concurrent.LinkedBlockingQueue<Pair<ByteArray, Long>>()
    private val pendingIndexes = java.util.concurrent.LinkedBlockingQueue<Int>()

    /** Bytes currently held in [pendingData], guarded by this instance's monitor. */
    private var pendingBytes = 0

    @Volatile
    private var released = false

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            pendingIndexes.offer(index)
            drain()
        }

        override fun onOutputBufferAvailable(
                mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            // Render as soon as the frame is out. Pacing by presentation time would
            // anchor the schedule to the *encoder's* clock on the other device - two
            // uptime bases this side cannot align, and some vendor codecs stretch
            // that into a visible queue of frames waiting to "catch up". A live
            // mirror wants the freshest frame on screen now; the encoder already
            // paces the stream to the source display's refresh, so there is nothing
            // left to smooth here.
            mediaCodec.releaseOutputBuffer(index, true)
        }

        override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error", e)
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) {}
    }

    init {
        // Hint levels to try, strongest first: 2 = real-time priority + low-latency,
        // 1 = priority only, 0 = no hints. The levels exist because the two keys are
        // independent and vendors reject them independently: Huawei's hisi HEVC fails
        // configure outright on KEY_LOW_LATENCY but accepts KEY_PRIORITY, and priority
        // alone still buys real-time scheduling at the codec.
        fun buildFormat(hints: Int): MediaFormat {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            // Rotation restarts the encoder with swapped edges while this decoder stays
            // alive, so give it capacity for the larger of the two. Clamped because the
            // dimensions come from the wire and `width * 2` is an int multiplication.
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, minOf(width * 2, MAX_VIDEO_DIMENSION))
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, minOf(height * 2, MAX_VIDEO_DIMENSION))
            if (hints >= 1) {
                // Priority 0 asks the hardware codec to meet the frame deadline rather
                // than buffer frames to smooth throughput.
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (hints >= 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                // The explicit "decode one, show one" hint. A mirrored screen carries
                // no B-frames, so the decoder's reordering depth is pure added delay.
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            return format
        }

        codec.setCallback(callback, handler)
        try {
            codec.configure(buildFormat(2), surface, null, 0)
        } catch (e: Exception) {
            // Most vendors treat the latency hints as advisory, but some (Huawei's hisi
            // HEVC among them) fail configure outright rather than ignore them. A failed
            // configure invalidates the instance, so rebuild codec and format and retry
            // once without the hints - a slightly slower picture beats a dead stream.
            Log.w(TAG, "full-hint configure failed, retrying priority-only", e)
            runCatching { codec.release() }
            codec = MediaCodec.createDecoderByType(mime)
            codec.setCallback(callback, handler)
            try {
                // Priority alone is still worth having: some codecs (Huawei's hisi
                // among them) reject KEY_LOW_LATENCY outright but accept
                // KEY_PRIORITY - lose only the rejected key, not the whole hint.
                codec.configure(buildFormat(1), surface, null, 0)
            } catch (e2: Exception) {
                Log.w(TAG, "priority-only configure failed, retrying bare", e2)
                runCatching { codec.release() }
                codec = MediaCodec.createDecoderByType(mime)
                codec.setCallback(callback, handler)
                codec.configure(buildFormat(0), surface, null, 0)
            }
        }
        codec.start()
        // The description is re-injected as a frame: some vendors' decoders only
        // accept it in band, and queueing it twice (format + stream) is harmless.
        decodeIn(csd0, csd0Pts)
    }

    fun decodeIn(data: ByteArray, ptsUs: Long) {
        if (released) return
        synchronized(this) {
            // Every frame the network delivers is parked here until the codec asks
            // for it, and the producer never blocks. Frames may only ever be shed at
            // the generous ceilings below: the stream is inter-predicted H.264/H.265,
            // so dropping a frame that later frames reference strands the decoder
            // (a cascading "Ref frame lost" in the vendor decoder logs) and the
            // picture stays frozen until the next IDR realigns the chain - a tight
            // queue limit therefore does not trade latency for a small hitch, it
            // trades it for a dead screen. Latency is fought with the decoder's
            // real-time priority instead; this queue only absorbs the codec's own
            // pipeline depth (a decoder running without the low-latency hint holds
            // a GOP's worth of frames internally before any output appears).
            while (pendingData.isNotEmpty() && (pendingData.size >= MAX_PENDING_FRAMES
                        || pendingBytes + data.size > MAX_PENDING_BYTES)) {
                pendingData.poll()?.let { pendingBytes -= it.first.size }
            }
            pendingData.offer(data to ptsUs)
            pendingBytes += data.size
        }
        drain()
    }

    @Synchronized
    private fun drain() {
        while (true) {
            val index = pendingIndexes.poll() ?: return
            val (data, pts) = pendingData.poll() ?: run {
                pendingIndexes.offer(index)
                return
            }
            pendingBytes -= data.size
            val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: continue
            buffer.clear()
            if (buffer.remaining() < data.size) continue // cannot happen with a sized input
            buffer.put(data)
            runCatching { codec.queueInputBuffer(index, 0, data.size, pts, 0) }
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TAG = "ConnectSomeVideo"

        /**
         * Backlog ceilings for [decodeIn] - a memory guard for a decoder that died
         * while the network kept sending, deliberately not a latency control; see
         * the comment on [decodeIn] for why shedding frames mid-GOP freezes the
         * picture. Sized well above any healthy decoder's pipeline depth (a couple
         * of seconds at 60fps) so normal operation never reaches them.
         */
        const val MAX_PENDING_FRAMES = 300
        const val MAX_PENDING_BYTES = 64 * 1024 * 1024
    }
}

/**
 * AAC/Opus decoder feeding an [AudioTrack].
 *
 * This plays as media on purpose. It used to be `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` plus
 * a +20 dB [android.media.audiofx.LoudnessEnhancer] so that mirroring a device could not
 * steal audio focus from what the phone was already doing. The routing cost more than it
 * bought: the navigation stream is not what the media volume - or the volume keys - move,
 * and the extra gain made the phone audible even when the mirrored device was silent, so
 * the mirror was louder than its source. An [AudioTrack] on its own never requests focus,
 * so media usage keeps the phone's media volume in charge and the two stay consistent.
 */
private class AndroidAudioDecoder(
        useOpus: Boolean,
        csd0: ByteArray,
        handler: Handler,
) {
    private val codec = MediaCodec.createDecoderByType(
            if (useOpus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC)
    private val track: AudioTrack

    /**
     * Local playback silence, driven by [AndroidClient.audioMuted]. Only this track is
     * turned down: the device keeps sending audio and the decoder keeps running, so unmuting
     * is instant and needs no renegotiation with the helper.
     */
    var muted = false
        set(value) {
            field = value
            runCatching { track.setVolume(if (value) 0f else 1f) }
        }

    private val pendingData = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val pendingIndexes = java.util.concurrent.LinkedBlockingQueue<Int>()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
            pendingIndexes.offer(index)
            drain()
        }

        override fun onOutputBufferAvailable(
                mediaCodec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val out = runCatching { mediaCodec.getOutputBuffer(index) }.getOrNull()
            if (out != null) {
                out.position(0)
                runCatching { track.write(out, info.size, AudioTrack.WRITE_NON_BLOCKING) }
            }
            mediaCodec.releaseOutputBuffer(index, false)
        }

        override fun onError(mediaCodec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "audio decoder error", e)
        }

        override fun onOutputFormatChanged(mediaCodec: MediaCodec, format: MediaFormat) {}
    }

    init {
        val format = MediaFormat.createAudioFormat(
                if (useOpus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE, CHANNEL_COUNT)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        // csd-0 is the first packet the helper sent: an AudioSpecificConfig for AAC,
        // an OpusHead-like pre-skip header for Opus.
        format.setByteBuffer("csd-0", ByteBuffer.wrap(if (csd0.isEmpty()) DEFAULT_AAC_CSD else csd0))
        if (useOpus) {
            // Opus wants pre-skip and gain explicitly; the helper's stream is
            // 20 ms frames at 48 kHz, so 48 samples per channel of pre-skip.
            format.setInteger(KEY_OPUS_PRE_SKIP, 48 * 2)
            format.setByteBuffer("csd-1", ByteBuffer.allocate(8))
            format.setByteBuffer("csd-2", ByteBuffer.allocate(8))
        }

        val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4
        track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build())
                .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build()

        codec.setCallback(callback, handler)
        codec.configure(format, null, null, 0)
        codec.start()
        track.play()
    }

    fun decodeIn(data: ByteArray) {
        if (released) return
        // Same reason as the video path, with a smaller budget: an audio packet is
        // 20 ms, so 50 of them already lag half a second behind the picture.
        while (pendingData.size >= MAX_PENDING_PACKETS)
            pendingData.poll()
        pendingData.offer(data)
        drain()
    }

    @Volatile
    private var released = false

    @Synchronized
    private fun drain() {
        while (true) {
            val index = pendingIndexes.poll() ?: return
            val data = pendingData.poll() ?: run {
                pendingIndexes.offer(index)
                return
            }
            val buffer = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: continue
            buffer.clear()
            if (buffer.remaining() < data.size) continue
            buffer.put(data)
            runCatching { codec.queueInputBuffer(index, 0, data.size, 0, 0) }
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { track.stop() }
        runCatching { track.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private companion object {
        const val TAG = "ConnectSomeAudio"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val BIT_RATE = 96000

        /**
         * `MediaFormat.KEY_PRE_SKIP` is a hidden framework constant on the APIs this app
         * supports; the key string itself is stable, so it is spelled out here.
         */
        const val KEY_OPUS_PRE_SKIP = "pre-skip"

        /** Ceiling for the audio backlog; see [decodeIn]. */
        const val MAX_PENDING_PACKETS = 50

        /** AAC-LC stereo @48 kHz, used only when the helper sent an empty header. */
        val DEFAULT_AAC_CSD = byteArrayOf(0x11.toByte(), 0x90.toByte())
    }
}
