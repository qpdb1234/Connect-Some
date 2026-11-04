/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.limelight.util.Log
import androidx.core.content.ContextCompat
import java.net.InetAddress

/**
 * Browses the local network for VNC servers. Each server is looked for under every
 * service type in [SERVICE_TYPES] - the RFB type registered for DNS-SD plus the
 * older `_rfb._tcp` spelling - because a host only publishes the one its stack was
 * written against, and browsing the wrong name finds nothing at all.
 *
 * It is intentionally *not* built on the jmDNS machinery that the GameStream
 * discovery uses: [com.limelight.nvstream.mdns.JmDNSDiscoveryAgent] owns the
 * shared jmDNS singleton and closes it when its last reference goes away, so a
 * second client would either be silently killed or would keep the singleton
 * alive after GameStream stopped. Using [NsdManager] here keeps the two
 * protocols independent - a failure in one never affects the other, which is
 * the whole point of the split.
 *
 * VNC servers publish a single address, so we do not need jmDNS's multi-address
 * workarounds; [NsdManager] resolves them fine on every supported API level.
 */
class VncDiscoveryAgent(context: Context, private val listener: Listener) {

    interface Listener {
        /** A VNC server was found. [host] is a literal address, already bracketed when IPv6. */
        fun onServerFound(name: String, host: String, port: Int)

        /** Browsing could not be started. Called at most once per [start]. */
        fun onDiscoveryFailure(e: Exception)
    }

    companion object {
        private const val TAG = "VncServiceDiscovery"

        /**
         * The types worth browsing. `_vnc-rgb._tcp` is the registration for the RFB
         * protocol; `_rfb._tcp` predates it and is still what libvncserver-based and
         * some Unix/macOS servers emit. A server answering under both lands on the
         * same card, because the aggregator keys services by protocol and port.
         */
        private val SERVICE_TYPES = listOf("_vnc-rgb._tcp", "_rfb._tcp")
    }

    private val nsdManager = ContextCompat.getSystemService(context, NsdManager::class.java)
    private val multicastLock = ContextCompat.getSystemService(context, WifiManager::class.java)
            ?.createMulticastLock(TAG)

    /** One listener per browsed type, because NsdManager refuses to reuse a listener. */
    private val discoveryListeners = ArrayList<NsdManager.DiscoveryListener>()

    /** Types that may still deliver results. Guarded by [lock] - callbacks are off-thread. */
    private var liveTypes = 0
    private var startFinished = false
    private var failureReported = false
    private val lock = Any()

    fun start() {
        synchronized(lock) {
            if (discoveryListeners.isNotEmpty())
                return // Already running
            failureReported = false
            startFinished = false
            liveTypes = 0
        }

        val manager = nsdManager
        if (manager == null) {
            reportFailure(IllegalStateException("NsdManager unavailable"))
            return
        }

        runCatching { multicastLock?.acquire() }

        var thrown: Throwable? = null
        for (type in SERVICE_TYPES) {
            val created = makeListener(type)
            synchronized(lock) { discoveryListeners.add(created) }
            runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, created) }
                    .onSuccess { synchronized(lock) { liveTypes++ } }
                    .onFailure {
                        Log.w(TAG, "Could not start browsing $type", it)
                        if (thrown == null) thrown = it
                    }
        }

        // From here on a type can still die through onStartDiscoveryFailed, but a browse
        // that never even reached the socket layer is reported right away.
        val live = synchronized(lock) {
            startFinished = true
            liveTypes
        }
        if (live == 0) {
            stop()
            val cause = thrown
            reportFailure(when {
                cause is Exception -> cause
                cause != null -> RuntimeException(cause)
                else -> IllegalStateException("No VNC browse could be started")
            })
        }
    }

    fun stop() {
        val listeners = synchronized(lock) {
            val copy = discoveryListeners.toList()
            discoveryListeners.clear()
            liveTypes = 0
            startFinished = false
            copy
        }
        for (discoveryListener in listeners) {
            runCatching { nsdManager?.stopServiceDiscovery(discoveryListener) }
                    .onFailure { Log.w(TAG, "Failed to stop VNC discovery agent", it) }
        }
        if (listeners.isNotEmpty()) {
            runCatching { multicastLock?.release() }
        }
    }

    /**
     * The callback handed to [NsdManager] for one browsed type. Each type gets its own
     * instance, and each one is expected to keep working even when its sibling fails.
     */
    private fun makeListener(type: String) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(failed: String, errorCode: Int) {
            Log.e(TAG, "VNC discovery of $failed failed to start: $errorCode")
            // Dropping one type must not cancel a browse that is still answering, so
            // the caller only hears about it once the last live type is gone.
            val exhausted = synchronized(lock) {
                if (liveTypes > 0) liveTypes--
                startFinished && liveTypes == 0
            }
            if (exhausted) {
                reportFailure(RuntimeException("onStartDiscoveryFailed: $errorCode"))
            }
        }

        override fun onStopDiscoveryFailed(failed: String, errorCode: Int) {
            Log.w(TAG, "VNC discovery of $failed failed to stop: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "VNC discovery started: $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "VNC discovery stopped: $serviceType")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            resolve(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            // Results are kept in the aggregator (a lost advertisement should not
            // make a server vanish from under the user mid-browse).
        }
    }

    /** Calls [Listener.onDiscoveryFailure] at most once per [start], as that method promises. */
    private fun reportFailure(e: Exception) {
        val first = synchronized(lock) {
            if (failureReported) false
            else {
                failureReported = true
                true
            }
        }
        if (first) listener.onDiscoveryFailure(e)
    }

    /**
     * Asks [NsdManager] for the full record of a found service and reports it back
     * through [Listener.onServerFound] once it arrives.
     */
    private fun resolve(info: NsdServiceInfo) {
        val requested = object : NsdManager.ResolveListener {
            override fun onResolveFailed(toResolve: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Failed to resolve ${toResolve.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = pickAddress(resolved) ?: return
                val name = resolved.serviceName?.takeIf { it.isNotBlank() } ?: host
                listener.onServerFound(name, host, resolved.port)
            }
        }

        // The non-deprecated overload (API 34+) needs an Executor, and this class must
        // also work on minSdk 26 - the deprecated form still works everywhere.
        @Suppress("DEPRECATION")
        runCatching { nsdManager?.resolveService(info, requested) }
                .onFailure { Log.w(TAG, "resolveService threw", it) }
    }

    /**
     * Returns a usable host string, bracketing IPv6 literals so they can be placed
     * into a URL. `hostAddresses` is only available from API 29, and `host` returns
     * null on API 33+, so both paths are needed.
     */
    private fun pickAddress(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            runCatching { info.hostAddresses }.getOrNull().orEmpty()
        else
            @Suppress("DEPRECATION")
            listOfNotNull(runCatching { info.host }.getOrNull())

        // A link-local IPv6 needs a scope id, which NsdManager does not give us, so
        // prefer any other address when there is one.
        val candidate = addresses.firstOrNull { !it.isLinkLocalAddress && it.hostAddress != null }
                ?: addresses.firstOrNull { it.hostAddress != null }

        val raw = candidate?.hostAddress ?: return null
        return if (raw.contains(':')) "[$raw]" else raw
    }
}
