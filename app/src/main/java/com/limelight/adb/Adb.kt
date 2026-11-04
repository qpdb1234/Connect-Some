/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.limelight.util.Log
import com.limelight.App
import com.limelight.BuildConfig
import com.limelight.R
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * An ADB *host* session running on top of one [AdbChannel].
 *
 * Responsibilities, in the order the connection sequence needs them:
 *
 *  1. authenticate with [AdbKeyPair] (CNXN -> AUTH signature -> AUTH public -> CNXN);
 *  2. multiplex ADB's logical channels on the single wire ([open], [handleIn],
 *     [handleOut]) - each `BufferStream` is one `localId`;
 *  3. install and launch the controlled-side helper ([startServer], [startScrcpyServer]);
 *  4. hand out shell / forward streams the session layer uses as byte pipes.
 *
 * The helper is *not* an installed app: the packaged APK in `res/raw` is pushed to
 * `/data/local/tmp` and its classes are run with `app_process`, which is what makes
 * the hidden-API access of the controlled-side code work under the shell uid.
 *
 * Every failure path records a human-readable [failureReason] because the setup
 * phase happens off the UI thread inside a session that can only report a throwable.
 */
class Adb(
        private val channel: AdbChannel,
        keyPair: AdbKeyPair,
) {

    companion object {
        private const val TAG = "ConnectSomeAdb"

        /** Must match the `LocalServerSocket` name in com.limelight.androidserver.Scrcpy. */
        const val SCRCPY_SOCKET_NAME = "connectsome_scrcpy"

        /** Prefix adbd expects in front of an abstract unix socket name. */
        private const val LOCAL_ABBSTRACT_SERVICE = "localabstract:"

        private const val REMOTE_TMP_DIR = "/data/local/tmp"
        private const val SERVER_MAIN_CLASS = "com.limelight.androidserver.Server"
        private const val SCRCPY_MAIN_CLASS = "com.limelight.androidserver.Scrcpy"

        /**
         * Version-suffixed so an updated ConnectSome overwrites its own predecessor
         * instead of reusing a stale jar of a different protocol revision.
         */
        @JvmStatic
        val remoteJarPath: String = "$REMOTE_TMP_DIR/connectsome_server_${BuildConfig.VERSION_CODE}.jar"

        /**
         * Covers jars of *other* versions too, so they get cleaned up on push. Restricted
         * to the `connectsome_server_` family on purpose: the helper keeps its own log and
         * its saved screen-off timeout in `/data/local/tmp/connectsome_*`, and those must
         * survive a re-push.
         */
        private val remoteJarGlob: String = "$REMOTE_TMP_DIR/connectsome_server_*"

        /** Octal 0666: adbd's `sync:` sends the file mode as a decimal int. */
        private const val SYNC_FILE_MODE = 33206

        /** mtime sent with DONE. Fixed so a re-push always looks like a new file. */
        private const val SYNC_DONE_TIMESTAMP = 1704038400

        /**
         * `sync:` DATA payload cap. adbd's file sync service accepts up to 64 KB per DATA
         * frame (`SYNC_DATA_MAX`) and the channel's WRTE limit no longer binds here, so the
         * helper jar travels in 64 KB steps instead of the 10 KB one that a decade-old host
         * implementation used.
         */
        private const val SYNC_CHUNK_SIZE = 64 * 1024 - 8

        /** Keep-alive headroom for a single WRTE frame. */
        private const val WRITE_MARGIN = 128

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CMD_TIMEOUT_MS = 20_000L
        private const val PUSH_TIMEOUT_MS = 120_000L
        private const val RPC_TIMEOUT_MS = 15_000L

        /** Poll slice used by every wait-for-notify loop, so failures cannot hang forever. */
        private const val WAIT_SLICE_MS = 200L

        /** Characters that would end (or corrupt) one line of the helper's request protocol. */
        private val RPC_LINE_BREAK_CHARS = charArrayOf('\n', '\r')

        /**
         * Boot grace for the RPC process. The helper runs with `logMode = 2`, which
         * keeps its log lines out of stdout, so there is no reliable ready-marker to
         * wait for; the Scrcpy socket retry loop absorbs the slack.
         */
        private const val SERVER_START_TIMEOUT_MS = 2_000L
        private const val PROBE_READ_TIMEOUT_MS = 2200

        /** Patience for adbd's reply to our CNXN, before any authorisation prompt is involved. */
        private const val HANDSHAKE_FIRST_REPLY_MS = 8_000

        /**
         * Most logical channels one session is expected to hold: the RPC shell, the
         * encoder shell, the video socket, a handful of short lived commands.
         *
         * A bound is needed because [handleIn] creates a stream for any `arg1` it has not
         * seen before - that is how the answer to an OPEN arrives before the requester has
         * registered anything. Without a ceiling, a peer that keeps inventing ids grows the
         * maps until the process runs out of memory, and the session thread has no way to
         * notice that it is being fed nonsense.
         */
        private const val MAX_CONCURRENT_STREAMS = 64

        /**
         * Largest answer the resident helper is believed to produce in one RPC reply.
         *
         * The reply starts with the length twice over, which the helper means as an
         * integrity check - but the two ints are read before any of the payload exists, so
         * an out of step pipe otherwise reaches [Buffer.readByteArray] with a number taken
         * on faith. Nothing in the protocol needs this much: the longest real answers are a
         * window list and a log tail.
         */
        private const val MAX_RPC_RESPONSE = 8 * 1024 * 1024

        /**
         * Largest backlog one channel is allowed to accumulate before it is hung up on.
         *
         * Every WRTE is acknowledged the moment it is buffered (see [handleIn]), which is
         * how this side switches ADB's own flow control off: the device is told it may send
         * the next chunk whether or not anyone above has read the last one. A consumer that
         * stops reading - a decoder that died, a UI that went away - therefore turns the
         * wire into a queue nobody drains, and the only thing standing between it and an
         * OutOfMemoryError is this number. It is deliberately far above any real backlog:
         * at a few megabits per second it is tens of seconds of picture.
         */
        private const val MAX_STREAM_BACKLOG = 32 * 1024 * 1024

        @Throws(IOException::class)
        fun open(transport: AdbTransport, keyPair: AdbKeyPair): Adb {
            val channel = when (transport) {
                is AdbTransport.Tcp -> tcpChannel(transport)
                is AdbTransport.Usb -> UsbChannel(usbManager(), findUsbDevice(transport.deviceName))
            }
            return Adb(channel, keyPair)
        }

        @Throws(IOException::class)
        private fun tcpChannel(transport: AdbTransport.Tcp): TcpChannel =
                try {
                    TcpChannel(transport.host, transport.port)
                } catch (e: Exception) {
                    throw IOException(App.appContext.getString(
                            R.string.error_adb_service_unreachable, transport.host, transport.port,
                            e.message ?: e.javaClass.simpleName), e)
                }

        /**
         * Action of the broadcast that carries the USB permission grant. Nobody
         * listens for it: the grant is remembered by [UsbManager], so the next
         * connect simply passes [findUsbDevice]. Only the request needs a token.
         */
        private const val USB_PERMISSION_ACTION = "com.connectsome.app.USB_PERMISSION"

        /** The device may be present but not trusted yet; both cases need distinct advice. */
        @Throws(IOException::class)
        @JvmStatic
        fun findUsbDevice(deviceName: String): UsbDevice {
            val manager = usbManager()
            val device = manager.deviceList[deviceName]
                    ?: throw IOException(App.appContext.getString(R.string.error_adb_usb_not_found, deviceName))
            if (!manager.hasPermission(device)) {
                // Ask the system once; the user still has to tick "always allow" if
                // they want later connects to run without a prompt.
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val token = PendingIntent.getBroadcast(App.appContext, 0, Intent(USB_PERMISSION_ACTION), flags)
                manager.requestPermission(device, token)
                throw IOException(App.appContext.getString(R.string.msg_android_usb_permission))
            }
            return device
        }

        @JvmStatic
        fun usbManager(): UsbManager =
                App.appContext.getSystemService(Context.USB_SERVICE) as UsbManager

        /**
         * True when [host]:[port] answers an ADB CNXN at all. Used by the editor to
         * tell "wrong address" apart from "not authorised". Leaves no session behind.
         */
        @Throws(IOException::class)
        fun probe(host: String, port: Int, keyPair: AdbKeyPair): Boolean {
            val probeChannel = try {
                TcpChannel(host, port, PROBE_READ_TIMEOUT_MS)
            } catch (_: Exception) {
                return false
            }
            return try {
                val adb = Adb(probeChannel, keyPair)
                adb.close()
                true
            } catch (_: Exception) {
                false
            } finally {
                probeChannel.close()
            }
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Localized, displayable cause of the first failure seen on this session. */
    @Volatile
    var failureReason: String? = null
        private set

    /** The shell that hosts the resident RPC (`Server`) process. Kept across sessions. */
    @Volatile
    var serverShell: BufferStream? = null
        private set

    /** The shell hosting the per-session Scrcpy encoder. Null unless a session runs. */
    @Volatile
    var scrcpyShell: BufferStream? = null
        private set

    /**
     * Largest WRTE payload the device accepts, negotiated in [connect]. Volatile because
     * the outbound loop reads it on its own thread while a retry on the connect path
     * writes it again.
     */
    @Volatile
    private var maxData = AdbProtocol.CONNECT_MAXDATA

    /**
     * Next local channel id. Atomic because [open] is reached from several threads at
     * once - the session's video loop, an RPC caller, a teardown killing the encoder -
     * and a plain `pool++` lets two of them take the same id. Two channels sharing an id
     * is not a cosmetic fault: the device's traffic for one is handed to the other.
     */
    private val localIdPool = java.util.concurrent.atomic.AtomicInteger(1)
    /**
     * Whether this session has been torn down.
     *
     * Volatile because it is written under [close]'s monitor but read without one: from the
     * poll loops below, from [isAlive] on the caller's thread, and from the inbound pump.
     * A plain field lets a poll loop cache it and sit out its whole deadline on a wire that
     * is already gone.
     */
    @Volatile
    private var closing = false

    private val connectionStreams = ConcurrentHashMap<Int, BufferStream>()
    private val openStreams = ConcurrentHashMap<Int, BufferStream>()

    /**
     * Local ids of the [open] calls still waiting for an answer. A stream only joins
     * [openStreams] while its requester is still there, so a reply that arrives after a
     * timeout cannot leave an entry nobody will ever take out again.
     */
    private val pendingOpens = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Local ids whose requester gave up. The device can still honour such an OPEN late;
     * its traffic is dropped instead of building a stream for a channel nobody owns.
     */
    private val abandonedOpens = ConcurrentHashMap.newKeySet<Int>()

    private val sendBuffer = Buffer()

    /** Guards the single request/response pipe of [getStringResponse]. */
    private val rpcLock = Any()

    private val handleInThread = Thread(this::handleIn, "connectsome-adb-in")
    private val handleOutThread = Thread(this::handleOut, "connectsome-adb-out")

    /** Whether the ADB wire is still usable (authenticated and not closed). */
    val isAlive: Boolean
        get() = !closing && handleInThread.isAlive && handleOutThread.isAlive

    /**
     * Whether the device still answers channel requests on this wire.
     *
     * [isAlive] cannot tell that: when a peer vanishes without a FIN (device asleep,
     * wireless adbd restarted, cable torn out mid-buffer) both pump threads stay parked on
     * the socket indefinitely and a half-dead TCP line reports itself as live forever.
     * The only honest test is a round trip, so this opens a channel that spawns no work
     * (`shell:true` runs and exits on its own) and hangs up as soon as - or instead of -
     * the device answering. A wire that cannot produce an OKAY within [timeoutMs] is not
     * one a reconnect should bet on.
     */
    @Throws(InterruptedException::class)
    fun isResponsive(timeoutMs: Long): Boolean {
        if (!isAlive) return false
        val stream = try {
            open("shell:true", true, timeoutMs)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (!closing) Log.w(TAG, "adb liveness probe failed", e)
            return false
        }
        runCatching { stream.close() }
        return true
    }

    init {
        connect(keyPair)
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private fun connect(keyPair: AdbKeyPair) {
        try {
            channel.write(AdbProtocol.generateConnect())
            // Only the very first reply is bounded. adbd answers a CNXN within milliseconds,
            // so silence here means the peer is not adbd on that port rather than a user who
            // has not tapped 允许 yet - the AUTH reads below must keep waiting indefinitely,
            // because those genuinely block on a human. A probing caller already asked for a
            // tighter bound than ours, so the smaller of the two wins.
            val firstReplyMs = if (channel.baselineReadTimeoutMs > 0)
                channel.baselineReadTimeoutMs else HANDSHAKE_FIRST_REPLY_MS
            channel.setReadTimeout(firstReplyMs)
            var message = AdbProtocol.AdbMessage.parseAdbMessage(channel)
            channel.setReadTimeout(channel.baselineReadTimeoutMs)

            if (message.command == AdbProtocol.CMD_AUTH) {
                channel.write(AdbProtocol.generateAuth(
                        AdbProtocol.AUTH_TYPE_SIGNATURE, keyPair.signPayload(message.payload)))
                message = AdbProtocol.AdbMessage.parseAdbMessage(channel)

                if (message.command == AdbProtocol.CMD_AUTH) {
                    // Second chance: the device has never seen this key, so hand it the
                    // public key. It is now up to the user on the controlled device.
                    channel.write(AdbProtocol.generateAuth(
                            AdbProtocol.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes))
                    message = AdbProtocol.AdbMessage.parseAdbMessage(channel)
                }
            }

            if (message.command != AdbProtocol.CMD_CNXN) {
                channel.close()
                throw IOException(App.appContext.getString(R.string.error_android_adb_auth))
            }

            // `arg1` is the peer's answer to our advertised [AdbProtocol.CONNECT_MAXDATA]:
            // the largest WRTE payload it accepts. It is a number off the wire, so it has
            // to be checked before it is used as an arithmetic operand - `maxData -
            // WRITE_MARGIN` is the size of every chunk written, and a small or negative
            // `arg1` turns that into `ByteArray(-n)`, or into a zero length chunk that the
            // write loop then spins on forever without ever draining the buffer.
            maxData = message.arg1.coerceAtMost(AdbProtocol.CONNECT_MAXDATA)
            if (maxData <= WRITE_MARGIN)
                throw IOException("Device proposed an unusable maxdata of ${message.arg1}")

            handleInThread.priority = Thread.MAX_PRIORITY
            handleInThread.start()
            handleOutThread.start()
        } catch (e: Exception) {
            failureReason = e.message ?: e.javaClass.simpleName
            channel.close()
            if (e is IOException) throw e
            throw IOException(failureReason, e)
        }
    }

    // ------------------------------------------------------------------
    // Controlled-side helper lifecycle
    // ------------------------------------------------------------------

    /**
     * Pushes the packaged helper if needed and starts the resident RPC process.
     *
     * The RPC process stays alive after a session ends (see [AdbManager]) so the next
     * connection skips the slow part; it is the Scrcpy encoder that is per-session.
     */
    @Synchronized
    @Throws(IOException::class, InterruptedException::class)
    fun startServer() {
        try {
            if (needsServerPush()) {
                runAdbCmd("rm $remoteJarGlob ")
                App.appContext.resources.openRawResource(R.raw.android_server).use {
                    pushFile(it, remoteJarPath)
                }
            }
        } catch (e: Exception) {
            failureReason = App.appContext.getString(
                    if (isOpenTimeout(e)) R.string.error_adb_channel_occupied
                    else R.string.error_adb_push_failed,
                    e.message ?: e.javaClass.simpleName)
            Log.e(TAG, "server push failed", e)
            throw IOException(failureReason, e)
        }

        try {
            serverShell?.close()
            val shell = getShell()
            serverShell = shell
            shell.write(ByteBuffer.wrap(
                    "CLASSPATH=$remoteJarPath app_process / $SERVER_MAIN_CLASS\n"
                            .toByteArray(StandardCharsets.UTF_8)))
            awaitStreamQuiet(shell, 0, SERVER_START_TIMEOUT_MS)
        } catch (e: Exception) {
            failureReason = App.appContext.getString(
                    if (isOpenTimeout(e)) R.string.error_adb_channel_occupied
                    else R.string.error_adb_server_launch,
                    e.message ?: e.javaClass.simpleName)
            Log.e(TAG, "server start failed", e)
            throw IOException(failureReason, e)
        }
    }

    /**
     * Whether a failure chain ends in an OPEN that adbd received but never answered.
     * That specific silence means the connection slot, not the wire and not the helper:
     * devices that serve one host at a time answer the handshake of a waiting client (or
     * replay it from cache) and then drop every channel request behind the incumbent's.
     */
    private fun isOpenTimeout(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is OpenTimedOut) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Launches one Scrcpy encoder process for a session.
     *
     * @param options already rendered as space separated `key=value` pairs; the key
     *   names and the default-omission rule are fixed by the helper's `Options.parse`,
     *   which rejects an argument without `=`.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun startScrcpyServer(options: String): BufferStream {
        val shell = getShell()
        scrcpyShell = shell
        shell.write(ByteBuffer.wrap(
                ("app_process -Djava.class.path=$remoteJarPath / $SCRCPY_MAIN_CLASS $options \n")
                        .toByteArray(StandardCharsets.UTF_8)))
        return shell
    }

    /** Debug builds always re-push: the payload changes without the version code changing. */
    private fun needsServerPush(): Boolean {
        if (BuildConfig.DEBUG) return true
        return try {
            !runAdbCmd("ls $remoteJarGlob").contains(remoteJarPath)
        } catch (e: Exception) {
            Log.w(TAG, "cannot inspect $remoteJarGlob, pushing", e)
            true
        }
    }

    /** Stops just the encoder, leaving the ADB session and the RPC process intact. */
    @Throws(InterruptedException::class)
    fun killScrcpyProcesses() {
        runCatching {
            runAdbCmd("ps -ef | grep $SCRCPY_MAIN_CLASS | grep -v grep | awk '{print \$2}' | xargs kill -9")
        }
    }

    /**
     * Makes way for a new encoder.
     *
     * [startScrcpyServer] always spawns a fresh process, and two processes cannot bind
     * the same abstract socket: the survivor of the previous session is the one holding
     * `connectsome_scrcpy`, and it is a helper with no client left. Its own exit is only
     * triggered by its sockets closing, which a session that kept it alive never did.
     * So the old process goes here, whatever the "keep the helper" preference said -
     * what actually makes a reconnect cheap is the wire and the resident RPC process,
     * neither of which is touched.
     */
    fun clearStaleEncoder() {
        runCatching { killScrcpyProcesses() }
        runCatching { scrcpyShell?.close() }
    }

    // ------------------------------------------------------------------
    // Logical channels
    // ------------------------------------------------------------------

    /**
     * Opens a channel to [destination] (`shell:`, `sync:`, `localabstract:`, `tcp:`...)
     * and waits for the device to accept it.
     *
     * A negative local id marks a channel whose writes must not be coalesced, which is
     * how `sync:` and `tcpip:` get their one-write-one-frame behaviour.
     *
     * [timeoutMs] is per call because the callers know their own patience: a shell
     * command can afford the full [CONNECT_TIMEOUT_MS], while something that is going to
     * be retried in a loop cannot - a long timeout multiplied by many retries is how a
     * connection ends up silent for minutes on one progress line.
     *
     * A timeout is answered with a CLSE rather than silence: the device may still accept
     * the request a moment later, and an accepted-but-abandoned channel would occupy the
     * first seat in a helper's ordered `accept` sequence.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun open(destination: String, canMultipleSend: Boolean,
                    timeoutMs: Long = CONNECT_TIMEOUT_MS): BufferStream {
        val localId = localIdPool.getAndIncrement() * (if (canMultipleSend) 1 else -1)
        pendingOpens.add(localId)
        var handedOff = false
        try {
            sendBuffer.write(AdbProtocol.generateOpen(localId, destination))

            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val stream = openStreams.remove(localId)
                if (stream != null) {
                    handedOff = true
                    return stream
                }
                if (closing) throw IOException(failureReason
                        ?: App.appContext.getString(R.string.error_adb_closed))
                if (System.currentTimeMillis() > deadline) {
                    abandon(localId, destination)
                    throw OpenTimedOut(destination)
                }
                // Polled rather than signalled: Kotlin does not expose Object.wait() on a
                // Kotlin-typed receiver, and one slice of latency per channel open is far
                // below the cost of the round-trip we are waiting for anyway.
                Thread.sleep(WAIT_SLICE_MS)
            }
        } finally {
            // Any path out of here that did not produce a stream - timeout, interrupt,
            // closed wire - means no one is going to read this id again, so whatever the
            // device still sends for it has to be dropped rather than turned into a stream.
            pendingOpens.remove(localId)
            if (!handedOff) abandonedOpens.add(localId)
        }
    }

    /**
     * Gives up on an OPEN politely: tell the device to drop the channel, and stop
     * trusting anything it still sends for that id.
     */
    private fun abandon(localId: Int, destination: String) {
        abandonedOpens.add(localId)
        runCatching {
            sendBuffer.write(AdbProtocol.generateClose(localId, 0))
        }.onFailure { Log.w(TAG, "could not close timed out channel $destination", it) }
    }

    /**
     * An OPEN the device never answered. Distinct from a refused OPEN because the request
     * may still be served late, which is exactly what mis-pairs a channel sequence.
     */
    class OpenTimedOut(destination: String) :
            IOException(App.appContext.getString(R.string.error_adb_channel_timeout, destination))

    /** A shell/forward channel that is closed by the device once its work is done. */
    @Throws(IOException::class, InterruptedException::class)
    private fun awaitStreamClose(stream: BufferStream, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!stream.isClosed) {
            if (closing) throw IOException(failureReason
                    ?: App.appContext.getString(R.string.error_adb_closed))
            if (System.currentTimeMillis() > deadline)
                throw IOException(App.appContext.getString(R.string.error_adb_response_timeout))
            Thread.sleep(WAIT_SLICE_MS)
        }
    }

    /**
     * Blocks until the stream has at least [needed] bytes queued, or the deadline passes.
     *
     * Everything above deliberately leaves this unanswered rather than waiting on it: a
     * [BufferStream.readByteArray] parks on the queue until the bytes show up, and a helper
     * that is alive but silent would then hold [rpcLock] for the rest of the session - which
     * is not a slow response, it is every later RPC and the UI thread waiting behind it.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun awaitBytes(stream: BufferStream, needed: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (stream.isClosed) throw IOException(failureReason
                    ?: App.appContext.getString(R.string.error_adb_server_off))
            if (stream.size >= needed) return true
            if (Thread.currentThread().isInterrupted) return false
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(WAIT_SLICE_MS)
        }
    }

    /**
     * Blocks until the stream's pending output stops growing - the signal that a
     * freshly launched process has printed its start-up banner.
     *
     * [expectedBytes] is a fast path: when more than that many bytes have piled up,
     * the process is considered ready without waiting for the output to settle.
     *
     * The interrupt status is *read*, not consumed: [Thread.interrupted] would clear the
     * flag, and the cancellation that this wait sits in front of would then be forgotten by
     * every later sleep in the same connect sequence.
     */
    @Throws(InterruptedException::class)
    private fun awaitStreamQuiet(stream: BufferStream, expectedBytes: Int, timeoutMs: Long) {
        val sizes = ArrayList<Int>(8)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < deadline) {
            // Looked at before sleeping: this wait sits in front of every RPC reply, and a
            // blind slice on top of the round-trip makes an answer that already arrived
            // look like a slow device.
            val newSize = stream.size
            if (newSize != 0) {
                if (expectedBytes > 0 && newSize > expectedBytes) return
                sizes.add(newSize)
                if (sizes.size > 1 && sizes[sizes.size - 2] == newSize) return
            }
            Thread.sleep(WAIT_SLICE_MS)
        }
    }

    /** One-shot shell command; returns whatever it wrote before the channel closed. */
    @Throws(IOException::class, InterruptedException::class)
    fun runAdbCmd(cmd: String): String {
        val bufferStream = open("shell:$cmd", true)
        awaitStreamClose(bufferStream, CMD_TIMEOUT_MS)
        return String(bufferStream.readByteArrayBeforeClose().array(), StandardCharsets.UTF_8)
    }

    /** A long-lived shell, used for RPC and for hosting the helper processes. */
    @Throws(IOException::class, InterruptedException::class)
    fun getShell(): BufferStream = open("shell:", true)

    @Throws(IOException::class, InterruptedException::class)
    fun tcpForward(port: Int): BufferStream {
        val bufferStream = open("tcp:$port", true)
        if (bufferStream.isClosed) throw IOException("error forward")
        return bufferStream
    }

    /**
     * Forward to the helper's abstract unix socket - the video and control pipes.
     *
     * [socketName] is the bare name (`connectsome_scrcpy`); this function owns the
     * `localabstract:` prefix. A caller that supplies the prefix anyway is corrected
     * rather than passed on: adbd treats the doubled form as a socket whose name contains
     * a colon, fails to connect, and says so in a line that reads like a device fault -
     * which is how every single ADB connection used to fail here.
     *
     * [timeoutMs] bounds only the wait for adbd's answer to the OPEN; the caller still
     * has to tell a refused socket (nothing listening yet) from an unanswered one.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun localSocketForward(socketName: String, timeoutMs: Long = CONNECT_TIMEOUT_MS): BufferStream {
        val bareName = socketName.removePrefix(LOCAL_ABBSTRACT_SERVICE)
        val bufferStream = open("$LOCAL_ABBSTRACT_SERVICE$bareName", true, timeoutMs)
        if (bufferStream.isClosed) throw IOException("error forward")
        return bufferStream
    }

    /**
     * Restarts adbd listening on TCP so an authorised device can be reached wirelessly.
     *
     * Deliberately does NOT flag the session as closing first: [open] aborts on `closing`,
     * and the whole point of the call is to collect adbd's reply.
     *
     * That reply is best effort either way - adbd rebinds by exiting, which usually tears
     * the wire under the last few bytes it wrote. So a closed wire here is the success
     * path, and what came through before it died is returned as-is. The caller cannot
     * trust any of it as proof of a listener, which is why it follows up with [probe].
     */
    @Throws(IOException::class, InterruptedException::class)
    fun restartOnTcpip(port: Int): String = try {
        val bufferStream = open("tcpip:$port", false)
        try {
            awaitStreamClose(bufferStream, CMD_TIMEOUT_MS)
        } catch (e: IOException) {
            Log.i(TAG, "wire ended during tcpip restart: ${e.message}")
        }
        String(bufferStream.readByteArrayBeforeClose().array(), StandardCharsets.UTF_8)
    } finally {
        close()
    }

    /**
     * Pushes [file] to [remotePath] through the `sync:` service:
     * `SEND <path>,mode` then `DATA` chunks, then `DONE <mtime>` and `QUIT`.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun pushFile(file: InputStream, remotePath: String) {
        val bufferStream = open("sync:", false)
        try {
            val sendString = "$remotePath,$SYNC_FILE_MODE"
            bufferStream.write(AdbProtocol.generateSyncHeader("SEND", sendString.length))
            bufferStream.write(ByteBuffer.wrap(sendString.toByteArray(StandardCharsets.UTF_8)))

            val chunk = ByteArray(SYNC_CHUNK_SIZE)
            var len = file.read(chunk)
            while (len > 0) {
                bufferStream.write(AdbProtocol.generateSyncHeader("DATA", len))
                bufferStream.write(ByteBuffer.wrap(chunk, 0, len))
                len = file.read(chunk)
            }

            bufferStream.write(AdbProtocol.generateSyncHeader("DONE", SYNC_DONE_TIMESTAMP))
            bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0))
            awaitStreamClose(bufferStream, PUSH_TIMEOUT_MS)
        } finally {
            runCatching { file.close() }
            bufferStream.close()
        }
    }

    // ------------------------------------------------------------------
    // RPC to the resident Server process
    // ------------------------------------------------------------------

    /**
     * Calls one of the helper's `/<request>?a=b&c=d` commands over the RPC shell's
     * stdin/stdout. The reply is length-prefixed twice, and the doubled int is the
     * only integrity check the helper offers.
     *
     * Serialized: the shell is one pipe shared by every request, so two interleaved
     * calls would read each other's answers.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun getStringResponse(request: String, vararg args: String): String {
        val shell = serverShell?.takeIf { !it.isClosed }
                ?: throw IOException(App.appContext.getString(R.string.error_adb_server_off))
        return synchronized(rpcLock) {
            String(getResponse(shell, request, args), StandardCharsets.UTF_8)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun getResponse(shell: BufferStream, request: String, args: Array<out String>): ByteArray {
        val sb = StringBuilder("/").append(request).append('?')
        for (arg in args) sb.append(arg).append('&')
        if (sb.endsWith("&")) sb.setLength(sb.length - 1)
        val requestCmd = sb.append('\n').toString()

        // One line is one request, and the helper's reader has no quoting or escaping: a
        // line break inside a request name or an argument would be executed as a second
        // command of the caller's choosing. Nothing in this app sends such a value, so a
        // line that does not fit the protocol is a programming error worth stopping on.
        if (requestCmd.indexOfAny(RPC_LINE_BREAK_CHARS) != requestCmd.length - 1 ||
                requestCmd.indexOf('\u0000') >= 0)
            throw IOException("RPC request contains a line break: $request")

        shell.readAllBytes()
        shell.write(ByteBuffer.wrap(requestCmd.toByteArray(StandardCharsets.UTF_8)))
        // The shell echoes the command back; skip it before parsing the answer. Bounded,
        // because that echo is a blocking read on a pipe this side does not control.
        if (!awaitBytes(shell, requestCmd.length + 1, RPC_TIMEOUT_MS))
            throw IOException(App.appContext.getString(R.string.error_adb_response_timeout))
        shell.readByteArray(requestCmd.length + 1)
        awaitStreamQuiet(shell, 8, RPC_TIMEOUT_MS)
        // [awaitStreamQuiet] returns on a quiet pipe, not on a complete answer, and the
        // reads below park until data shows up - so without this a helper that never
        // replies would hold [rpcLock] until the session is torn down.
        if (shell.size < 8) throw IOException(App.appContext.getString(R.string.error_adb_server_silent))

        val len1 = shell.readInt()
        val len2 = shell.readInt()
        if (len1 != len2) throw IOException("bad data format")
        if (len1 !in 0..MAX_RPC_RESPONSE)
            throw IOException(App.appContext.getString(R.string.error_adb_response_too_large, len1))
        // Same bound as the echo above: the header promised [len1] bytes, and a helper that
        // died after writing the length but before the body would otherwise park here with
        // the lock taken.
        if (!awaitBytes(shell, len1, RPC_TIMEOUT_MS))
            throw IOException(App.appContext.getString(R.string.error_adb_response_timeout))
        return shell.readByteArray(len1).array()
    }

    // ------------------------------------------------------------------
    // Wire pumps
    // ------------------------------------------------------------------

    private fun handleIn() {
        try {
            while (!Thread.interrupted()) {
                val message = AdbProtocol.AdbMessage.parseAdbMessage(channel)

                // Anything the device sends that is not an answer to one of our channels
                // must not reach the stream bookkeeping below: the `when` would create a
                // stream for it and then do nothing with it.
                if (message.command != AdbProtocol.CMD_OKAY
                        && message.command != AdbProtocol.CMD_WRTE
                        && message.command != AdbProtocol.CMD_CLSE) continue

                // On anything the device sends, arg1 is *our* local id.
                val localId = message.arg1
                if (localId in abandonedOpens) {
                    // An OPEN the requester walked away from can still be served late.
                    // Hang up on the channel instead of feeding a buffer nobody reads.
                    if (message.command != AdbProtocol.CMD_CLSE)
                        sendBuffer.write(AdbProtocol.generateClose(localId, message.arg0))
                    continue
                }

                var bufferStream = connectionStreams[localId]
                if (bufferStream == null) {
                    // A CLSE for a channel that is not here is already what we want.
                    if (message.command == AdbProtocol.CMD_CLSE) continue
                    if (connectionStreams.size >= MAX_CONCURRENT_STREAMS) {
                        if (failureReason == null) failureReason =
                                App.appContext.getString(R.string.error_adb_too_many_channels)
                        Log.e(TAG, "device opened more than $MAX_CONCURRENT_STREAMS ADB channels")
                        close()
                        return
                    }
                    bufferStream = createNewStream(localId, message.arg0, localId > 0)
                }

                when (message.command) {
                    AdbProtocol.CMD_OKAY -> bufferStream.setCanWrite(true)
                    AdbProtocol.CMD_WRTE -> {
                        bufferStream.pushSource(message.payload)
                        if (bufferStream.size > MAX_STREAM_BACKLOG) {
                            Log.e(TAG, "channel $localId fell more than $MAX_STREAM_BACKLOG bytes behind")
                            if (failureReason == null) failureReason =
                                    App.appContext.getString(R.string.error_adb_backlog_too_large)
                            // Close first, mark it abandoned afterwards: the close callback
                            // takes the id out of [abandonedOpens], so the other order would
                            // undo itself and the device would keep feeding a dead channel.
                            bufferStream.close()
                            abandonedOpens.add(localId)
                            continue
                        }
                        sendBuffer.write(AdbProtocol.generateOkay(localId, message.arg0))
                    }
                    AdbProtocol.CMD_CLSE -> bufferStream.close()
                }
            }
        } catch (e: Exception) {
            // The wire is gone: remember why, because everything waiting on it will now
            // fail, and "channel open failed" is a misleading diagnosis when the real event
            // is that the device hung up on us.
            if (failureReason == null) failureReason = e.message ?: e.javaClass.simpleName
            if (!closing) Log.e(TAG, "adb inbound loop ended", e)
            close()
        }
    }

    private fun handleOut() {
        try {
            while (!Thread.interrupted()) {
                channel.write(sendBuffer.readNext())
                if (!sendBuffer.isEmpty) channel.write(sendBuffer.read(sendBuffer.size))
                channel.flush()
            }
        } catch (e: Exception) {
            if (failureReason == null) failureReason = e.message ?: e.javaClass.simpleName
            if (!closing) Log.e(TAG, "adb outbound loop ended", e)
            close()
        }
    }

    private fun createNewStream(localId: Int, remoteId: Int, canMultipleSend: Boolean): BufferStream {
        return BufferStream(false, canMultipleSend, object : BufferStream.UnderlySocketFunction {
            override fun connect(bufferStream: BufferStream) {
                connectionStreams[localId] = bufferStream
                // Only a requester that is still in [open] can take this stream out of
                // [openStreams]. Registering it any other way leaves an entry that nothing
                // will ever remove.
                if (localId in pendingOpens) openStreams[localId] = bufferStream
            }

            override fun write(bufferStream: BufferStream, buffer: ByteBuffer) {
                // ADB caps one WRTE payload; leave headroom for the header round-trip.
                while (buffer.hasRemaining()) {
                    val byteArray = ByteArray(minOf(maxData - WRITE_MARGIN, buffer.remaining()))
                    buffer.get(byteArray)
                    sendBuffer.write(AdbProtocol.generateWrite(localId, remoteId, byteArray))
                }
            }

            override fun flush(bufferStream: BufferStream) {
                sendBuffer.write(AdbProtocol.generateOkay(localId, remoteId))
            }

            override fun close(bufferStream: BufferStream) {
                // Do NOT touch openStreams here. A channel that finishes fast (a shell command
                // whose OKAY/WRTE/CLSE all land within one WAIT_SLICE) would otherwise have its
                // stream removed from openStreams before the still-polling open() call can take
                // it out, so the caller would time out on a channel the device already served.
                // Handing the (already closed) stream back is safe: its source buffer still holds
                // the WRTE payload, which readByteArrayBeforeClose reads out.
                connectionStreams.remove(localId)
                abandonedOpens.remove(localId)
                sendBuffer.write(AdbProtocol.generateClose(localId, remoteId))
            }
        })
    }

    /**
     * Tears the session down. Idempotent: the wire pumps call it when they die, and
     * [AdbManager] calls it when an idle session is recycled.
     */
    @Synchronized
    fun close() {
        if (closing) return
        closing = true
        for (bufferStream in connectionStreams.values.toTypedArray()) bufferStream.close()
        connectionStreams.clear()
        openStreams.clear()
        pendingOpens.clear()
        abandonedOpens.clear()
        serverShell = null
        scrcpyShell = null
        handleInThread.interrupt()
        handleOutThread.interrupt()
        channel.close()
        // Nothing to signal: the waiters in [open] and [awaitStreamClose] poll, and each
        // of them checks [closing] on every slice, so they leave within one slice.
    }
}
