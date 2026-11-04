/**
 * A clipboard-only RFB client.
 *
 * GameStream has no clipboard message - the protocol's control channel simply does not
 * define one - so a session that speaks it has nowhere to put a `copy`. This class opens
 * a second, throwaway VNC connection to the same machine beside the streaming session and
 * carries nothing over it but `CutText`. Video, input and audio stay on GameStream.
 *
 * It is deliberately not built on [RfbClientNative]: `rfbClientInitialise()` in
 * libvncserver always allocates a framebuffer and follows it with a full, non-incremental
 * update request, which would pull the whole desktop down at the exact moment the user
 * asked for text. Speaking the handful of handshake bytes that lead to a clipboard, and
 * then only ever exchanging message type 3 and 6, keeps the extra traffic at a few hundred
 * bytes and removes the dependency on the optional native library.
 *
 * Only the classic cut-text message is negotiated. The extended-clipboard capability would
 * buy UTF-8, and [ClipboardTextCodec] already handles its absence the way the full client
 * does - so a server that speaks neither still gets the same bytes as it would from
 * [RfbClientNative], `Strict Latin-1` included.
 */
package com.limelight.vnc

import com.limelight.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RfbClipboardBridge(
        private val host: String,
        private val port: Int,
        private val password: String,
        private val strictLatin1: Boolean,
        private val onCutText: (String) -> Unit,
) {

    companion object {
        private const val TAG = "RfbClipboardBridge"

        // RFB message types. FramebufferUpdate is conspicuously absent from the loop
        // below: see `loop()`.
        private const val MSG_SET_COLOR_MAP = 1
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3
        private const val MSG_CLIENT_CUT_TEXT = 6

        private const val SEC_TYPE_CONN_FAILED = 0
        private const val SEC_TYPE_NONE = 1
        private const val SEC_TYPE_VNC_AUTH = 2

        private const val CONNECT_TIMEOUT_MS = 5000

        /** A stream that has lost its framing produces a bogus length before it produces anything else. */
        private const val MAX_CUT_TEXT_BYTES = 1 shl 20
    }

    /**
     * True between a completed handshake and the end of the message loop. Everything the
     * session layer asks about this bridge is expressed through it: it either works or it
     * does not, and a dead bridge never fails a streaming session.
     */
    @Volatile
    var live = false
        private set

    /** Whether [stop] was called - used to keep a normal teardown out of the logs. */
    @Volatile
    private var stopped = false

    /**
     * Set while the socket is being written to. Owned by the connect/loop thread, used by
     * [sendCutText] on a caller thread, dropped by [stop] from a third - hence volatile for
     * both: a [stop] that never sees the socket cannot close it, and closing it is the only
     * thing that releases the blocked read in [loop]. The bridge thread is a daemon, so it
     * would simply sit there holding the server's client slot (and the password in memory)
     * for the rest of the process' life.
     */
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var out: DataOutputStream? = null

    private val writeLock = Any()

    /**
     * Value of the most recent cut text sent/received, so that copying something the host
     * already holds does not travel the wire twice - same rule [RfbClientNative] applies.
     */
    @Volatile
    private var lastText: String? = null

    private var desktopName: String? = null

    /**
     * Connects in the background. Returns immediately; [live] is the only signal that
     * anything worked, and a bridge that fails simply stays dead.
     */
    fun start() {
        Thread { run() }.apply {
            name = "RfbClipboardBridge"
            isDaemon = true
            start()
        }
    }

    /**
     * Closes the bridge. Returning here does not mean the thread has finished: closing the
     * socket is what releases its blocked read, and the thread is a daemon either way.
     */
    fun stop() {
        stopped = true
        live = false
        runCatching { socket?.close() }
    }

    /**
     * Puts [text] on the host's clipboard.
     *
     * @return false when the bridge is not usable, which is the caller's cue to fall back
     *         to whatever the protocol it really speaks offers for the same intent.
     */
    fun sendCutText(text: String): Boolean {
        if (!live) return false
        if (text == lastText) return true

        val stream = out ?: return false
        val bytes = ClipboardTextCodec.encode(text, strictLatin1)
        return runCatching {
            synchronized(writeLock) {
                stream.writeByte(MSG_CLIENT_CUT_TEXT)
                stream.writeByte(0); stream.writeByte(0); stream.writeByte(0)
                stream.writeInt(bytes.size)
                stream.write(bytes)
                stream.flush()
            }
            lastText = text
        }.onFailure {
            live = false
            if (!stopped) Log.w(TAG, "clipboard write failed: ${it.message}")
        }.isSuccess
    }

    /**************************************************************************
     * Connection thread
     **************************************************************************/

    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            val input = DataInputStream(s.getInputStream().buffered())
            val output = DataOutputStream(s.getOutputStream().buffered())

            handshake(input, output)
            out = output
            live = true
            Log.i(TAG, "clipboard bridge ready on $host:$port (${desktopName ?: "unnamed desktop"})")
            loop(input)
        } catch (e: Exception) {
            if (!stopped) Log.w(TAG, "clipboard bridge unavailable: ${e.message}")
        } finally {
            live = false
            out = null
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun handshake(input: DataInputStream, output: DataOutputStream) {
        val minor = negotiateVersion(input, output)
        val secType = selectSecurityType(input, output, minor)
        if (secType == SEC_TYPE_VNC_AUTH)
            answerVncChallenge(input, output)
        checkSecurityResult(input, minor, secType)

        // Shared-flag = 1: a clipboard lookup must not disconnect the operator's viewer.
        output.writeByte(1)
        output.flush()
        readServerInit(input)
    }

    /**
     * Reads the server's 12 byte version banner, replies with the highest minor both
     * sides have, and returns that negotiated minor.
     */
    private fun negotiateVersion(input: DataInputStream, output: DataOutputStream): Int {
        val banner = ByteArray(12).also { input.readFully(it) }.toString(StandardCharsets.US_ASCII)
        val match = Regex("RFB (\\d{3})\\.(\\d{3})").find(banner)
                ?: throw IOException("server did not send an RFB version banner")

        val major = match.groupValues[1].toInt()
        val serverMinor = match.groupValues[2].toInt()
        // 4.x replaces this whole handshake with a different security negotiation.
        if (major != 3 || serverMinor < 3)
            throw IOException("unsupported RFB version $major.$serverMinor")

        val minor = serverMinor.coerceAtMost(8)
        output.write("RFB 003.%03d\n".format(minor).toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        return minor
    }

    /**
     * Picks None or VNC authentication out of what the server offers, and returns it.
     *
     * 3.7 restyled this message: up to 3.6 the server names exactly one 32 bit type, from
     * 3.7 on it sends a count followed by that many one byte types, and the client has to
     * answer the list with the byte it picked. Reading a byte where a word was sent - or
     * staying silent about the choice - is the difference between a clipboard and a
     * handshake that falls apart two messages early.
     */
    private fun selectSecurityType(input: DataInputStream, output: DataOutputStream, minor: Int): Int {
        val offered = if (minor < 7) {
            // 3.3 to 3.6 name a single type; 0 means "connection failed, reason follows".
            val type = input.readInt()
            if (type == SEC_TYPE_CONN_FAILED) throw IOException(readReason(input))
            listOf(type)
        } else {
            // A count of zero is the list form of the same refusal.
            val count = input.readUnsignedByte()
            if (count == 0) throw IOException(readReason(input))
            (1..count).map { input.readUnsignedByte() }
        }

        // Both of these need nothing beyond the bytes handled above. A server that offers
        // neither - Apple's ARD, the TLS/NAK variants - is refused outright instead of
        // being half-implemented.
        val selected = when {
            SEC_TYPE_NONE in offered -> SEC_TYPE_NONE
            SEC_TYPE_VNC_AUTH in offered -> SEC_TYPE_VNC_AUTH
            else -> throw IOException("no supported VNC authentication, server offered $offered")
        }
        if (minor >= 7) {
            output.writeByte(selected)
            output.flush()
        }
        return selected
    }

    private fun answerVncChallenge(input: DataInputStream, output: DataOutputStream) {
        val challenge = ByteArray(16).also { input.readFully(it) }
        output.write(desEncrypt(challenge, password))
        output.flush()
    }

    private fun checkSecurityResult(input: DataInputStream, minor: Int, secType: Int) {
        // VNC authentication always answers. None answers only from 3.8 on, where a result
        // was added for symmetry: before that the four bytes following are the start of
        // ServerInit, so reading a result there would desync the rest of the handshake.
        if (secType != SEC_TYPE_VNC_AUTH && minor < 8) return

        if (input.readInt() != 0)
            throw IOException(if (minor >= 8) "VNC server: ${readReason(input)}" else "VNC authentication failed")
    }

    /**
     * Consumes ServerInit. The geometry and pixel format are not kept: nothing this
     * bridge ever sends refers to them.
     */
    private fun readServerInit(input: DataInputStream) {
        input.readFully(ByteArray(4))   // framebuffer width + height
        input.readFully(ByteArray(16))  // pixel format

        val nameLength = input.readInt()
        if (nameLength < 0 || nameLength > 0xFFFF)
            throw IOException("implausible desktop name length $nameLength")
        desktopName = ByteArray(nameLength).also { input.readFully(it) }
                .toString(StandardCharsets.ISO_8859_1).ifBlank { null }
    }

    /**
     * Reads server messages until the connection ends.
     *
     * Any [FramebufferUpdate] is fatal on purpose. Its rectangles carry encoding-dependent
     * payloads (Tight and Hextile cannot be skipped without decoding them), so a client
     * that does not want pixels has exactly one honest option: stop. Servers only send
     * updates when asked, and this bridge never asks - an unsolicited one means the
     * stream is out of sync, which is the other reason to bail.
     */
    private fun loop(input: DataInputStream) {
        while (true) {
            when (val type = input.readUnsignedByte()) {
                MSG_SERVER_CUT_TEXT -> readCutText(input)
                MSG_BELL -> {}
                MSG_SET_COLOR_MAP -> {
                    // padding(1) + firstColour(2); the count that follows is read next.
                    // Reading one word too many here swallows the first two bytes of the
                    // colour data and desyncs every message after it.
                    input.readFully(ByteArray(3))
                    val colors = input.readUnsignedShort()
                    input.readFully(ByteArray(colors * 6))
                }
                else -> throw IOException("unexpected message type $type from server")
            }
        }
    }

    private fun readCutText(input: DataInputStream) {
        input.readFully(ByteArray(3)) // padding
        val length = input.readInt()
        if (length < 0 || length > MAX_CUT_TEXT_BYTES)
            throw IOException("implausible cut-text length $length")

        val bytes = ByteArray(length).also { input.readFully(it) }
        val text = ClipboardTextCodec.decode(bytes, false, strictLatin1)
        if (text == lastText) return

        lastText = text
        onCutText(text)
    }

    /** The 3.7+ failure text that replaces the security-type list when a server refuses everyone. */
    private fun readReason(input: DataInputStream): String {
        val length = input.readInt().coerceIn(0, 0xFFFF)
        return ByteArray(length).also { input.readFully(it) }.toString(StandardCharsets.UTF_8)
    }

    /**
     * VNC authentication's answer to a challenge: the password truncated or zero-padded to
     * eight bytes, each byte's bits reversed (the protocol's own twist on a passphrase),
     * then both halves of the challenge through single DES with no padding.
     */
    private fun desEncrypt(challenge: ByteArray, password: String): ByteArray {
        // The protocol's key is eight bytes wide: take the first eight of the password and
        // zero-pad a shorter one. Longer is not an error, it is simply truncated - the same
        // thing every VNC viewer does.
        val raw = password.toByteArray(StandardCharsets.ISO_8859_1)
        val key = ByteArray(8)
        raw.copyInto(key, endIndex = minOf(raw.size, key.size))
        for (i in key.indices)
            key[i] = reverseBits(key[i])

        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(value: Byte): Byte {
        var bits = value.toInt() and 0xFF
        var result = 0
        for (i in 0 until 8) {
            result = (result shl 1) or (bits and 1)
            bits = bits shr 1
        }
        return result.toByte()
    }
}
