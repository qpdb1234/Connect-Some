/*
 * Copyright (c) 2023  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.limelight.R
import com.limelight.model.ServerProfile

/**
 * Backs the search screen ([com.limelight.ui.home.UrlBarActivity]).
 *
 * Serves searching the saved servers only. Unlike the fixed SQL it replaced, the query
 * can be matched against a user-chosen set of aspects - name, address, protocol, ports,
 * MAC, broadcast address, SSH host, username - so the matching lives here, over the live
 * list of profiles, and re-runs whenever the query or the checked fields change.
 */
class UrlBarViewModel(app: Application) : BaseViewModel(app) {

    /**
     * One searchable aspect of a server: its persisted [key] (what the settings dialog
     * toggles, matched against [AppPreferences.SEARCH_FIELDS_DEFAULT]), the [labelRes]
     * shown for its checkbox, and the [matcher] deciding whether [query] hits [profile].
     */
    data class SearchField(val key: String, @StringRes val labelRes: Int,
                           val matcher: (profile: ServerProfile, query: String) -> Boolean)

    // Ordered as advertised to the user; also the canonical order written back to prefs.
    private val fields = listOf(
            SearchField("name", R.string.search_field_name) { p, q -> p.name.contains(q, true) },
            SearchField("host", R.string.search_field_host) { p, q -> p.host.contains(q, true) },
            SearchField("protocol", R.string.search_field_protocol) { p, q -> protocolMatches(p.protocol, q) },
            SearchField("port", R.string.search_field_port) { p, q -> p.port.toString().contains(q) },
            SearchField("mac", R.string.search_field_mac) { p, q ->
                normalizeMac(p.wolMAC).contains(normalizeMac(q)) },
            SearchField("broadcastAddress", R.string.search_field_broadcast_address) { p, q ->
                p.wolBroadcastAddress.contains(q, true) },
            SearchField("broadcastPort", R.string.search_field_broadcast_port) { p, q ->
                p.wolPort.toString().contains(q) },
            SearchField("sshHost", R.string.search_field_ssh_host) { p, q -> p.sshHost.contains(q, true) },
            SearchField("username", R.string.search_field_username) { p, q -> p.username.contains(q, true) },
    )

    val query = MutableLiveData("")

    // Declared before filteredServers: the mediator adds it as a source at construction.
    private val allServers by lazy { serverProfileDao.getLiveList() }

    /**
     * Saved servers matching [query], considering only the checked fields. An empty query
     * shows nothing (as before); with every field unchecked the result is empty by design.
     */
    val filteredServers = MediatorLiveData<List<ServerProfile>>().apply {
        val recompute = {
            val q = query.value?.trim().orEmpty()
            if (q.isEmpty()) {
                value = emptyList()
            } else {
                val enabled = enabledFieldKeys()
                value = allServers.value.orEmpty().filter { p ->
                    fields.any { it.key in enabled && it.matcher(p, q) }
                }
            }
        }
        addSource(allServers) { recompute() }
        addSource(query) { recompute() }
        addSource(pref.server.searchFieldsLive) { recompute() }
    }

    /** Definitions shown as the search-settings checkboxes, in display order. */
    fun fieldDefinitions(): List<SearchField> = fields

    fun isFieldEnabled(key: String): Boolean = key in enabledFieldKeys()

    fun setFieldEnabled(key: String, enabled: Boolean) {
        val set = enabledFieldKeys().toMutableSet()
        if (enabled) set.add(key) else set.remove(key)
        writeFields(set)
    }

    /** Check every field ([enabled] true) or clear them all (false). */
    fun setAllFields(enabled: Boolean) = writeFields(if (enabled) fields.map { it.key }.toSet() else emptySet())

    fun isAllEnabled(): Boolean = fields.all { it.key in enabledFieldKeys() }

    /**
     * The checked field keys, read straight from preferences rather than the live holder:
     * the initial `recompute()` can fire before the [com.limelight.util.AppPreferences
     * .LivePref] has been activated, and the synchronous read always resolves to the stored
     * value or the all-fields default.
     */
    private fun enabledFieldKeys(): Set<String> =
            (pref.server.searchFields ?: com.limelight.util.AppPreferences.SEARCH_FIELDS_DEFAULT)
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Persist [keys] in the canonical field order so the stored string stays stable. */
    private fun writeFields(keys: Set<String>) {
        pref.server.searchFields = fields.filter { it.key in keys }.joinToString(",") { it.key }
    }

    /**
     * Protocol is an int on the row, so a typed name is matched against a fixed set of
     * aliases per protocol: the query (already non-empty) counts as a hit when it is a
     * substring of one alias, which lets `rfb`, `vnc`, `sunshine`, `adb` and so on all work.
     * A user-configured custom badge name is also checked, so renaming RFB to "VNC" and
     * searching "VNC" still finds every RFB profile.
     */
    private fun protocolMatches(protocol: Int, query: String): Boolean {
        val q = query.lowercase()
        val aliases = when (protocol) {
            ServerProfile.PROTOCOL_VNC -> PROTOCOL_ALIASES_VNC.toMutableList().apply {
                pref.server.protocolVncBadge?.takeIf { it.isNotBlank() }?.lowercase()?.let { add(it) }
            }
            ServerProfile.PROTOCOL_ANDROID -> PROTOCOL_ALIASES_ANDROID.toMutableList().apply {
                pref.server.protocolAndroidBadge?.takeIf { it.isNotBlank() }?.lowercase()?.let { add(it) }
            }
            else -> PROTOCOL_ALIASES_GAME_STREAM.toMutableList().apply {
                pref.server.protocolGameStreamBadge?.takeIf { it.isNotBlank() }?.lowercase()?.let { add(it) }
            }
        }
        return aliases.any { it.contains(q) }
    }

    private fun normalizeMac(value: String): String =
            value.replace(":", "").replace("-", "").replace(" ", "").lowercase()

    companion object {
        private val PROTOCOL_ALIASES_VNC = listOf("rfb", "vnc")
        private val PROTOCOL_ALIASES_GAME_STREAM =
                listOf("gamestream", "game stream", "moonlight", "sunshine", "nvstream")
        private val PROTOCOL_ALIASES_ANDROID = listOf("adb", "android", "scrcpy")
    }
}