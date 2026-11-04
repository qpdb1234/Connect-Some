/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.vnc

import android.net.Uri
import androidx.core.net.toUri
import com.limelight.model.ServerProfile
import java.net.URI

/**
 * This class implements the server address URI syntax.
 * In this app it accepts the classic `vnc` scheme (RFC 7869, see
 * https://tools.ietf.org/html/rfc7869), the Moonlight/Sunshine style
 * `moonlight://` prefix and `android://` for a device driven over ADB.
 * All three are normalized internally so parsing stays the same,
 * but the scheme the user actually typed is not thrown away: it selects the
 * protocol of the resulting profile (see [protocolFromScheme]).
 *
 * If host in given URI string is an IPv6 address, it MUST be wrapped in square brackets.
 * (This requirement come from using Java [URI] internally.)
 *
 * If given URI doesn't start with a known scheme, 'vnc://' will be automatically added
 * and the profile keeps whatever protocol it already had.
 */
class VncUri(str: String) {

    /**
     * Scheme exactly as the user typed it (before normalization), lowercase.
     * Null when the address carried no scheme at all. Matches the same prefix
     * grammar as [uriString], so common typos like 'vnc:host' still count.
     */
    private val typedScheme = Regex("^(vnc|moonlight|android):")
            .find(str.trim().lowercase())
            ?.groupValues?.get(1)

    /**
     * Add scheme if missing.
     * It is also common for users to accidentally type 'vnc:host' instead of 'vnc://host',
     * so we gracefully handle that case too. A 'moonlight' (Sunshine) or 'android'
     * (ADB-driven device) prefix is accepted the same way and simply stripped before
     * the usual parsing - only [typedScheme] remembers it.
     */
    private val uriString = str.replaceFirst(
            Regex("^(?:(?:vnc|moonlight|android):/?/?)?", RegexOption.IGNORE_CASE), "vnc://")

    private val uri = uriString.toUri()

    /**
     * Older versions of Android [Uri] does not support IPv6, so we need to use Java [URI] for host & port.
     * It also serves as a validation step because [URI] verifies that address is well-formed.
     */
    private val javaUri = runCatching { URI(uriString) }.getOrNull()


    val host = javaUri?.host?.trim('[', ']')
    val port = if (javaUri?.port == -1) null else javaUri?.port

    /**
     * Protocol requested by the scheme, or null when the URI is silent about it.
     * `moonlight://` is a GameStream/Sunshine host, `vnc://` is an RFB server and
     * `android://` is an Android device driven over ADB.
     */
    private val protocolFromScheme: Int? = when (typedScheme) {
        "moonlight" -> ServerProfile.PROTOCOL_GAME_STREAM
        "vnc" -> ServerProfile.PROTOCOL_VNC
        "android" -> ServerProfile.PROTOCOL_ANDROID
        else -> null
    }

    /**
     * Protocol guessed from the port when the user typed a bare address. RFB
     * displays always live in 59xx (5900 + display number) and adbd listens on
     * 5555; the GameStream HTTPS port (47984) and everything else are treated as
     * Sunshine. The user
     * can still correct this in the editor - this only saves a step for the
     * common case of pasting an address into the URL bar.
     */
    private val protocolFromPort: Int? = when {
        port == null -> null
        port / 100 == 59 -> ServerProfile.PROTOCOL_VNC
        port == ServerProfile.DEFAULT_ANDROID_PORT -> ServerProfile.PROTOCOL_ANDROID
        else -> ServerProfile.PROTOCOL_GAME_STREAM
    }

    /**
     * The protocol this URI asks for, or null when it says nothing about it (a
     * bare host without a port). Applied by [applyToProfile].
     */
    val protocol: Int? = protocolFromScheme ?: protocolFromPort

    val connectionName = uri.getQueryParameter("ConnectionName")
    val connectionNameForProfile = connectionName ?: host?.let { "vnc://$it" }
    val saveConnection = uri.getBooleanQueryParameter("SaveConnection", false)

    val username = uri.getQueryParameter("VncUsername")
    val password = uri.getQueryParameter("VncPassword")
    val securityType = uri.getQueryParameter("SecurityType")?.toIntOrNull()
    val channelType = uri.getQueryParameter("ChannelType")?.toIntOrNull()
    val colorLevel = uri.getQueryParameter("ColorLevel")?.toIntOrNull()
    val viewOnly = uri.getQueryParameter("ViewOnly")?.let { uri.getBooleanQueryParameter("ViewOnly", false) }

    // SSH tunneling is not implemented in this build, so the `Ssh*` query parameters
    // are deliberately not read at all. Carrying them into a profile would store a
    // password for a tunnel that can never be opened, and would let any link plant
    // credentials in the database. [channelType] alone is enough to turn such a link
    // down with a message, instead of silently degrading it to a direct connection.

    /**
     *  Applies this URI to given profile. Any parameter present in this URI will
     *  overwrite corresponding member of [profile]. Returns the same profile.
     *
     *  The result is put through [ServerProfile.sanitize]: every field this reads
     *  (`ConnectionName`, `VncUsername`, `VncPassword`, `SecurityType`, `ColorLevel`, `ViewOnly`
     *  and the host and port themselves) is written by whoever made the link, and a link is
     *  accepted from a browser, a messenger or a QR code. Those values become a TCP port, an
     *  RFB security type and a pixel format, and with `SaveConnection` a row in the database.
     */
    fun applyToProfile(profile: ServerProfile): ServerProfile {
        connectionNameForProfile?.let { profile.name = it }
        host?.let { profile.host = it }

        protocol?.let { selected ->
            profile.protocol = selected

            // The port is meaningless across protocols: fill in the default of the
            // selected one, but only when the URI did not name a port.
            if (port == null)
                profile.port = profile.defaultPortForProtocol()
        }

        port?.let { profile.port = it }

        username?.let { profile.username = it }
        password?.let { profile.password = it }
        securityType?.let { profile.securityType = it }
        // Only TCP is a real channel here. A link asking for anything else has been
        // turned down by the caller before reaching this point.
        if (channelType == ServerProfile.CHANNEL_TCP)
            profile.channelType = ServerProfile.CHANNEL_TCP
        colorLevel?.let { profile.colorLevel = it }
        viewOnly?.let { profile.viewMode = if (it) ServerProfile.VIEW_MODE_NO_INPUT else ServerProfile.VIEW_MODE_NORMAL }

        return profile.sanitize()
    }

    /**
     * Generates a [ServerProfile] using this instance.
     */
    fun toServerProfile() = applyToProfile(ServerProfile())

    override fun toString() = uriString
}