/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.limelight.R
import com.limelight.model.ServerProfile
import com.limelight.util.LiveEvent
import com.limelight.util.broadcastWoLPackets
import com.limelight.viewmodel.service.DiscoveredDevice
import com.limelight.viewmodel.service.Discovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : BaseViewModel(app) {

    /**
     * [ServerProfile]s stored in database.
     * Depending on the user pref, this list may be sorted by server name.
     */
    val serverProfiles by lazy {
        pref.ui.sortServerList.switchMap {
            if (it) serverProfileDao.getSortedLiveList()
            else serverProfileDao.getLiveList()
        }
    }

    /**
     * Used to find new servers.
     */
    val discovery = Discovery

    /**
     * Used for starting new VNC connections.
     */
    val newConnectionEvent = LiveEvent<ServerProfile>()

    /**
     * One line for the top of the discovered tab: which stage the scan is in and how many
     * machines it has turned up while it runs, then what the round ended with. A list of
     * six cards does not announce itself, and "nothing was found" is a result too.
     */
    val discoveryStatus by lazy { prepareDiscoveryStatus() }

    /**
     * This event is used for editing/creating server profiles.
     * Home activity observes this event and starts profile editor when it is fired.
     */
    val editProfileEvent = LiveEvent<ServerProfile>()

    /**
     * Fired together with [editProfileEvent] when the editor opens for a machine whose
     * hardware address no source could name. The editor then runs its own lookup once the
     * host is filled in, instead of leaving the user to find the scan button themselves.
     */
    val macScanRequested = LiveEvent<Boolean>()

    /**
     * Fired when a profile is saved to database.
     * Can be used to highlight the new profile in UI.
     */
    val profileSavedEvent = LiveEvent<ServerProfile>()

    /**
     * Fired when a profile is deleted from database.
     * This is used for notifying the user and potentially undo the deletion.
     */
    val profileDeletedEvent = LiveEvent<ServerProfile>()

    /**
     * Fired after multiple profiles have been deleted at once (multi-select). The list of
     * deleted profiles is carried so the UI can offer a single undo that restores all.
     */
    val profilesDeletedEvent = LiveEvent<List<ServerProfile>>()

    /**
     * Outcome of a manual Wake-on-LAN request, already turned into a sentence that
     * explains what happened. Observed by [com.limelight.ui.home.HomeActivity].
     */
    val wakeResultEvent = LiveEvent<String>()

    /**
     * Starts new connection to given profile.
     */
    fun startConnection(profile: ServerProfile) = newConnectionEvent.fire(profile)

    /**
     * Sends a Wake-on-LAN magic packet for [profile] without connecting to it.
     *
     * This is the only place a wake packet is ever sent: a machine that is already
     * running must not receive a broadcast just because its card was tapped, and a
     * failed wake has to be reported against the button that was pressed rather than
     * buried in a connection attempt. [waking] keeps a repeated tap from firing a
     * volley of identical packets.
     */
    private var waking = false

    fun wakeProfile(profile: ServerProfile) = launchMain {
        if (waking) return@launchMain
        waking = true

        val name = profile.name.ifBlank { profile.host }
        // try/finally rather than a straight line: this coroutine dies with the ViewModel's
        // scope, and a cancellation between the two hops would leave the flag raised for the
        // rest of the ViewModel's life - every later wake button on the card would then
        // silently do nothing at all.
        try {
            val message = withContext(Dispatchers.IO) {
                runCatching { broadcastWoLPackets(app, profile.wolMAC, profile.wolBroadcastAddress, profile.wolPort) }
                        .map { app.getString(R.string.msg_wol_sent, name) }
                        .getOrElse { app.getString(R.string.msg_wol_failed, it.message ?: it.javaClass.simpleName) }
            }

            wakeResultEvent.fire(message)
        } finally {
            waking = false
        }
    }

    fun maybeConnectOnAppStart() = launchMain {
        serverProfileDao.getConnectableOnAppStart().firstOrNull()?.let { startConnection(it) }
    }

    /**************************************************************************
     * Server Discovery
     *
     * A scan is started only when the user asks for one: a round walks the whole subnet
     * with hundreds of probes, and firing that off because a screen happened to open is
     * not our call to make. A round still running when the activity leaves the
     * foreground is cancelled - nothing is watching its results in the background, and
     * holding the radio open there would only cost battery.
     **************************************************************************/

    fun startDiscovery() {
        discovery.start(app)
    }

    fun stopDiscovery() {
        discovery.stop()
    }

    private fun prepareDiscoveryStatus(): LiveData<String> = with(MediatorLiveData<String>()) {
        val recompute = {
            value = statusLine(discovery.isRunning.value == true,
                    discovery.stage.value ?: Discovery.ScanStage.IDLE,
                    discovery.devices.value ?: emptyList())
        }
        addSource(discovery.isRunning) { recompute() }
        addSource(discovery.stage) { recompute() }
        addSource(discovery.devices) { recompute() }
        this
    }

    /**
     * Text for [discoveryStatus]. While a round runs the sentence names its stage - the
     * subnet walk, the port check, the name lookups - because those take seconds and an
     * unexplained spinner reads as a hang; afterwards it states what the round holds.
     *
     * The walk's percentage is deliberately not repeated here: the ring under this line
     * already counts it, and one number shown in two places only invites disagreement.
     */
    private fun statusLine(running: Boolean, stage: Discovery.ScanStage,
                           devices: List<DiscoveredDevice>): String {
        val found = devices.count { it.hasService }
        if (!running) {
            // An empty tab after a round says something different from an empty tab before
            // the first one, so the two must not share a sentence.
            if (devices.isEmpty())
                return if (discovery.roundFinished.value == null) app.getString(R.string.msg_discovery_idle)
                else app.getString(R.string.msg_scan_none)
            return scanResultText(devices.size, found)
        }

        val resId = when (stage) {
            Discovery.ScanStage.WALKING -> R.string.msg_scan_walking
            Discovery.ScanStage.PROBING -> R.string.msg_scan_probing
            Discovery.ScanStage.NAMING -> R.string.msg_scan_naming
            Discovery.ScanStage.SETTLING -> R.string.msg_scan_settling
            else -> R.string.msg_scan_browsing
        }
        return app.getString(resId, found)
    }

    /**
     * Sentence for the outcome of a round: [total] cards, [connectable] of them openable.
     * Shared by [discoveryStatus] and by the notice shown when a round finishes, so the
     * two can never word the same result differently.
     */
    fun scanResultText(total: Int, connectable: Int): String =
            app.resources.getQuantityString(R.plurals.msg_scan_done, total, total, connectable)


    /**************************************************************************
     * Profile editing/creating
     *
     * These are invoked from UI on user actions. We simply fire [editProfileEvent]
     * with appropriate profile, causing the profile editor to be shown.
     *
     * NOTE: We need to make a copy of given profile because the instance
     * given to [editProfileEvent] can be modified by the editor.
     **************************************************************************/

    /**
     * The blank form opened by the "add server" button. It starts on RFB - the protocol
     * this app is used with most - rather than the model's GameStream default, so the
     * common case needs no toggle. The port follows the protocol (a bare [ServerProfile]
     * would otherwise show GameStream's 47984 under an RFB selection); the user can still
     * switch to another protocol in the form, which re-derives the port as before.
     */
    fun onNewProfile() = editProfileEvent.fire(ServerProfile(
            protocol = ServerProfile.PROTOCOL_VNC,
            port = ServerProfile.defaultPortFor(ServerProfile.PROTOCOL_VNC)))
    fun onNewProfile(source: ServerProfile) = editProfileEvent.fire(source.copy(ID = 0))
    fun onEditProfile(profile: ServerProfile) = editProfileEvent.fire(profile.copy())

    /**
     * A new profile for [source], with the editor asked to look up the hardware address by
     * itself. The scan event goes first: it is held until an observer exists, and the editor
     * that [editProfileEvent] creates is the one that consumes it.
     */
    fun onNewProfileWithMacScan(source: ServerProfile) {
        macScanRequested.fire(true)
        onNewProfile(source)
    }

    fun onDuplicateProfile(profile: ServerProfile) {
        val duplicate = profile.copy(ID = 0)
        duplicate.name += " (Copy)"
        editProfileEvent.fire(duplicate)
    }

    /**************************************************************************
     * Profile persistence
     *
     * These operations are asynchronous.
     **************************************************************************/

    fun saveProfile(profile: ServerProfile) = launchMain {
        serverProfileDao.save(profile)
        profileSavedEvent.fire(profile)
    }

    fun deleteProfile(profile: ServerProfile) = launchMain {
        serverProfileDao.delete(profile)
        profileDeletedEvent.fire(profile)
    }

    /**
     * Bulk deletion used by multi-select. One event for the entire batch so that undo
     * restores all deleted cards with a single tap.
     */
    fun deleteProfiles(profiles: List<ServerProfile>) = launchMain {
        for (p in profiles) serverProfileDao.delete(p)
        profilesDeletedEvent.fire(profiles)
    }

    /** Restore a batch previously removed by [deleteProfiles] (undo). */
    fun saveProfiles(profiles: List<ServerProfile>) = launchMain {
        for (p in profiles) serverProfileDao.save(p)
    }

    /**************************************************************************
     * Rediscovery Indicator
     *
     * [rediscoveredProfiles] is the intersection of saved & discovered servers.
     *
     * To detect reachable server in [serverProfiles], we could directly 'ping'
     * them, but that has its own issues.
     **************************************************************************/
    val rediscoveredProfiles by lazy {
        pref.server.rediscoveryIndicator.switchMap {
            if (it) prepareRediscoveredProfiles()
            else MutableLiveData(null)
        }
    }

    private fun prepareRediscoveredProfiles() = with(MediatorLiveData<List<ServerProfile>>()) {
        val filter = { saved: List<ServerProfile>?, discovered: List<ServerProfile>? ->
            // Same host can serve both protocols; only the exact entry lights up.
            saved?.filter { s -> discovered?.find { s.host == it.host && s.port == it.port && s.protocol == it.protocol } != null }
        }
        addSource(serverProfiles) { value = filter(it, discovery.servers.value) }
        addSource(discovery.servers) { value = filter(serverProfiles.value, it) }
        this
    }
}