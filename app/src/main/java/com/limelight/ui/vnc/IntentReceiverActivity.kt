/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.ui.vnc

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limelight.R
import com.limelight.model.ServerProfile
import com.limelight.model.db.MainDb
import com.limelight.ui.home.HomeActivity
import com.limelight.vnc.VncUri
import kotlinx.coroutines.launch

/**
 * Handles "external" intents and launches [VncActivity] with appropriate profiles.
 *
 * Current intent types:
 *  - vnc:// , moonlight:// (Sunshine) and android:// (ADB-driven device) URIs
 *  - App shortcuts
 *  - USB device attached (the controlled device plugged in over OTG)
 */
class IntentReceiverActivity : AppCompatActivity() {

    companion object {
        private const val SHORTCUT_PROFILE_ID_KEY = "com.limelight.shortcut_profile_id"

        fun createShortcutIntent(context: Context, profileId: Long): Intent {
            check(profileId != 0L) { "Cannot create shortcut with profileId = 0." }
            return Intent(context, IntentReceiverActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(SHORTCUT_PROFILE_ID_KEY, profileId)
            }
        }
    }

    private val profileDao by lazy { MainDb.getInstance(this).serverProfileDao }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Being recreated - a rotation, or the system restoring this activity after the
        // process was stopped - hands back the very same intent. Handling it a second
        // time means a second prompt, a second saved server and a second session stacked
        // on the first.
        if (savedInstanceState != null) {
            finish()
            return
        }

        handleIntent()
    }

    private fun handleIntent() {
        val data = intent.data
        if (data?.scheme in listOf("vnc", "moonlight", "android")) {
            // External input: it gets a confirmation step, and it owns ending this
            // activity (the dialog decides), so it must not fall through to the
            // immediate `finish()` below.
            askBeforeConnecting(VncUri(data.toString()))
            return
        }

        lifecycleScope.launch {
            when {
                intent.hasExtra(SHORTCUT_PROFILE_ID_KEY) -> {
                    handleProfileIdShortcut()
                    return@launch
                }

                intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbAttach()
                else -> toast(getString(R.string.msg_unknown_intent))
            }
            finish()
        }
    }

    /**
     * A tap on an app shortcut connects the named server without asking anything, so this
     * path is the one an other app would rather be holding the trigger of.
     *
     * This activity is not exported; only the `.UriReceiverActivity` alias is, and reaching
     * the handler through that alias leaves the alias - not the activity - in the received
     * intent's component. A shortcut was built here against the activity itself, so the
     * component says whether the request could only have come from inside this app. Anyone
     * else naming a profile id gets the same confirmation a `vnc://` link gets: opening a
     * session takes over the remote screen with the saved password, and no app should be
     * able to do that while the user is looking at something else.
     */
    private suspend fun handleProfileIdShortcut() {
        val profileId = intent.getLongExtra(SHORTCUT_PROFILE_ID_KEY, 0)
        val profile = profileDao.getByID(profileId)
        if (profile == null) {
            toast(getString(R.string.msg_shortcut_server_deleted))
            finish()
            return
        }

        if (intent.component?.className == IntentReceiverActivity::class.java.name) {
            startVncActivity(this, profile)
            finish()
        } else {
            askBeforeConnectingProfile(profile)
        }
    }

    /**
     * An ADB device appeared on the USB port. There is no address in such an
     * intent, so the only useful thing is to get the user to the place where the
     * device can be picked - after saying what the other phone has to confirm.
     */
    private fun handleUsbAttach() {
        toast(getString(R.string.msg_android_usb_allow_debugging))
        startActivity(Intent(this, HomeActivity::class.java))
    }

    /**
     * `vnc://`, `moonlight://` and `android://` links reach this activity from other
     * apps - a browser, a messenger, a QR scanner. Such a link can name any host and
     * carry the RFB password in its query, and the app used to connect straight away
     * without the user seeing where it was going. Nothing on the controlled screen
     * tells the difference between that and a tap on the user's own server entry, so
     * the one place it can be said is here, before anything is saved or started.
     */
    private fun askBeforeConnecting(uri: VncUri) {
        if (!validateUri(uri)) {
            finish()
            return
        }

        val profile = uri.toServerProfile()
        MaterialAlertDialogBuilder(this, R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_external_connection)
                .setMessage(getString(R.string.msg_external_connection, profile.host, profile.port,
                        protocolLabel(profile.protocol)))
                .setNegativeButton(R.string.title_cancel) { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setOnCancelListener { finish() }
                .setPositiveButton(R.string.title_connect) { _, _ -> onExternalConnectionConfirmed(uri) }
                .show()
    }

    private fun onExternalConnectionConfirmed(uri: VncUri) = lifecycleScope.launch {
        if (uri.saveConnection)
            saveVncUri(uri)

        launchFromVncUri(uri)
        finish()
    }

    private fun protocolLabel(protocol: Int) = when (protocol) {
        ServerProfile.PROTOCOL_VNC -> getString(R.string.protocol_vnc)
        ServerProfile.PROTOCOL_ANDROID -> getString(R.string.protocol_android)
        else -> getString(R.string.protocol_game_stream)
    }

    private suspend fun launchFromVncUri(uri: VncUri) {
        val matchingProfile = findAndMergeMatchingProfile(uri)

        if (matchingProfile != null)
            startVncActivity(this, matchingProfile)
        else if (!uri.host.isNullOrBlank())
            startVncActivity(this, uri)
        else if (!uri.connectionName.isNullOrBlank())
            toast(getString(R.string.msg_no_server_named, uri.connectionName))
        else
            toast(getString(R.string.msg_invalid_vnc_uri))
    }

    private fun askBeforeConnectingProfile(profile: ServerProfile) {
        MaterialAlertDialogBuilder(this, R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_external_connection)
                .setMessage(getString(R.string.msg_external_connection, profile.host, profile.port,
                        protocolLabel(profile.protocol)))
                .setNegativeButton(R.string.title_cancel) { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setOnCancelListener { finish() }
                .setPositiveButton(R.string.title_connect) { _, _ ->
                    startVncActivity(this, profile)
                    finish()
                }
                .show()
    }

    private suspend fun findAndMergeMatchingProfile(uri: VncUri): ServerProfile? {
        return uri.connectionNameForProfile
                ?.let { profileDao.getByName(it).firstOrNull() }
                ?.let { uri.applyToProfile(it) }
    }

    private suspend fun saveVncUri(uri: VncUri) {
        val profile = findAndMergeMatchingProfile(uri) ?: uri.toServerProfile()
        if (profile.host.isBlank())
            return

        val id = profileDao.save(profile)
        if (id > 0) // New profile
            toast(getString(R.string.msg_server_added_for_uri))
    }

    private fun validateUri(uri: VncUri): Boolean {
        if (uri.host.isNullOrBlank() && uri.connectionName.isNullOrBlank()) {
            toast(getString(R.string.msg_invalid_vnc_uri))
            return false
        }

        val channel = uri.channelType
        if (channel == ServerProfile.CHANNEL_SSH_TUNNEL) {
            // There is no tunnel to open. Saying so beats connecting straight at the
            // target, which for a host only reachable through a bastion reads as a
            // wrong password rather than as a missing feature.
            toast(getString(R.string.error_ssh_tunnel_unsupported))
            return false
        }
        if (channel != null && channel != ServerProfile.CHANNEL_TCP) {
            toast(getString(R.string.msg_unknown_channel_type, channel))
            return false
        }

        val security = uri.securityType
        val supportedSecurityTypes = resources.getStringArray(R.array.profile_editor_security_values)
        if (security != null && security.toString() !in supportedSecurityTypes) {
            toast(getString(R.string.msg_unknown_security_type, security))
            return false
        }

        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}