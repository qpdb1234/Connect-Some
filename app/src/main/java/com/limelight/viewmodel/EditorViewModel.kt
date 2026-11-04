/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.limelight.model.ServerProfile

/**
 * ViewModel for profile editor
 */
class EditorViewModel(app: Application, state: SavedStateHandle, initialProfile: ServerProfile) : BaseViewModel(app) {

    /**
     * Profile being edited
     */
    val profile = state["profile"] ?: initialProfile.copy()

    init {
        state["profile"] = profile
    }

    /**
     * While most fields of [profile] are straightforward to edit, some require
     * more complex handling, and live feedback in UI.
     * For these, we have to use dedicated LiveData fields.
     */
    val protocol = state.getLiveData("protocol", profile.protocol)
    val useRepeater = state.getLiveData("useRepeater", profile.useRepeater)
    val idOnRepeater = state.getLiveData("idOnRepeater", if (profile.useRepeater) profile.idOnRepeater.toString() else "")
    val useRawEncoding = state.getLiveData("useRawEncoding", profile.useRawEncoding)
    val enableWol = state.getLiveData("enableWol", profile.enableWol)
    // SSH tunneling is not implemented, and the form has no way to turn it back off
    // once it is on (the checkbox is hidden). Starting from the stored `channelType`
    // would hand an existing tunnel entry a block of SSH fields that cannot be
    // dismissed, leaving the user unable to save anything - so the toggle always
    // begins off, and the editor explains why.
    val useSshTunnel = state.getLiveData("useSshTunnel", false)
    val hadSshTunnel = profile.channelType == ServerProfile.CHANNEL_SSH_TUNNEL
    val sshUsePassword = state.getLiveData("sshUsePassword", profile.sshAuthType == ServerProfile.SSH_AUTH_PASSWORD)
    val sshUsePrivateKey = state.getLiveData("sshUsePrivateKey", profile.sshAuthType == ServerProfile.SSH_AUTH_KEY)
    val hasSshPrivateKey = state.getLiveData("hasSshPrivateKey", profile.sshPrivateKey.isNotBlank())

    // Android-device protocol (see [com.limelight.vnc.AndroidClient]). Codec and
    // touch routing change what the wire carries, so they need live feedback like
    // the RFB knobs do; the USB name is picked by a button rather than typed.
    val androidUseH265 = state.getLiveData("androidUseH265", profile.androidUseH265)
    val androidAudio = state.getLiveData("androidAudio", profile.androidAudio)
    val androidKeepAwake = state.getLiveData("androidKeepAwake", profile.androidKeepAwake)
    val androidDirectTouch = state.getLiveData("androidDirectTouch", profile.androidDirectTouch)
    val androidUsbDeviceName = state.getLiveData("androidUsbDeviceName", profile.androidUsbDeviceName)


    fun prepareProfileForSave(): ServerProfile {
        val abandoned = profile.protocol
        val selected = protocol.value ?: abandoned
        if (selected != abandoned) {
            profile.protocol = selected
            // Protocol was switched on an existing entry: the knobs of the protocol
            // just left behind are reset instead of silently surviving in the row.
            // Pairing data (certificate, app id) is kept - it is inert for RFB and
            // expensive to recreate.
            if (abandoned == ServerProfile.PROTOCOL_VNC) {
                resetVncFields()
                // The RFB panel writes its desktop size into the two stream columns,
                // which this protocol shares with GameStream. Leaving RFB hands them
                // back: a 1024x768 desktop is not a mode a PC host offers, and the next
                // protocol would otherwise inherit it without anyone having picked it.
                val defaults = ServerProfile()
                profile.streamWidth = defaults.streamWidth
                profile.streamHeight = defaults.streamHeight
            } else if (abandoned == ServerProfile.PROTOCOL_ANDROID) resetAndroidFields()

            // An Android device has no RFB semantics at all (no security type, no
            // repeater, no raw encoding), so entering that protocol clears them even
            // when the profile came from GameStream.
            if (selected == ServerProfile.PROTOCOL_ANDROID) resetVncFields()
        }

        profile.useRepeater = useRepeater.value == true
        profile.idOnRepeater = idOnRepeater.value?.toIntOrNull() ?: 0
        profile.useRawEncoding = useRawEncoding.value == true
        profile.enableWol = enableWol.value == true
        profile.channelType = if (useSshTunnel.value == true) ServerProfile.CHANNEL_SSH_TUNNEL else ServerProfile.CHANNEL_TCP
        profile.sshAuthType = if (sshUsePassword.value == true) ServerProfile.SSH_AUTH_PASSWORD else ServerProfile.SSH_AUTH_KEY

        // Nothing can ever open a tunnel with these, but they are a password and a
        // private key at rest: they land in the database, in a backup and in an
        // exported file. Dropping them on save removes a secret that only ever
        // promised a capability this app does not have.
        profile.sshHost = ""
        profile.sshPort = 22
        profile.sshUsername = ""
        profile.sshPassword = ""
        profile.sshPrivateKey = ""
        hasSshPrivateKey.value = false

        profile.androidUseH265 = androidUseH265.value == true
        profile.androidAudio = androidAudio.value == true
        profile.androidKeepAwake = androidKeepAwake.value == true
        profile.androidDirectTouch = androidDirectTouch.value == true
        profile.androidUsbDeviceName = androidUsbDeviceName.value.orEmpty()
        return profile
    }

    /**
     * Selects another protocol for the profile being edited.
     *
     * The port means something different per protocol, so a port that is still
     * just the default of the protocol being left is replaced by the default of
     * the new one. A hand-picked port survives, so an accidental toggle does not
     * destroy a `5901` style setup.
     *
     * @return the new port when it changed (UI should redisplay it), else null.
     */
    fun onProtocolChanged(newProtocol: Int): Int? {
        if (protocol.value == newProtocol)
            return null

        val previousDefault = ServerProfile.defaultPortFor(protocol.value ?: profile.protocol)
        protocol.value = newProtocol

        if (profile.port <= 0 || profile.port == previousDefault) {
            profile.port = ServerProfile.defaultPortFor(newProtocol)
            return profile.port
        }
        return null
    }

    /**
     * Whether the user picked a different protocol than the saved profile has.
     * [profile] only receives the choice on save, so it still holds the original.
     */
    fun isProtocolChanged(): Boolean = (protocol.value != profile.protocol)

    /**
     * Whether the form currently describes an Android device. Data binding uses
     * this to show the Android-only block - a protocol test stays here, in the
     * editor's own view model, instead of reaching the layout as an int compare.
     */
    val androidProtocol: Boolean
        get() = protocol.value == ServerProfile.PROTOCOL_ANDROID

    /**
     * Restores RFB-only fields to their pristine values. Also used to keep the
     * form consistent with the loaded profile.
     */
    private fun resetVncFields() {
        profile.securityType = 0
        profile.imageQuality = 5
        profile.colorLevel = 7
        profile.useLocalCursor = true
        profile.resizeRemoteDesktop = false
        profile.useRepeater = false
        profile.idOnRepeater = 0
        profile.channelType = ServerProfile.CHANNEL_TCP
        profile.useRawEncoding = false
        profile.fRfbDesktopSize = false

        useRepeater.value = false
        idOnRepeater.value = ""
        useRawEncoding.value = false
        useSshTunnel.value = false
    }

    /**
     * Restores the Android-device fields to their pristine values, mirroring
     * [resetVncFields]. `androidUseOpus` is not editable in the form (the client
     * degrades to AAC by itself), but it belongs to the protocol, so it resets too.
     */
    private fun resetAndroidFields() {
        profile.androidUseH265 = true
        profile.androidUseOpus = true
        profile.androidAudio = true
        profile.androidKeepAwake = true
        profile.androidDirectTouch = true
        profile.androidUsbDeviceName = ""

        androidUseH265.value = true
        androidAudio.value = true
        androidKeepAwake.value = true
        androidDirectTouch.value = true
        androidUsbDeviceName.value = ""

        // The quality panel of this protocol writes into the stream fields, which are
        // shared with GameStream. Leaving the protocol hands them back: a square
        // 1600x1600 at a few megabits is a mirror setting, and as a PC stream mode it
        // is a black-barred nonsense picture.
        val defaults = ServerProfile()
        profile.streamWidth = defaults.streamWidth
        profile.streamHeight = defaults.streamHeight
        profile.streamFps = defaults.streamFps
        profile.bitrateKbps = defaults.bitrateKbps
        profile.fAndroidStreamTuned = false
    }
}