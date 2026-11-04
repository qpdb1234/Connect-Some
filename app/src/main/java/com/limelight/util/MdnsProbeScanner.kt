/*
 * Adapted from csicar/Ning (https://github.com/csicar/Ning, LGPL-3.0-or-later),
 * Copyright (c) csicar.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import com.limelight.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.Charset

/**
 * Active mDNS probing, ported from csicar/Ning's `LowLevelMDnsScanner`. The request
 * encoding, the DNS record parsing and the list of service types are its; this app's are
 * the one socket per interface instead of one per service, the packet cap and the name
 * callback.
 *
 * Browsing (`NsdManager` / jmDNS) only hears what hosts choose to announce, and on
 * Android it cannot cover the dozen service types that would make a device list
 * readable. This instead sends unsolicited PTR queries for a fixed set of common
 * service types straight to the mDNS multicast group and listens to who answers.
 * Every machine running a Bonjour/mDNS stack - Windows, macOS, Linux with avahi,
 * Chromecasts, printers, NAS boxes - replies, and its answer carries the human
 * instance name ("Work PC", "Living Room Speaker"). That name is what turns an IP
 * address into a card the user recognizes, including for machines that never
 * advertise GameStream or VNC and that reverse DNS cannot name either.
 *
 * One socket per multicast-capable IPv4 interface: the phone may sit on Wi-Fi and
 * Ethernet at once, and a query leaving through the wrong interface never reaches
 * the LAN the user is scanning.
 */
object MdnsProbeScanner {
    private val TAG = MdnsProbeScanner::class.java.name

    private val MDNS_IP: InetAddress =
            Inet4Address.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
    private const val MDNS_PORT = 5353
    private val MDNS_GROUP = InetSocketAddress(MDNS_IP, MDNS_PORT)

    /** How long a socket keeps listening after its queries went out. */
    private const val LISTEN_MS = 2500

    /** A bounded number of answers per interface; a healthy LAN replies with far fewer. */
    private const val MAX_PACKETS = 256

    /**
     * A DNS-SD enumeration answer carries this as its record name. It lists a host's service
     * types (`_type._proto.local`) rather than naming a machine, so the probe follows it up
     * with a direct query for each type to reach the instance/TXT that actually carries a name.
     */
    private const val ENUMERATION_SUFFIX = "_services._dns-sd._udp.local"

    /** Cap on follow-up queries per interface, so a host that lists many types cannot flood. */
    private const val MAX_FOLLOWUP = 64

    /**
     * Service types worth asking about - ning's list, with this app's own protocols at the
     * front. `_services._dns-sd._udp` is the enumeration of everything a host offers; the
     * rest name machines directly (`_workstation`, `_device-info`), OS features (`_smb`,
     * `_ssh`, `_companion-link`), or appliances (cast, printer, homekit, coap).
     *
     * Browsing already listens for `_nvstream._tcp` and both VNC spellings, but listening only
     * hears what a host decides to announce: asking directly is what still answers when the
     * advertisement went into a black hole, or when the platform's mDNS stack refused to
     * start browsing at all.
     */
    private val COMMON_SERVICES = listOf(
            "_nvstream._tcp",
            "_vnc-rgb._tcp",
            "_rfb._tcp",
            "_workstation._tcp",
            "_companion-link._tcp",
            "_ssh._tcp",
            "_adisk._tcp",
            "_afpovertcp._tcp",
            "_device-info._tcp",
            "_googlecast._tcp",
            "_printer._tcp",
            "_ipp._tcp",
            "_http._tcp",
            "_smb._tcp",
            "_services._dns-sd._udp",
            "_hap._tcp",
            "_coap._udp",
    )

    /** Labels that are always DNS machinery, never a device's instance name. */
    private val NON_NAME_LABELS =
            setOf("local", "arpa", "in-addr", "ip6", "tcp", "udp", "dns-sd", "services")

    /** A 16+ character run of hex looks like a generated id (KDE Connect), not a host name. */
    private val ID_LIKE = Regex("[0-9a-fA-F]{16,}")

    /**
     * Queries every common service type and reports (IPv4 address, instance name, model)
     * for each answer. [name] is the mDNS instance name (or a TXT friendly name); [model]
     * is a device model read from a TXT attribute (`model=` / `md=`), empty when the
     * responder published none - which is how a phone's model reaches the scan. Best
     * effort: interfaces or sockets that cannot be opened are skipped, and a caller
     * cancelling this job just stops collecting.
     */
    suspend fun probeCommon(onNameHit: (ip: String, name: String, model: String) -> Unit) =
            withContext(Dispatchers.IO) {
                multicastInterfaces().map { iface ->
                    async { probeInterface(iface, onNameHit) }
                }.awaitAll()
            }

    /** Up, multicast-capable, non-loopback interfaces with an IPv4, capped at 4. */
    private fun multicastInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
                .filter { nif -> nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>().any { !it.isLoopbackAddress } }
                .take(4)
                .toList()
    }.getOrElse {
        Log.w(TAG, "Cannot enumerate interfaces for mDNS probing", it)
        emptyList()
    }

    private fun probeInterface(iface: NetworkInterface, onNameHit: (String, String, String) -> Unit) {
        // Bind the well-known mDNS port, not an ephemeral one. Android's responder answers a
        // PTR query for an instance with MULTICAST on 5353 even when the unicast-response bit
        // is set - verified: a phone's `_kdeconnect` TXT carrying `name=` only ever reaches a
        // socket bound to 5353. An ephemeral port sees only unicast replies, enough to learn a
        // host's service types from the enumeration but never the friendly name in the instance
        // TXT. SO_REUSEADDR lets this coexist with the jmDNS/NSD browsers already on 5353
        // (Linux delivers a multicast copy to every joined socket); if the bind still fails,
        // fall back to an ephemeral port and keep the old unicast-only behaviour.
        val socket = try {
            val s = MulticastSocket(null as InetSocketAddress?)
            s.reuseAddress = true
            s.bind(InetSocketAddress(MDNS_PORT))
            s.timeToLive = 2
            s.networkInterface = iface
            s.joinGroup(MDNS_GROUP, iface)
            s
        } catch (e: Exception) {
            Log.d(TAG, "Cannot bind mDNS port on ${iface.name}, using ephemeral: $e")
            try {
                val s = MulticastSocket(0)
                s.timeToLive = 2
                s.networkInterface = iface
                s.joinGroup(MDNS_GROUP, iface)
                s
            } catch (e2: Exception) {
                Log.d(TAG, "Cannot open mDNS socket on ${iface.name}: $e2")
                return
            }
        }

        try {
            socket.soTimeout = LISTEN_MS
            val queried = COMMON_SERVICES.toMutableSet()
            for (service in queried) {
                val bytes = createMDnsRequest("$service.local")
                socket.send(DatagramPacket(bytes, bytes.size, MDNS_GROUP.address, MDNS_PORT))
            }

            // A host answers several times over the round (roughly one packet per service type),
            // and its answers disagree on what to call it: an instance label ("adb-1a2b3c4d", a
            // debug serial) and a TXT friendly name ("fn=" / "name=", the device's real name) can
            // both arrive. Emitting per packet would let whichever came first win, so names are
            // gathered per host and resolved once the round goes quiet - friendly TXT names over
            // instance labels - which is how a phone reads "MI MIX 2S" rather than its ADB id.
            val friendlyNames = HashMap<String, String>()
            val instanceNames = HashMap<String, String>()
            val models = HashMap<String, String>()

            val buffer = ByteArray(16 * 1024)
            var packets = 0
            while (packets++ < MAX_PACKETS) {
                val dp = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(dp)
                } catch (e: SocketTimeoutException) {
                    break // quiet network: everyone who wanted to answer has answered
                }
                val content = buffer.copyOfRange(0, dp.length)
                val answers = parse(content)

                // A DNS-SD enumeration answer names no machine; it only lists the service types
                // a host offers. Many phones advertise nothing in COMMON_SERVICES but do carry
                // their friendly name in one of those types' TXT (KDE Connect publishes `name=`
                // under `_kdeconnect._udp`). Ask about each freshly-listed type once so the
                // instance/TXT answer, which does name the host, arrives in a later packet below.
                for (a in answers) {
                    if (!a.name.endsWith(ENUMERATION_SUFFIX) || a.domainName.isEmpty()) continue
                    val service = a.domainName.joinToString(".").removeSuffix(".local")
                    if (service.startsWith("_") && queried.size < MAX_FOLLOWUP && queried.add(service)) {
                        val bytes = createMDnsRequest("$service.local")
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, MDNS_GROUP.address, MDNS_PORT))
                        }
                    }
                }

                val host = (dp.address as? Inet4Address)?.hostAddress ?: continue
                val txtAttributes = answers.flatMap { it.attributes }
                friendlyFromAttributes(txtAttributes)?.let { friendlyNames.putIfAbsent(host, it) }
                answers.firstNotNullOfOrNull { instanceName(it.domainName) }
                        ?.let { instanceNames.putIfAbsent(host, it) }
                modelFromAttributes(txtAttributes)?.let { models.putIfAbsent(host, it) }
            }

            for (host in friendlyNames.keys + instanceNames.keys + models.keys) {
                val name = friendlyNames[host] ?: instanceNames[host] ?: ""
                val model = models[host] ?: ""
                if (name.isNotEmpty() || model.isNotEmpty()) {
                    Log.d(TAG, "mDNS: $host answers as '$name' model='$model'")
                    onNameHit(host, name, model)
                }
            }
            runCatching { socket.leaveGroup(MDNS_GROUP, iface) }
        } catch (e: Exception) {
            // Transient on flaky Wi-Fi stacks; another interface may still have answered.
            Log.d(TAG, "mDNS probe on ${iface.name} failed: $e")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * One DNS header (query count 1, everything else empty), the wire-form of
     * [serviceName], and a PTR question in the unicast-response bit of the class.
     */
    private fun createMDnsRequest(serviceName: String): ByteArray {
        val nameParts = serviceName
                .split(".")
                .flatMap { listOf(it.length.toByte()) + it.toByteArray(Charsets.US_ASCII).toList() }
                .toByteArray()

        return byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
                nameParts + 0x00 +
                0x00 + 0x0c +                 // type PTR
                0x80.toByte() + 0x01          // class IN with the unicast bit set
    }

    // ---- DNS message parsing, as in ning ----------------------------------------

    data class DnsAnswer(val name: String, val domainName: List<String>, val txt: String,
            val attributes: List<String> = emptyList())

    /**
     * Decodes a DNS TXT rdata (a run of length-prefixed byte strings) into its `key=value`
     * attribute strings. [dataLength] bounds the read so a malformed final length byte
     * cannot run past this record into the next one.
     */
    internal fun decodeTxt(bytes: ByteArray, dataIndex: Int, dataLength: Int): List<String> {
        val out = ArrayList<String>()
        var i = dataIndex
        val end = minOf(dataIndex + dataLength, bytes.size)
        while (i < end) {
            val len = bytes[i].toInt() and 0xFF
            i++
            if (len == 0 || i + len > end) break
            out += String(bytes, i, len, Charsets.UTF_8)
            i += len
        }
        return out
    }

    /** Value of a TXT attribute, matched case-insensitively; null when absent or blank. */
    private fun attribute(attrs: List<String>, key: String): String? =
            attrs.firstOrNull { it.startsWith("$key=", ignoreCase = true) }
                    ?.substringAfter('=')?.takeIf { it.isNotBlank() }

    /** Device model from the TXT attribute Sunshine / Chromecast / AirPlay responders set. */
    internal fun modelFromAttributes(attrs: List<String>): String? =
            attribute(attrs, "model") ?: attribute(attrs, "md")

    /**
     * Friendly device name from a TXT attribute: `fn=` (Google Cast / AirPlay) first, then
     * `name=` (KDE Connect, which carries the phone's model there). Preferred over the mDNS
     * instance label, which for some services is a random id rather than a readable name.
     */
    internal fun friendlyFromAttributes(attrs: List<String>): String? =
            attribute(attrs, "fn") ?: attribute(attrs, "name")

    /**
     * The instance label of a PTR/SRV target, when that target names a machine. A service-type
     * or DNS-SD enumeration target starts with `_` (`_kdeconnect._udp.local`) and its mandatory
     * `.local`/`.arpa` suffix is never a name; an A-record target is an address; and some
     * responders use a random hex id as the instance. None of those should reach the card, so
     * they are all rejected here - which is exactly the bug that used to name a phone "local".
     */
    internal fun instanceName(labels: List<String>): String? {
        val first = labels.firstOrNull() ?: return null
        if (first.startsWith("_") || first.startsWith("/")) return null
        if (first.lowercase() in NON_NAME_LABELS) return null
        if (first.matches(ID_LIKE)) return null
        return first.takeIf { it.isNotBlank() }
    }

    private fun ByteArray.rangeToInt(start: Int, end: Int): Int {
        var value = 0
        for (i in start..end) {
            value = (value shl 8) or (this[i].toInt() and 0xFF)
        }
        return value
    }

    private fun parse(byteArray: ByteArray): List<DnsAnswer> {
        val answers = mutableListOf<DnsAnswer>()
        try {
            val noQuestionRecords = byteArray.rangeToInt(4, 5)
            val noAnswerRecords = byteArray.rangeToInt(6, 7)
            val noAuthorityRecords = byteArray.rangeToInt(8, 9)
            val noAdditionalRecords = byteArray.rangeToInt(10, 11)
            var index = 12

            // Each question is a variable-length name plus QTYPE and QCLASS.
            repeat(noQuestionRecords) {
                val (nameEnd, _) = parseString(index, byteArray)
                index = nameEnd + 1 + 4
            }
            repeat(noAnswerRecords + noAuthorityRecords + noAdditionalRecords) {
                val (newIndex, answer) = parseAnswer(index, byteArray)
                index = newIndex
                if (answer != null) answers += answer
            }
        } catch (e: Exception) {
            // Truncated or vendor-mangled multicast packet; the records parsed so far stay.
            Log.d(TAG, "mDNS packet parse stopped early: $e")
        }
        return answers
    }

    /** Reads a (possibly compressed) DNS name; returns the index of its terminator. */
    private fun parseString(
            startIndex: Int,
            byteArray: ByteArray,
            references: MutableMap<Int, List<String>> = mutableMapOf(),
            depth: Int = 1,
    ): Pair<Int, List<String>> {
        val name = mutableListOf<String>()
        var i = startIndex
        var hitReference = false
        while (i < byteArray.size && byteArray[i] != 0x00.toByte() && !hitReference) {
            val partLength = byteArray[i].toUByte()
            val referenceMask = 0b11000000
            if (partLength.toInt() and referenceMask != 0) {
                // Compression pointer: two bytes of offset into the same packet.
                val referenceIndex = (partLength.toInt() and referenceMask.inv() shl 8) or
                        (byteArray[i + 1].toInt() and 0xFF)
                val referenceValue = references.getOrPut(referenceIndex) {
                    if (depth > 30) listOf("")
                    else parseString(referenceIndex, byteArray, references, depth + 1).second
                }
                name += referenceValue
                hitReference = true
                i += 1
            } else {
                val stringStart = i + 1
                val stringEnd = stringStart + partLength.toInt()
                if (stringEnd > byteArray.size) break
                name += String(byteArray.copyOfRange(stringStart, stringEnd), Charsets.UTF_8)
                i += partLength.toInt() + 1
            }
        }
        return i to name
    }

    private fun parseAnswer(startIndex: Int, byteArray: ByteArray): Pair<Int, DnsAnswer?> {
        val (i, name) = parseString(startIndex, byteArray)
        val type = byteArray.rangeToInt(i + 1, i + 2)
        val dataLength = byteArray.rangeToInt(i + 9, i + 10)
        val dataIndex = i + 11
        val combinedName = name.joinToString(".")

        val dnsAnswer = when (type) {
            12 -> DnsAnswer(combinedName, parseString(dataIndex, byteArray).second, "")   // PTR
            // SRV: priority(2) weight(2) port(2), then the target name.
            33 -> DnsAnswer(combinedName, parseString(dataIndex + 6, byteArray).second, "")
            16 -> DnsAnswer(combinedName, emptyList(), "", decodeTxt(byteArray, dataIndex, dataLength))
            1 -> DnsAnswer(combinedName, listOf(Inet4Address.getByAddress(
                    byteArray.copyOfRange(dataIndex, dataIndex + 4)).toString()), "")      // A
            28 -> DnsAnswer(combinedName, listOf(
                    java.net.Inet6Address.getByAddress(byteArray.copyOfRange(dataIndex, dataIndex + 16)).toString()),
                    "")                                                                    // AAAA
            else -> null
        }
        return (i + 11 + dataLength) to dnsAnswer
    }
}
