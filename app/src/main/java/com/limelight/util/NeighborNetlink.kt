/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * Reading the neighbour table over netlink is csicar/Ning's third ARP source
 * (LGPL-3.0-or-later), app/src/main/java/de/csicar/ning/scanner/ArpScanner.kt.
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import com.limelight.util.Log

/**
 * The kernel's neighbour table, asked the way the kernel means it to be asked: an
 * `RTM_GETNEIGH` dump over a `NETLINK_ROUTE` socket, implemented in
 * `app/src/main/cpp/netlink-neigh.c`.
 *
 * Why a syscall needs a shared library at all: neither Java nor libcore speaks netlink.
 * NetworkInterface stops at the interface list, `Os.*` stops at what bionic wrapped, and
 * the neighbour table is only reachable through that socket - which is why
 * the other sources of [LanScanner.readNeighbors] are the `ip` binary and a file under
 * /proc. Both of those are things a modern Android build is free to withhold from an
 * ordinary app: `ip` cannot be exec'd on devices whose SELinux policy says so, and since
 * Android 10 `/proc/net/arp` shows an unprivileged process nothing. Asking the kernel
 * directly is neither of those two things, so it keeps answering where they stop.
 *
 * The reason ning's scanner has a third ARP source, then, and the reason this app needs
 * one too. His is a prebuilt artifact fetched from a package repository at build time;
 * the dump here reads the same table off the same socket, in code that can be inspected.
 *
 * Nothing here fails loudly: [lines] hands back an empty list on any problem, because
 * this is one source among several and a scan must go on without it. The reason is logged.
 */
object NeighborNetlink {
    private const val TAG = "NeighborNetlink"

    /** Cleared when the library turns out to be missing, since retrying cannot fix that. */
    @Volatile
    private var usable = true

    init {
        try {
            System.loadLibrary("netlink-neigh")
            // The C side logs one INFO line per dump; hand it the recording switch and
            // every later one (see AppLogging), so an install with logging off stays quiet
            // even while scans keep running.
            AppLogging.registerNative { setLogEnabled(it) }
        } catch (e: UnsatisfiedLinkError) {
            usable = false
            Log.w(TAG, "libnetlink-neigh.so is unavailable", e)
        }
    }

    /**
     * One `"192.168.1.1 aa:bb:cc:dd:ee:ff"` entry per line: IPv4 only, and without the
     * entries the kernel itself calls unresolved. Empty when the table could not be read.
     *
     * Blocking - the dump waits on the kernel, so this belongs on an IO dispatcher.
     */
    fun lines(): List<String> {
        if (!usable) return emptyList()

        return try {
            dumpNeighbors()?.toList() ?: emptyList()
        } catch (e: UnsatisfiedLinkError) {
            usable = false
            Log.w(TAG, "Cannot call into libnetlink-neigh.so", e)
            emptyList()
        }
    }

    /**
     * The native dump. Kept private and named exactly as the C side expects: the default
     * ProGuard configuration keeps the names of classes that declare native methods, so
     * this binding survives a minified build without an explicit keep rule.
     */
    private external fun dumpNeighbors(): Array<String>?

    /** Parks the C-side log switch; named exactly as the C side expects. */
    private external fun setLogEnabled(enabled: Boolean)
}
