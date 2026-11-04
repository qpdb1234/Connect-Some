/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * Based in part on csicar/Ning (LGPL-3.0-or-later), scanner/InterfaceScanner.kt,
 * PingScanner.kt, ArpScanner.kt and HostnameScanner.kt - the division into subnet walk,
 * neighbour read and name resolution, and all three of its neighbour sources. The TCP
 * liveness probe that replaces `isReachable`, the network binding, the command variants
 * and the address shape checks are this app's.
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.limelight.util.Log
import com.limelight.adb.AdbProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Dotted quad to its 32 bit value, or null when [this] is not one. Deliberately
 * stricter than [InetAddress.getByName], which also resolves hostnames and tolerates
 * the short forms `10.1` or `168864` - none of which should be walked as an address.
 */
internal fun String.ipv4ToLong(): Long? {
    val parts = trim().split('.')
    if (parts.size != 4) return null

    var value = 0L
    for (part in parts) {
        if (!part.matches(Regex("[0-9]{1,3}"))) return null
        val octet = part.toInt()
        if (octet > 255) return null
        value = (value shl 8) or octet.toLong()
    }
    return value
}

/** Inverse of [ipv4ToLong]. */
internal fun ipv4FromLong(value: Long): String =
        "${value ushr 24 and 255}.${value ushr 16 and 255}.${value ushr 8 and 255}.${value and 255}"

/**
 * One IPv4 subnet worth walking during a scan.
 *
 * [prefix] is the prefix actually swept, which can be shorter than the interface's
 * own: a /16 would take minutes, so wide networks get normalised down to a /24 around
 * this device. Narrow networks are left alone - inventing addresses beyond a /29 would
 * only probe machines that are not on this LAN.
 *
 * [network] is the handle of the system network this interface belongs to, or null when
 * the platform does not know the interface. Probes are bound to it so that they leave
 * through the LAN rather than through the default network; see [LanScanner.connect].
 */
data class Subnet(val base: Long, val prefix: Int, val interfaceName: String,
                  val network: Network? = null) {

    /** Number of host addresses inside [base]/[prefix], excluding network & broadcast. */
    val hostCount: Int
        get() = if (prefix >= 31) 1 else (1 shl (32 - prefix)) - 2

    /** All sweepable addresses of this subnet, as dotted quads. */
    fun addresses(): List<String> {
        if (prefix >= 31) return listOf(ipv4FromLong(base))

        val last = base or ((1L shl (32 - prefix)) - 1)
        val result = ArrayList<String>(hostCount)
        var value = base + 1
        while (value < last) {
            result.add(ipv4FromLong(value))
            value++
        }
        return result
    }

    /** Whether [address] (dotted quad) belongs to this subnet. */
    fun contains(address: String): Boolean {
        val value = address.ipv4ToLong() ?: return false
        return (value and MASKS[prefix]) == base
    }

    companion object {
        /** Network masks by prefix length, as unsigned 32 bit values in a Long. */
        private val MASKS = Array(33) { if (it == 0) 0L else (0xffffffffL shl (32 - it)) and 0xffffffffL }

        internal fun maskOf(value: Long, prefix: Int): Long = value and MASKS[prefix]
    }
}

/**
 * Walks the local network the way a router's table does: probe every address of the
 * subnet, then read the kernel's neighbour cache for the hardware addresses that the
 * probing itself provoked.
 *
 * Why not [InetAddress.isReachable], the obvious tool: it needs raw ICMP and therefore
 * root on Android, and otherwise silently falls back to connecting to port 7 - which
 * practically nothing answers, so every host would look dead. A plain TCP connect
 * distinguishes the three real cases instead: the port accepts (alive), the host sends
 * RST (alive with the service closed, reported as [ConnectException]), or nothing
 * replies within the timeout (absent, or dropping). Both of the first two make the
 * kernel ARP-resolve the address, which is what fills the neighbour table that
 * [readNeighbors] then reads. That is how a device with no open port - a printer, a
 * camera, the router itself - still shows up with a MAC address.
 *
 * A host that drops probes *and* is not in the neighbour cache cannot be found without
 * raw ICMP; that is a limitation of an unprivileged app, not of this walk.
 *
 * Structure follows ning's scanner package (`InterfaceScanner`, `PingScanner`,
 * `ArpScanner`, `HostnameScanner`), with the ICMP part replaced as described above and
 * all three of its neighbour sources: the netlink dump it reaches through a prebuilt
 * library is here [NeighborNetlink], `ip neigh` and `/proc/net/arp` are the fallbacks
 * for kernels that answer those better than they answer the socket.
 */
object LanScanner {
    private const val TAG = "LanScanner"

    /**
     * Port used to find out whether an address is occupied at all. Any port answers
     * that question equally well - a closed one replies just as informatively as an
     * open one - so the first VNC display is used, because for a host offering VNC this
     * same attempt is also the service detection.
     */
    private const val PROBE_PORT = 5900

    /**
     * Ports the liveness probe asks at once. 5900 doubles as the service detection for a
     * VNC host; 47989 catches a GameStream/Sunshine machine, because Windows does not
     * refuse an uninstalled VNC port - its firewall drops it - and a drop alone would make
     * the box look absent while it is running the very service we are looking for.
     */
    private val ALIVE_PORTS = listOf(PROBE_PORT, 47989)

    /**
     * Ports that mean "this machine can be remote-controlled from here". Only hosts
     * already known to be alive are probed for these, so the set can be wider than what
     * mDNS advertises. 47984/47989 are the GameStream/Sunshine HTTPS ports.
     */
    val SERVICE_PORTS = listOf(5900, 5901, 5902, 5903, 47984, 47989, ADB_PORT)

    /** GameStream/Sunshine HTTPS (TLS) and plaintext HTTP ports. */
    private const val GAME_STREAM_HTTPS_PORT = 47984
    private const val GAME_STREAM_HTTP_PORT = 47989

    /**
     * VNC display ports. Unlike a GameStream machine, which reveals itself in a
     * serverinfo reply, a VNC server is only proven by the RFB banner it sends first -
     * a bare accepted connection says nothing (see [isVncServing]).
     */
    private val VNC_PORTS = listOf(5900, 5901, 5902, 5903)

    /**
     * The ADB port of a device running the classic `adb tcpip 5555`. Like VNC it is proven by
     * a protocol exchange rather than the bare accept (see [isAdbServing]). The modern
     * wireless-debugging transport is deliberately not scanned: it lives on a random
     * per-session port and refuses any client that has not completed RSA pairing, so a subnet
     * walk could confirm nothing about it.
     */
    const val ADB_PORT = 5555

    /** Bytes read to catch a protocol banner, and how much of a serverinfo reply is parsed. */
    private const val BANNER_READ_BYTES = 32
    private const val SERVERINFO_READ_BYTES = 4096

    /** How long one liveness attempt waits before the address is called absent. */
    private const val PROBE_TIMEOUT_MS = 700

    /** Ceiling for a helper command such as `ip neigh`. */
    private const val COMMAND_TIMEOUT_MS = 5_000L

    /**
     * Commands that can print the neighbour table, in the order they are tried - after
     * [NeighborNetlink] has been asked. Which one answers is a property of the particular
     * Android build rather than of the network: `ip neigh` is the intended source, its two
     * variants cover `ip` implementations that insist on an address family or on an
     * explicit `show`, and `arp -a` is the busybox compatibility command on the builds
     * where `ip` is a stub.
     */
    private val NEIGH_COMMANDS = listOf(
            arrayOf("ip", "neigh"),
            arrayOf("ip", "-4", "neigh"),
            arrayOf("ip", "neigh", "show"),
            arrayOf("arp", "-a"),
    )

    /** Service probes run against few hosts, so they can afford to wait a bit longer. */
    private const val SERVICE_TIMEOUT_MS = 600

    /**
     * How long a targeted [preProbe] waits after its connects, for the kernel to write the
     * resolved neighbour entry into the table that [readNeighbors] then reads. A TCP connect
     * returns before ARP has fully settled, so reading too eagerly finds nothing and the
     * single-host MAC lookup looks like the whole-feature-is-broken case it is not.
     */
    private const val ARP_SETTLE_MS = 400L

    /** Reverse DNS is bounded per address; names are a nicety, not a requirement. */
    private const val NAME_TIMEOUT_MS = 1200L

    /**
     * Addresses probed at once. Kept modest: this runs on the phone's own Wi-Fi radio
     * while the user is watching, and a wider fan-out only makes the access point drop
     * packets.
     */
    private const val CONCURRENCY = 32

    /** Never sweep more than a /24 per interface, however wide its real prefix is. */
    private const val MAX_SWEEP_PREFIX = 24

    /**
     * Subnets to walk: every interface with a usable IPv4, ordered so that the ones
     * ConnectivityManager reports as Wi-Fi or wired come first.
     *
     * Enumerating interfaces rather than trusting only the default network is
     * deliberate - a home LAN without Internet access, a wired adapter behind Wi-Fi,
     * and the emulator's `radio0` are all invisible to a single-network query.
     */
    fun subnets(context: Context): List<Subnet> {
        val networks = lanNetworks(context)
        val found = LinkedHashMap<String, Subnet>()

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
                .onFailure { Log.w(TAG, "Cannot enumerate interfaces", it) }
                .getOrNull() ?: return emptyList()

        for (iface in interfaces.toList()) {
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable) continue

            for (address in iface.interfaceAddresses) {
                val inet = address.address as? Inet4Address ?: continue
                val value = inet.hostAddress?.ipv4ToLong() ?: continue
                if (isLinkLocal(value)) continue

                val raw = address.networkPrefixLength.toInt()
                if (raw !in 1..32) continue

                val sweepPrefix = if (raw < MAX_SWEEP_PREFIX) MAX_SWEEP_PREFIX else raw
                val subnet = Subnet(Subnet.maskOf(value, sweepPrefix), sweepPrefix, iface.name,
                        networks[iface.name])
                val key = "${iface.name}/${subnet.base}"

                // The preferred view of a subnet wins over the plainly enumerated one.
                val better = iface.name in networks && found[key]?.let { it.interfaceName !in networks } == true
                if (!found.containsKey(key) || better) found[key] = subnet
            }
        }

        return found.values.sortedByDescending { it.interfaceName in networks }
    }

    /**
     * System networks worth scanning, keyed by the interface they own: Wi-Fi or wired, and
     * not a VPN tunnel. VPN is excluded on purpose - its /32 has no neighbours to find, and
     * sweeping a corporate tunnel from a phone is not this app's business.
     *
     * The handles are kept rather than just the names because a probe has to be bound to
     * one: an unbound socket follows the default network, which on a phone that is on
     * Wi-Fi *and* has mobile data (or a VPN) is frequently not the LAN being walked.
     */
    private fun lanNetworks(context: Context): Map<String, Network> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyMap()
        val names = HashMap<String, Network>()

        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!onLan) continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

            manager.getLinkProperties(network)?.interfaceName?.let { names.putIfAbsent(it, network) }
        }
        return names
    }

    private fun isLinkLocal(value: Long) = value ushr 16 == 0xa9feL

    /**
     * Whether a VPN currently owns this app's traffic - the default network lacks
     * [NetworkCapabilities.NET_CAPABILITY_NOT_VPN].
     *
     * This is what makes an unbound probe lie: a local VPN proxy answers *every*
     * `connect` with success, so a bare TCP check reports the whole subnet as live
     * servers with every port open, and the LAN walk never actually reaches the wire to
     * ARP for a hardware address. The scan uses this to decide whether to bind every
     * probe to a real LAN handle and drop any address that has none (see [sweep] and
     * [connect]); it must never fall back to the default network under a VPN.
     */
    fun vpnCapturesTraffic(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = runCatching { manager.activeNetwork }.getOrNull() ?: return false
        val capabilities = runCatching { manager.getNetworkCapabilities(active) }.getOrNull()
                ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    /**
     * Walks [subnets] and returns every address that answered, whether it accepted
     * [PROBE_PORT] or refused it. [onAlive] fires as each result arrives so the caller
     * can publish progressively instead of only at the end. [onProgress] reports
     * completed-over-total addresses so the UI can show how far the walk has come,
     * the way ning's scan bar does.
     *
     * [knownFirst] (ning's ipGuesses idea, without its database): addresses that a
     * previous round actually found populated. They move to the front of the sweep so
     * the machines the user cares about appear within the first seconds instead of
     * wherever the address walk reaches them; the rest keep their subnet order.
     *
     * Suspending and cancellation-friendly: the caller cancelling this job stops the
     * next chunk from being probed, which is what the stop button relies on.
     *
     * [requireLanBind] (set when [vpnCapturesTraffic] is true) makes the walk fail closed:
     * an address whose subnet carries no LAN handle is skipped outright rather than
     * probed on the default network, because under a VPN that default is the local proxy
     * that accepts everything and would turn the whole subnet into fake servers.
     */
    suspend fun sweep(subnets: List<Subnet>, knownFirst: Set<String> = emptySet(),
                      requireLanBind: Boolean = false,
                      onAlive: (String) -> Unit,
                      onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<String> =
            withContext(Dispatchers.IO) {
                // The phone's own address is swept exactly like any other host, so the device
                // running the scan is listed too; its services (adbd on 5555, for one) are then
                // probed by the same signature rules as a neighbour's.
                //
                // Address -> the network its subnet was found on. Insertion order is the
                // sweep order, and the first subnet to claim an address decides where its
                // probes leave.
                val routes = LinkedHashMap<String, Network?>()
                for (subnet in subnets) {
                    for (address in subnet.addresses()) {
                        routes.putIfAbsent(address, subnet.network)
                    }
                }
                val candidates = routes.entries
                        .filter { !skipProbeForMissingLanBind(requireLanBind, it.value != null) }
                        .map { it.key }
                        .sortedByDescending { it in knownFirst }
                Log.i(TAG, "Sweeping ${candidates.size} addresses on " +
                        subnets.joinToString { "${it.interfaceName}/${it.prefix}" } +
                        if (requireLanBind) " (LAN-bound only)" else "")

                val alive = ArrayList<String>()
                var done = 0
                for (group in candidates.chunked(CONCURRENCY)) {
                    // coroutineScope only returns once the chunk's probes are done, so
                    // awaiting here never blocks; it just unpacks the result.
                    val results = coroutineScope {
                        group.map { address ->
                            async { isAlive(address, routes[address], requireLanBind) to address }
                        }
                    }
                    for (result in results) {
                        val (reachable, address) = result.await()
                        if (reachable) {
                            alive.add(address)
                            onAlive(address)
                        }
                    }
                    done += group.size
                    onProgress(done, candidates.size)
                }
                alive
            }

    /**
     * Which services [address] really serves, decided by a protocol signature rather than
     * by a bare accepted connection. A VNC display must send its RFB banner; a GameStream
     * box must answer a plaintext `GET /serverinfo` with a real serverinfo XML (both its
     * 47984 and 47989 ports are then reported, since Sunshine and GeForce Experience serve
     * them together). A proxy - a VPN's local acceptor - completes the TCP handshake and
     * then speaks neither protocol, so nothing is invented on top of it.
     *
     * A device driven over ADB is proven the same way - it must answer our CNXN with a valid
     * ADB header (see [isAdbServing]) - so a proxy that accepts 5555 without speaking the
     * protocol is not mistaken for one.
     *
     * [network] is the LAN handle from [networkFor]; [requireLanBind] fails a probe closed
     * when it is null (see [sweep]).
     */
    suspend fun openServicePorts(address: String, network: Network? = null,
                                 requireLanBind: Boolean = false): List<Int> =
            withContext(Dispatchers.IO) {
                val ports = ArrayList<Int>()
                for (port in VNC_PORTS) {
                    if (isVncServing(address, port, network, requireLanBind)) ports.add(port)
                }
                if (serverinfoBody(address, network, requireLanBind)?.let { isServerinfoReply(it) } == true) {
                    ports.add(GAME_STREAM_HTTPS_PORT)
                    ports.add(GAME_STREAM_HTTP_PORT)
                }
                if (isAdbServing(address, network, requireLanBind)) ports.add(ADB_PORT)
                ports
            }

    /**
     * The network probes of [address] should use: the one owning whichever of [subnets]
     * contains it. The walk's own bookkeeping is finished by the time services get
     * checked, so this re-derives the route from the same list.
     */
    fun networkFor(subnets: List<Subnet>, address: String): Network? =
            subnets.firstOrNull { it.contains(address) }?.network

    /**
     * Whether anything is home at [address]. The probe port accepting is proof; so is a
     * quick refusal, which means a stack answered. [ALIVE_PORTS] are asked at the same
     * time, since a firewall that drops one port says nothing about the other.
     *
     * Under [requireLanBind] a null [network] means there is no LAN route for the address,
     * so it is reported dead rather than probed through the default network's VPN proxy.
     */
    private suspend fun isAlive(address: String, network: Network?,
                                requireLanBind: Boolean = false): Boolean = coroutineScope {
        ALIVE_PORTS.map { port ->
            async(Dispatchers.IO) {
                connect(address, port, PROBE_TIMEOUT_MS, refuseMeansAlive = true,
                        network = network, requireLanBind = requireLanBind)
            }
        }.any { it.await() }
    }

    /**
     * One connect attempt. [refuseMeansAlive] decides how a refusal reads - as proof of a
     * live machine when looking for hosts. The RST from a closed port is what tells a real
     * (if service-less) host apart from an absent one; reading it as an open port would
     * invent a VNC server on every machine in the house.
     *
     * [network] gets the socket bound to it before the attempt. That is not a detail: an
     * unbound socket follows the default network, so on a phone that is on Wi-Fi with
     * mobile data or a VPN up the probe never leaves through the LAN. Fail closed now: a
     * bind that throws means the handle went stale and the socket would slip onto the
     * default network, so the probe is abandoned rather than retried unbound; and under
     * [requireLanBind] a null [network] is refused outright.
     */
    private fun connect(address: String, port: Int, timeoutMs: Int, refuseMeansAlive: Boolean,
                        network: Network?, requireLanBind: Boolean): Boolean {
        if (skipProbeForMissingLanBind(requireLanBind, network != null)) return false
        val socket = Socket()
        return try {
            if (network != null) network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            true
        } catch (e: ConnectException) {
            refuseMeansAlive
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: NoRouteToHostException) {
            false
        } catch (e: SocketException) {
            false
        } catch (e: Exception) {
            Log.d(TAG, "Probe $address:$port failed: $e")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Opens a connected TCP socket to [address]:[port] bound to [network], or null on any
     * failure. Unlike [connect] it hands the live socket back so the caller can speak a
     * protocol over it and read a signature.
     *
     * Fail closed by the same rule that defeats a VPN's local proxy: under [requireLanBind]
     * a null [network] yields null instead of following the default network, and a bind
     * that throws yields null instead of falling back. [readTimeoutMs] later bounds the
     * caller's read, so a proxy that accepts but never speaks the protocol cannot stall the
     * probe. The caller owns [socket] and must close it.
     */
    private fun openConnectedSocket(address: String, port: Int, timeoutMs: Int,
                                    network: Network?, requireLanBind: Boolean): Socket? {
        if (skipProbeForMissingLanBind(requireLanBind, network != null)) return null
        val socket = Socket()
        return try {
            if (network != null) network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            Log.d(TAG, "Probe $address:$port failed: $e")
            null
        }
    }

    /**
     * Whether [address]:[port] is a real VNC display: it must send the RFB banner on the
     * first line after the handshake, which a bare accepting proxy does not.
     */
    private fun isVncServing(address: String, port: Int, network: Network?,
                             requireLanBind: Boolean): Boolean {
        val socket = openConnectedSocket(address, port, SERVICE_TIMEOUT_MS, network, requireLanBind)
                ?: return false
        return try {
            socket.soTimeout = SERVICE_TIMEOUT_MS
            val banner = socket.getInputStream().readAtMost(BANNER_READ_BYTES)
                    .toString(Charsets.ISO_8859_1)
            isVncBanner(banner)
        } catch (e: Exception) {
            Log.d(TAG, "VNC banner read from $address:$port failed: $e")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Whether [address]:[ADB_PORT] is a real adbd. Sends our CNXN and requires the reply to be
     * a well-formed ADB header (see [isAdbHandshakeReply]); a bare accept, or a VPN proxy that
     * answers 5555 without speaking ADB, fails that check. Bound to [network] and bounded by
     * [SERVICE_TIMEOUT_MS] exactly like [isVncServing], so the probe never leaves the LAN and
     * never blocks on a silent acceptor.
     */
    private fun isAdbServing(address: String, network: Network?, requireLanBind: Boolean): Boolean {
        val socket = openConnectedSocket(address, ADB_PORT, SERVICE_TIMEOUT_MS, network, requireLanBind)
                ?: return false
        return try {
            socket.soTimeout = SERVICE_TIMEOUT_MS
            val connect = AdbProtocol.generateConnect()
            val packet = ByteArray(connect.remaining())
            connect.get(packet)
            socket.getOutputStream().apply { write(packet); flush() }
            isAdbHandshakeReply(socket.getInputStream().readAtMost(AdbProtocol.ADB_HEADER_LENGTH))
        } catch (e: Exception) {
            Log.d(TAG, "ADB probe to $address:$ADB_PORT failed: $e")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * The body of a plaintext `GET /serverinfo` reply from [address], or null when it is
     * not a GameStream/Sunshine host. Reads the HTTP port (47989); a proxy that accepts
     * without answering, or answers with anything that is not serverinfo XML, yields null
     * through [isServerinfoReply] at the call site.
     */
    private fun serverinfoBody(address: String, network: Network?,
                               requireLanBind: Boolean): String? {
        val socket = openConnectedSocket(address, GAME_STREAM_HTTP_PORT, SERVICE_TIMEOUT_MS,
                network, requireLanBind) ?: return null
        return try {
            socket.soTimeout = SERVICE_TIMEOUT_MS
            val request = "GET /serverinfo HTTP/1.1\r\n" +
                    "Host: $address\r\n" +
                    "Connection: close\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
            socket.getInputStream().readAtMost(SERVERINFO_READ_BYTES).toString(Charsets.ISO_8859_1)
        } catch (e: Exception) {
            Log.d(TAG, "serverinfo request to $address failed: $e")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * The hardware address a GameStream/Sunshine host self-reports in its serverinfo reply,
     * or null. The reply is fetched over the LAN socket bound to [network], so it comes from
     * the real machine rather than a VPN proxy, and the address is validated as a unicast
     * MAC before it is returned - a proxy's all-zero answer is rejected here.
     */
    suspend fun gameStreamMac(address: String, network: Network?,
                              requireLanBind: Boolean): String? = withContext(Dispatchers.IO) {
        val body = serverinfoBody(address, network, requireLanBind) ?: return@withContext null
        parseServerinfoMac(body)
    }

    /**
     * Hardware addresses from the kernel's neighbour cache, keyed by dotted quad.
     *
     * The netlink dump goes first: it is the only source that needs neither a binary to
     * exec nor a file the platform is free to hide, and Android 11+ builds deny one or
     * both of those to apps often enough that `ip neigh` cannot be assumed to answer.
     * The commands follow for the builds that answer them better, and `/proc/net/arp`
     * last for the devices that allow it. Only entries carrying a real link address
     * count: `FAILED` means the cache learned nothing, so that address must not be
     * reported as a device.
     */
    suspend fun readNeighbors(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, String>()

        result.addAllFrom("netlink", NeighborNetlink.lines()) { it.parseNeighborPair() }

        for (command in NEIGH_COMMANDS) {
            result.addAllFrom("${command.joinToString(" ")}", runCommand(*command)) { line ->
                line.parseNeighLine() ?: line.parseArpCommandLine()
            }
        }
        result.addAllFrom("/proc/net/arp", readArpFile().drop(1)) { it.parseArpLine() }

        Log.i(TAG, "Neighbour cache gave ${result.size} entries")
        result
    }

    /**
     * Touch [address] so the kernel resolves and caches its hardware address, then wait for
     * the entry to settle. [readNeighbors] only reports what a probe has already provoked, and
     * a whole walk fills the table as a side effect; asking for one machine on demand - the
     * "find this MAC" button on a single profile - otherwise reads a cache that machine was
     * never in. The liveness connect's answer is discarded here: it is wanted purely for the
     * ARP resolution it forces. Blocking; call off the main thread and before [readNeighbors].
     */
    suspend fun preProbe(address: String, network: Network?) = withContext(Dispatchers.IO) {
        runCatching { isAlive(address, network) }
        delay(ARP_SETTLE_MS)
    }

    /**
     * Puts what [lines] says into [this], without disturbing what is already there, and
     * logs how much it added. Every source is logged because "no card shows a MAC
     * address" is otherwise indistinguishable from "the network is empty" - the answer
     * only exists in the log, and the two cases need different fixes.
     */
    private fun LinkedHashMap<String, String>.addAllFrom(source: String, lines: List<String>,
                                                         parse: (String) -> Pair<String, String>?) {
        var added = 0
        for (line in lines) {
            val (ip, mac) = parse(line) ?: continue
            if (putIfAbsent(ip, mac) == null) added++
        }
        Log.i(TAG, "$source gave $added addresses")
    }

    /** `192.168.1.1 52:55:0a:00:02:02`, one per line, as [NeighborNetlink] returns them. */
    private fun String.parseNeighborPair(): Pair<String, String>? {
        val fields = trim().split(WHITESPACE)
        if (fields.size != 2) return null

        val mac = fields[1]
        if (fields[0].ipv4ToLong() == null || !mac.isUnicastMac()) return null
        return fields[0] to mac
    }

    /** `10.0.2.2 dev radio0 lladdr 52:55:0a:00:02:02 REACHABLE` */
    private fun String.parseNeighLine(): Pair<String, String>? {
        val fields = trim().split(WHITESPACE)
        val index = fields.indexOf("lladdr")
        if (index < 1 || index + 1 >= fields.size) return null

        val mac = fields[index + 1]
        if (fields[0].ipv4ToLong() == null || !mac.isUnicastMac()) return null
        return fields[0] to mac
    }

    /** `/proc/net/arp` columns: ip, hw, type, status-less mac at index 3. */
    private fun String.parseArpLine(): Pair<String, String>? {
        val fields = trim().split(WHITESPACE)
        if (fields.size < 4) return null

        val mac = fields[3]
        if (fields[0].ipv4ToLong() == null || !mac.isUnicastMac()) return null
        return fields[0] to mac
    }

    /**
     * `router (192.168.1.1) at 52:55:0a:00:02:02 [ether] on wlan0`. The name in front is
     * whatever the table calls the host - often `?` - so only the bracketed address and
     * the hardware address behind `at` are read.
     */
    private fun String.parseArpCommandLine(): Pair<String, String>? {
        val match = ARP_COMMAND_LINE.find(trim()) ?: return null
        val ip = match.groupValues[1]
        val mac = match.groupValues[2].asMacOrNull() ?: return null
        if (ip.ipv4ToLong() == null) return null
        return ip to mac
    }

    private fun readArpFile(): List<String> = runCatching {
        File("/proc/net/arp").inputStream().bufferedReader().use { it.readLines() }
    }.getOrElse {
        Log.d(TAG, "Cannot read /proc/net/arp: $it")
        emptyList()
    }

    /**
     * Output of a helper command, or nothing at all when it misbehaves.
     *
     * The bound has to cover the reading too, not just the exit: [BufferedReader.readLines]
     * only returns at end of stream, so a command that stays alive while holding its
     * output open - an `ip` implementation waiting for something it never gets - would
     * block here forever and take the scan round (and the pooled thread running it) with
     * it. Hence the reader runs on its own daemon thread and the whole attempt is
     * bounded, with a forced kill so the pipe actually closes.
     */
    private fun runCommand(vararg command: String): List<String> = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val lines = arrayOfNulls<List<String>>(1)
        val done = CountDownLatch(1)
        val reader = Thread {
            try {
                lines[0] = process.inputStream.use { it.bufferedReader().readLines() }
            } catch (e: Exception) {
                Log.d(TAG, "Cannot read output of ${command.joinToString(" ")}: $e")
            } finally {
                done.countDown()
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            if (!done.await(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    || !process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "${command.joinToString(" ")} did not finish in time")
                emptyList()
            } else {
                lines[0] ?: emptyList()
            }
        } finally {
            runCatching { process.destroyForcibly() }
        }
    }.getOrElse {
        Log.d(TAG, "Cannot run ${command.joinToString(" ")}: $it")
        emptyList()
    }

    /**
     * The device's own IPv4 addresses, used to recognise the phone itself: the mDNS probe
     * ignores a responder that only echoes these back (it is answering its own query, not
     * naming another machine), and local sharing binds to them. The subnet walk no longer
     * excludes them - the scanning device now appears in the list too.
     */
    fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .toSet()
    }.getOrDefault(emptySet())

    /**
     * The phone's own hardware addresses, keyed by the IPv4 address of the interface that
     * carries them. A neighbour's MAC comes from the ARP table, but the device is never in
     * its own - it does not resolve itself - so its address has to be read off the interface.
     *
     * Android hides the Wi-Fi MAC from [NetworkInterface.getHardwareAddress] and from WifiInfo
     * for unprivileged apps, so the Java API alone leaves the scanning device blank on most
     * phones. The `ip addr show` netlink dump still carries the address the interface is really
     * using, so it is parsed as the primary source and the Java result only fills gaps on builds
     * where the command is a stub. A `02:00:00:00:00:00` placeholder or an all-zero address is
     * rejected as non-unicast, so a locked-down build simply leaves the own device without a MAC
     * rather than inventing one.
     */
    fun localHardwareAddressMap(): Map<String, String> {
        val result = HashMap<String, String>()

        // An interface block in `ip addr show` is `N: name: <flags>`, then `link/ether <mac>`,
        // then one `inet <ip>/<prefix>` per address. The header clears the pending MAC so a
        // block that reported none can never borrow the previous interface's address.
        var pendingMac: String? = null
        for (line in runCommand("ip", "addr", "show")) {
            val trimmed = line.trim()
            if (INTERFACE_HEADER.containsMatchIn(trimmed)) {
                pendingMac = null
            } else if (trimmed.startsWith("link/ether")) {
                pendingMac = trimmed.split(WHITESPACE).getOrNull(1)
                        ?.asMacOrNull()?.takeIf { it.isUnicastMac() }
            } else if (trimmed.startsWith("inet ")) {
                val mac = pendingMac ?: continue
                val ip = trimmed.split(WHITESPACE).getOrNull(1)?.substringBefore('/') ?: continue
                if (ip.ipv4ToLong() != null) result.putIfAbsent(ip, mac)
            }
        }

        // The Java view as a fallback: it carries the address wherever the platform does
        // expose it to the app, and costs nothing where `ip` was a stub.
        runCatching {
            for (network in NetworkInterface.getNetworkInterfaces()) {
                val mac = network.hardwareAddress?.let { bytes ->
                    if (bytes.size == 6) bytes.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
                    else null
                }?.takeIf { it.isUnicastMac() } ?: continue
                for (address in network.inetAddresses.asSequence().filterIsInstance<Inet4Address>()) {
                    address.hostAddress?.let { result.putIfAbsent(it, mac) }
                }
            }
        }
        return result
    }

    /**
     * Reverse names for [addresses], best effort.
     *
     * [InetAddress.getCanonicalHostName] blocks on a DNS round trip per address and
     * hands back the literal when nothing answers, so each one is bounded and the
     * useless fallback is filtered out. mDNS names take precedence over these.
     *
     * The bound cannot be a coroutine timeout: the lookup is a blocking call with no
     * suspension point inside, so a cancelled coroutine only notices once the call has
     * already returned - which on a network with a black-holed DNS server means the whole
     * round waits for the OS resolver rather than for [NAME_TIMEOUT_MS]. Hence a watcher
     * thread per lookup, and a daemon one: an answer arriving after the deadline has
     * nowhere to go, and must not hold the process open either.
     */
    suspend fun reverseNames(addresses: List<String>): Map<String, String> = coroutineScope {
        addresses.map { address -> async(Dispatchers.IO) { lookupName(address) } }
                .mapNotNull { it.await() }
                .associate { it.first to it.second }
    }

    private suspend fun lookupName(address: String): Pair<String, String>?
        = withContext(Dispatchers.IO) {
            val answer = arrayOfNulls<String>(1)
            val done = CountDownLatch(1)
            val worker = Thread {
                try {
                    val name = InetAddress.getByName(address).canonicalHostName
                    if (name != address && name.ipv4ToLong() == null) answer[0] = name
                } catch (e: Exception) {
                    Log.d(TAG, "Reverse lookup for $address failed: $e")
                } finally {
                    done.countDown()
                }
            }
            worker.isDaemon = true
            worker.start()
            if (!done.await(NAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.d(TAG, "Reverse lookup for $address timed out")
                return@withContext null
            }
            answer[0]?.let { address to it }
        }

    /**
     * Reads up to [max] bytes from a socket stream, stopping at EOF or on the stream's own
     * timeout. A server that sends its banner and then waits for us (VNC) never closes, and
     * an accepting-but-silent proxy never sends anything; in both cases the read timeout set
     * on the socket throws and whatever has arrived so far is returned. Empty when nothing
     * does.
     */
    private fun java.io.InputStream.readAtMost(max: Int): ByteArray {
        val buffer = ByteArray(max)
        var filled = 0
        while (filled < max) {
            val read = try {
                read(buffer, filled, max - filled)
            } catch (e: java.io.IOException) {
                break
            }
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    private val WHITESPACE = Regex("\\s+")

    private val ARP_COMMAND_LINE = Regex(
            "\\(([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\\)\\s+at\\s+\\(?([0-9a-fA-F]{2}(?:[::.-][0-9a-fA-F]{2}){5})")

    /** An `ip addr show` interface header: `42: wlan0: <BROADCAST,...>`. */
    private val INTERFACE_HEADER = Regex("^[0-9]+: ")
}

/**
 * The text form of a hardware address, normalised to lowercase colon-separated pairs,
 * or null when [this] is not one. Some `arp` builds print dotted or dash-separated
 * groups, which are the same address in a different typeface.
 */
internal fun String.asMacOrNull(): String? {
    val hex = trim().lowercase(Locale.ROOT).replace('.', ':').replace('-', ':')
    if (!hex.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
    return hex
}

/**
 * Well-formed, non-zero and unicast: the locally-administered/multicast bit being
 * clear is what separates a station's address from a group address.
 */
internal fun String.isUnicastMac(): Boolean {
    val normalized = asMacOrNull() ?: return false
    if (normalized == "00:00:00:00:00:00") return false
    return normalized.substring(0, 2).toInt(16) and 1 == 0
}

/**
 * A VNC/RFB server opens every connection by announcing its protocol version on the first
 * line: `RFB 003.008\n`. Text that does not lead with that shape is not a real display, so
 * a TCP proxy that merely accepts the connection - and then says nothing, or something else
 * - is rejected here rather than counted as a VNC server.
 */
private val VNC_BANNER = Regex("RFB [0-9]{3}[.:][0-9]")

internal fun isVncBanner(text: String): Boolean = VNC_BANNER.containsMatchIn(text.trimStart())

/**
 * Whether [header] is a real adbd's reply to our CNXN. An ADB message is a 24-byte
 * little-endian frame `cmd / arg0 / arg1 / length / checksum / magic`, and `magic` is always
 * the bit complement of `cmd`; the device answers a CNXN with either CNXN (a host it already
 * trusts) or AUTH (a device that wants pairing first). A TCP proxy that accepts 5555 but never
 * speaks ADB produces neither a matching magic nor one of those commands, so it is rejected
 * here rather than counted as a device - the same rule the RFB banner enforces for VNC.
 */
internal fun isAdbHandshakeReply(header: ByteArray): Boolean {
    if (header.size < AdbProtocol.ADB_HEADER_LENGTH) return false
    val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val command = buf.int
    buf.int; buf.int; buf.int; buf.int   // arg0, arg1, payload length, payload checksum
    val magic = buf.int
    if (magic != command.inv()) return false
    return command == AdbProtocol.CMD_CNXN || command == AdbProtocol.CMD_AUTH
}

/**
 * Markers that make a plaintext reply a real GameStream/Sunshine serverinfo document. A
 * live Sunshine was measured to answer `GET /serverinfo` with a `<root status_code=...>`
 * document carrying `<hostname>`, `<uniqueid>`, `<appversion>` and a `<mac>` element - it
 * uses neither a `<serverinfo>` root nor a `<version>` tag, so those would reject the real
 * host. `<hostname>` plus `<uniqueid>` are stable across GeForce Experience and Sunshine,
 * and are not the arbitrary bytes a bare accepting proxy would hand back.
 */
private val SERVERINFO_MARKERS = listOf("<hostname", "<uniqueid")

/** Whether [body] is a genuine serverinfo reply rather than a proxy's arbitrary bytes. */
internal fun isServerinfoReply(body: String): Boolean {
    val lower = body.lowercase(Locale.ROOT)
    return SERVERINFO_MARKERS.all { lower.contains(it) }
}

/**
 * The `<mac>` element of a serverinfo reply, validated as a unicast address. A VPN proxy
 * that fabricates a reply with an all-zero or otherwise invalid MAC yields null here, which
 * is what kept a `00:00:00:00:00:00` / "XEROX" card off the list.
 */
private val MAC_TAG = Regex("<mac>\\s*([0-9a-fA-F:]{17})\\s*</mac>", RegexOption.IGNORE_CASE)

internal fun parseServerinfoMac(body: String): String? =
        MAC_TAG.find(body)?.groupValues?.get(1)?.asMacOrNull()?.takeIf { it.isUnicastMac() }

/**
 * The fail-closed rule shared by the subnet walk and the service probes: when a VPN owns
 * the traffic ([requireLanBind]) an address with no LAN handle ([hasLanNetwork] false) must
 * not be probed, because an unbound socket would leave through the default network - the
 * proxy that answers for every address and would invent machines that are not there. With
 * no VPN up ([requireLanBind] false) an unbound probe is harmless, so nothing is skipped.
 */
internal fun skipProbeForMissingLanBind(requireLanBind: Boolean, hasLanNetwork: Boolean): Boolean =
        requireLanBind && !hasLanNetwork
