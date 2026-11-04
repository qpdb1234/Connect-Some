/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.model

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.limelight.util.LocalNetwork
import com.limelight.util.isValidMacAddress
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty

/**
 * This class holds connection configuration of a remote server.
 *
 * One profile speaks exactly one protocol, selected by [protocol]. Fields that
 * only make sense for a single protocol are marked below; they are kept in the
 * same table (and the same class) on purpose - the columns are cheap, while a
 * second table would fork every CRUD path in the app.
 *
 * Some fields remain unused until that feature is implemented.
 */
@Parcelize
@Serializable
@Entity(tableName = "profiles")
data class ServerProfile(

        @PrimaryKey(autoGenerate = true)
        var ID: Long = 0,

        /**
         * Which protocol this host is reached with.
         * [PROTOCOL_GAME_STREAM] (Moonlight/Sunshine), [PROTOCOL_VNC] (RFB) or
         * [PROTOCOL_ANDROID] (a directly driven Android device over ADB).
         *
         * This is the *only* place the protocol is recorded; everything downstream
         * derives from it ([com.limelight.vnc.VncClientFactory], [defaultPortForProtocol]).
         */
        @ColumnInfo(defaultValue = "0")
        var protocol: Int = PROTOCOL_GAME_STREAM,

        /**
         * Descriptive name of the server (e.g. 'Kitchen PC').
         */
        var name: String = "",

        /**
         * Internet address of the server (without port number).
         * This can be hostname or IP address.
         */
        var host: String = "",

        /**
         * Port number of the server.
         * Its meaning depends on [protocol]: the GameStream HTTPS port (47984), the
         * RFB display port (5900) or the ADB port of an Android device (5555).
         * See [defaultPortForProtocol]. For a USB-OTG connection it is unused.
         */
        var port: Int = 47984,

        /**
         * Username used for authentication.
         */
        var username: String = "",

        /**
         * Password used for authentication.
         * Note: Username & password may not be used for all security types.
         */
        var password: String = "",

        // ------------------------------------------------------------------
        // VNC / RFB only from here on (unused for GameStream hosts):
        // [password], [securityType], [channelType], [colorLevel],
        // [imageQuality], [useRawEncoding], [useLocalCursor], [useRepeater],
        // [idOnRepeater], [resizeRemoteDesktop] and the [ssh*] fields.
        // ------------------------------------------------------------------

        /**
         * Security type to use when connecting to this server (e.g. VncAuth).
         * 0 enables all supported types.
         */
        var securityType: Int = 0,

        /**
         * Transport channel to be used for communicating with the server.
         * e.g. TCP, SSH Tunnel
         */
        var channelType: Int = CHANNEL_TCP,

        /**
         * Specifies the color level of received frames.
         * This value determines the pixel-format used for framebuffer.
         * Not used yet.
         */
        var colorLevel: Int = 7,

        /**
         * Specifies the image quality of the frames.
         * This mainly affects the compression level used by some encodings.
         */
        var imageQuality: Int = 5,

        /**
         * Use raw encoding for framebuffer.
         * This can improve performance when server is running on localhost.
         */
        var useRawEncoding: Boolean = false,

        /**
         * Initial zoom for the viewer.
         * This will be used in portrait orientation, or when per-orientation zooming is disabled.
         */
        var zoom1: Float = 1f,

        /**
         * This will be used in landscape orientation if per-orientation zooming is enabled.
         */
        var zoom2: Float = 1f,

        /**
         * View mode for this connection
         */
        var viewMode: Int = VIEW_MODE_NORMAL,

        /**
         * Whether the cursor should be drawn by client instead of server.
         * It's value is currently ignored, and hardcoded to true.
         * See [com.limelight.viewmodel.VncViewModel.preConnect]
         */
        var useLocalCursor: Boolean = true,

        /**
         * Server type hint received from user, e.g. tigervnc, tightvnc, vino
         * Can be used in future to handle known server quirks.
         */
        var serverTypeHint: String = "",

        /**
         * Composite field for various flags.
         * This is accessed via individual members like [fZoomLocked].
         */
        var flags: Long = 0,

        /**
         * Preferred style to use for gesture handling.
         * Possible values: auto, touchscreen, touchpad
         */
        var gestureStyle: String = "auto",

        /**
         * Preferred screen orientation.
         * Possible values: auto, portrait, landscape
         */
        var screenOrientation: String = "auto",

        /**
         * Usage count tracks how many times user has connected to a server.
         * Can be used to put frequent servers on top.
         */
        var useCount: Int = 0,

        /**
         * Whether UltraVNC Repeater is used for connections.
         * When repeater is used, [host] & [port] identifies the repeater.
         */
        var useRepeater: Boolean = false,

        /**
         * When using a repeater, this value identifies the VNC server.
         * Valid IDs: [0, 999999999].
         */
        var idOnRepeater: Int = 0,

        /**
         * Resize remote desktop to match with local window size.
         */
        var resizeRemoteDesktop: Boolean = false,

        /**
         * Enable Wake-on-LAN
         */
        var enableWol: Boolean = false,

        /**
         * MAC address for Wake-on-LAN
         */
        var wolMAC: String = "",

        /**
         * Broadcast address for Wake-on-LAN
         * Optional.
         */
        @ColumnInfo(defaultValue = "")
        var wolBroadcastAddress: String = "",

        @ColumnInfo(defaultValue = "9")
        var wolPort: Int = 9,

        // ==================================================================
        // Moonlight / GameStream / Sunshine streaming fields
        // ==================================================================

        /** NvApp id to launch when streaming. 0 = default (Resurrection/Big Picture). */
        @ColumnInfo(defaultValue = "0")
        var preferredAppId: Int = 0,

        /** Human-readable app name corresponding to [preferredAppId]. */
        @ColumnInfo(defaultValue = "")
        var preferredAppName: String = "",

        /**
         * Stream resolution for newly created profiles: 1920x1080.
         * The column defaults deliberately keep the old 720p values - changing
         * them would alter the CREATE TABLE statement and thus the schema of
         * already installed databases. Rows are always written explicitly.
         */
        @ColumnInfo(defaultValue = "1280")
        var streamWidth: Int = 1920,

        @ColumnInfo(defaultValue = "720")
        var streamHeight: Int = 1080,

        @ColumnInfo(defaultValue = "60")
        var streamFps: Int = 60,

        @ColumnInfo(defaultValue = "20000")
        var bitrateKbps: Int = 20000,

        @ColumnInfo(defaultValue = "0")
        var enableHdr: Boolean = false,

        @ColumnInfo(defaultValue = "1")
        var enableSops: Boolean = true,

        @ColumnInfo(defaultValue = "1")
        var playLocalAudio: Boolean = true,

        /** Base64-encoded pinned server certificate (DER). Empty = not yet trusted. */
        @ColumnInfo(defaultValue = "")
        var serverCertBase64: String = "",

        /** Stable client unique id used for pairing with this host. */
        @ColumnInfo(defaultValue = "")
        var clientUniqueId: String = "",

        /** Whether the host has been paired successfully. */
        @ColumnInfo(defaultValue = "0")
        var paired: Boolean = false,

        // ==================================================================
        // GameStream clipboard-over-RFB fields (see com.limelight.vnc.RfbClipboardBridge)
        // ==================================================================

        /**
         * RFB port of the VNC server running on this GameStream host, used only to
         * carry the clipboard. GameStream has no clipboard message, so a session that
         * speaks it opens this second connection beside the stream.
         *
         * Kept separate from [port] and from the RFB [password]: a streaming host is
         * reached over Sunshine's HTTPS port, and its VNC server is a different
         * program with a different secret. 0 disables the bridge for this profile.
         */
        @ColumnInfo(defaultValue = "5900")
        var clipboardVncPort: Int = DEFAULT_VNC_PORT,

        /** Password of that VNC server. Empty means the server allows unauthenticated clients. */
        @ColumnInfo(defaultValue = "")
        var clipboardVncPassword: String = "",

        // ==================================================================
        // Android-device (ADB / scrcpy) protocol fields
        // ==================================================================

        /**
         * Encode the screen as H.265 instead of H.264. On by default, because the ADB
         * wire is the bottleneck of this protocol and HEVC carries the same picture in
         * roughly half the bytes - the reference implementation defaults to it too.
         * [com.limelight.vnc.AndroidClient] still sends `useH265=0` when this device
         * cannot decode HEVC, and the controlled side falls back if it has no HEVC
         * encoder.
         *
         * The column default stays "0": changing it would alter the CREATE TABLE
         * statement and therefore the schema of installed databases. Only rows written
         * after this change pick up the new default.
         */
        @ColumnInfo(defaultValue = "0")
        var androidUseH265: Boolean = true,

        /**
         * Opus audio. The controlled side needs an Opus encoder, the reading side
         * Android 10+ - [com.limelight.vnc.AndroidClient] falls back to AAC on older
         * devices regardless of this flag.
         */
        @ColumnInfo(defaultValue = "1")
        var androidUseOpus: Boolean = true,

        /** Forward the controlled device's system audio. */
        @ColumnInfo(defaultValue = "1")
        var androidAudio: Boolean = true,

        /** Keep the controlled device's screen awake while mirrored. */
        @ColumnInfo(defaultValue = "1")
        var androidKeepAwake: Boolean = true,

        /**
         * Send fingers straight to the device (multi-touch capable) instead of
         * translating them through AVNC's desktop gesture system. Off = gestures.
         */
        @ColumnInfo(defaultValue = "1")
        var androidDirectTouch: Boolean = true,

        /**
         * USB-OTG target: the `UsbDevice.deviceName` picked in the editor. Empty
         * means the device is reached over TCP at [host]:[port].
         */
        @ColumnInfo(defaultValue = "")
        var androidUsbDeviceName: String = "",

        /**
         * These values are used for SSH Tunnel (VNC only; not wired up yet).
         */
        var sshHost: String = "",
        var sshPort: Int = 22,
        var sshUsername: String = "",
        var sshAuthType: Int = SSH_AUTH_KEY,
        var sshPassword: String = "",
        var sshPrivateKey: String = ""

) : Parcelable {

    companion object {
        // Protocols
        const val PROTOCOL_GAME_STREAM = 0
        const val PROTOCOL_VNC = 1
        const val PROTOCOL_ANDROID = 2

        /** GameStream/Sunshine HTTPS port (GFE default; HTTP is 47989). */
        const val DEFAULT_GAME_STREAM_PORT = 47984

        /** First RFB display (5900 + display number). */
        const val DEFAULT_VNC_PORT = 5900

        /** adbd's TCP port on the controlled Android device. */
        const val DEFAULT_ANDROID_PORT = 5555

        /**
         * Default port of a protocol. Port numbers are not transferable between
         * protocols, so every "the port does not fit this protocol" decision goes
         * through here rather than repeating the constants.
         */
        fun defaultPortFor(protocol: Int): Int = when (protocol) {
            PROTOCOL_VNC -> DEFAULT_VNC_PORT
            PROTOCOL_ANDROID -> DEFAULT_ANDROID_PORT
            else -> DEFAULT_GAME_STREAM_PORT
        }

        // Channel types (from RFC 7869)
        const val CHANNEL_TCP = 1
        const val CHANNEL_SSH_TUNNEL = 24

        // SSH auth types
        const val SSH_AUTH_KEY = 1
        const val SSH_AUTH_PASSWORD = 2

        // View Modes
        const val VIEW_MODE_NORMAL = 0
        const val VIEW_MODE_NO_INPUT = 1
        const val VIEW_MODE_NO_VIDEO = 2

        // Flag masks
        // private const val FLAG_LEGACY_KEYSYM = 0x01L
        private const val FLAG_BUTTON_UP_DELAY = 0x02L
        private const val FLAG_ZOOM_LOCKED = 0x04L
        const val FLAG_CONNECT_ON_APP_START = 0x08L

        /**
         * The ADB picture quality of this profile was chosen by hand. Carried in
         * [flags] rather than as a column: a new column would change the CREATE TABLE
         * statement, and this database migrates by dropping everything (see
         * [com.limelight.model.db.MainDb]), so a schema change costs the user their
         * saved servers.
         */
        private const val FLAG_ANDROID_STREAM_TUNED = 0x10L

        /**
         * The RFB quality panel picked a desktop size for this host, which the session
         * asks for with a SetDesktopSize request after connecting. Carried in [flags]
         * for the same reason as [FLAG_ANDROID_STREAM_TUNED]; the numbers themselves
         * reuse [streamWidth] / [streamHeight], which is how the size reaches the session.
         */
        private const val FLAG_RFB_DESKTOP_SIZE = 0x20L

        /** Highest repeater id the protocol carries. */
        private const val MAX_REPEATER_ID = 999_999_999

        /** Zoom outside this window is either invisible or a single pixel per screen. */
        const val MIN_ZOOM = 0.1f
        const val MAX_ZOOM = 10f

        /**
         * Resolution the streaming side is asked for. The lower bound is what an encoder
         * can still describe; the upper is 8K, past which no host encodes and this app's
         * own framebuffer ceiling (16384 per edge) is irrelevant because the helper takes
         * the long edge alone.
         *
         * Public because the code which hands these numbers to an encoder refuses them on
         * the same grounds, whatever the row in the database said.
         */
        const val MIN_STREAM_DIMENSION = 240
        const val MAX_STREAM_DIMENSION = 7680

        /** Frame rates a host will actually negotiate. */
        private const val MIN_STREAM_FPS = 24
        private const val MAX_STREAM_FPS = 120

        /**
         * Half a megabit is the useful floor; Moonlight's own slider stops at 150.
         * Public so the editor can refuse a value here instead of having it silently
         * replaced at connect time.
         */
        const val MIN_BITRATE_KBPS = 500
        const val MAX_BITRATE_KBPS = 150_000

        /** Longest text a field can meaningfully hold before it is a mistake or an attack. */
        private const val MAX_LABEL_LENGTH = 255
        private const val MAX_SECRET_LENGTH = 512
        private const val MAX_ENCODED_LENGTH = 8192

        /** Values the gesture/orientation pickers can produce; nothing else is understood. */
        private val GESTURE_STYLES = setOf("auto", "touchscreen", "touchpad")
        private val SCREEN_ORIENTATIONS = setOf("auto", "portrait", "landscape")
    }

    /**
     * Puts every value back inside the range the rest of the app assumes.
     *
     * A profile does not only come from the editor, which checks what it writes. It also
     * arrives from a restored activity state, a shortcut, a `vnc://` link - and, with no
     * check whatsoever, from a backup file or a scanned QR code, whose JSON is decoded
     * straight into this class and saved. The values below are used as a TCP port, a
     * security type handed to libvncserver, an encoder's resolution and frame rate, a
     * bitrate multiplier and a magnification factor, so a file saying `port: 0`,
     * `streamWidth: 2000000000` or `zoom1: NaN` is not a cosmetic problem: it is a
     * connection that cannot be made, an argument the helper refuses, a value that
     * overflows on its way into native code, or a viewer whose input lands nowhere.
     *
     * This is not a judgement about what the user meant. A number outside its range has no
     * sensible reading, so it becomes the documented default of that field, and a string
     * that cannot be one of the few permitted ones falls back the same way.
     */
    fun sanitize(): ServerProfile {
        protocol = if (protocol in PROTOCOL_GAME_STREAM..PROTOCOL_ANDROID) protocol
        else PROTOCOL_GAME_STREAM

        port = portInRange(port) ?: defaultPortFor(protocol)

        // Zero is a real value here: it means "no clipboard bridge for this host".
        clipboardVncPort = if (clipboardVncPort == 0) 0 else portInRange(clipboardVncPort) ?: 0

        sshPort = portInRange(sshPort) ?: 22
        wolPort = portInRange(wolPort) ?: 9

        channelType = if (channelType == CHANNEL_TCP || channelType == CHANNEL_SSH_TUNNEL)
            channelType else CHANNEL_TCP

        securityType = if (securityType in 0..0xFF) securityType else 0
        viewMode = if (viewMode in VIEW_MODE_NORMAL..VIEW_MODE_NO_VIDEO) viewMode
        else VIEW_MODE_NORMAL
        colorLevel = if (colorLevel in 0..7) colorLevel else 7
        imageQuality = if (imageQuality in 0..9) imageQuality else 5
        idOnRepeater = if (idOnRepeater in 0..MAX_REPEATER_ID) idOnRepeater else 0
        useCount = if (useCount >= 0) useCount else 0

        // A non-finite zoom survives coerceIn() (NaN compares false against both bounds) and
        // then poisons every coordinate conversion built on it: touches land at (0,0) and
        // the frame collapses.
        zoom1 = if (zoom1.isFinite()) zoom1.coerceIn(MIN_ZOOM, MAX_ZOOM) else 1f
        zoom2 = if (zoom2.isFinite()) zoom2.coerceIn(MIN_ZOOM, MAX_ZOOM) else 1f

        streamWidth = if (streamWidth in MIN_STREAM_DIMENSION..MAX_STREAM_DIMENSION) streamWidth
        else ServerProfile().streamWidth
        streamHeight = if (streamHeight in MIN_STREAM_DIMENSION..MAX_STREAM_DIMENSION) streamHeight
        else ServerProfile().streamHeight
        streamFps = if (streamFps in MIN_STREAM_FPS..MAX_STREAM_FPS) streamFps
        else ServerProfile().streamFps
        bitrateKbps = if (bitrateKbps in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS) bitrateKbps
        else ServerProfile().bitrateKbps

        // A negative id is not a host's app; 0 means "pick one myself".
        preferredAppId = if (preferredAppId >= 0) preferredAppId else 0

        // Anything that is not a MAC address cannot be woken, and a broadcast address that
        // is not one leaves the magic packet going wherever the system guesses.
        wolMAC = if (wolMAC.isBlank() || wolMAC.isValidMacAddress()) wolMAC else ""
        wolBroadcastAddress =
                if (wolBroadcastAddress.isBlank() || LocalNetwork.isIpv4(wolBroadcastAddress))
                    wolBroadcastAddress else ""

        name = name.withoutControlChars().take(MAX_LABEL_LENGTH)
        host = host.withoutControlChars().trim().take(MAX_LABEL_LENGTH)
        serverTypeHint = serverTypeHint.withoutControlChars().take(MAX_LABEL_LENGTH)
        preferredAppName = preferredAppName.withoutControlChars().take(MAX_LABEL_LENGTH)
        androidUsbDeviceName = androidUsbDeviceName.withoutControlChars().take(MAX_LABEL_LENGTH)
        username = username.take(MAX_SECRET_LENGTH)
        password = password.take(MAX_SECRET_LENGTH)
        sshUsername = sshUsername.take(MAX_SECRET_LENGTH)
        sshHost = sshHost.withoutControlChars().trim().take(MAX_LABEL_LENGTH)
        sshPassword = sshPassword.take(MAX_SECRET_LENGTH)
        sshPrivateKey = sshPrivateKey.take(MAX_ENCODED_LENGTH)
        clipboardVncPassword = clipboardVncPassword.take(MAX_SECRET_LENGTH)
        serverCertBase64 = serverCertBase64.take(MAX_ENCODED_LENGTH)
        clientUniqueId = clientUniqueId.take(MAX_ENCODED_LENGTH)

        // These two are read as enumerations, not as free text.
        if (gestureStyle !in GESTURE_STYLES) gestureStyle = "auto"
        if (screenOrientation !in SCREEN_ORIENTATIONS) screenOrientation = "auto"

        return this
    }

    /** The port as it stands, or null when it is not a port at all. */
    private fun portInRange(value: Int): Int? = value.takeIf { it in 1..65535 }

    /**
     * Control characters become spaces.
     *
     * These strings come from places that are not the editor: a link opened from a browser,
     * a scanned QR code, a backup file. They are then shown on the server list, in the
     * connection confirmation dialog, in toasts and in the log. A newline in a hostname can
     * fake an extra line of that dialog, and in the log it can forge a line that was never
     * written. Neither is something a real address or a real name needs.
     */
    private fun String.withoutControlChars(): String {
        if (none { it.isISOControl() })
            return this
        return buildString { this@withoutControlChars.forEach { append(if (it.isISOControl()) ' ' else it) } }
    }

    /**
     * Delegated property builder for [flags] field.
     */
    private class Flag(val flag: Long) {
        operator fun getValue(p: ServerProfile, kp: KProperty<*>) = (p.flags and flag) != 0L
        operator fun setValue(p: ServerProfile, kp: KProperty<*>, value: Boolean) {
            p.flags = if (value) p.flags or flag else p.flags and flag.inv()
        }
    }

    /**
     * Flag to insert artificial delay before UP event of left-click.
     */
    @IgnoredOnParcel
    var fButtonUpDelay by Flag(FLAG_BUTTON_UP_DELAY)

    /**
     * If zoom is locked, user requests to change [zoom1] & [zoom2]
     * should be ignored.
     */
    @IgnoredOnParcel
    var fZoomLocked by Flag(FLAG_ZOOM_LOCKED)

    /**
     * Try to automatically connect to this server when app starts.
     */
    @IgnoredOnParcel
    var fConnectOnAppStart by Flag(FLAG_CONNECT_ON_APP_START)

    /**
     * Whether the user has set this phone's picture quality explicitly, from the
     * quality panel of the editor.
     *
     * [streamWidth] / [streamHeight] / [streamFps] / [bitrateKbps] are shared with
     * GameStream, whose defaults (1920x1080 at 20 Mbps) describe a PC on its own
     * network - so until somebody actually picks them for this device, the ADB side
     * trims them toward its own latency ceiling instead of obeying them. Setting this
     * says "those numbers are mine", and the trimming stops.
     */
    @IgnoredOnParcel
    var fAndroidStreamTuned by Flag(FLAG_ANDROID_STREAM_TUNED)

    /**
     * Whether [streamWidth] / [streamHeight] describe a desktop size this RFB host
     * should be asked for, rather than another protocol's stream settings which happen
     * to be in the same two columns.
     */
    @IgnoredOnParcel
    var fRfbDesktopSize by Flag(FLAG_RFB_DESKTOP_SIZE)

    /**
     * Checks if this profile is persisted in database.
     * Simply checks for non-zero [ID]
     */
    fun isSaved() = (ID != 0L)

    /**
     * Whether tapping the wake button on this server's card can do anything.
     *
     * Waking is a manual action - connecting never sends a magic packet - so the button
     * is what advertises the capability. A discovered (unsaved) entry has no WoL setup
     * of its own yet, so it must not offer it.
     */
    val canWake: Boolean
        get() = isSaved() && enableWol && wolMAC.isNotBlank()

    /**
     * Port used when the profile has none (or a leftover from the other
     * protocol). Meaning differs per protocol, so this is the one place that
     * decides it.
     */
    fun defaultPortForProtocol(): Int = defaultPortFor(protocol)

    /**
     * `host[:port]` for display purposes: the port is left out when it is just the
     * default of this profile's protocol, because a VNC server on 5900 and a
     * Sunshine host on 47984 are equally "standard" for their users.
     *
     * A USB-OTG Android target has no address at all - the cable is the address -
     * so its device name is shown instead.
     */
    val displayAddress: String
        get() = when {
            protocol == PROTOCOL_ANDROID && androidUsbDeviceName.isNotBlank() -> androidUsbDeviceName
            port == defaultPortForProtocol() -> host
            else -> "$host | $port"
        }

    /**
     * Strictly GameStream: with a third protocol, `!= PROTOCOL_VNC` would quietly
     * start treating a phone as a Sunshine host (pairing, app list, WoL).
     */
    fun isGameStream() = (protocol == PROTOCOL_GAME_STREAM)
}
