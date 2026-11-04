/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.session

import android.app.Activity
import android.util.Base64
import com.limelight.util.Log
import android.view.Surface
import com.limelight.App
import com.limelight.R
import com.limelight.model.ServerProfile
import com.limelight.stream.StreamConfig
import com.limelight.vnc.AndroidControlExtras
import com.limelight.vnc.AndroidStreamConfig
import com.limelight.vnc.GameStreamExtras
import com.limelight.vnc.VideoSink
import com.limelight.vnc.VncClient
import com.limelight.vnc.VncClientFactory
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Controls a remote session.
 *
 * The protocol itself is chosen by [VncClientFactory]; everything after that is
 * driven by what the created client says about itself, so this class holds no
 * protocol-specific knowledge. The one structural difference it does branch on is
 * how a connection is *kept alive*:
 *
 *  - [VncClient.ownsReceiveLoop] = false (GameStream, Android device): fully
 *    callback-driven, so the session thread just blocks on a [CountDownLatch]
 *    which is released when the connection terminates (or [stop] is called).
 *  - [VncClient.ownsReceiveLoop] = true (RFB): the session thread *is* the receive
 *    loop, pumping [VncClient.processServerMessage] until it reports the end.
 *
 * The host [Activity] and the decoded-video [Surface] are supplied by the UI layer
 * through [attachActivity] / [setVideoSurface]. Either may arrive before or after
 * [start]; both are forwarded to the live client when available (protocols without
 * a hardware decoder simply ignore them).
 */
class RemoteSession(private val observer: Observer) {

    interface Observer : VncClient.Observer {
        fun onConnecting()
        fun onConnected(vncClient: VncClient, messenger: Messenger)
        fun onDisconnected()

        fun onConnectionError(error: Throwable)
    }

    private val id = sessionCounter.incrementAndGet()
    private val tag = "RemoteSession[$id]"

    // Published on the session thread and read without a lock by the UI thread's
    // attachActivity/setVideoSurface/setAudioMuted: a surface handed over just as the client
    // is published can otherwise be read as null and never reach the decoder, which means a
    // permanently black picture with a connection the user believes is fine.
    @Volatile
    private var vncClient: VncClient? = null

    @Volatile
    private var messenger: Messenger? = null
    private var sessionThread: Thread? = null

    // Replaced on every start: a restart has to hand the new session its own latch,
    // otherwise the previous connection's countdown would release the new one early.
    @Volatile
    private var finished = CountDownLatch(1)

    /**
     * Identifies the session run that is in charge; replaced on every [start].
     * [restart] gives the previous session only [RESTART_JOIN_TIMEOUT_MS] to finish and then
     * starts the new one regardless, so a superseded run can still be unwinding behind it.
     * Its callbacks and its teardown belong to a connection that no longer exists, and
     * running them against the current fields would report a live session as disconnected -
     * or null out [sessionThread], after which [stop] concludes there is nothing to stop and
     * a disconnect silently does nothing at all.
     */
    @Volatile
    private var sessionToken = Any()

    @Volatile
    private var stopRequested = false

    // Bumped by every [stop] that really asked for one. [stopRequested] cannot tell restart's
    // own stop apart from a stop the user requested meanwhile, because it stays set until the
    // next [start] clears it; a counter lets [restart] compare against the value it recorded
    // rather than read a flag that its own teardown just raised.
    @Volatile
    private var stopCount = 0

    // Supplied by the UI layer; may change after the session has started.
    @Volatile
    private var activityRef: Activity? = null
    @Volatile
    private var surfaceRef: Surface? = null
    @Volatile
    private var audioMutedRef = false

    /** Attach the hosting activity so the decoder can render into the GL surface. */
    fun attachActivity(activity: Activity?) {
        activityRef = activity
        // Both hardware-decoding protocols need a host; RFB uploads pixels and
        // implements none of it, so the cast simply fails there.
        (vncClient as? VideoSink)?.attachActivity(activity)
    }

    /** Provide the decoded-video render surface (from the GL SurfaceTexture). */
    fun setVideoSurface(surface: Surface?) {
        surfaceRef = surface
        (vncClient as? VideoSink)?.setVideoSurface(surface)
    }

    /**
     * Silence the remote audio on this phone. Supplied by the UI layer like [attachActivity]:
     * it is a viewer-wide choice rather than part of the profile, and the session remembers
     * it so [configureClient] can hand it to the next client before the connection starts.
     * Doing it there and not after [Observer.onConnected] matters: the playback device is
     * built during setup, so a mute applied only once the session is up would let the first
     * fraction of a second of audio through at full volume.
     */
    fun setAudioMuted(muted: Boolean) {
        audioMutedRef = muted
        vncClient?.audioMuted = muted
    }

    /**
     * Starts the session for the given [profile].
     * For now, [start] can be called only once for an instance.
     */
    @Synchronized
    fun start(profile: ServerProfile) {
        check(sessionThread == null) { "Session re-start is not yet supported" }

        log("Requesting session start")
        stopRequested = false
        val token = Any()
        sessionToken = token
        sessionThread = startSession(profile, token)
    }

    /**
     * Drop the current connection and open a fresh one for the same [profile].
     *
     * Needed when the decoded-video render surface is replaced while the session is
     * alive - the floating mini window hosts its own GL surface, and a hardware decoder
     * captures its output surface once at `configure()` and never re-binds it, so the
     * only way to point it at the new surface is to tear the connection down and build
     * it again. Everything above the client survives: the ViewModel, the profile, the
     * zoom state and the user's toolbar choices.
     *
     * The handover runs on its own thread because the old session may still be inside
     * its protocol loop; joining it here would block whoever asked for the restart -
     * which for a new surface is the GL thread, where a stall would freeze rendering.
     * A host that supports resuming (GameStream) picks the desktop session back up; the
     * rest reconnect like any fresh start.
     *
     * Restarting asks [stop] for the old connection, which raises [stopRequested] and never
     * gets cleared again until the new session starts. So the guard below compares [stopCount]
     * against the value this restart left behind: reading the flag alone would make every
     * restart mistake its own stop for the user's and cancel itself, killing the picture.
     */
    fun restart(profile: ServerProfile) {
        val previous: Thread?
        val stopsAtRestart: Int

        synchronized(this) {
            if (stopRequested) {
                // A teardown the user asked for is already in flight. Whatever surface is
                // arriving now belongs to a window on its way out; it must not raise a
                // session that was just disconnected.
                log("Restart ignored: a stop was already in progress")
                return
            }

            previous = sessionThread
            stop()
            stopsAtRestart = stopCount
        }

        thread(name = "$tag-restart", isDaemon = true) {
            runCatching { previous?.join(RESTART_JOIN_TIMEOUT_MS) }
                    .onFailure { log("Waiting for the previous session failed: $it") }

            if (previous?.isAlive == true)
                log("Previous session outlived the restart timeout; starting anyway")

            synchronized(this) {
                // The session thread clears itself on the way out, but if it was
                // stuck past the timeout the field may still point at it. Without
                // this, start() would reject the restart outright.
                sessionThread = null
                if (stopCount != stopsAtRestart) {
                    log("Restart cancelled by a stop that came in meanwhile")
                    return@thread
                }
                start(profile)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (sessionThread?.isAlive != true)
            return // Already stopped

        log("Requesting session stop")
        stopRequested = true
        stopCount++
        val client = vncClient
        finished.countDown()
        if (client == null) return

        // Cleanup runs on its own thread rather than here, for two reasons that share one
        // root: tearing a live client down is not instant (the native bridge's stop waits on
        // MoonBridge's monitor, which a connection thread in the middle of `startConnection`
        // holds for seconds; sockets and decoders take their own time to close). Called from
        // the ViewModel's teardown, this method runs on the UI thread - so a synchronous
        // cleanup kept the viewer's window alive long after the user pressed back, swallowing
        // taps meant for the server list behind it, and it held this object's monitor while
        // doing so, which parked the dying session's own callbacks ([onClientConnected])
        // behind a teardown that was waiting on them. The session thread's [stopSession]
        // performs the same cleanup for its own client, so idempotence across the two is a
        // property of [VncClient.cleanup] either way - moving it here changes the thread,
        // not the calls.
        thread(name = "$tag-teardown", isDaemon = true) {
            runCatching { client.cleanup() }
        }
    }


    /******************************************************************************************/

    private fun startSession(profile: ServerProfile, token: Any): Thread {
        // The latch is local to this run and only also published through [finished], so a
        // stale connection's termination can release this run's wait and nothing else.
        val latch = CountDownLatch(1)
        finished = latch
        return thread(name = tag) {
            val superseded = { token !== sessionToken }
            log("Session started")

            runCatching {
                startConnection(profile, token, latch)
            }.let {
                handleConnectionEnd(it, superseded())
            }

            stopSession(token)
        }
    }

    private fun startConnection(profile: ServerProfile, token: Any, latch: CountDownLatch): VncClient? {
        // An SSH tunnel was never ported over to this app. The field survives from
        // imported/legacy profiles and from `vnc://` links, so the only place that can
        // still say "no" with a readable message is here - before a client is built.
        // Silently connecting straight to the host instead would mean the credentials
        // the user typed for SSH travel over an unencrypted, untunneled link.
        if (profile.channelType == ServerProfile.CHANNEL_SSH_TUNNEL)
            throw IOException(App.appContext.getString(R.string.error_ssh_tunnel_unsupported))

        log("Preparing client")

        val client = VncClientFactory.create(
                profile,
                observer,
                onConnectionStarted = { onClientConnected(token) },
                onConnectionTerminated = { latch.countDown() })

        // Publishing the client and reading the stop request happen under [stop]'s monitor,
        // so one of the two always sees the other: either [stop] finds this client and tears
        // it down, or this thread learns that the session was cancelled and never starts the
        // attempt. Any other order let a viewer that the user already left keep connecting for
        // the whole timeout budget - pushing the helper, launching the encoder, opening
        // channels - with nothing left on screen to show it on.
        val cancelled = synchronized(this) {
            vncClient = client
            stopRequested
        }
        if (cancelled) {
            log("Session was stopped before the connection could start")
            return client
        }

        configureClient(client, profile)
        attachActivity(activityRef)
        setVideoSurface(surfaceRef)

        log("Connecting to server")
        observer.onConnecting()
        client.connect(profile.host, profile.port)

        if (client.ownsReceiveLoop) {
            // Pump the protocol's own message loop until it ends (server closed,
            // or cleanup / stop requested from another thread).
            log("Running message loop")
            while (!stopRequested && client.processServerMessage()) {
            }
            latch.countDown()
        } else {
            // Block this thread until the connection terminates or stop() is called.
            // Waited for in slices rather than with one long deadline: a session is
            // expected to outlive any fixed timeout, and a single `await(1, HOURS)` would
            // tear down a perfectly healthy stream the moment it ran for an hour. Re-reading
            // the client in between also recovers from a termination callback that never
            // arrived, which would otherwise park this thread forever.
            while (!latch.await(SESSION_SLICE_MS, TimeUnit.MILLISECONDS)) {
                if (stopRequested || !client.connected) break
            }
        }

        // If the connection never got established, the attempt failed during setup
        // (e.g. RTSP handshake or launch). Surface the client's captured reason as a
        // connection error so the UI can explain the failure instead of leaving the
        // "connecting" spinner up.
        if (!client.connectionStarted) {
            throw IOException(client.failureReason
                    ?: App.appContext.getString(R.string.error_session_start_failed))
        }

        // A client that runs its own receive loop ends that loop either because we
        // asked it to stop, or because the connection broke. In the latter case it
        // has captured a human-readable reason, which has to reach the UI as an
        // error instead of a silent "disconnected". Callback-driven clients leave
        // the reason empty, so this stays a no-op for them.
        if (!stopRequested) client.failureReason?.let { throw IOException(it) }

        return client
    }

    /**
     * Invoked (from the kernel callback thread) once streaming has started.
     * Publishes the live client + messenger to the observer.
     */
    private fun onClientConnected(token: Any) {
        val client: VncClient
        val activeMessenger: Messenger

        // The ownership test and the reading of the fields it protects happen in one step:
        // deciding elsewhere that this run is still in charge, then reading `vncClient`, lets
        // a session that was replaced in between publish *another* run's client to the UI.
        synchronized(this) {
            if (token !== sessionToken) {
                // A connection that has already been replaced reporting that it came up: making
                // the observer look at it would point the UI's input at a dead client.
                log("Ignoring a connect report from a superseded session")
                return
            }

            client = vncClient ?: return
            activeMessenger = messenger ?: Messenger(client).also { messenger = it }
        }

        log("Connected to server")
        observer.onConnected(client, activeMessenger)
    }

    private fun stopSession(token: Any) {
        val ownedMessenger: Messenger?
        val ownedClient: VncClient?
        val superseded: Boolean

        synchronized(this) {
            superseded = token !== sessionToken
            ownedMessenger = messenger
            ownedClient = vncClient
            if (!superseded) {
                messenger = null
                vncClient = null
                // Allows [start] to accept this instance again, which is exactly what
                // [restart] waits for before it kicks off the next connection.
                sessionThread = null
            }
        }

        if (superseded) {
            // Nothing above this run may be touched: the fields it would clear or shut down
            // are the next session's. Cleanup of this run's own client has already happened
            // in [stop], which restart calls before handing the session over.
            log("Session stopped behind a newer one; leaving its fields alone")
            return
        }

        // Outside the lock on purpose: shutting the sender down waits for the thread, and
        // [stop] takes the same monitor from the UI thread.
        ownedMessenger?.shutdown()
        ownedClient?.cleanup()
        log("Session stopped")
    }


    /*********************************************************************************/

    private fun configureClient(vncClient: VncClient, profile: ServerProfile) {
        // Common (RFB shaped) configuration. Clients that do not implement a part
        // of it simply ignore the values.
        vncClient.configure(profile.securityType, profile.useLocalCursor,
                profile.imageQuality, profile.useRawEncoding)

        if (profile.useRepeater)
            vncClient.setupRepeater(profile.idOnRepeater)

        vncClient.setInputDisabled(profile.viewMode == ServerProfile.VIEW_MODE_NO_INPUT)
        vncClient.setFrameBufferUpdatesPaused(profile.viewMode == ServerProfile.VIEW_MODE_NO_VIDEO)

        // Not part of the profile: a viewer-wide choice, pushed by the UI layer before start.
        // A protocol without audio has nothing to silence and ignores it.
        vncClient.audioMuted = audioMutedRef

        // Protocol-specific extras, present only for a GameStream host.
        (vncClient as? GameStreamExtras)?.let { client ->
            client.setStreamConfig(StreamConfig(
                    width = profile.streamWidth,
                    height = profile.streamHeight,
                    fps = profile.streamFps,
                    bitrateKbps = profile.bitrateKbps,
                    enableHdr = profile.enableHdr,
                    enableSops = profile.enableSops,
                    playLocalAudio = profile.playLocalAudio))

            client.setAppId(profile.preferredAppId)
            client.setClientUniqueId(profile.clientUniqueId)
            client.setServerCertificateDer(decodeServerCert(profile.serverCertBase64))
        }

        // ... or for a device driven over ADB, where the same quality knobs decide
        // the encoder arguments of the helper process.
        (vncClient as? AndroidControlExtras)?.setAndroidStreamConfig(
                AndroidStreamConfig.forProfile(profile))
    }

    private fun decodeServerCert(base64: String): ByteArray? {
        if (base64.isBlank()) return null
        return runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
    }

    private fun handleConnectionEnd(result: Result<*>, superseded: Boolean) {
        runCatching {
            result.onFailure {
                if (!stopRequested && !superseded) {
                    logE("Connection error", it)
                    observer.onConnectionError(it)
                }
            }

            if (superseded) {
                // A newer session is running; telling the UI that "the" session ended would
                // drop it back to the disconnected screen while a live stream is up.
                log("Connection of a superseded session ended; not reporting it")
                return
            }

            log("Disconnected from server")
            observer.onDisconnected()

        }.onFailure {
            log("Error handling connection end: $it")
        }
    }

    private fun log(msg: String) = Log.i(tag, msg)
    private fun logE(msg: String, e: Throwable?) = Log.e(tag, msg, e)

    companion object {
        private val sessionCounter = AtomicInteger(0)

        /** How long [restart] lets the old session take to finish before forcing on. */
        private const val RESTART_JOIN_TIMEOUT_MS = 3000L

        /** How often a callback-driven session re-checks that its connection is still there. */
        private const val SESSION_SLICE_MS = 30_000L
    }
}
