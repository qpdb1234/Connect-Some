/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * Based in part on Duorem (GPL-3.0-or-later), Network/NetBiosNodeStatus.java, for the idea
 * of asking a machine for its own hardware address over UDP 137. The request flags, the
 * record walk and the statistics-block check below follow RFC 1002 rather than that file.
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.net.Network
import com.limelight.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.Random

/**
 * The hardware address of a machine, asked of the machine itself.
 *
 * A NetBIOS Node Status request (RFC 1002 section 14.1.11, the query behind Windows'
 * `nbtstat -a <ip>`) is answered by the name service on UDP 137 and its reply carries
 * the interface address in clear. That matters because it needs no permission at all:
 * an app cannot open a raw socket, and Android denies the neighbour table to
 * unprivileged processes on no fewer builds than it does, which is how the scan's cards
 * end up without a MAC line even though the machine is right there.
 *
 * Best effort, and honestly so: a modern Windows can ship with NetBIOS over TCP/IP
 * disabled by group policy, and anything not running a NetBIOS stack at all (a phone, a
 * camera, most appliances) never answers. Routers and Windows boxes - the machines this
 * app is used with - usually do.
 */
object NetbiosLookup {
    private const val TAG = "NetbiosLookup"

    /** NetBIOS Name Service, the port `nbtstat` speaks on. */
    private const val NAME_SERVICE_PORT = 137

    /**
     * How long one machine is given to answer. Same order as a TCP liveness probe: this
     * runs for every address the neighbour table left blank, so a patient timeout would
     * be paid by every round of the scan.
     */
    private const val TIMEOUT_MS = 700

    /** Length of the header every NBSTAT packet starts with. */
    private const val HEADER_LEN = 12

    /** Node Status response data: a name count, then this many bytes of statistics. */
    private const val STATISTICS_LEN = 32

    /** One entry of the name table: 16 encoded name bytes plus its two flag bytes. */
    private const val NAME_ENTRY_LEN = 18

    /** The single separator byte between the name table and the statistics block. */
    private const val NULL_NAME_LEN = 1

    /** The interface address is the first six bytes of those statistics. */
    private const val MAC_LEN = 6

    private val random = Random()

    /**
     * Hardware address reported by the machine at [address] (dotted quad), or null when
     * it did not answer, answered something other than a Node Status response, or
     * reported something that is not a station address.
     *
     * [network], when given, is what the socket is bound to - for the same reason as in
     * [LanScanner]: an unbound socket leaves through the default network, which is not
     * this LAN.
     */
    fun macOf(address: String, network: Network? = null): String? {
        if (address.ipv4ToLong() == null) return null

        val transactionId = random.nextInt(0xffff)
        val socket = DatagramSocket()
        return try {
            runCatching { network?.bindSocket(socket) }
            socket.soTimeout = TIMEOUT_MS
            val request = buildRequest(transactionId)
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(address, NAME_SERVICE_PORT)))

            val buffer = ByteArray(2048)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            val mac = parseResponse(buffer, reply.offset, reply.length, transactionId)
            if (mac == null) {
                Log.d(TAG, "No usable node status from $address")
            } else {
                Log.i(TAG, "NetBIOS node status gave $address -> $mac")
            }
            mac
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "NetBIOS query for $address timed out")
            null
        } catch (e: Exception) {
            Log.d(TAG, "NetBIOS query for $address failed: $e")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Header (transaction id, flags, one question) plus the wildcard name in its
     * first-level encoding: every nibble of the 16 byte name becomes a letter, so
     * `*` padded with spaces turns into `CK` followed by fifteen `CA` pairs.
     */
    private fun buildRequest(transactionId: Int): ByteArray {
        val name = encodeName("*")
        val packet = ByteArray(HEADER_LEN + name.size + 4)
        putShort(packet, 0, transactionId)
        putShort(packet, 2, 0x0100) // standard query, recursion desired
        putShort(packet, 4, 1)      // one question
        name.copyInto(packet, HEADER_LEN)
        putShort(packet, HEADER_LEN + name.size, 33) // NBSTAT
        putShort(packet, HEADER_LEN + name.size + 2, 1) // IN
        return packet
    }

    /** The NetBIOS name [value] padded to 16 bytes, length-prefixed and zero terminated. */
    private fun encodeName(value: String): ByteArray {
        val raw = ByteArray(16) { index ->
            if (index < value.length) value[index].code.toByte() else ' '.code.toByte()
        }
        val encoded = ByteArray(raw.size * 2)
        for (i in raw.indices) {
            val byte = raw[i].toInt() and 0xff
            encoded[i * 2] = ('A'.code + (byte ushr 4)).toByte()
            encoded[i * 2 + 1] = ('A'.code + (byte and 0x0f)).toByte()
        }

        val label = ByteArray(encoded.size + 2)
        label[0] = encoded.size.toByte()
        encoded.copyInto(label, 1)
        label[label.size - 1] = 0
        return label
    }

    /**
     * The address out of a received reply: fixed header, the echoed question, then the
     * answer record whose data is `OWNER_NAME`, that many 18-byte `NAME_ENTRY` records, a
     * one-byte separator, and the 32-byte `NODE_STAT_DATA` whose first six bytes are the
     * interface address (RFC 1002 section 14.1.11).
     *
     * Reading the six bytes straight after the name count - which an earlier version of this
     * did - returns the first encoded NetBIOS name instead: ASCII letters whose low bit makes
     * them look multicast, so every answer this source ever got was thrown away as invalid.
     *
     * Only the [length] bytes the machine actually sent are read, from [offset] - the rest of
     * the receive buffer is whatever the last round left in it.
     */
    private fun parseResponse(buffer: ByteArray, offset: Int, length: Int, transactionId: Int): String? {
        fun u16(at: Int): Int = (buffer[at].toInt() and 0xff shl 8) or (buffer[at + 1].toInt() and 0xff)
        fun u8(at: Int): Int = buffer[at].toInt() and 0xff

        val end = offset + length
        if (length < HEADER_LEN) return null
        if (u16(offset) != transactionId) return null
        if (u16(offset + 2) and 0x8000 == 0) return null // not a response
        if (u16(offset + 6) < 1) return null             // no answer record

        var at = skipName(buffer, offset + HEADER_LEN, end) ?: return null // question name
        at += 4                                                            // question type and class

        at = skipName(buffer, at, end) ?: return null // RR name, usually a compression pointer
        if (at + 10 > end) return null                // type, class, ttl, rdlength
        val dataLength = u16(at + 8)
        at += 10

        if (dataLength < STATISTICS_LEN + 1 || end - at < dataLength) return null
        val names = u8(at)
        val statsAt = at + 1 + names * NAME_ENTRY_LEN + NULL_NAME_LEN
        if (statsAt + STATISTICS_LEN > at + dataLength) {
            Log.d(TAG, "Node status from transaction $transactionId does not hold a statistics block")
            return null
        }
        // The statistics block repeats the name count right after the address, and every
        // sender fills it the same way. A mismatch means the record was laid out differently
        // than the RFC, so the six bytes read here are not the interface address.
        if (u8(statsAt + MAC_LEN) != names) {
            Log.d(TAG, "Node status statistics do not echo the $names name(s) - not a hardware address")
            return null
        }
        val mac = (0 until MAC_LEN).joinToString(":") {
            String.format(Locale.ROOT, "%02x", buffer[statsAt + it])
        }
        return mac.asMacOrNull()?.takeIf { it.isUnicastMac() }.also {
            if (it == null) Log.d(TAG, "Node status gave an unusable address: $mac")
        }
    }

    /**
     * End of a length-prefixed label sequence, following a compression pointer when
     * there is one: a pointer ends the name on the spot, so the record after it starts
     * two bytes later.
     */
    private fun skipName(buffer: ByteArray, from: Int, end: Int): Int? {
        var at = from
        while (at < end) {
            val len = buffer[at].toInt() and 0xff
            if (len and 0xc0 == 0xc0) return at + 2
            if (len == 0) return at + 1
            if (len > 63) return null
            at += len + 1
        }
        return null
    }

    private fun putShort(packet: ByteArray, at: Int, value: Int) {
        packet[at] = (value ushr 8 and 0xff).toByte()
        packet[at + 1] = (value and 0xff).toByte()
    }
}
