/*
 * The LocalSend front page of this app: owns the device identity, the peer table built by
 * UDP discovery, and everything outgoing. One object for the whole process because the
 * network identity must be stable across toolbar open/close cycles - a device that
 * re-mints its certificate between two announcements shows up twice on every peer.
 *
 * Discovery follows the v2.2 model: we multicast an announcement, whoever hears it dials
 * us with `register` (handled by [LsReceiver]), and we answer; symmetrically, every
 * announcement we hear is answered by registering with its author. A peer only enters the
 * table through a completed HTTPS handshake whose certificate fingerprint we could read,
 * so entries are proof-of-reachability, not just UDP chatter - same rule the Rust
 * `discovery.rs` store follows.
 */
package com.limelight.localsend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import com.limelight.util.LanScanner
import com.limelight.util.LocalNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What one outgoing transfer needs about its content; everything else is protocol noise. */
data class LsOutgoingFile(
    val fileName: String,
    val size: Long,
    val mime: String,
    /** Message body for text sends; embedded in `preview` like the official app does. */
    val preview: String? = null,
    val open: () -> InputStream,
)

/**
 * One row waiting in the outbox: either a picked document ([uri]) or a typed text message
 * ([text]). The name and size are read once, when it joins the outbox, so its row can be drawn
 * without asking the content provider again.
 *
 * [persisted] says whether the read grant for a document row was converted into a persistable
 * one. A picked uri that only holds the picker's temporary grant can stop being readable while
 * it sits in the outbox, and when the send then fails the row has to be reported as an expired
 * authorization rather than as some provider error the user cannot act on. A message row carries
 * no uri and is always readable, so [persisted] means nothing there.
 */
data class LsStagedFile(
    val uri: Uri?,
    val name: String,
    val size: Long,
    val persisted: Boolean,
    /** Non-null for a typed-message row: the body, sent as a small text file like the official app. */
    val text: String? = null,
)

sealed class LsSendResult {
    object Accepted : LsSendResult()
    object DeclinedByReceiver : LsSendResult()
    /** Receiver selected none of the files (204): the transfer is over, nothing was saved. */
    object NothingSelected : LsSendResult()
    /**
     * The picked file could not be opened any more: the document provider refused us. Reached
     * when the read grant behind a staged uri has lapsed, which the outbox makes possible by
     * holding uris across pages and minutes. Reported as its own outcome because the only fix
     * is to add the file again, and no provider error string says that.
     */
    object PermissionLost : LsSendResult()
    /**
     * Part of a batch went out and part was skipped because its source could no longer be read -
     * a document deleted after it joined the outbox, most often. [names] lists the files that did
     * not go, so a send that partly succeeded is not read as one that sent everything; [result]
     * is the outcome of the files that did go.
     */
    class PartiallySkipped(val names: String, val result: LsSendResult) : LsSendResult()
    class Failed(val message: String) : LsSendResult()
}

/**
 * Live state of the one transfer currently moving, out or in. Null between transfers. The
 * home screen and the stream drawer both mirror this into a progress bar; a peer that moves
 * several files reports per-file progress with its index and count so the UI can say
 * "file 2 of 5" the way the official app does.
 */
data class LsTransferProgress(
    val label: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val fileIndex: Int,
    val fileCount: Int,
    val incoming: Boolean = false,
)

object LsManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Minted once per install; the generation costs an RSA keypair, so it runs off-main. */
    @Volatile
    private var identityCache: LsSecurityContext? = null

    private val identity: LsSecurityContext
        get() = identityCache ?: throw IllegalStateException("LsManager.initialize was not called yet")

    private val _peers = MutableStateFlow<List<LsPeer>>(emptyList())
    val peers: StateFlow<List<LsPeer>> = _peers

    /**
     * Devices the user pinned. Separate flow rather than a flag on [LsPeer] because a
     * favorite is routinely in neither list of discovered peers: it is kept while the machine
     * is off, so the table has nothing to attach it to.
     */
    private val _favorites = MutableStateFlow<List<LsPeer>>(emptyList())
    val favorites: StateFlow<List<LsPeer>> = _favorites

    /** True while the toolbar's receive switch is on; drives the server and announcements. */
    val receiveEnabled = MutableStateFlow(false)

    /**
     * The live download links, mirrored out of [LsShareServer] so both hosts can draw a
     * manageable list. Owned here (rather than read straight off the server) because the server
     * is built inside [initialize] on the IO thread, and a UI collector needs one stable flow
     * that exists from the moment the object does.
     */
    private val _shares = MutableStateFlow<List<ShareInfo>>(emptyList())
    val shares: StateFlow<List<ShareInfo>> = _shares

    /** The transfer currently in flight, or null. Updated from the IO threads. */
    val transferProgress = MutableStateFlow<LsTransferProgress?>(null)

    /**
     * True while [rescan] is walking the subnet. Exposed so the two LocalSend hosts can show
     * a "searching..." cue next to the rescan button: without it the walk is silent for a few
     * seconds on an empty table and reads as if nothing had been pressed at all.
     */
    private val _rescanInFlight = MutableStateFlow(false)
    val rescanInFlight: StateFlow<Boolean> = _rescanInFlight

    /**
     * Progress of the [rescan] subnet walk as a (done, total) pair, or null between scans. The
     * candidate set is materialised and counted up front, then every completed probe bumps the
     * done count, so the three LocalSend hosts can render a real percentage and address tally
     * instead of an open-ended "searching..." that gave no sense of how long the walk would run.
     */
    private val _scanProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val scanProgress: StateFlow<Pair<Int, Int>?> = _scanProgress

    /**
     * The in-flight [rescan] walk, kept so [stopRescan] can cancel it. [scanToken] is bumped on
     * every start and stop: a walk only reports progress and clears the searching flags while its
     * own token is still the current one, so a cancelled walk that is finishing its last chunk
     * cannot clobber the state of a scan the user started right after pressing stop.
     */
    @Volatile
    private var rescanJob: Job? = null
    private val scanToken = java.util.concurrent.atomic.AtomicInteger(0)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoverySocket: MulticastSocket? = null

    /**
     * How many visible LocalSend screens (the full page, the overlay) want to keep hearing
     * multicast announcements even while the receive switch is off. Discovering *other* devices
     * never requires us to accept files, so the multicast socket is held for as long as any
     * LocalSend screen is on screen; [receiveEnabled] is the other owner of it. The socket is
     * released only when both are gone (see [maybeReleaseDiscovery]), which is what lets a sender
     * that has receiving switched off still find a peer in about a second instead of waiting on
     * the whole /24 walk.
     */
    private var uiDiscoveryCount = 0

    /** Per-fingerprint clients: building an SSLContext per file would dominate small
     *  transfers, and the pin is per peer anyway. */
    private val clients = HashMap<String, OkHttpClient>()
    private var discoveryClient: OkHttpClient? = null
    /** Short-timeout client used only by the [rescan] walk; see [probeClient]. */
    private var probeClientCache: OkHttpClient? = null
    private val ioDispatcherLock = Object()

    /**
     * Direct `info` probes [rescan] runs at once across the subnet walk. Wide enough to clear a
     * /24 in a couple of seconds yet bounded, the same reason [LanScanner] bounds its own sweep:
     * this runs on the phone's Wi-Fi radio while the user is watching, and a wider fan-out only
     * makes the access point drop packets.
     */
    private const val RESCAN_CONCURRENCY = 64

    private val receiver = LsReceiver()

    /** The plain-HTTP link server, built once the app context exists (see [initialize]). */
    private var shareServer: LsShareServer? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            // LocalSend is an add-on: minting the identity or starting the pruning loop must
            // never bring the whole app down. If it fails we log and carry on - the receive
            // switch and share flows will simply report the error when the user reaches them.
            try {
                identityCache = LsCrypto.loadOrCreate(appContext.filesDir)
                shareServer = LsShareServer(appContext) { _shares.value = it }
                _favorites.value = LsFavorites.load(appContext)
                receiver.configure(
                    { securityIdentity() },
                    { myInfoResponse() },
                    { info, certFingerprint, sourceIp -> onRegisterFromPeer(info, certFingerprint, sourceIp) },
                )
                pruneLoop()
            } catch (e: Exception) {
                com.limelight.util.Log.e("LsManager", "LocalSend initialization failed", e)
            }
        }
    }

    /**
     * Turns receiving on: the HTTPS server runs while the flag is set, and announcement
     * bursts go out periodically, which is how the other side's device list fills itself
     * without anyone pressing anything there.
     */
    fun setReceiveEnabled(enabled: Boolean) {
        if (receiveEnabled.value == enabled) return
        receiveEnabled.value = enabled
        if (enabled) {
            scope.launch {
                startDiscovery()
                receiver.start { running() }
                announce()
            }
        } else {
            scope.launch {
                // Stop accepting files, but keep listening for announcements while a LocalSend
                // screen is still on screen: turning off receiving should not blind the sender to
                // peers, which is exactly the case that used to fall back to the slow /24 walk.
                maybeReleaseDiscovery()
                receiver.stop()
            }
        }
    }

    private fun running() = receiveEnabled.value

    // region discovery

    private suspend fun startDiscovery() = withContext(Dispatchers.IO) {
        if (discoverySocket != null) return@withContext
        try {
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("localsend").apply {
                setReferenceCounted(false)
                acquire()
            }
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = 2000
                bind(java.net.InetSocketAddress(LsConstants.PORT))
            }
            // Join on every multicast-capable, up interface rather than letting the kernel
            // pick a default route. Phones with more than one active network (Wi-Fi plus
            // mobile data, or a USB-Ethernet adapter) otherwise end up subscribed on the
            // wrong NIC after a network switch, which is the reason a rescan can look dead
            // while the AP keeps announcing to us. The plain joinGroup(address) is kept as
            // a last resort so the socket is subscribed on some interface even when every
            // NetworkInterface query fails - the receive loop then still gets a heartbeat.
            val group = InetAddress.getByName(LsConstants.MULTICAST_GROUP)
            val groupAddress = java.net.InetSocketAddress(group, LsConstants.PORT)
            var joined = false
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
                    .getOrNull()?.asSequence()?.toList().orEmpty()
            for (iface in interfaces) {
                val usable = runCatching {
                    iface.isUp && !iface.isLoopback && iface.supportsMulticast()
                }.getOrDefault(false)
                if (!usable) continue
                if (runCatching { socket.joinGroup(groupAddress, iface) }.isSuccess) joined = true
            }
            if (!joined) runCatching { socket.joinGroup(group) }
            discoverySocket = socket
            scope.launch { receiveAnnouncements(socket) }
        } catch (e: Exception) {
            // Port held by a stock LocalSend app on this same device, most likely.
            // Announcements still go out on an ephemeral socket, and inbound registers
            // arrive through the HTTPS server regardless.
            com.limelight.util.Log.w("LsManager", "Multicast bind failed", e)
        }
    }

    private fun stopDiscovery() {
        try {
            discoverySocket?.let {
                it.leaveGroup(InetAddress.getByName(LsConstants.MULTICAST_GROUP))
                it.close()
            }
        } catch (e: Exception) {
            // already dead
        }
        discoverySocket = null
        multicastLock?.release()
        multicastLock = null
    }

    /**
     * Called when a LocalSend screen (the full page or the overlay) comes to the front. Starts the
     * multicast listener and fires one announcement burst regardless of the receive switch, so an
     * actively-broadcasting peer shows up in about a second the way the stock app does, rather than
     * only after the explicit subnet walk. Ref-counted with [stopUiDiscovery] so the page and the
     * overlay can share one socket, and harmless when receiving is already holding it.
     */
    fun startUiDiscovery() {
        if (!::appContext.isInitialized) return
        synchronized(ioDispatcherLock) { uiDiscoveryCount++ }
        scope.launch {
            startDiscovery()
            announce()
        }
    }

    /** Drops one [startUiDiscovery] holder and releases the socket if nothing visible needs it. */
    fun stopUiDiscovery() {
        if (!::appContext.isInitialized) return
        synchronized(ioDispatcherLock) { if (uiDiscoveryCount > 0) uiDiscoveryCount-- }
        scope.launch { maybeReleaseDiscovery() }
    }

    /**
     * The single place that may tear discovery down behind the UI's back. Keeps the multicast
     * socket alive while either the receive switch or any on-screen LocalSend holder still wants
     * it, so the receive-owned and screen-owned references to one socket stay consistent.
     */
    private fun maybeReleaseDiscovery() {
        val ui = synchronized(ioDispatcherLock) { uiDiscoveryCount }
        if (receiveEnabled.value || ui > 0) return
        stopDiscovery()
    }

    private suspend fun receiveAnnouncements(socket: MulticastSocket) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(65535)
        while (socket === discoverySocket) {
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val info = lsJson.decodeFromString<LsDeviceInfo>(String(packet.data, 0, packet.length))
                if (info.fingerprint == identity.fingerprint) continue // our own loopback
                confirmByRegister(packet.address.hostAddress, info)
            } catch (e: kotlinx.serialization.SerializationException) {
                // not a LocalSend datagram
            } catch (e: java.net.SocketTimeoutException) {
                // the poll heartbeat; keep looping
            } catch (e: Exception) {
                if (e is java.net.SocketException) return@withContext // socket closed
            }
        }
    }

    /**
     * Answers an announcement the way v2.2 demands: an HTTP register to the announcer.
     * Only a completed handshake adds the peer - its certificate fingerprint is read
     * from the TLS session, never from the body, so a spoofed announcement stays out.
     */
    private fun confirmByRegister(host: String?, info: LsDeviceInfo) {
        if (host == null) return
        scope.launch {
            try {
                val body = lsJson.encodeToString(LsDeviceInfo.serializer(), myDeviceInfo())
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://$host:${info.port}${LsConstants.PATH_PREFIX}/register")
                    .post(body)
                    .build()
                val client = discoveryClient()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val fingerprint = response.handshakeFingerprint ?: return@use
                    upsertPeer(fingerprint, host, info.port, info.alias, info.deviceModel, info.version)
                }
            } catch (e: Exception) {
                // The announcer may be mid-startup or may not run a server at all.
            }
        }
    }

    /** Sends one announcement burst; peers answer by registering with us. */
    fun announce() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val json = lsJson.encodeToString(
                    LsDeviceInfo.serializer(),
                    myDeviceInfo().copy(announce = true),
                ).toByteArray()
                val group = InetAddress.getByName(LsConstants.MULTICAST_GROUP)
                val socket = discoverySocket ?: try {
                    // Even when the receive bind failed we can still be the polite side:
                    // an ephemeral socket makes the kernel pick the multicast interface.
                    MulticastSocket(null).apply { bind(java.net.InetSocketAddress(0)) }
                } catch (e: Exception) {
                    return@withContext
                }
                val borrowed = socket !== discoverySocket
                for (delayMs in LsConstants.ANNOUNCE_DELAYS_MS) {
                    delay(delayMs)
                    try {
                        socket.send(
                            java.net.DatagramPacket(
                                json, json.size, java.net.InetSocketAddress(group, LsConstants.PORT)))
                    } catch (e: Exception) {
                        // interface went away; the next enable/announce retries
                    }
                }
                if (borrowed) socket.close()
            }
        }
    }

    /**
     * Re-searches the way a manual "rescan" should: an announcement burst for anyone who still
     * listens to multicast, plus a direct `info` probe of every address on the local subnets.
     * The subnet probe is what finds a machine whose access point swallows multicast - the case a
     * lone [announce] can never cover, and the reason a search could look like it did nothing.
     * Hits enter the table through the same fingerprint-pinned path discovery uses; this device's
     * own addresses are skipped. Fire-and-forget on the scope's IO threads, [RESCAN_CONCURRENCY]
     * probes at a time so a whole /24 walk does not fan out across the Wi-Fi radio all at once.
     */
    fun rescan() {
        if (!::appContext.isInitialized) return
        // One walk at a time: the full page, the overlay and the toolbar can all ask for a scan,
        // and two concurrent /24 walks would fight over the transient multicast lock and the shared
        // progress. A request that arrives while a walk is already running is simply absorbed.
        if (rescanJob?.isActive == true) return
        // Rebind the socket before every search: after a Wi-Fi/AP switch the group membership
        // we hold is stale (it lives on the interface the kernel picked when we first bound),
        // and a plain announce on that dead socket looks like "nothing happened" to the user.
        // Only meaningful while receive is on - setReceiveEnabled owns the socket otherwise,
        // and a rescan with the switch off still reaches peers through the direct probes.
        // Stopping is synchronous here so the fresh startDiscovery in the launch below sees a
        // null socket and rebuilds it - stopDiscovery also releases the multicast lock, which
        // is what lets the Wi-Fi radio re-elect a route before we reacquire.
        val rebinding = receiveEnabled.value
        if (rebinding) stopDiscovery()
        // A scan with the receive switch off still wants the multicast lock: the announce
        // burst at the top of the walk needs the radio in promiscuous-multicast mode or the
        // group send is dropped on several chipsets. startDiscovery owns the lock while receive
        // is on, so only the switched-off path acquires a transient one here, released in the
        // finally so the whole rescan round keeps it and nothing leaks when the walk ends.
        val transientLock = if (!rebinding) runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("localsend-rescan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull() else null
        _rescanInFlight.value = true
        val token = scanToken.incrementAndGet()
        rescanJob = scope.launch {
            try {
                if (rebinding) startDiscovery()
                announce()
                val own = LanScanner.localAddresses()
                // Insertion order deduplicates an address that two interfaces both claim.
                val candidates = LinkedHashSet<String>()
                for (subnet in LanScanner.subnets(appContext)) {
                    for (address in subnet.addresses()) {
                        if (address in own) continue
                        candidates.add(address)
                    }
                }
                // Empty subnet table means every interface reported no prefix; rather than
                // returning quietly, build a /24 for each local IPv4 address so the walk has
                // something to hit. This is the case that used to look like "rescan does
                // nothing" on head units whose Wi-Fi driver hides the network mask.
                if (candidates.isEmpty()) {
                    for (seed in own) {
                        val prefix = seed.substringBeforeLast('.', "")
                        if (prefix.isEmpty()) continue
                        for (last in 1..254) {
                            val address = "$prefix.$last"
                            if (address in own) continue
                            candidates.add(address)
                        }
                    }
                }
                // Probe the addresses the user has a reason to expect even when discovery is
                // blind to them: the pinned favorites and the last peer snapshot. Either can sit
                // on a subnet the interfaces did not report, which is why a pure /24 walk misses
                // a machine that is plainly reachable. They fold into the same deduplicated set.
                for (peer in _favorites.value) {
                    if (peer.host !in own) candidates.add(peer.host)
                }
                for (peer in _peers.value) {
                    if (peer.host !in own) candidates.add(peer.host)
                }
                // Materialise the count before the first probe so the UI can show "0%" the moment
                // the walk starts; each completed probe advances it as it lands.
                val total = candidates.size
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                _scanProgress.value = 0 to total
                val client = probeClient()
                // Pipelined, not chunked: every candidate is launched up front and a semaphore caps
                // how many probes are in flight at once. The old per-chunk coroutineScope barrier
                // made the whole batch wait on the slowest host of each chunk, so a handful of
                // silent addresses (each burning the full connect timeout) stretched a /24 walk into
                // tens of seconds. With the short probe timeouts and no barrier, dead addresses drop
                // out fast and the walk keeps a steady fan-out - the gap the user saw against the
                // stock app, which relies on the multicast burst above rather than a slow sweep.
                val permits = Semaphore(RESCAN_CONCURRENCY)
                coroutineScope {
                    for (address in candidates) {
                        // A stop bumps the token and cancels this job; bail before queueing more
                        // (probes already in flight finish on their own, capped by the call timeout).
                        if (scanToken.get() != token) break
                        launch {
                            permits.withPermit {
                                probePeer(client, address)
                                if (scanToken.get() == token)
                                    _scanProgress.value = done.incrementAndGet() to total
                            }
                        }
                    }
                }
            } finally {
                // Only the current walk owns the shared flags. A cancelled or superseded one that
                // is just now unwinding must not clear the searching state of a scan the user
                // restarted right after pressing stop.
                if (scanToken.get() == token) {
                    _scanProgress.value = null
                    _rescanInFlight.value = false
                }
                runCatching { transientLock?.release() }
            }
        }
    }

    /**
     * Stops an in-progress [rescan]. Bumps the token so the running walk stops taking new chunks
     * and leaves the shared flags alone, cancels its job, and clears the searching state immediately
     * so the button flips back at once - the walk's last chunk may still drain for the length of one
     * probe timeout in the background, but the UI no longer reads as searching. A no-op when idle.
     */
    fun stopRescan() {
        if (rescanJob?.isActive != true) return
        scanToken.incrementAndGet()
        rescanJob?.cancel()
        rescanJob = null
        _scanProgress.value = null
        _rescanInFlight.value = false
    }

    /**
     * One direct `info` query against [host]: a successful answer proves a LocalSend receiver and
     * is upserted, pinned to the certificate the answer arrived over. Every failure is silent - a
     * rescan walks dead addresses by design, and "no route", a timeout, a non-LocalSend host and
     * our own loopback are all expected, not things to report per address.
     */
    private suspend fun probePeer(client: OkHttpClient, host: String) {
        try {
            val request = Request.Builder()
                .url("https://$host:${LsConstants.PORT}${LsConstants.PATH_PREFIX}/info")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = runCatching {
                    lsJson.decodeFromString<LsInfoResponse>(response.body?.string().orEmpty())
                }.getOrNull() ?: return@use
                if (body.fingerprint.uppercase() == identity.fingerprint) return@use
                val fingerprint = response.handshakeFingerprint ?: body.fingerprint.uppercase()
                upsertPeer(fingerprint, host, LsConstants.PORT, body.alias, body.deviceModel, body.version)
            }
        } catch (e: Exception) {
            // Unreachable or not a LocalSend device; the walk expects these and stays quiet.
        }
    }

    /** Periodic re-announcement while receiving, and TTL pruning of silent peers. */
    private suspend fun pruneLoop() {
        while (true) {
            delay(60_000)
            // Re-announce rather than rescan. A full rescan walks every address on the subnet and
            // flips the searching flag, so running it here made the list suddenly drop into "search"
            // on its own every minute - the report of an unexplained auto-rescan. Staying discover
            // able only needs the announcement burst: peers answer it by registering with us, and an
            // announcement we hear refreshes their lastSeen, which keeps them inside the TTL without
            // a subnet walk. Deeper rediscovery is left to the explicit, stoppable Search again.
            if (receiveEnabled.value) announce()
            val now = System.currentTimeMillis()
            synchronized(ioDispatcherLock) {
                _peers.value = _peers.value.filter { now - it.lastSeen < LsConstants.PEER_TTL_MS }
            }
        }
    }

    private fun onRegisterFromPeer(info: LsDeviceInfo, certFingerprint: String?, sourceIp: String) {
        // The receiver only forwards registrations whose claim the client certificate
        // proved; here that check is already done, so this is a confirmed peer.
        val fingerprint = certFingerprint ?: info.fingerprint.uppercase()
        upsertPeer(fingerprint, sourceIp, info.port, info.alias, info.deviceModel, info.version)
    }

    private fun upsertPeer(
        fingerprint: String, host: String, port: Int,
        alias: String, deviceModel: String?, version: String,
    ) {
        val peer = LsPeer(fingerprint, alias, host, port, deviceModel, version, System.currentTimeMillis())
        synchronized(ioDispatcherLock) {
            val list = _peers.value.toMutableList()
            list.removeAll { it.fingerprint == fingerprint }
            list.add(peer)
            _peers.value = list.sortedByDescending { it.lastSeen }
            refreshFavorite(peer)
        }
    }

    /**
     * Adds one device by its address instead of waiting to be announced to it: a plain `info`
     * query, whose answer proves the machine is a LocalSend receiver and whose TLS certificate
     * gives the fingerprint every later transfer is pinned to. This is how a peer on another
     * subnet - or one whose multicast the access point swallows - still gets reached.
     *
     * [onResult] gets the peer, or null with a message saying why not. It is called on the
     * scope's IO thread.
     */
    fun addPeerByAddress(
        host: String,
        port: Int = LsConstants.PORT,
        onResult: (LsPeer?, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("https://$host:$port${LsConstants.PATH_PREFIX}/info")
                    .get()
                    .build()
                discoveryClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onResult(null, errorOf(response))
                        return@use
                    }
                    val body = runCatching {
                        lsJson.decodeFromString<LsInfoResponse>(response.body?.string().orEmpty())
                    }.getOrNull()
                    if (body == null) {
                        onResult(null, "Not a LocalSend device")
                        return@use
                    }
                    // The certificate the answer arrived over is the identity; the one the
                    // body claims is a claim. Discovery pins to the former for the same reason.
                    val fingerprint = response.handshakeFingerprint ?: body.fingerprint.uppercase()
                    upsertPeer(fingerprint, host, port, body.alias, body.deviceModel, body.version)
                    onResult(
                        LsPeer(fingerprint, body.alias, host, port, body.deviceModel, body.version,
                            System.currentTimeMillis()),
                        null,
                    )
                }
            } catch (e: Exception) {
                onResult(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // endregion

    // region favorites

    /**
     * Pins [peer], or takes it off the list if it is already there. The row written is the one
     * seen on the wire at that moment, so a favorite keeps the address it was reachable at.
     */
    fun toggleFavorite(peer: LsPeer) {
        synchronized(ioDispatcherLock) {
            val list = _favorites.value.toMutableList()
            if (!list.removeAll { it.fingerprint == peer.fingerprint }) {
                list.add(peer.copy(lastSeen = System.currentTimeMillis()))
            }
            _favorites.value = list
            LsFavorites.save(appContext, list)
        }
    }

    fun isFavorite(fingerprint: String): Boolean = synchronized(ioDispatcherLock) {
        _favorites.value.any { it.fingerprint == fingerprint }
    }

    /**
     * Drops every pinned device at once, on screen and on disk. Reached behind a confirm in the
     * UI, because a favorites list is exactly the kind of thing nobody means to lose - the same
     * lock [toggleFavorite] uses keeps a discovery thread from racing the write.
     */
    fun clearFavorites() {
        synchronized(ioDispatcherLock) {
            _favorites.value = emptyList()
            LsFavorites.save(appContext, emptyList())
        }
    }

    /**
     * Re-reads the pinned devices from disk into the live table. A backup restore writes the
     * favorites file directly (outside this object), so the in-memory list would otherwise show
     * the pre-restore set until the app restarted. No-op before [initialize] has run.
     */
    fun reloadFavorites() {
        if (!::appContext.isInitialized) return
        synchronized(ioDispatcherLock) {
            _favorites.value = LsFavorites.load(appContext)
        }
    }

    /**
     * Drops [fingerprint] from the pinned list without the toggle's "add it back if absent"
     * behavior: the favorite row's menu wants a plain removal, and a device that is both
     * discovered and pinned must be unpinable while it is on screen.
     */
    fun removeFavorite(fingerprint: String) {
        synchronized(ioDispatcherLock) {
            val list = _favorites.value.filterNot { it.fingerprint == fingerprint }
            if (list.size == _favorites.value.size) return
            _favorites.value = list
            LsFavorites.save(appContext, list)
        }
    }

    /**
     * Replaces the alias of the favorite whose fingerprint matches [fingerprint] with [newAlias].
     * The new name is saved immediately so it survives a restart. No-op when the fingerprint is
     * not in the pinned list or [newAlias] is blank.
     */
    fun renameFavorite(fingerprint: String, newAlias: String) {
        if (newAlias.isBlank()) return
        synchronized(ioDispatcherLock) {
            val list = _favorites.value.map { if (it.fingerprint == fingerprint) it.copy(alias = newAlias) else it }
            _favorites.value = list
            LsFavorites.save(appContext, list)
        }
    }

    /**
     * Moves the pinned device at [from] to [to], the way a drag reorder reports a single
     * continuous move rather than a step at a time. The list order is the display order and
     * [LsFavorites.save] writes it verbatim, so reindexing here and saving is the whole of what
     * "the user can reorder favorites by dragging" needs. Indices out of range are ignored.
     */
    fun reorderFavorite(from: Int, to: Int) {
        synchronized(ioDispatcherLock) {
            val list = _favorites.value.toMutableList()
            if (from < 0 || from >= list.size || to < 0 || to >= list.size || from == to) return
            list.add(to, list.removeAt(from))
            _favorites.value = list
            LsFavorites.save(appContext, list)
        }
    }

    /**
     * Keeps a pinned device's row current as it turns up again: a favorite is a bookmark, not a
     * photograph, and a machine that moved to a new address is still the machine the user
     * pinned. Caller holds [ioDispatcherLock].
     */
    private fun refreshFavorite(peer: LsPeer) {
        val list = _favorites.value
        if (list.none { it.fingerprint == peer.fingerprint }) return
        _favorites.value = list.map { if (it.fingerprint == peer.fingerprint) peer else it }
        LsFavorites.save(appContext, _favorites.value)
    }

    // endregion

    // region identity

    private fun myDeviceInfo() = LsDeviceInfo(
        alias = Build.MODEL ?: "Android",
        version = LsConstants.VERSION,
        deviceModel = Build.MANUFACTURER,
        deviceType = "mobile",
        fingerprint = identity.fingerprint,
        port = LsConstants.PORT,
        protocol = LsConstants.PROTOCOL_HTTPS,
        download = false,
    )

    fun myInfoResponse(): LsInfoResponse {
        val info = myDeviceInfo()
        return LsInfoResponse(info.alias, info.version, info.deviceModel, info.deviceType, info.fingerprint)
    }

    fun securityIdentity(): LsSecurityContext = identity

    // endregion

    // region sending

    suspend fun sendAll(peer: LsPeer, files: List<LsOutgoingFile>): LsSendResult = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext LsSendResult.NothingSelected
        try {
            val ids = files.map { UUID.randomUUID().toString() }
            val dtos = HashMap<String, LsFileDto>()
            files.forEachIndexed { i, f ->
                dtos[ids[i]] = LsFileDto(ids[i], f.fileName, f.size, f.mime, preview = f.preview)
            }
            val request = LsPrepareUploadRequest(myDeviceInfo(), dtos)
            val client = pinnedClient(peer)

            val prepareBody = lsJson.encodeToString(LsPrepareUploadRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())
            val prepareUrl = "https://${peer.host}:${peer.port}${LsConstants.PATH_PREFIX}/prepare-upload"
            val prepared = client.newCall(Request.Builder().url(prepareUrl).post(prepareBody).build())
                .execute().use { response ->
                    when {
                        response.code == 204 -> return@withContext LsSendResult.NothingSelected
                        response.code == 403 -> return@withContext LsSendResult.DeclinedByReceiver
                        response.code == 401 ->
                            return@withContext LsSendResult.Failed("The receiver requires a PIN")
                        response.code == 409 ->
                            return@withContext LsSendResult.Failed("The receiver is busy with another transfer")
                        !response.isSuccessful -> return@withContext LsSendResult.Failed(errorOf(response))
                        else -> lsJson.decodeFromString(
                            LsPrepareUploadResponse.serializer(), response.body?.string().orEmpty())
                    }
                }

            // Progress is charged against every offered file, including the ones the
            // receiver unticked (they come back without a token), so the bar still fills
            // to 100% across the batch rather than stalling at the last accepted file.
            val totalBytes = files.sumOf { it.size }.coerceAtLeast(1L)
            var doneBytes = 0L
            var moved = 0
            for (i in files.indices) {
                val file = files[i]
                val token = prepared.files[ids[i]]
                if (token == null) {
                    doneBytes += file.size
                    continue
                }
                moved++
                val uploadUrl = "https://${peer.host}:${peer.port}${LsConstants.PATH_PREFIX}/upload" +
                        "?sessionId=${query(prepared.sessionId)}&fileId=${query(ids[i])}&token=${query(token)}"
                val uploadBody = object : RequestBody() {
                    override fun contentType(): MediaType = file.mime.toMediaType()
                    override fun contentLength(): Long = file.size
                    override fun writeTo(sink: BufferedSink) {
                        file.open().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                doneBytes += read
                                transferProgress.value = LsTransferProgress(
                                    label = file.fileName,
                                    doneBytes = doneBytes.coerceAtMost(totalBytes),
                                    totalBytes = totalBytes,
                                    fileIndex = moved,
                                    fileCount = files.size,
                                )
                            }
                        }
                    }
                }
                client.newCall(Request.Builder().url(uploadUrl).post(uploadBody).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            transferProgress.value = null
                            return@withContext LsSendResult.Failed(errorOf(response))
                        }
                    }
            }
            transferProgress.value = null
            LsSendResult.Accepted
        } catch (e: SecurityException) {
            // A staged file whose read grant lapsed surfaces here rather than in
            // sendDocuments: the stream is opened from writeTo(), which is the first moment
            // the provider is actually dialed.
            transferProgress.value = null
            LsSendResult.PermissionLost
        } catch (e: Exception) {
            transferProgress.value = null
            LsSendResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun send(peer: LsPeer, file: LsOutgoingFile): LsSendResult = sendAll(peer, listOf(file))

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun errorOf(response: Response): String {
        val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val message = runCatching { lsJson.decodeFromString<LsErrorResponse>(text).message }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}"
    }

    /** Text messages ride as one small text file with the body echoed in `preview`. */
    fun sendText(peer: LsPeer, text: String, onResult: (LsSendResult) -> Unit) {
        scope.launch {
            val bytes = text.toByteArray()
            val result = send(
                peer,
                LsOutgoingFile(
                    fileName = "message.txt",
                    size = bytes.size.toLong(),
                    mime = "text/plain",
                    preview = text,
                    open = { bytes.inputStream() },
                ),
            )
            onResult(result)
        }
    }

    /**
     * Reads name and size from each picked document uri and sends the batch to [peer].
     *
     * A uri that cannot be read no longer aborts the whole batch: it is skipped, the rest go out,
     * and the outcome is reported as [LsSendResult.PartiallySkipped] naming the files that did not
     * leave. Silently shrinking the batch once made a send look successful while two of the three
     * files never left the device; naming the skipped ones is the opposite of that.
     */
    fun sendDocuments(peer: LsPeer, uris: List<Uri>, onResult: (LsSendResult) -> Unit) =
        sendBatch(peer, uris, onUnreadable = {}, onResult = onResult)

    fun sendDocument(peer: LsPeer, uri: Uri, onResult: (LsSendResult) -> Unit) =
        sendDocuments(peer, listOf(uri), onResult)

    /**
     * The shared body of the two send paths: read [uris] into descriptors, hand the unreadable
     * ones to [onUnreadable] (so the outbox can drop a row that has gone stale), send what is left,
     * and call [onResult] on the scope's IO thread with the outcome - [LsSendResult.PartiallySkipped]
     * when some were skipped but others went, [LsSendResult.PermissionLost] when none were readable.
     */
    private fun sendBatch(peer: LsPeer, uris: List<Uri>, onUnreadable: (List<Uri>) -> Unit,
            onResult: (LsSendResult) -> Unit) {
        scope.launch {
            val (files, unreadable) = buildBatch(uris)
            if (unreadable.isNotEmpty()) onUnreadable(unreadable)
            val result = when {
                files.isEmpty() ->
                    if (unreadable.isNotEmpty()) LsSendResult.PermissionLost
                    else LsSendResult.NothingSelected
                else -> {
                    val sent = sendAll(peer, files)
                    if (unreadable.isEmpty()) sent
                    else LsSendResult.PartiallySkipped(
                        unreadable.joinToString(", ") { displayName(it) }, sent)
                }
            }
            onResult(result)
        }
    }

    /**
     * Splits [uris] into the files that can be read now and the ones that cannot (an expired
     * grant, a source deleted out from under the outbox). One unreadable row must not cost the
     * batch, so failures are collected rather than thrown.
     */
    private suspend fun buildBatch(uris: List<Uri>): Pair<List<LsOutgoingFile>, List<Uri>> =
        withContext(Dispatchers.IO) {
            val readable = ArrayList<LsOutgoingFile>(uris.size)
            val unreadable = ArrayList<Uri>()
            for (uri in uris) {
                try {
                    readable += buildOutgoingFile(uri)
                } catch (e: Exception) {
                    unreadable += uri
                }
            }
            readable to unreadable
        }

    /** The display name of [uri] for the partial-failure message, best effort; never throws. */
    private fun displayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"

    /**
     * Turns a content uri into an outgoing descriptor: display name, size, mime, stream.
     *
     * Throws [SecurityException] when the provider will not open the file, which is decided
     * before the handshake starts: the metadata query and the size probe stay best effort
     * because a provider can refuse those and still serve the bytes, but the descriptor is
     * only handed back once one open() has actually succeeded.
     */
    private fun buildOutgoingFile(uri: Uri): LsOutgoingFile {
        val resolver = appContext.contentResolver
        var name = "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0) ?: name
                        size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                    }
                }
        }
        if (name == "file") name = uri.lastPathSegment ?: "file"
        if (size < 0) {
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.let { if (it > 0) size = it }
        }
        // Pre-flight open: this is where a lapsed grant is found, rather than halfway through
        // an upload that has already made the receiver show its save dialog.
        runCatching { resolver.openInputStream(uri)?.close() }.getOrThrow()
        if (size < 0) size = 0L
        val mime = resolver.getType(uri) ?: guessMime(name)
        // A provider that answers the pre-flight and then hands back nothing on the real open
        // is a broken provider; saying so beats a null stream at the far end of the upload.
        return LsOutgoingFile(name, size, mime) {
            checkNotNull(resolver.openInputStream(uri)) { "Provider returned no stream for $uri" }
        }
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "apk" -> "application/vnd.android.package-archive"
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    // endregion

    // region outbox

    /**
     * The files picked for sending, held until the user throws them away. A send does not
     * empty this and neither does the receiver being absent: the ordinary case is to gather a
     * few files, wait for the other machine to show up, and then hand the same batch to one
     * device after another.
     *
     * Kept in memory only. A persistable read grant would let a queue outlive the process
     * (see [takePersistableGrant]), but a row whose name and size were read on another day is
     * a row the user cannot trust either - and the point of the grant is the minutes a batch
     * waits for a machine to appear, not the days it waits for nobody.
     */
    private val _staged = MutableStateFlow<List<LsStagedFile>>(emptyList())
    val staged: StateFlow<List<LsStagedFile>> = _staged

    /**
     * Adds picked documents to the outbox, skipping the ones already waiting there.
     *
     * The persistable grant is taken right here, at the moment the picker's result is still
     * in hand, because that is the only moment it can be taken at all. Whether it succeeded
     * is remembered per row: a media-store uri cannot carry a persistable grant, and a send
     * of such a row that later gets refused has to be reported as an expired authorization.
     */
    fun addStaged(uris: List<Uri>) {
        scope.launch {
            val waiting = _staged.value.map { it.uri }.toSet()
            val added = uris.filter { it !in waiting }.mapNotNull { uri ->
                val persisted = takePersistableGrant(uri)
                runCatching {
                    val file = buildOutgoingFile(uri)
                    LsStagedFile(uri, file.fileName, file.size, persisted)
                }.getOrNull()
            }
            if (added.isNotEmpty()) _staged.value = _staged.value + added
        }
    }

    /**
     * Adds a typed message to the outbox, the same way picked files join it. A message is not
     * something to fire off only while a device happens to be present: it waits for a device like
     * a file does and goes out as a small text file carrying its body in the preview, which is how
     * a LocalSend receiver shows an inline message rather than a plain download.
     */
    fun addStagedText(text: String) {
        if (text.isEmpty()) return
        val bytes = text.toByteArray()
        _staged.value = _staged.value + LsStagedFile(uri = null, name = text,
                size = bytes.size.toLong(), persisted = true, text = text)
    }

    /**
     * Converts the picker's temporary read grant on [uri] into a lasting one.
     *
     * Best effort by necessity: only uris the document picker offered with the persistable
     * flag can be converted, and the platform throws for every other one. The grant belongs to
     * the uid rather than to the Activity that received the result, so the application context
     * is the right resolver to ask.
     */
    private fun takePersistableGrant(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)

    /**
     * Whether a lasting read grant is held on [uri] (as opposed to only the picker's own).
     *
     * The platform drops a grant whose lifetime has passed from this list, so anything still
     * in it is a grant that can be used.
     */
    private fun hasPersistableGrant(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }.getOrDefault(false)

    /** Drops one row from the outbox, matched by identity so a repeated message frees a single
     *  row rather than every copy of the same text. */
    fun removeStaged(item: LsStagedFile) {
        val remaining = _staged.value.toMutableList()
        val index = remaining.indexOf(item)
        if (index >= 0) remaining.removeAt(index)
        _staged.value = remaining
    }

    fun clearStaged() {
        _staged.value = emptyList()
    }

    /**
     * Sends everything waiting, and leaves the readable part waiting so the next device gets the
     * same batch. A row whose source has since been deleted can never be sent again, so it is
     * dropped from the outbox the moment its read fails - otherwise the stale row would sit there
     * looking present and make every later send report the same partial failure.
     */
    fun sendStaged(peer: LsPeer, onResult: (LsSendResult) -> Unit) {
        val items = _staged.value
        scope.launch {
            val (files, unreadable) = buildStagedBatch(items)
            if (unreadable.isNotEmpty()) synchronized(ioDispatcherLock) {
                _staged.value = _staged.value.filterNot { it in unreadable }
            }
            val result = when {
                files.isEmpty() ->
                    if (unreadable.isNotEmpty()) LsSendResult.PermissionLost
                    else LsSendResult.NothingSelected
                else -> {
                    val sent = sendAll(peer, files)
                    if (unreadable.isEmpty()) sent
                    else LsSendResult.PartiallySkipped(
                        unreadable.joinToString(", ") { it.name }, sent)
                }
            }
            onResult(result)
        }
    }

    /**
     * Turns the outbox rows into outgoing files. A message row becomes a small text file whose body
     * rides in the preview, exactly like [sendText]; a document row is read from its uri and, if
     * that read now fails, is returned in the unreadable list so the caller can drop the stale row
     * rather than silently shrink the batch. [sendBatch] stays for the direct pick-and-send paths,
     * which have no outbox rows to interpret.
     */
    private suspend fun buildStagedBatch(items: List<LsStagedFile>):
            Pair<List<LsOutgoingFile>, List<LsStagedFile>> = withContext(Dispatchers.IO) {
        val readable = ArrayList<LsOutgoingFile>(items.size)
        val unreadable = ArrayList<LsStagedFile>()
        for (item in items) {
            val text = item.text
            if (text != null) {
                val bytes = text.toByteArray()
                readable += LsOutgoingFile(
                    fileName = "message.txt",
                    size = bytes.size.toLong(),
                    mime = "text/plain",
                    preview = text,
                    open = { bytes.inputStream() },
                )
                continue
            }
            val uri = item.uri
            if (uri == null) {
                unreadable += item
                continue
            }
            try {
                readable += buildOutgoingFile(uri)
            } catch (e: Exception) {
                unreadable += item
            }
        }
        readable to unreadable
    }

    /**
     * Whether the batch waiting in the outbox can still be read. A row that was staged without
     * a persistable grant and whose temporary one has since gone is the case the user has to
     * be told about before the transfer starts, not in the middle of it.
     */
    fun stagedReadable(): Boolean = _staged.value.all {
        it.text != null || (it.uri != null && (it.persisted || hasPersistableGrant(it.uri)))
    }

    // endregion

    // region link sharing

    /**
     * Publish whatever the outbox holds - picked files and typed messages alike - as a plain HTTP
     * link on this network, so a link is made of exactly the batch a tap on a device would send.
     * This is the shared entry every host routes "create a link" through, in place of each opening
     * its own picker. Calls back with the URL on the main thread, or with null when the outbox
     * turned out to hold nothing publishable or no LAN address could be named.
     *
     * The descriptors are opened again for every request rather than read once and held: the
     * content can be hundreds of megabytes, and this app has no room to buffer it and no reason
     * to - the content provider is reachable for as long as the app lives, which is exactly as
     * long as the link is.
     */
    fun shareStaged(onResult: (String?) -> Unit) {
        scope.launch {
            val (files, _) = buildStagedBatch(_staged.value)
            val link = if (files.isEmpty()) null
                       else shareServer?.share(files, LocalNetwork.current(appContext)?.address)
            withContext(Dispatchers.Main) { onResult(link) }
        }
    }

    /** Whether any link is being handed out; decides whether the stop entry has a job. */
    fun sharesActive(): Boolean = shareServer?.active == true

    /** Takes every link down. A browser holding one finds the port refused. */
    fun stopShares() {
        shareServer?.stop()
    }

    /**
     * Adds picked documents to an existing link. Files that cannot be read are skipped rather
     * than failing the call, which matches [shareStaged]: a link that grows by whatever is
     * readable is more useful than a strict batch that adds nothing.
     */
    fun addFilesToShare(token: String, uris: List<Uri>) {
        scope.launch {
            uris.forEach { takePersistableGrant(it) }
            val files = uris.mapNotNull { runCatching { buildOutgoingFile(it) }.getOrNull() }
            shareServer?.addFiles(token, files)
        }
    }

    /** Drops one file from a link; an emptied link is taken down by the server itself. */
    fun removeSharedFile(token: String, fileId: String) {
        shareServer?.removeFile(token, fileId)
    }

    /** Takes one link down without touching the others. */
    fun revokeShare(token: String) {
        shareServer?.revoke(token)
    }

    // endregion

    // region http plumbing

    private fun newClient(
        pinnedFingerprint: String?,
        callTimeoutMs: Long?,
        connectTimeoutMs: Long = 3000,
        readTimeoutMs: Long = 0,
    ): OkHttpClient {
        val tls = LsCrypto.clientContext(identity, pinnedFingerprint)
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
            .hostnameVerifier { _, _ -> true }
            // The upstream client pins ALPN to http/1.1 for the same reason we do: HTTP/2's
            // flow-control window caps bulk upload throughput, and the peer never redirects.
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
        if (callTimeoutMs != null) builder.callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return builder.build()
    }

    private fun discoveryClient(): OkHttpClient = synchronized(ioDispatcherLock) {
        discoveryClient ?: newClient(null, 5000).also { discoveryClient = it }
    }

    /**
     * Client used only by the [rescan] subnet walk's direct `info` probes. Both timeouts are
     * deliberately short: the walk fires at every address on the subnet and the overwhelming
     * majority are silent, so at the default 3s connect timeout each dead host burns seconds that
     * stack into the slow sweep the user complained about. A real LocalSend peer on the same LAN
     * answers in milliseconds, so a tight budget never loses a hit - it only discards empty
     * addresses faster. Discovery's register replies and manual add-by-IP keep the generous client.
     */
    private fun probeClient(): OkHttpClient = synchronized(ioDispatcherLock) {
        probeClientCache ?: newClient(null, 1500, connectTimeoutMs = 800, readTimeoutMs = 1500)
            .also { probeClientCache = it }
    }

    private suspend fun pinnedClient(peer: LsPeer): OkHttpClient = withContext(Dispatchers.IO) {
        synchronized(ioDispatcherLock) {
            clients.getOrPut(peer.fingerprint) { newClient(peer.fingerprint, null) }
        }
    }

    /** The pinned certificate fingerprint the response arrived over, uppercase hex. */
    private val Response.handshakeFingerprint: String?
        get() = handshake?.peerCertificates?.firstOrNull()?.encoded?.let { LsCrypto.fingerprintOf(it) }

    // endregion
}