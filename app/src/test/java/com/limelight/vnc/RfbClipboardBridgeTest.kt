/**
 * The clipboard bridge against a fake VNC server.
 *
 * The bridge speaks nothing but bytes, so it can be tested without a screen, a desktop or
 * an Android: each test below is one scripted conversation, and the assertions live on both
 * halves of it - what the bridge selected, and what it put on the wire afterwards.
 *
 * The handshake cases are the point. They are also the cases nothing else in this project
 * can reach: a GameStream session plus a VNC server on the same PC has to be somebody's
 * real machine, and a handshake that slips a byte out of place fails silently by falling
 * back to typing.
 */
package com.limelight.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RfbClipboardBridgeTest {

    companion object {
        private const val TIMEOUT_MS = 5_000L

        /**
         * ASCII `12345678` with every byte's bits reversed - the VNC authentication key,
         * spelled out rather than computed, so that the bridge's own reversal is checked
         * against the protocol and not against a second copy of itself.
         */
        private val REVERSED_12345678 = byteArrayOf(
                0x8C.toByte(), 0x4C.toByte(), 0xCC.toByte(), 0x2C.toByte(),
                0xAC.toByte(), 0x6C.toByte(), 0xEC.toByte(), 0x1C.toByte())

        private val CHALLENGE = ByteArray(16) { (it + 1).toByte() }
    }

    /**************************************************************************
     * Harness
     **************************************************************************/

    /** A bridge pointed at a script, plus the verdicts the script reached. */
    private inner class Session(password: String, strictLatin1: Boolean,
                                script: (DataInputStream, DataOutputStream) -> Unit) {
        private val listener = ServerSocket(0)
        private val cutTextLatch = CountDownLatch(1)
        private val serverDone = CountDownLatch(1)

        val received = AtomicReference<String>()
        val serverFailure = AtomicReference<Throwable>()
        val bridge = RfbClipboardBridge("127.0.0.1", listener.localPort, password, strictLatin1) {
            received.set(it)
            cutTextLatch.countDown()
        }

        init {
            // No read timeouts anywhere: a script that waits for something that never comes is
            // a script that must still be waiting when the test's own deadline expires, and
            // not one that logs a SocketTimeoutException the test might or might not see.
            Thread {
                try {
                    listener.accept().use { socket ->
                        script(DataInputStream(socket.inputStream.buffered()),
                                DataOutputStream(socket.outputStream.buffered()))
                    }
                } catch (e: Throwable) {
                    serverFailure.compareAndSet(null, e)
                } finally {
                    runCatching { listener.close() }
                    serverDone.countDown()
                }
            }.apply { isDaemon = true; name = "FakeVncServer"; start() }
        }

        /** Waits for [RfbClipboardBridge.live] to settle, then lets the script's verdict through. */
        fun awaitLive(alive: Boolean = true) {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (bridge.live != alive && System.currentTimeMillis() < deadline)
                Thread.sleep(10)
            rethrow()
            assertEquals("bridge liveness", alive, bridge.live)
        }

        /**
         * Waits until the script has run to its end - which for the tests below means the
         * bridge has been refused, and therefore has nothing left to become live with.
         */
        fun awaitServerDone() {
            assertTrue("the fake server is still talking", serverDone.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            rethrow()
        }

        fun awaitCutText(): String {
            val seen = cutTextLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            rethrow()
            assertTrue("no cut text reached the app", seen)
            return received.get()
        }

        /** A failed assertion inside the script runs off the test thread; surface it here. */
        fun rethrow() {
            serverFailure.get()?.let { throw AssertionError("fake VNC server: ${it.message}", it) }
        }
    }

    private fun session(password: String = "", strictLatin1: Boolean = false,
                        script: (DataInputStream, DataOutputStream) -> Unit): Session {
        val session = Session(password, strictLatin1, script)
        session.bridge.start()
        return session
    }

    // ---- server-side helpers -------------------------------------------------------------

    private fun ascii(text: String) = text.toByteArray(StandardCharsets.US_ASCII)

    /**
     * The version handshake has two halves: the banner goes out, the client's answer comes
     * back. Reading past the reply is how every later assertion ends up comparing ASCII.
     */
    private fun greet(input: DataInputStream, out: DataOutputStream, minor: Int) {
        val expected = "RFB 003.%03d\n".format(minor)
        out.write(ascii(expected)); out.flush()
        assertEquals("client replies with the version it can speak", expected,
                ByteArray(12).also { input.readFully(it) }.toString(StandardCharsets.US_ASCII))
    }

    /** Security types, in the shape the negotiated minor demands. */
    private fun offerTypes(out: DataOutputStream, minor: Int, types: List<Int>) {
        if (minor < 7) {
            assertEquals("one type at a time below 3.7", 1, types.size)
            out.writeInt(types[0])
        } else {
            out.writeByte(types.size)
            types.forEach { out.writeByte(it) }
        }
        out.flush()
    }

    private fun serverInit(out: DataOutputStream, name: String = "ConnectSome test desktop") {
        out.writeShort(1024); out.writeShort(768)
        out.write(ByteArray(16))          // pixel format: read past, never kept
        out.writeInt(name.length)
        out.write(name.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    /**
     * The `shared` flag the client sends between the security handshake and ServerInit.
     * Leaving it in the socket is how a later read mistakes it for a message type.
     */
    private fun readClientInit(input: DataInputStream) {
        assertEquals("a clipboard lookup must not steal the desktop", 1, input.readUnsignedByte())
    }

    private fun pushCutText(out: DataOutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        out.writeByte(3); out.writeByte(0); out.writeByte(0); out.writeByte(0)
        out.writeInt(bytes.size); out.write(bytes); out.flush()
    }

    private fun desEncrypt(challenge: ByteArray, key: ByteArray): ByteArray =
            Cipher.getInstance("DES/ECB/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            }.doFinal(challenge)

    /**************************************************************************
     * Handshake
     **************************************************************************/

    /**
     * 3.8 with a password: the client picks from the byte list, answers with the byte it
     * picked, and the server answers even a `None` from 3.8 on.
     */
    @Test
    fun threeEightSelectsNoneFromTheByteList() {
        val session = session { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(2, 1))
            assertEquals("client must answer the list with its choice", 1, input.readUnsignedByte())
            out.writeInt(0); out.flush()                       // SecurityResult, 3.8 style
            readClientInit(input)
            serverInit(out)
            pushCutText(out, "from the PC")
            // Keep the socket open: the bridge hangs up the moment the script stops reading.
            while (input.read() >= 0) {}
        }

        assertEquals("from the PC", session.awaitCutText())
        session.awaitLive()
    }

    /**
     * 3.7 with `None` sends no SecurityResult - the four bytes that follow are the start of
     * ServerInit. Reading a result here is how a clipboard bridge eats its own geometry and
     * dies one message later.
     */
    @Test
    fun threeSevenNoneAuthGoesStraightToServerInit() {
        val session = session { input, out ->
            greet(input, out, 7)
            offerTypes(out, minor = 7, types = listOf(1))
            assertEquals(1, input.readUnsignedByte())
            readClientInit(input)
            serverInit(out)
            pushCutText(out, "3.7, no result")
            while (input.read() >= 0) {}
        }

        assertEquals("3.7, no result", session.awaitCutText())
        session.awaitLive()
    }

    /** 3.3 names a single 32 bit type and expects no selection byte back. */
    @Test
    fun threeThreeNamesOneTypeAsAWord() {
        val session = session(password = "12345678") { input, out ->
            greet(input, out, 3)
            offerTypes(out, minor = 3, types = listOf(2))      // VNC authentication
            out.write(CHALLENGE); out.flush()
            assertEquals("3.3 leaves the choice implicit", desEncrypt(CHALLENGE, REVERSED_12345678)
                    .toList(), ByteArray(16).also { input.readFully(it) }.toList())
            out.writeInt(0); out.flush()
            readClientInit(input)
            serverInit(out)
            pushCutText(out, "3.3 with VNC auth")
            while (input.read() >= 0) {}
        }

        assertEquals("3.3 with VNC auth", session.awaitCutText())
        session.awaitLive()
    }

    /** The key is eight bytes wide: longer passwords are cut, not rejected. */
    @Test
    fun passwordLongerThanEightBytesIsTruncated() {
        val session = session(password = "1234567890") { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(2))
            assertEquals(2, input.readUnsignedByte())
            out.write(CHALLENGE); out.flush()
            ByteArray(16).also { input.readFully(it) }.let { response ->
                assertEquals(desEncrypt(CHALLENGE, REVERSED_12345678).toList(), response.toList())
            }
            out.writeInt(0); out.flush()
            readClientInit(input)
            serverInit(out)
            pushCutText(out, "long password")
            while (input.read() >= 0) {}
        }

        assertEquals("long password", session.awaitCutText())
        session.awaitLive()
    }

    /** A server that offers nothing gets the reason it gave, and the bridge stays dead. */
    @Test
    fun refusalWithAReasonLeavesTheBridgeDead() {
        val reason = ascii("VNC server disabled authentication")
        val session = session { input, out ->
            greet(input, out, 8)
            out.writeByte(0); out.writeInt(reason.size); out.write(reason); out.flush()
        }

        session.awaitServerDone()
        session.awaitLive(alive = false)
        assertNull("nothing should have been handed over", session.received.get())
    }

    /** A wrong password: 3.8 puts the explanation where the bridge must not mistake it for pixels. */
    @Test
    fun failedAuthenticationReportsTheReason() {
        val reason = ascii("Challenge failed!")
        val session = session(password = "12345678") { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(2))
            assertEquals(2, input.readUnsignedByte())
            out.write(CHALLENGE); out.flush()
            input.readFully(ByteArray(16))
            out.writeInt(1)                                    // VNC authentication failed
            out.writeInt(reason.size); out.write(reason); out.flush()
        }

        session.awaitServerDone()
        session.awaitLive(alive = false)
    }

    /**************************************************************************
     * Cut text in both directions
     **************************************************************************/

    /**
     * What the app copies has to land as a well formed `ClientCutText`: type 6, three unused
     * bytes, a length, then the payload - and nothing else, because the bridge never asks for
     * pixels and never should.
     */
    @Test
    fun copiedTextIsFramedAsClientCutText() {
        val frame = CountDownLatch(1)
        var framed = false
        val session = session { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(1))
            assertEquals(1, input.readUnsignedByte())
            out.writeInt(0); out.flush()
            readClientInit(input)
            serverInit(out)
            pushCutText(out, "first")
            assertEquals(6, input.readUnsignedByte())
            input.readFully(ByteArray(3))
            val bytes = ByteArray(input.readInt()).also { input.readFully(it) }
            framed = "second" == String(bytes, StandardCharsets.UTF_8)
            frame.countDown()
        }

        assertEquals("first", session.awaitCutText())
        assertTrue("text was not accepted by the bridge", session.bridge.sendCutText("second"))
        assertTrue("the server never saw a clipboard message", frame.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        session.rethrow()
        assertTrue("cut text must arrive as type 6 with the right length", framed)
    }

    /**
     * Copying what the host already holds must not travel back out again. Without this, every
     * round trip of a copy is one more message - and one more chance for the two sides to
     * echo each other forever.
     */
    @Test
    fun textThatCameInIsNotSentBackOut() {
        val session = session { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(1))
            assertEquals(1, input.readUnsignedByte())
            out.writeInt(0); out.flush()
            readClientInit(input)
            serverInit(out)
            pushCutText(out, "echo")
            assertEquals("nothing may come back for the same text", -1, input.read())
        }

        assertEquals("echo", session.awaitCutText())
        assertTrue("a bridge that already holds the text reports success without sending",
                session.bridge.sendCutText("echo"))
        // Close it so the script's read returns -1 and the assertion above gets to run.
        session.bridge.stop()
        session.awaitServerDone()
    }

    /**
     * A `SetColourMapEntries` is the one message that may arrive unasked on an indexed
     * display. Its padding is easy to miscount, so the test reads on past it: if the bridge
     * lost its place in the stream, the clipboard that follows would never be parsed.
     */
    @Test
    fun colourMapEntriesAreSkippedWithoutLosingFraming() {
        val session = session { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(1))
            assertEquals(1, input.readUnsignedByte())
            out.writeInt(0); out.flush()
            readClientInit(input)
            serverInit(out)
            out.writeByte(1)
            out.writeByte(0)                                     // padding (the message has one byte of it)
            out.writeShort(0)                                    // first colour
            out.writeShort(2)                                    // two colours
            out.write(ByteArray(2 * 6))
            out.writeByte(2)                                     // Bell
            pushCutText(out, "after the colour map")
            while (input.read() >= 0) {}
        }

        assertEquals("after the colour map", session.awaitCutText())
        session.awaitLive()
    }

    /**
     * Pixels were never requested, so an update means the stream is out of step. There is no
     * safe way to skip a rectangle of Tight or Hextile, and no reason to try: the clipboard
     * dies here rather than feeding the app garbage.
     */
    @Test
    fun anUnsolicitedFramebufferUpdateEndsTheBridge() {
        val session = session { input, out ->
            greet(input, out, 8)
            offerTypes(out, minor = 8, types = listOf(1))
            assertEquals(1, input.readUnsignedByte())
            out.writeInt(0); out.flush()
            readClientInit(input)
            serverInit(out)
            out.writeByte(0)                                     // FramebufferUpdate
            out.writeByte(1)                                     // ...with one rectangle
            pushCutText(out, "text the bridge must never see")
            Thread.sleep(300)                                    // give the update time to be fatal
        }

        session.awaitServerDone()
        session.awaitLive(alive = false)
        assertNull("pixels are not a clipboard", session.received.get())
    }
}
