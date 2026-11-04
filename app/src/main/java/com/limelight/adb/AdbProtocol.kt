/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The ADB wire format.
 *
 * Every message is a 24-byte little-endian header - `cmd / arg0 / arg1 /
 * payloadLength / payloadChecksum / magic(=~cmd)` - optionally followed by the
 * payload. Note the deliberate oddity of the protocol: `magic` is the bit
 * complement of `cmd`, and the checksum is a plain byte sum (it is *not* sent for
 * zero-length payloads... which are also sent with a zero checksum).
 */
object AdbProtocol {
    const val ADB_HEADER_LENGTH = 24

    const val AUTH_TYPE_TOKEN = 1
    const val AUTH_TYPE_SIGNATURE = 2
    const val AUTH_TYPE_RSA_PUBLIC = 3

    const val CMD_AUTH = 0x48545541
    const val CMD_CNXN = 0x4e584e43
    const val CMD_OPEN = 0x4e45504f
    const val CMD_OKAY = 0x59414b4f
    const val CMD_CLSE = 0x45534c43
    const val CMD_WRTE = 0x45545257

    const val CONNECT_VERSION = 0x01000001

    /**
     * Advertised maximum payload; the device answers with its own value in `arg1`.
     *
     * This number is what adbd splits *its* writes by, and adbd will not send the next
     * chunk until it has an OKAY for the previous one - so the wire runs at
     * `maxdata / round-trip`. At the reference implementation's 15 KB a mirrored screen
     * is pushed through ~68 lock-step exchanges per megabyte and a high-bitrate frame
     * stalls behind its own acknowledgements; advertising the 1 MB a modern host uses
     * turns those 68 exchanges into one.
     *
     * Safe on old devices: adbd clamps a peer's maxdata to its own `MAX_PAYLOAD`, which
     * is smaller on every release that ever shipped a smaller one.
     */
    const val CONNECT_MAXDATA = 1024 * 1024

    /**
     * The banner, byte for byte what a current official adb client sends.
     *
     * It looks cosmetic - the feature list only *declares* what this host can speak, and
     * every destination string below stays on the v1 services - but devices are out there
     * (a MIX 2S among them) whose adbd registers a 1.0.1 host over whatever else holds
     * its single connection slot while a 1.0.0 host is left waiting, answered at all and
     * never served. Copying the official banner - version and checksum scheme included -
     * is what makes an in-app client take over a slot the way `adb connect` always could.
     */
    val CONNECT_PAYLOAD: ByteArray =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun generateConnect(): ByteBuffer =
            generateMessage(CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD)

    fun generateAuth(type: Int, data: ByteArray): ByteBuffer =
            generateMessage(CMD_AUTH, type, 0, data)

    fun generateOpen(localId: Int, dest: String): ByteBuffer {
        // Destination strings are NUL terminated on the wire. Sized from the encoded
        // bytes rather than the character count: a destination carrying anything outside
        // ASCII is longer in UTF-8 than it is in chars, and the extra byte would then
        // overflow the buffer on the terminating NUL.
        val payload = dest.toByteArray(StandardCharsets.UTF_8)
        val bbuf = ByteBuffer.allocate(payload.size + 1)
        bbuf.put(payload)
        bbuf.put(0.toByte())
        return generateMessage(CMD_OPEN, localId, 0, bbuf.array())
    }

    fun generateWrite(localId: Int, remoteId: Int, data: ByteArray): ByteBuffer =
            generateMessage(CMD_WRTE, localId, remoteId, data)

    fun generateClose(localId: Int, remoteId: Int): ByteBuffer =
            generateMessage(CMD_CLSE, localId, remoteId, null)

    fun generateOkay(localId: Int, remoteId: Int): ByteBuffer =
            generateMessage(CMD_OKAY, localId, remoteId, null)

    private fun generateMessage(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?): ByteBuffer {
        val size = if (payload == null) ADB_HEADER_LENGTH else ADB_HEADER_LENGTH + payload.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(cmd)
        buffer.putInt(arg0)
        buffer.putInt(arg1)

        if (payload == null) {
            buffer.putInt(0)
            buffer.putInt(0)
        } else {
            buffer.putInt(payload.size)
            buffer.putInt(payloadChecksum(payload))
        }
        buffer.putInt(cmd.inv())
        payload?.let { buffer.put(it) }
        buffer.flip()

        return buffer
    }

    /**
     * The 8-byte `sync:` sub-protocol frame header (`SEND`/`DATA`/`DONE`/`QUIT`/
     * `RECV` + a 32-bit argument whose meaning depends on the id).
     */
    fun generateSyncHeader(id: String, arg: Int): ByteBuffer {
        val tmpBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        tmpBuffer.put(id.toByteArray(StandardCharsets.UTF_8))
        tmpBuffer.putInt(arg)
        tmpBuffer.flip()
        return tmpBuffer
    }

    /**
     * The ADB "shift model" checksum a >= 1.0.1 connection is expected to carry: every
     * byte is added rotated by its position modulo 32, where the legacy model of 2016 and
     * before added the plain byte sum. adbd itself stopped *verifying* checksums back then
     * (see [AdbProtocol] header notes and the comment on `parseAdbMessage`), so this is
     * about looking exactly like the reference client to the peers that fingerprint on it,
     * not about integrity on this side.
     */
    private fun payloadChecksum(payload: ByteArray): Int {
        var checksum = 0
        for (i in payload.indices) {
            checksum += (payload[i].toInt() and 0xFF) shl (i % Integer.SIZE)
        }
        return checksum
    }

    /** One decoded ADB message. [payload] is null for the zero-length kinds. */
    class AdbMessage {
        var command = 0
        var arg0 = 0
        var arg1 = 0
        var payloadLength = 0
        var payload: ByteBuffer? = null

        companion object {
            /**
             * Largest payload this side is willing to receive in one message.
             *
             * [CONNECT_MAXDATA] is what we advertise, and adbd clamps its own limit
             * against it, so a well behaved device never goes above 1 MB. The value here
             * is a whole megabyte of slack on top of that because the length comes straight
             * off the wire: without a bound, a peer (or a stream that slipped by a few bytes)
             * announcing 2 GB reaches `ByteBuffer.allocate` and kills the process with an
             * OutOfMemoryError - which is an `Error`, so it walks straight past every
             * `catch (Exception)` on the session thread.
             */
            private const val MAX_ACCEPTED_PAYLOAD = 2 * 1024 * 1024

            @Throws(IOException::class, InterruptedException::class)
            fun parseAdbMessage(channel: AdbChannel): AdbMessage {
                val msg = AdbMessage()
                val buffer = channel.read(ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

                msg.command = buffer.int
                msg.arg0 = buffer.int
                msg.arg1 = buffer.int
                msg.payloadLength = buffer.int
                buffer.int // payload checksum: adbd has shipped it inconsistently, so it is only skipped over
                val magic = buffer.int

                // `magic` is the bit complement of the command. That is the one header field
                // that says whether this really is the start of a message; reading on after it
                // fails means allocating from a length that is not a length.
                if (magic != msg.command.inv())
                    throw IOException("ADB header out of step (magic ${Integer.toHexString(magic)}"
                            + " does not match command ${Integer.toHexString(msg.command)})")

                if (msg.payloadLength < 0 || msg.payloadLength > MAX_ACCEPTED_PAYLOAD)
                    throw IOException("Rejected ADB payload of ${msg.payloadLength} bytes")

                if (msg.payloadLength > 0) msg.payload = channel.read(msg.payloadLength)

                return msg
            }
        }
    }
}
