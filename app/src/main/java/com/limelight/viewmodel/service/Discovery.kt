/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.limelight.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.limelight.model.ServerProfile
import com.limelight.nvstream.mdns.JmDNSDiscoveryAgent
import com.limelight.nvstream.mdns.MdnsComputer
import com.limelight.nvstream.mdns.MdnsDiscoveryAgent
import com.limelight.nvstream.mdns.MdnsDiscoveryListener
import com.limelight.nvstream.mdns.NsdManagerDiscoveryAgent
import com.limelight.util.LanScanner
import com.limelight.util.LiveEvent
import com.limelight.util.MacVendorLookup
import com.limelight.util.MdnsProbeScanner
import com.limelight.util.NetbiosLookup
import com.limelight.util.Subnet
import com.limelight.util.ipv4ToLong
import com.limelight.util.isUnicastMac
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet6Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds what is on the local network, on one explicit request from the user.
 *
 * A scan is one round of two independent halves:
 *
 *  - **mDNS browsing** for hosts that announce themselves: Moonlight-compatible
 *    machines (GeForce Experience GameStream and Sunshine) publishing `_nvstream._tcp`,
 *    using the same agents Moonlight itself uses, and VNC servers publishing
 *    either RFB service type (see [VncDiscoveryAgent]). Those advertisements carry the exact
 *    port, so the resulting [ServerProfile]s pair and connect without further editing.
 *  - **A walk of the subnet** ([LanScanner]) for everything else. Each address is
 *    probed, live hosts are then checked for the ports that mean remote desktop, and the
 *    kernel's neighbour cache supplies the hardware addresses of machines that never
 *    answered a probe. This is what puts the router, the printer and a PC whose mDNS
 *    advertisement got lost on the list.
 *  - **Active mDNS probing** ([MdnsProbeScanner], from ning's LowLevelMDnsScanner):
 *    unsolicited PTR queries for a dozen common service types, which makes machines
 *    that run a Bonjour stack but advertise nothing we browse - and name nothing we
 *    browse - answer with their instance name anyway.
 *
 * Both halves feed one aggregate keyed by address, so a machine that announces itself
 * *and* answers a probe is a single card with one or more services on it.
 *
 * Nothing here runs on its own: discovery starts only when the user presses the scan
 * button and ends by itself once the round is over, because a phone should not be
 * flooding a home network with 254 probes just because the app is open.
 */
object Discovery {
    private const val TAG = "NvServiceDiscovery"
    private const val DISCOVERY_INTERVAL_MS = 1000

    /**
     * Neighbour cache refresh interval while the walk is running. ning merged the ARP
     * table after every ping hit; a tick gets the same progressively-appearing cards
     * without running `ip neigh` hundreds of times per round.
     */
    private const val ARP_TICK_MS = 1500L

    /**
     * How many previously seen addresses a round remembers for the next one.
     */
    private const val KNOWN_ADDRESS_LIMIT = 128
    private const val PREFS = "lan_scan_history"
    private const val PREF_KNOWN_ADDRESSES = "known_addresses"

    /**
     * Hardware addresses learned across rounds, kept under `address=mac` strings.
     * The neighbour cache is a cache: an entry ages out of REACHABLE a few seconds
     * after the traffic that caused it, which is why a card that showed a MAC a moment
     * ago would lose it again without a memory of its own.
     */
    private const val PREF_MAC_ADDRESSES = "mac_addresses"

    /** Cap on that memory, so a phone that has walked a few busy networks stays cheap. */
    private const val SAVED_MAC_LIMIT = 256

    /**
     * NetBIOS queries in flight. Lower than the service probes: one UDP exchange per
     * address plus its timeout, and the addresses that never answer are the majority.
     */
    private const val MAC_PROBE_CONCURRENCY = 8

    /**
     * The neighbour cache is filled by the probes themselves, a moment after they
     * return. Reading it immediately loses the slow responders.
     */
    private const val ARP_SETTLE_MS = 500L

    /**
     * How long browsing keeps running after the walk is done. mDNS replies to the
     * initial query usually arrive within a second; this is the tail that catches the
     * access point relaying them late.
     */
    private const val MDNS_GRACE_MS = 2500L

    /**
     * Minimum length of a round. On a live LAN the walk can be over in a blink -
     * every probe comes back with a quick refusal or ICMP error - and browsing
     * replies that take a few seconds would then arrive after the browsers had
     * already been stopped, so the round ends up reporting less than it would have.
     * The walk only counts the addresses; this gives the announcements their time.
     */
    private const val MIN_ROUND_MS = 20_000L

    /**
     * Live hosts whose services get probed at once. Widening this would only make a
     * round take longer without finding more.
     */
    private const val SERVICE_PROBE_CONCURRENCY = 8

    /**
     * Ports that identify a GameStream/Sunshine host rather than a VNC server. The two
     * sets do not overlap, which is what lets a probe result be turned into a protocol.
     */
    private val GAME_STREAM_PORTS = listOf(47984, 47989)

    /**
     * List of connectable servers found by the current or most recent scan. Kept
     * separate from [devices] because saved-server rediscovery works on services.
     *
     * Both lists start out empty rather than undefined: the discovered tab shows its
     * explanation only while the result is empty, and a tab that opens on a blank page
     * would leave the user without either a result or a hint.
     */
    val servers = MutableLiveData<List<ServerProfile>>(emptyList())

    /**
     * Everything the scan found, connectable or not. This is what the discovered tab
     * shows.
     */
    val devices = MutableLiveData<List<DiscoveredDevice>>(emptyList())

    /**
     * Status of the scan.
     */
    val isRunning = MutableLiveData(false)

    /**
     * Percent of the subnet walk completed, 0-100, or -1 while no walk is running
     * (stages without a meaningful denominator - mDNS, browsing - leave it where the
     * walk put it, exactly like ning's scan bar which only counts the ping sweep).
     */
    val progress = MutableLiveData(-1)

    /**
     * Stages one round walks through, in order. Only [WALKING] has a countable
     * denominator, which is why [progress] is left alone by the others.
     */
    enum class ScanStage {
        /** No round is running. */
        IDLE,

        /** Listening for announcements, with or without a subnet to walk. */
        BROWSING,

        /** The subnet sweep. */
        WALKING,

        /** Checking the remote-desktop ports of the hosts that turned out to exist. */
        PROBING,

        /** Reverse lookups for machines nobody announced. */
        NAMING,

        /** Waiting out the tail of the announcements still in flight. */
        SETTLING,
    }

    /**
     * What the scan is doing right now, for the status line on the discovered tab.
     */
    val stage = MutableLiveData(ScanStage.IDLE)

    /**
     * What a round ended with, fired once each time a round runs to its end. A list of
     * machines is a result the user should not have to count by hand, and "the scan found
     * nothing" is worth saying out loud. A round that was stopped early reports nothing:
     * its cards stay on the tab, and the status line already carries their number.
     */
    val roundFinished = LiveEvent<ScanSummary>()

    /**
     * Fired once per round when a VPN was found to be capturing this app's traffic. Under
     * a VPN the scan binds every probe to the physical LAN and drops any address it cannot
     * confirm there, so the fake hosts a VPN's local proxy would otherwise accept are gone -
     * but so can hosts that only answer off the LAN. The discovered tab says this once so the
     * user learns why the list looks shorter than with the VPN off.
     */
    val vpnInterference = LiveEvent<Boolean>()

    /**
     * Outcome of one round. [total] counts every card the scan filled in, [connectable]
     * only those that can actually be opened.
     */
    data class ScanSummary(val total: Int, val connectable: Int)

    private val impl by lazy { Impl() }

    /**
     * Starts a scan round, discarding previous results.
     */
    fun start(context: Context) = impl.start(context)

    /**
     * Aborts a running scan. Results found so far stay visible.
     */
    fun stop() = impl.stop()

    /**
     * The hardware address learned for [address] in an earlier round, if any. An on-demand
     * MAC lookup consults this first, so a host this device has already identified does not
     * have to be probed over again - and, with the cache carried through a backup (see
     * [exportCache]), a reinstall can answer it before the network is ever walked.
     */
    fun savedMac(address: String): String? = impl.savedMac(address)

    /**
     * The two caches discovery persists between rounds - the addresses a previous round found
     * real and the hardware addresses learned across rounds - keyed by the preference name each
     * lives under. Neither is secret (they are addresses and MACs the device already learned on
     * its own LAN), yet without them a fresh install must re-walk the network and re-learn every
     * MAC before a card can name a host. [importCache] is the other half.
     */
    fun exportCache(context: Context): Map<String, Set<String>> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet(PREF_KNOWN_ADDRESSES, emptySet())?.toSet() ?: emptySet()
        val macs = prefs.getStringSet(PREF_MAC_ADDRESSES, emptySet())?.toSet() ?: emptySet()
        return mapOf(PREF_KNOWN_ADDRESSES to known, PREF_MAC_ADDRESSES to macs)
                .filterValues { it.isNotEmpty() }
    }

    /**
     * Writes a backed-up discovery cache into the preferences [exportCache] read, so the next
     * scan starts from what this device already knew. Only the two recognised keys are accepted -
     * anything else in the map is ignored, so a hand-edited file cannot reach an unrelated
     * preference. The live MAC memory is re-read so a restore takes effect without a restart.
     */
    fun importCache(context: Context, cache: Map<String, Set<String>>) {
        val appContext = context.applicationContext
        val editor = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        cache[PREF_KNOWN_ADDRESSES]?.let { editor.putStringSet(PREF_KNOWN_ADDRESSES, it) }
        cache[PREF_MAC_ADDRESSES]?.let { editor.putStringSet(PREF_MAC_ADDRESSES, it) }
        runCatching { editor.apply() }
        impl.reloadSavedMacs(appContext)
    }

    /**
     * mDNS start/stop runs on a dedicated single-thread executor so that requests are
     * serialized (the agents tolerate repeated calls, but a defined order still matters
     * when a failure inside an agent triggers another stop).
     *
     * GameStream agent choice follows Moonlight's DiscoveryService: jmDNS below Android
     * 14 (NsdManager there lacks multi-address service resolution), NsdManager on
     * Android 14+ where it works better in mDNS-proxying environments (emulator, WSA,
     * ChromeOS). VNC always uses NsdManager - see [VncDiscoveryAgent] for why it must
     * not share the jmDNS singleton.
     */
    @Suppress("DEPRECATION")  // Yeah, f**k you too Google
    private class Impl {
        private val executor = Executors.newSingleThreadExecutor()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var scanJob: Job? = null

        // Read by [updateRunningState] on the caller's thread and written on [executor],
        // hence volatile: a stale null makes the scan button stop spinning while browsing is
        // still running (and a stale non-null keeps it spinning after the agents are gone).
        @Volatile
        private var gsAgent: MdnsDiscoveryAgent? = null

        @Volatile
        private var vncAgent: VncDiscoveryAgent? = null

        @Volatile
        private var multicastLock: WifiManager.MulticastLock? = null

        /**
         * Round counter. A new [start] queues its agent setup on [executor] *before* the
         * previous round's coroutine notices the cancellation and queues its own agent
         * teardown, so the queue can end up as create-then-stop: the fresh round loses both
         * browsers and only the subnet walk reports anything, while the button still spins.
         * Each round therefore records the number it started with and skips the teardown when
         * a newer round has taken over.
         */
        private val round = AtomicInteger(0)

        /**
         * Whether a round is in progress. Kept as a plain flag rather than read off the
         * job because the job's own completion callback can run before the executor has
         * finished stopping the agents, which would leave the button spinning.
         */
        @Volatile
        private var scanning = false

        /**
         * Aggregate of the whole scan, guarded by [lock] because mDNS callbacks, the
         * executor and the coroutine all write to it.
         *
         * Services are keyed by `protocol:port` so that a re-advertisement of the same
         * service replaces its entry while a second display on the same machine stays a
         * second entry.
         */
        private val lock = Any()
        private val servicesByAddress = LinkedHashMap<String, LinkedHashMap<String, ServerProfile>>()
        private val announcedNames = HashMap<String, String>()
        private val resolvedNames = HashMap<String, String>()
        private val macAddresses = HashMap<String, String>()

        /**
         * Device models read from mDNS TXT attributes (`model=` / `md=`), keyed by address.
         * This is what names a phone's concrete model - the TXT is the only place an
         * Android responder states it without a connection or a permission.
         */
        private val models = HashMap<String, String>()

        /**
         * What earlier rounds saw, as a fallback for the addresses the neighbour table
         * has since dropped. Only ever consulted for an address this round already has
         * a card for - old knowledge alone must not resurrect a machine.
         */
        private val savedMacs = HashMap<String, String>()
        private val presentAddresses = LinkedHashSet<String>()

        private val gsListener = object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) = execute {
                val addr = computer.localAddress ?: computer.ipv6Address ?: return@execute
                val host = addr.hostAddress ?: return@execute
                // IPv6 literals must be bracketed to be usable as a URL host.
                val hostString = if (addr is Inet6Address) "[$host]" else host
                val profile = ServerProfile(name = computer.name, host = hostString,
                        port = computer.port, protocol = ServerProfile.PROTOCOL_GAME_STREAM)
                addService(hostString, profile, computer.name)
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                Log.w(TAG, "GameStream discovery failure", e)
                // Only give up on this protocol; VNC browsing and the walk keep running.
                execute { stopGameStream() }
            }
        }

        private val vncListener = object : VncDiscoveryAgent.Listener {
            override fun onServerFound(name: String, host: String, port: Int) = execute {
                val profile = ServerProfile(name = name, host = host, port = port,
                        protocol = ServerProfile.PROTOCOL_VNC)
                addService(host, profile, name)
            }

            override fun onDiscoveryFailure(e: Exception) {
                Log.w(TAG, "VNC discovery failure", e)
                execute { stopVnc() }
            }
        }

        private fun execute(action: Runnable) {
            runCatching { executor.execute(action) }.onFailure { Log.e(TAG, "Cannot execute action", it) }
        }

        private fun addService(address: String, profile: ServerProfile, announcedName: String) {
            synchronized(lock) {
                val services = servicesByAddress.getOrPut(address) { LinkedHashMap() }
                services["${profile.protocol}:${profile.port}"] = profile
                presentAddresses.add(address)
                mergeName(announcedNames, address, announcedName)
            }
            postResults()
        }

        private fun addPresent(address: String) {
            synchronized(lock) { presentAddresses.add(address) }
            postResults()
        }

        /**
         * A name and (optionally) a device model learned from an active mDNS reply. These
         * name machines rather than services, so they never outrank a real advertisement but
         * do fill the gap the reverse lookup would leave - and the address itself is confirmed
         * present by the fact that it answered. [model] comes from a TXT attribute and is
         * kept even when the machine announced no name we could use.
         */
        private fun addMdnsName(address: String, name: String, model: String) {
            synchronized(lock) {
                presentAddresses.add(address)
                mergeName(announcedNames, address, name)
                if (model.isNotBlank() && models[address].isNullOrBlank()) models[address] = model
            }
            postResults()
        }

        /**
         * A name that identifies a machine only to a machine: a bare "local" suffix, a random
         * hex id, or an ADB wireless-debugging instance (`adb-1a2b3c4d`, a device serial). These
         * are better than nothing but must not beat a friendly name another source learned.
         */
        private val WEAK_NAME =
                Regex("^(?:local|adb-[0-9a-fA-F]{4,}|[0-9a-fA-F]{16,})$", RegexOption.IGNORE_CASE)

        private fun isWeakName(name: String): Boolean = name.isBlank() || name.matches(WEAK_NAME)

        /**
         * Records a discovered host name, upgrading a weak placeholder to a real name but never
         * the reverse: the first source to give a readable name wins, and a later serial or
         * "local" cannot clobber it. This is what stops an ADB debug serial from hiding the KDE
         * Connect / cast friendly name that names the same phone. [target] is mutated under `lock`.
         */
        private fun mergeName(target: HashMap<String, String>, address: String, candidate: String) {
            if (candidate.isBlank()) return
            val existing = target[address]
            if (existing.isNullOrBlank() || (isWeakName(existing) && !isWeakName(candidate))) {
                target[address] = candidate
            }
        }

        /**
         * Merges the kernel's neighbour table into the scan's MAC memory and republishes:
         * its hardware addresses are what every surviving card is enriched with (see
         * [buildDevices]).
         *
         * The neighbour table only ever adds MACs and names now, never cards. Every stale
         * ARP entry of a busy home network is a machine that was once seen and may well be
         * gone; promoting each to a discovered host is exactly the "a lot of servers that
         * are not really there" bug. A card must be earned by a confirmed-live probe, an
         * mDNS announcement or a service advertisement - not by the cache's memory.
         *
         * Returns the table so the caller can name the hosts it only now learned about.
         */
        private suspend fun mergeNeighbors(): Map<String, String> {
            val neighbors = LanScanner.readNeighbors()
            synchronized(lock) {
                macAddresses.putAll(neighbors)
                savedMacs.putAll(neighbors)
            }
            postResults()
            return neighbors
        }

        /**
         * Record a hardware address that something other than the neighbour table told
         * us, and let the cards catch up with it. An address that is not a real unicast
         * MAC is dropped here: a VPN proxy's fabricated `00:00:00:00:00:00` reply used to
         * reach the store unchecked and show up as an "XEROX CORPORATION" card.
         */
        private fun learnMac(address: String, mac: String) {
            val usable = mac.isUnicastMac()
            if (!usable) {
                Log.d(TAG, "Ignoring invalid MAC for $address: $mac")
                return
            }
            val changed = synchronized(lock) {
                if (macAddresses[address] == mac) false
                else {
                    macAddresses[address] = mac
                    savedMacs[address] = mac
                    true
                }
            }
            if (changed) postResults()
        }

        /**
         * One card per thing the user can act on: a discovered service gets its own card
         * (a PC running both Sunshine and VNC appears twice, exactly as two saved
         * profiles would), and a machine with no service we can speak gets one
         * informational card so it is still visible in the inventory.
         *
         * What earns a card is evidence that the machine is really there *this round*: an
         * answer to one of the (LAN-bound) probes, or a service on it from an mDNS
         * announcement or an advertisement. The neighbour table is *not* one of those - it
         * only fills in a MAC and a name for a card some other source already earned,
         * because a busy home network's cache is full of stale entries for machines that
         * are simply gone, and promoting each of those was the fake-server bug. A hardware
         * address is not required, and used to be: Android denies both `ip neigh` and
         * `/proc/net/arp` to unprivileged apps on some devices, and a host that announced
         * itself over mDNS can perfectly well sit beyond this phone's own subnet. Asking
         * for a MAC therefore threw away exactly the machines the scan is for - up to and
         * including the entire result, empty.
         *
         * Ordered by address so the cards of one machine stay next to each other and the
         * list does not reshuffle while a round is still filling it in.
         */
        private fun buildDevices(): List<DiscoveredDevice> = synchronized(lock) {
            val result = ArrayList<DiscoveredDevice>()
            val addresses = LinkedHashSet<String>()
            addresses.addAll(presentAddresses)
            addresses.addAll(servicesByAddress.keys)
            addresses.addAll(macAddresses.keys)

            // The scanning phone is a card like any other, but it is never in its own ARP
            // table, so its MAC has to come from the interface; used only where the neighbour
            // table and saved results both fell silent, i.e. for the device's own address.
            val ownMacs = LanScanner.localHardwareAddressMap()

            for (address in addresses) {
                val services = servicesByAddress[address]?.values?.toList() ?: emptyList()
                val deviceName = announcedNames[address] ?: resolvedNames[address] ?: ""
                val deviceModel = models[address] ?: ""
                val mac = macAddresses[address] ?: savedMacs[address] ?: ownMacs[address] ?: ""
                if (services.isEmpty() && address !in presentAddresses) continue
                val vendor = if (mac.isBlank()) "" else MacVendorLookup.vendor(mac) ?: ""

                if (services.isEmpty()) {
                    result.add(DiscoveredDevice(address, deviceName, mac, vendor, deviceModel, emptyList()))
                } else {
                    // One card per protocol, not per port: a Sunshine host that answers on
                    // both 47984 and 47989, or a VNC server on 5900 and 5901, is a single
                    // machine the user acts on - the extra ports are alternate entry points,
                    // not separate servers. The lowest port represents the card, and every
                    // port that answered is kept so the card can list what was folded in.
                    for ((_, group) in services.groupBy { it.protocol }) {
                        val representative = group.minByOrNull { it.port } ?: continue
                        val ports = group.map { it.port }.distinct().sorted()
                        // The advertisement names the service; a probe only names the host. But an
                        // ADB advertisement names it with a debug serial ("adb-1a2b3c4d"), weaker
                        // than a friendly host name the mDNS probe learned - so when the service
                        // name is such a generated id, prefer the (better) host name.
                        val name = if (isWeakName(representative.name) && !isWeakName(deviceName)) deviceName
                        else representative.name.ifBlank { deviceName }
                        result.add(DiscoveredDevice(address, name, mac, vendor, deviceModel, listOf(representative), ports))
                    }
                }
            }

            result.sortedWith(
                    compareBy({ it.address.ipv4ToLong() ?: Long.MAX_VALUE },
                            { it.services.firstOrNull()?.protocol ?: -1 }))
        }

        private fun postResults() {
            val found = buildDevices()
            devices.postValue(found)
            // The cards fold a protocol's ports into one representative, but the raw server
            // feed keeps every port that answered: the rediscovery indicator matches a saved
            // profile on its exact port, and dropping the folded-away ones here would stop a
            // saved 47989 Sunshine entry from lighting up even though the host still serves it.
            val allServices = synchronized(lock) {
                servicesByAddress.values.flatMap { it.values.toList() }
            }
            servers.postValue(allServices)
        }

        private fun updateRunningState() {
            isRunning.postValue(scanning || gsAgent != null || vncAgent != null)
        }

        fun start(context: Context) {
            val appContext = context.applicationContext // Need app context to avoid possibility of WiFiManager leaks

            execute {
                // Forget old results
                synchronized(lock) {
                    servicesByAddress.clear()
                    announcedNames.clear()
                    resolvedNames.clear()
                    macAddresses.clear()
                    models.clear()
                    presentAddresses.clear()
                }
                loadSavedMacs(appContext)
                postResults()

                if (gsAgent == null) {
                    gsAgent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // jmDNS manages its own multicast lock
                        JmDNSDiscoveryAgent(appContext, gsListener)
                    } else {
                        // NsdManager does not acquire a multicast lock automatically
                        val wifiManager = ContextCompat.getSystemService(appContext, WifiManager::class.java)
                        multicastLock = wifiManager?.createMulticastLock(TAG)?.also { it.acquire() }
                        NsdManagerDiscoveryAgent(appContext, gsListener)
                    }

                    runCatching { gsAgent!!.startDiscovery(DISCOVERY_INTERVAL_MS) }
                            .onFailure { Log.w(TAG, "Failed to start GameStream discovery", it) }
                }

                if (vncAgent == null) {
                    vncAgent = VncDiscoveryAgent(appContext, vncListener)
                    runCatching { vncAgent!!.start() }
                            .onFailure { Log.w(TAG, "Failed to start VNC discovery", it) }
                }
            }

            scanJob?.cancel()
            scanning = true
            progress.postValue(-1)
            stage.postValue(ScanStage.BROWSING)
            val myRound = round.incrementAndGet()
            scanJob = scope.launch {
                var cancelled = false
                try {
                    runScan(appContext)
                } catch (e: CancellationException) {
                    // Stopped by the user, or by the screen going away. Whatever was found
                    // stays on the tab, but no summary is announced: a round that was cut
                    // short is not a result, and this path also runs when nobody is left
                    // watching to read one.
                    cancelled = true
                    throw e
                } finally {
                    // Over whether the round finished or was cancelled: browsing must not
                    // keep running once the user is no longer scanning - unless a newer round
                    // has already taken the agents over (see [round]).
                    scanning = false
                    execute {
                        if (myRound == round.get()) {
                            if (!cancelled) reportRound()
                            stopGameStream()
                            stopVnc()
                        } else {
                            Log.i(TAG, "A newer round owns the browsers; leaving them alone")
                        }
                    }
                }
            }
            updateRunningState()
        }

        /**
         * Tell the UI what the round ended with, counted off the very cards it is showing -
         * so the number in the sentence cannot disagree with the list under it.
         */
        private fun reportRound() {
            val found = buildDevices()
            roundFinished.fireAsync(ScanSummary(found.size, found.count { it.hasService }))
        }

        /**
         * One round: walk the subnet, name and identify what answered, then let browsing
         * finish and go idle on its own. The active mDNS probe runs alongside the walk -
         * its replies need none of the probes' patience.
         */
        private suspend fun runScan(context: Context) = coroutineScope {
            val startedAt = SystemClock.elapsedRealtime()
            MacVendorLookup.init(context)
            val mdnsJob = launch { probeMdnsNames(context) }

            val subnets = LanScanner.subnets(context)
            // A VPN up means an unbound probe would reach the local proxy that accepts
            // every address on the subnet; force every probe onto a real LAN handle and
            // drop any address that has none, so the walk reports the machines that are
            // actually there rather than the tunnel's fabrications.
            val requireLanBind = LanScanner.vpnCapturesTraffic(context)
            if (requireLanBind) vpnInterference.fireAsync(true)
            if (subnets.isEmpty()) {
                Log.i(TAG, "No scannable subnet; relying on mDNS announcements alone")
            } else {
                coroutineScope {
                    // Cards with a hardware address appear as the walk provokes the
                    // neighbour cache, instead of only when the whole sweep is done.
                    val arpTicker = launch { while (isActive) { delay(ARP_TICK_MS); mergeNeighbors() } }
                    stage.postValue(ScanStage.WALKING)
                    LanScanner.sweep(subnets, knownAddresses(context), requireLanBind,
                            onAlive = { address -> addPresent(address) },
                            onProgress = { done, total ->
                                progress.postValue(((done * 100L) / maxOf(total, 1)).toInt().coerceIn(0, 100))
                            })
                    arpTicker.cancel()
                }

                // The sweep has just talked to everything that answers, so the neighbour
                // table already holds the hardware addresses of most of the network. Taking
                // them now rather than after the service probes means the first cards appear
                // while the probe stage is still running instead of all at once at its end.
                mergeNeighbors()

                // Only hosts that proved they exist get the wider check: probing 254
                // addresses for six ports each would take minutes and tell us nothing about
                // the dead ones. "Proved" is the whole set the walk and the neighbour table
                // filled in, not just the addresses that answered a probe - a machine whose
                // firewall drops the liveness ports is still the machine the user came here
                // to find, and it is exactly the one that needs a port that does answer.
                // Off-LAN addresses are left out: their own subnet is what the probes are
                // bound to, and a route elsewhere would turn the check into an attempt on
                // networks this scan was never about.
                stage.postValue(ScanStage.PROBING)
                val targets = synchronized(lock) {
                    LinkedHashSet(presentAddresses.filter { ip -> subnets.any { it.contains(ip) } })
                }
                for (group in targets.chunked(SERVICE_PROBE_CONCURRENCY)) {
                    coroutineScope {
                        group.map { address -> launch { probeServices(address, subnets, requireLanBind) } }
                    }
                }

                delay(ARP_SETTLE_MS)
                stage.postValue(ScanStage.NAMING)
                val neighbors = mergeNeighbors()

                // On the builds that hide the neighbour table, that merge reported nothing
                // at all while the walk had just proved a dozen machines exist. Asking the
                // machines themselves is the other way to a hardware address without a
                // permission, and only the addresses left blank are worth asking about.
                lookupMissingMacs(subnets)

                resolveMissingNames(neighbors, subnets)
                postResults()
                saveKnownAddresses(context)
                saveSavedMacs(context)
            }

            // Let the announcements still in flight arrive; the round ends in the
            // caller's finally block. A walk that finished quickly must not drag the
            // browsers down with it: they stay up until the round has run its minimum
            // length, which is what late replies are delivered into (see [MIN_ROUND_MS]).
            stage.postValue(ScanStage.SETTLING)
            mdnsJob.join()
            val remaining = MIN_ROUND_MS - (SystemClock.elapsedRealtime() - startedAt)
            delay(maxOf(MDNS_GRACE_MS, remaining))

            // Announcements arriving during that tail bring machines the walk never touched,
            // so no earlier pass could have looked for their hardware addresses. Sweeping the
            // still-blank addresses once more is cheap by construction: the lookup skips every
            // address something has already named (see [lookupMissingMacs]), and a learned
            // address repaints its card on the way in.
            if (subnets.isNotEmpty()) {
                mergeNeighbors()
                lookupMissingMacs(subnets)
            }
        }

        /**
         * Active PTR queries for the common service types. Needs the multicast lock even
         * though the browsing agents hold one: this socket bypasses the platform mDNS
         * stack, so without it the Wi-Fi chip would keep the replies to itself.
         */
        @Suppress("DEPRECATION")
        private suspend fun probeMdnsNames(context: Context) {
            val wifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)
            val lock = runCatching { wifiManager?.createMulticastLock("$TAG-probe")?.also { it.acquire() } }
                    .onFailure { Log.w(TAG, "Cannot acquire multicast lock for mDNS probe", it) }
                    .getOrNull()
            try {
                val own = LanScanner.localAddresses()
                MdnsProbeScanner.probeCommon { ip, name, model ->
                    // A responder that reports the phone's own address is answering its own
                    // query; that is not a machine to put on the list.
                    if (ip !in own) addMdnsName(ip, name, model)
                }
            } finally {
                runCatching { lock?.release() }
            }
        }

        /** Addresses a previous round found real - probe them first next time. */
        private fun knownAddresses(context: Context): Set<String> = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(PREF_KNOWN_ADDRESSES, emptySet()) ?: emptySet()
        }.getOrDefault(emptySet())

        /**
         * Hardware addresses from earlier rounds. Read at the start of a scan so that the
         * first card a machine gets can already name its address, rather than waiting for
         * this round's probes to jog the neighbour cache into remembering it.
         */
        private fun loadSavedMacs(context: Context) {
            val entries = runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getStringSet(PREF_MAC_ADDRESSES, emptySet()) ?: emptySet()
            }.getOrDefault(emptySet())

            synchronized(lock) {
                savedMacs.clear()
                for (entry in entries) {
                    val separator = entry.indexOf('=')
                    if (separator <= 0) continue
                    val address = entry.substring(0, separator)
                    val mac = entry.substring(separator + 1)
                    if (address.ipv4ToLong() != null && mac.isUnicastMac()) savedMacs[address] = mac
                }
            }
        }

        /** Re-reads the persisted MAC memory into the live map, after a restore wrote new prefs. */
        fun reloadSavedMacs(context: Context) = loadSavedMacs(context.applicationContext)

        /** A MAC learned across earlier rounds, read without disturbing the scan. */
        fun savedMac(address: String): String? = synchronized(lock) { savedMacs[address] }

        private fun saveSavedMacs(context: Context) {
            val entries = synchronized(lock) {
                savedMacs.entries.asSequence().take(SAVED_MAC_LIMIT)
                        .map { "${it.key}=${it.value}" }.toSet()
            }
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putStringSet(PREF_MAC_ADDRESSES, entries).apply()
            }
        }

        /**
         * NetBIOS node status queries for the live addresses whose hardware address no
         * source gave. Best effort by construction: an address that does not answer costs
         * one timeout, and a card without a MAC line is still a card.
         */
        private suspend fun lookupMissingMacs(subnets: List<Subnet>) {
            val targets = synchronized(lock) {
                presentAddresses
                        .filter { address -> address.ipv4ToLong() != null }
                        .filter { address -> !macAddresses.containsKey(address) && !savedMacs.containsKey(address) }
                        .filter { address -> subnets.any { it.contains(address) } }
                        .toList()
            }
            if (targets.isEmpty()) return

            for (group in targets.chunked(MAC_PROBE_CONCURRENCY)) {
                coroutineScope {
                    group.map { address ->
                        launch(Dispatchers.IO) {
                            val mac = NetbiosLookup.macOf(address, LanScanner.networkFor(subnets, address))
                            if (mac != null) learnMac(address, mac)
                        }
                    }
                }
            }
        }

        private fun saveKnownAddresses(context: Context) {
            val seen = synchronized(lock) { macAddresses.keys.take(KNOWN_ADDRESS_LIMIT).toSet() }
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putStringSet(PREF_KNOWN_ADDRESSES, seen).apply()
            }
        }

        private suspend fun probeServices(address: String, subnets: List<Subnet>,
                                          requireLanBind: Boolean) {
            val network = LanScanner.networkFor(subnets, address)
            val ports = LanScanner.openServicePorts(address, network, requireLanBind)
            var streaming = false
            for (port in ports) {
                val protocol = when {
                    port in GAME_STREAM_PORTS -> {
                        streaming = true
                        ServerProfile.PROTOCOL_GAME_STREAM
                    }
                    port == LanScanner.ADB_PORT -> ServerProfile.PROTOCOL_ANDROID
                    else -> ServerProfile.PROTOCOL_VNC
                }
                addService(address, ServerProfile(host = address, port = port, protocol = protocol), "")
            }

            // A machine offering GameStream tells its own hardware address away in the
            // plaintext serverinfo reply, without pairing and without any permission - the
            // one dependable MAC source on builds that hide the neighbour table. It is asked
            // over the LAN-bound socket, so the reply is the real host's and not a VPN
            // proxy's all-zero fabrication; a proxy that never speaks the protocol simply
            // yields nothing. Only addresses still lacking a MAC are asked about.
            if (streaming && lacksMac(address)) {
                val mac = LanScanner.gameStreamMac(address, network, requireLanBind)
                if (mac != null) learnMac(address, mac)
            }
        }

        /** Whether no source has given a hardware address for [address] yet. */
        private fun lacksMac(address: String): Boolean = synchronized(lock) {
            !macAddresses.containsKey(address) && !savedMacs.containsKey(address)
        }

        /**
         * Reverse names for the hosts nobody announced, so a card is not left blank even
         * when the machine never answered an mDNS query.
         */
        private suspend fun resolveMissingNames(neighbors: Map<String, String>, subnets: List<Subnet>) {
            val pending = synchronized(lock) {
                (presentAddresses + neighbors.keys + macAddresses.keys)
                        .filter { address -> address.ipv4ToLong() != null }
                        .filter { address -> announcedNames[address].isNullOrBlank() && resolvedNames[address].isNullOrBlank() }
                        .filter { address -> subnets.any { it.contains(address) } }
                        .toList()
            }
            if (pending.isEmpty()) return

            val resolved = LanScanner.reverseNames(pending)
            if (resolved.isNotEmpty()) {
                synchronized(lock) { resolvedNames.putAll(resolved) }
                postResults()
            }
        }

        private fun stopGameStream() {
            gsAgent?.let {
                runCatching { it.stopDiscovery() }.onFailure { Log.w(TAG, "Failed to stop GameStream agent", it) }
                gsAgent = null
            }
            runCatching { multicastLock?.release() }
            multicastLock = null
            updateRunningState()
        }

        private fun stopVnc() {
            vncAgent?.let {
                runCatching { it.stop() }.onFailure { Log.w(TAG, "Failed to stop VNC agent", it) }
                vncAgent = null
            }
            updateRunningState()
        }

        /**
         * Aborts the round. Whatever was found stays on screen: the user stopped the
         * scan, they did not ask to lose its results.
         */
        fun stop() {
            scanning = false
            progress.postValue(-1)
            stage.postValue(ScanStage.IDLE)
            round.incrementAndGet()
            scanJob?.cancel()
            scanJob = null
            execute {
                stopGameStream()
                stopVnc()
            }
            updateRunningState()
        }

        /**
         * Called if the process is going away; the scan coroutines have nothing to come
         * back to afterwards.
         */
        fun destroy() {
            stop()
            scope.cancel()
            executor.shutdown()
        }
    }
}
