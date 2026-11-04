/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the mDNS probe: turning a TXT rdata into its `key=value` attributes and
 * reading a device model / friendly name back out of them. This is what lets a phone's model
 * (published in a `_googlecast` / `_device-info` TXT record) reach a scan card without a
 * connection or a permission. The socket plumbing around it is exercised on a device.
 */
class MdnsProbeScannerTest {

    /** One length-prefixed TXT entry, wire form. */
    private fun txt(vararg entries: String): ByteArray {
        val out = ArrayList<Byte>()
        for (e in entries) {
            val bytes = e.toByteArray(Charsets.UTF_8)
            out += bytes.size.toByte()
            out += bytes.toList()
        }
        return out.toByteArray()
    }

    @Test
    fun aRunOfLengthPrefixedEntriesDecodesToAttributes() {
        val rdata = txt("googlecast=1", "md=Pixel 8", "fn=Living Room")
        val attrs = MdnsProbeScanner.decodeTxt(rdata, 0, rdata.size)
        assertEquals(listOf("googlecast=1", "md=Pixel 8", "fn=Living Room"), attrs)
    }

    @Test
    fun decodingReadsOnlyTheRecordItself() {
        // Two records share a buffer; the second must not bleed into the first's decode.
        val first = txt("model=MacBookPro15,1")
        val buffer = first + txt("ignored=yes")
        val attrs = MdnsProbeScanner.decodeTxt(buffer, 0, first.size)
        assertEquals(listOf("model=MacBookPro15,1"), attrs)
    }

    @Test
    fun aTruncatedFinalLengthStopsInsteadOfRunningPast() {
        // Declares 20 bytes but only 3 remain: stop cleanly rather than overrun the record.
        val rdata = byteArrayOf(20, 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())
        assertEquals(emptyList<String>(), MdnsProbeScanner.decodeTxt(rdata, 0, rdata.size))
    }

    @Test
    fun theModelComesFromModelThenMd() {
        assertEquals("MacBookPro15,1",
                MdnsProbeScanner.modelFromAttributes(listOf("model=MacBookPro15,1", "md=other")))
        assertEquals("Chromecast Ultra",
                MdnsProbeScanner.modelFromAttributes(listOf("fn=TV", "md=Chromecast Ultra")))
        assertNull(MdnsProbeScanner.modelFromAttributes(listOf("fn=TV", "cd=NexusPlayer")))
    }

    @Test
    fun attributeKeysAreMatchedCaseInsensitively() {
        assertEquals("Pixel 8", MdnsProbeScanner.modelFromAttributes(listOf("MODEL=Pixel 8")))
        assertEquals("Living Room", MdnsProbeScanner.friendlyFromAttributes(listOf("FN=Living Room")))
    }

    @Test
    fun aBlankValueIsTreatedAsAbsent() {
        assertNull(MdnsProbeScanner.modelFromAttributes(listOf("model=")))
        assertNull(MdnsProbeScanner.friendlyFromAttributes(listOf("fn=   ")))
    }

    @Test
    fun theFriendlyNameComesFromFn() {
        assertEquals("Kitchen Speaker", MdnsProbeScanner.friendlyFromAttributes(listOf("md=Speaker", "fn=Kitchen Speaker")))
    }

    @Test
    fun theFriendlyNameFallsBackToNameWhenNoFn() {
        // KDE Connect carries the phone's model as a TXT `name=`, with no `fn=`.
        assertEquals("MI MIX 2S", MdnsProbeScanner.friendlyFromAttributes(
                listOf("protocol=8", "name=MI MIX 2S", "type=phone")))
        // `fn=` still wins when both are present.
        assertEquals("Living Room", MdnsProbeScanner.friendlyFromAttributes(
                listOf("name=Cast Device", "fn=Living Room")))
    }

    @Test
    fun anInstanceLabelNamesAMachineOnlyWhenItIsAHost() {
        // A real instance PTR / SRV target: the first label is the host name.
        assertEquals("Marisa", MdnsProbeScanner.instanceName(
                listOf("Marisa", "_workstation", "_tcp", "local")))
    }

    @Test
    fun aServiceTypeOrSuffixIsNeverAnInstanceName() {
        // The bug this guards: a DNS-SD enumeration target must not resolve to "local".
        assertNull(MdnsProbeScanner.instanceName(listOf("_kdeconnect", "_udp", "local")))
        assertNull(MdnsProbeScanner.instanceName(listOf("local")))
        assertNull(MdnsProbeScanner.instanceName(listOf("arpa")))
    }

    @Test
    fun anAddressOrRandomIdTargetIsRejected() {
        // An A-record target is an address string; a KDE Connect instance is a 32-hex id.
        assertNull(MdnsProbeScanner.instanceName(listOf("/192.168.1.11")))
        assertNull(MdnsProbeScanner.instanceName(
                listOf("771d4cf69bcf4cae800e57fc4646e320", "_kdeconnect", "_udp", "local")))
    }
}
