/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.limelight.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * IPv4 details of the local network this device is currently attached to.
 *
 * A Wake-on-LAN magic packet only reaches a sleeping machine when it leaves through
 * the interface that shares the machine's subnet, and only when it is addressed to
 * that subnet's broadcast address. Both are properties of the *network* the phone
 * happens to be on rather than of the saved server, so they are looked up here
 * instead of being typed out by the user.
 */
class LocalNetwork(val network: Network?, val address: String, val prefixLength: Int) {

    /**
     * Last address of this subnet, e.g. `192.168.1.255` for `192.168.1.30/24`.
     */
    val broadcast: String get() = broadcast(address, prefixLength)

    /**
     * Whether [target] sits in this network's own subnet, so a packet addressed to it
     * leaves through this interface and reaches the machine it names.
     */
    fun covers(target: String?): Boolean = covers(address, prefixLength, target)

    companion object {
        /**
         * The local network to send on, or null when the device has none.
         *
         * Cellular is deliberately excluded from the answer: a broadcast packet sent there
         * only ever reaches the carrier's network, so it could not wake anything, and letting
         * it win would silently replace a configured address that does work.
         */
        fun current(context: Context): LocalNetwork? = all(context).firstOrNull()

        /**
         * Every network this device can broadcast on, the platform's own answer first and the
         * plainly enumerated interfaces after it, in interface-preference order. Two callers
         * need the whole list rather than the head of it: picking the one that holds a given
         * server (see [forTarget]), and saying nothing at all when the list is empty.
         */
        fun all(context: Context): List<LocalNetwork> {
            val found = ArrayList<LocalNetwork>()
            fromConnectivityManager(context)?.let { found.add(it) }
            for (candidate in fromInterfaces(context)) {
                // The system's answer for the same address wins: it carries the network
                // handle a socket can be bound to, which an enumerated interface cannot.
                if (found.none { it.address == candidate.address }) found.add(candidate)
            }
            return found
        }

        /**
         * The network to reach [target] through, falling back to [current] when nothing
         * claims its subnet.
         *
         * Asked for rather than taken for granted because a phone routinely holds more than
         * one local network: a machine on the hotspot the phone itself is broadcasting is on
         * the phone's second interface, not on the Wi-Fi it may also be joined to.
         */
        fun forTarget(context: Context, target: String?): LocalNetwork? {
            val networks = all(context)
            val covering = if (target != null) networks.filter { it.covers(target) } else emptyList()
            return covering.firstOrNull { it.network != null } ?: covering.firstOrNull() ?: networks.firstOrNull()
        }

        /**
         * The Wi-Fi or wired network the platform itself names, with its [Network] handle.
         */
        private fun fromConnectivityManager(context: Context): LocalNetwork? {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
            for (candidate in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(candidate) ?: continue
                val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!onLan) continue

                // A VPN tunnel usually has no usable IPv4 broadcast; the LAN lives below it.
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

                val properties = manager.getLinkProperties(candidate) ?: continue
                for (link in properties.linkAddresses) {
                    if (link.address !is Inet4Address) continue
                    val address = link.address.hostAddress ?: continue
                    return LocalNetwork(candidate, address, link.prefixLength)
                }
            }
            return null
        }

        /**
         * Fallback for the network the platform will not describe: the hotspot this phone is
         * broadcasting. Its access-point interface carries the LAN address that a sleeping
         * machine's neighbour will answer, but it is never listed among the system's networks
         * - there is no upstream to connect to, so ConnectivityManager has no reason to know
         * it exists, and a caller that asked only it reports "no Wi-Fi or wired network".
         *
         * Enumerating interfaces is the only way left. Virtual ones are deliberately not
         * filtered out - an access point interface is virtual by construction - so the junk is
         * named away instead ([IGNORED_PREFIXES]), and what remains is ordered by how much it
         * looks like the LAN ([INTERFACE_PREFERENCE]).
         */
        private fun fromInterfaces(context: Context): List<LocalNetwork> {
            val found = LinkedHashMap<String, LocalNetwork>()
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
                    .onFailure { Log.w(TAG, "Cannot enumerate interfaces", it) }
                    .getOrNull() ?: return emptyList()

            for (iface in interfaces.toList()) {
                val name = iface.name ?: continue
                if (IGNORED_PREFIXES.any { name.startsWith(it) }) continue
                // Up and not a loopback is all the sanity check an interface gets here.
                if (!runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)) continue

                val addresses = runCatching { iface.interfaceAddresses }.getOrNull() ?: continue
                for (link in addresses) {
                    val inet = link.address as? Inet4Address ?: continue
                    val text = inet.hostAddress ?: continue
                    val value = text.toIpv4Long() ?: continue
                    // An address from the APIPA range says the interface never got one.
                    if (value ushr 16 == LINK_LOCAL_TOP) continue

                    val prefix = link.networkPrefixLength.toInt()
                    if (prefix !in 1..31) continue
                    found.putIfAbsent(name, LocalNetwork(null, text, prefix))
                }
            }
            return found.entries.sortedBy { interfaceRank(it.key) }.map { it.value }
        }

        /** Lower ranks leave first; an unlisted name goes last but is still offered. */
        private fun interfaceRank(name: String): Int {
            val exact = INTERFACE_PREFERENCE.indexOf(name)
            if (exact >= 0) return exact
            // `wlan1`, `ap1`: the family is known, the index is not.
            val family = INTERFACE_PREFERENCE.indexOfFirst { name.startsWith(it) }
            return if (family >= 0) family + INTERFACE_PREFERENCE.size else INTERFACE_PREFERENCE.size * 2
        }

        /** Host mask of a `prefix`-sized subnet, as a 32-bit value. */
        private fun maskOf(prefix: Int): Long =
                if (prefix >= 32) 0xffffffffL else 0xffffffffL shl (32 - prefix) and 0xffffffffL

        private fun covers(address: String, prefix: Int, target: String?): Boolean {
            val here = address.toIpv4Long() ?: return false
            val there = target?.toIpv4Long() ?: return false
            if (prefix !in 0..32) return false
            val mask = maskOf(prefix)
            return (here and mask) == (there and mask)
        }

        private const val TAG = "LocalNetwork"

        /** 169.254.0.0/16 - the range a host assigns itself when nothing answered. */
        private const val LINK_LOCAL_TOP = 0xa9feL

        /**
         * Interface names in the order they are trusted to be a real LAN: the station
         * interfaces first, then the access points a phone hosts, then wired and tethered.
         */
        private val INTERFACE_PREFERENCE = listOf("wlan0", "wlan", "ap0", "ap", "swlan0", "ath0",
                "airport-0", "eth0", "eth", "usb0", "rndis0", "br0", "bridge0")

        /**
         * Never a LAN: mobile radios, the tunnel interfaces [fromInterfaces] cannot filter
         * out by virtuality, and the pure-forwarding ports of a hotspot stack.
         */
        private val IGNORED_PREFIXES = listOf("rmnet", "ccmap", "dummy", "ifb", "tun", "utun",
                "p2p0", "nan0", "lo", "sit", "ip6", "gre", "ipsec", "wlan_diag")

        /**
         * Whether [text] is a dotted-quad IPv4 address. Deliberately stricter than
         * [java.net.InetAddress.getByName], which also accepts hostnames.
         */
        fun isIpv4(text: String): Boolean = text.toIpv4Long() != null

        /**
         * Broadcast address of the subnet `address/prefix`.
         */
        fun broadcast(address: String, prefix: Int): String {
            val value = address.toIpv4Long() ?: return NO_ADDRESS
            if (prefix !in 0..32) return NO_ADDRESS
            return fromIpv4Long(value or (0xffffffffL ushr prefix))
        }

        /**
         * Whether [text] names the all-hosts broadcast address, which is really the
         * placeholder for "figure it out yourself".
         */
        fun isAnyBroadcast(text: String) = text.trim() == ANY_ADDRESS

        private const val NO_ADDRESS = "0.0.0.0"
        private const val ANY_ADDRESS = "255.255.255.255"

        private fun String.toIpv4Long(): Long? {
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

        private fun fromIpv4Long(value: Long): String {
            return "${value ushr 24 and 255}.${value ushr 16 and 255}." +
                    "${value ushr 8 and 255}.${value and 255}"
        }
    }
}
