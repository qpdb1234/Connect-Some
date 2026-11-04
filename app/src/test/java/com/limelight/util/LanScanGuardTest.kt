/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import com.limelight.adb.AdbProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The guard rails that keep a VPN's local proxy from being mistaken for a whole room of
 * servers, and its fabricated replies from being written in as hardware addresses.
 *
 * Only the pure decisions are covered here - the ones that need no socket and no Android:
 * the MAC shape check, the fail-closed "no LAN handle means no probe" rule, and the three
 * protocol-signature parsers that turn a bare accepted connection into a real VNC display,
 * a real GameStream host, or a real adbd. The socket plumbing around them is exercised on a
 * device.
 */
class LanScanGuardTest {

    // ---- asMacOrNull / isUnicastMac: reject the all-zero and group addresses ----------------

    @Test
    fun aWellFormedAddressNormalisesToLowerCaseColonPairs() {
        assertEquals("a4:5e:60:12:34:56", "A4-5E-60-12-34-56".asMacOrNull())
        assertEquals("a4:5e:60:12:34:56", "A4:5E:60:12:34:56".asMacOrNull())
    }

    @Test
    fun nonAddressesAreNotMacs() {
        assertNull("not a mac".asMacOrNull())
        assertNull("12:34".asMacOrNull())
        assertNull("a4:5e:60:12:34:zz".asMacOrNull())
    }

    @Test
    fun theAllZeroAddressIsRejected() {
        // The fabrication that used to show up as an "XEROX CORPORATION" card.
        assertFalse("00:00:00:00:00:00".isUnicastMac())
    }

    @Test
    fun aGroupAddressIsRejected() {
        // First octet's low bit set is the multicast marker; a station is never one.
        assertFalse("01:23:45:67:89:ab".isUnicastMac())
        assertFalse("33:33:00:00:00:01".isUnicastMac())
    }

    @Test
    fun aRealStationAddressIsAccepted() {
        assertTrue("a4:5e:60:12:34:56".isUnicastMac())
        assertTrue("50:eb:f6:11:22:33".isUnicastMac())
    }

    // ---- skipProbeForMissingLanBind: the fail-closed walk/probe decision --------------------

    @Test
    fun underAVpnAnAddressWithNoLanHandleIsSkipped() {
        assertTrue(skipProbeForMissingLanBind(requireLanBind = true, hasLanNetwork = false))
    }

    @Test
    fun underAVpnAnAddressWithALanHandleIsStillProbed() {
        assertFalse(skipProbeForMissingLanBind(requireLanBind = true, hasLanNetwork = true))
    }

    @Test
    fun withoutAVpnNothingIsSkippedForLackOfAHandle() {
        // With no VPN the default network really is the LAN, so an unbound probe is fine.
        assertFalse(skipProbeForMissingLanBind(requireLanBind = false, hasLanNetwork = false))
        assertFalse(skipProbeForMissingLanBind(requireLanBind = false, hasLanNetwork = true))
    }

    // ---- isVncBanner: a real RFB hello, not a proxy's silent accept --------------------------

    @Test
    fun rfbProtocolHelloIsAVncServer() {
        assertTrue(isVncBanner("RFB 003.008\n"))
        assertTrue(isVncBanner("RFB 004.001\n"))
        assertTrue(isVncBanner("  RFB 003.003"))
    }

    @Test
    fun anythingElseIsNotAVncServer() {
        // A VPN proxy that completes the handshake but never speaks RFB.
        assertFalse(isVncBanner(""))
        assertFalse(isVncBanner("SSH-2.0-OpenSSH_8.9"))
        assertFalse(isVncBanner("HTTP/1.1 200 OK\r\n"))
        assertFalse(isVncBanner("RFB abc.defg"))
    }

    // ---- isAdbHandshakeReply: a real adbd on 5555, not a proxy's silent accept --------------

    /** A 24-byte ADB reply header carrying [command] with the matching magic (the command's complement). */
    private fun adbHeader(command: Int): ByteArray =
            ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(command)       // cmd
                    .putInt(0)             // arg0
                    .putInt(0)             // arg1
                    .putInt(0)             // payload length
                    .putInt(0)             // payload checksum
                    .putInt(command.inv()) // magic
                    .array()

    @Test
    fun anAdbCnxnReplyIsRecognised() {
        assertTrue(isAdbHandshakeReply(adbHeader(AdbProtocol.CMD_CNXN)))
    }

    @Test
    fun anAdbAuthReplyIsRecognised() {
        // A device that wants pairing answers our CNXN with AUTH before it agrees to anything.
        assertTrue(isAdbHandshakeReply(adbHeader(AdbProtocol.CMD_AUTH)))
    }

    @Test
    fun aFrameWhoseMagicIsNotTheCommandsComplementIsRefused() {
        val corrupt = adbHeader(AdbProtocol.CMD_CNXN)
        ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0x12345678)
        assertFalse(isAdbHandshakeReply(corrupt))
    }

    @Test
    fun aProxyThatSpeaksNoAdbIsRefused() {
        assertFalse(isAdbHandshakeReply(ByteArray(0)))
        assertFalse(isAdbHandshakeReply(ByteArray(AdbProtocol.ADB_HEADER_LENGTH)))
        // Long enough, but arbitrary bytes rather than a well-formed ADB frame.
        val notAdb = ByteArray(AdbProtocol.ADB_HEADER_LENGTH)
        "HTTP/1.1 200 OK".toByteArray().copyInto(notAdb)
        assertFalse(isAdbHandshakeReply(notAdb))
    }

    // ---- isServerinfoReply / parseServerinfoMac: a real GameStream host ---------------------

    // The shape was captured from a live Sunshine's `GET /serverinfo` on 47989: a <root>
    // document with <hostname>/<uniqueid>/<appversion>, not a <serverinfo> root.
    private val serverinfo = """
        HTTP/1.1 200 OK
        Content-Length: 530

        <?xml version="1.0" encoding="utf-8"?>
        <root status_code="200"><hostname>DESKTOP-X</hostname><appversion>7.1.431.-1</appversion><uniqueid>96EC8703-B290-790D-7291-791CE0EC574B</uniqueid><HttpsPort>47984</HttpsPort><mac>50:eb:f6:11:22:33</mac><state>SUNSHINE_SERVER_BUSY</state></root>
    """.trimIndent()

    @Test
    fun aGenuineServerinfoReplyIsRecognised() {
        assertTrue(isServerinfoReply(serverinfo))
    }

    @Test
    fun aProxyReplyWithoutTheMarkersIsRefused() {
        assertFalse(isServerinfoReply("HTTP/1.1 200 OK\r\n\r\naccepted"))
        assertFalse(isServerinfoReply("<html><body>not serverinfo</body></html>"))
        // Present but incomplete: a hostname without the unique id is not the real thing.
        assertFalse(isServerinfoReply("<root><hostname>X</hostname><mac>50:eb:f6:11:22:33</mac></root>"))
    }

    @Test
    fun theSelfReportedMacIsExtractedAndValidated() {
        assertEquals("50:eb:f6:11:22:33", parseServerinfoMac(serverinfo))
    }

    @Test
    fun aFabricatedAllZeroMacIsDropped() {
        val forged = serverinfo.replace("50:eb:f6:11:22:33", "00:00:00:00:00:00")
        assertNull(parseServerinfoMac(forged))
    }

    @Test
    fun aMissingMacElementYieldsNothing() {
        val noMac = "<serverinfo><version>4.0.0</version></serverinfo>"
        assertNull(parseServerinfoMac(noMac))
    }
}
