/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer

/**
 * Canonical form of a MAC address: six hexadecimal pairs separated by colons.
 * Matches what [parseMacAddress] accepts and what the editor writes back.
 */
private val MAC_PATTERN = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")

/** A MAC of all zeroes means "nobody filled this in yet". */
private const val ZERO_MAC = "00:00:00:00:00:00"

/** Placeholder for "no particular subnet"; never a real destination on its own. */
private const val ZERO_BROADCAST = "0.0.0.0"

/**
 * Whether this string is a complete, usable MAC address. Extension form of
 * [normalizeMac], so callers can ask a value straight off an input field.
 */
fun String.isValidMacAddress(): Boolean {
    val normalized = normalizeMac()
    return normalized.matches(MAC_PATTERN) && normalized != ZERO_MAC
}

/**
 * Parses given MAC address.
 * Throws an exception if it is not a valid MAC address.
 */
@OptIn(ExperimentalStdlibApi::class)
fun parseMacAddress(macAddress: String): ByteArray {
    val normalized = macAddress.normalizeMac()
    if (!normalized.matches(MAC_PATTERN)) throw IllegalArgumentException("Invalid MAC address")
    if (normalized == ZERO_MAC) throw IllegalArgumentException("MAC address is not set")
    return normalized.hexToByteArray(HexFormat { bytes.byteSeparator = ":" })
}

/**
 * Coverts to [InetAddress].
 */
fun parseBroadcastAddress(address: String): InetAddress {
    try {
        return InetAddress.getByName(address)
    } catch (t: Throwable) {
        throw IllegalArgumentException("Invalid broadcast address: $address", t)
    }
}

/**
 * Strips whatever separator the user happened to type and returns the colon form.
 *
 * MAC addresses are copied off a `getmac` window, an IPMI readout or a router page,
 * and each of those has its own style (`AA-BB-...`, `aabb.ccdd...`, spaces, no
 * separator at all). Only the colon form is stored, so the value read back always
 * round-trips through [parseMacAddress].
 */
fun String.normalizeMac(): String {
    val digits = filter { it.isLetterOrDigit() }
    if (digits.length != 12) return trim().lowercase()
    return digits.lowercase().chunked(2).joinToString(":")
}

/**
 * Broadcasts Wake-on-LAN magic packet for [macAddress].
 * Cannot be called from main thread.
 *
 * [configuredBroadcast] is what the server profile stores. When it is empty, or the
 * `255.255.255.255` placeholder, the address is taken from the network this device is
 * actually attached to, falling back to every interface's own broadcast address. The
 * socket is then bound to that network so the packet leaves through the Wi-Fi or wired
 * interface rather than whatever the OS currently considers the default route.
 */
fun broadcastWoLPackets(context: Context, macAddress: String, configuredBroadcast: String, port: Int) {
    val macBytes = parseMacAddress(macAddress)
    val network = LocalNetwork.current(context)
    val addresses = linkedSetOf<String>()

    val configured = configuredBroadcast.trim()
    // An address, not a name. `getByName` resolves hostnames, so a profile that came out of
    // a backup file or a scanned QR code would turn a "broadcast address" field into a DNS
    // lookup of the caller's choosing - and the answer would then be where the packet goes.
    // The editor and the profile sanitizer only ever keep a dotted-quad here; insist on it.
    if (configured.isNotEmpty() && configured != ZERO_BROADCAST && !LocalNetwork.isAnyBroadcast(configured) &&
            LocalNetwork.isIpv4(configured)) {
        addresses += configured
    } else {
        network?.let { addresses += it.broadcast }
        addresses += getSystemBroadcastAddresses()
    }

    check(addresses.isNotEmpty()) { "No broadcast address available" }
    val packet = createMagicPacket(macBytes)
    addresses.forEach { address -> sendMagicPacket(packet, address, port, network?.network) }
}

/**
 * Returns every broadcast address currently configured on this device, one per
 * attached interface. Used when the profile did not name a subnet of its own.
 */
private fun getSystemBroadcastAddresses(): List<String> {
    return NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .map { it.interfaceAddresses }
            .fold(mutableListOf<InterfaceAddress>()) { list, addresses -> list.apply { addAll(addresses) } }
            .mapNotNull { it.broadcast?.hostAddress }
}

/**
 * Creates WoL magic packet payload: six synchronisation bytes followed by sixteen
 * copies of the target MAC address.
 */
private fun createMagicPacket(macBytes: ByteArray): ByteArray {
    check(macBytes.size == 6)
    return ByteBuffer.allocate(6 + (16 * 6)).apply {
        repeat(6) { put(255.toByte()) }
        repeat(16) { put(macBytes) }
    }.array()
}

private fun sendMagicPacket(payload: ByteArray, address: String, port: Int, network: Network?) {
    val socket = DatagramSocket()
    try {
        // Binding is best effort: a network that refuses it still has a working
        // route most of the time, and failing here would lose the other targets.
        runCatching { network?.bindSocket(socket) }
        socket.broadcast = true
        socket.send(DatagramPacket(payload, payload.size, parseBroadcastAddress(address), port))
    } finally {
        socket.close()
    }
}
