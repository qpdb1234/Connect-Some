/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.home

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import com.limelight.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.os.BundleCompat
import androidx.core.view.MenuProvider
import androidx.core.view.descendants
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.limelight.R
import com.limelight.adb.Adb
import com.limelight.adb.AdbManager
import com.limelight.adb.AdbPairingPort
import com.limelight.adb.AdbTransport
import com.limelight.databinding.FragmentProfileEditorAdvancedBinding
import com.limelight.model.ServerProfile
import com.limelight.stream.GameStreamHost
import com.limelight.stream.NativeLibraryProbe
import com.limelight.util.FloatingText
import com.limelight.util.LanScanner
import com.limelight.util.LocalNetwork
import com.limelight.util.MsgDialog
import com.limelight.util.NetbiosLookup
import com.limelight.util.OpenableDocument
import com.limelight.util.isValidMacAddress
import com.limelight.util.normalizeMac
import com.limelight.util.protocolDisplayName
import com.limelight.viewmodel.EditorViewModel
import com.limelight.viewmodel.HomeViewModel
import com.limelight.viewmodel.service.Discovery
import com.limelight.vnc.AndroidStreamConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.ElevationOverlayProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/********************************************************************************
 * ServerProfile editor.
 *
 * A single fullscreen fragment attached to the content root.
 * All available options are always shown - there is no simplified mode.
 *
 *******************************************************************************/

private const val PROFILE_KEY = "profile"

/**
 * The address the host field should hold after a USB pick, or null to leave it alone.
 *
 * A wired device is addressed by its own name - `/dev/bus/usb/002/003` - so picking one
 * puts that name in the field, and taking the pick back ("none", reach the device over
 * the network) removes it again: an entry with no cable has no address until one is
 * typed. Only a value a pick put there is removed, recognised by comparing against the
 * pick in the form ([previousPick]) and the one the profile was opened with ([savedPick]),
 * so opening this dialog and choosing "none" cannot wipe an address typed by hand.
 */
internal fun hostForUsbPick(
        deviceName: String,
        previousPick: String,
        savedPick: String,
        currentHost: String,
): String? {
    if (deviceName.isNotEmpty())
        return deviceName
    if (currentHost.isNotEmpty() && (currentHost == previousPick || currentHost == savedPick))
        return ""
    return null
}

fun startProfileEditor(host: FragmentActivity, profile: ServerProfile) {
    startAdvancedProfileEditor(host, profile)
}

private fun startAdvancedProfileEditor(host: FragmentActivity, profile: ServerProfile) {
    val fragment = AdvancedProfileEditor().apply {
        arguments = Bundle().apply { putParcelable(PROFILE_KEY, profile) }
    }

    host.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
}

/**
 * Extracts [ServerProfile] passed to given fragment via argument.
 */
private fun getProfileArg(f: Fragment): ServerProfile {
    return f.arguments?.let { BundleCompat.getParcelable(it, PROFILE_KEY, ServerProfile::class.java) }
           ?: ServerProfile()
}

/**
 * Returns title string based on whether we are editing an existing profile, or creating a new profile.
 */
private fun getTitle(f: Fragment): Int {
    return if (!getProfileArg(f).isSaved()) R.string.title_add_server_profile
    else R.string.title_edit_server_profile
}


/**
 * If [preCondition] is `true`, validates that [target] is not empty.
 */
private fun validateNotEmpty(target: EditText, preCondition: Boolean = true): Boolean {
    if (preCondition && target.length() == 0) {
        target.error = target.context.getText(R.string.msg_required)
        return false
    }
    return true
}

/**
 * Pinned server certificate (DER) decoded from the profile's base64 field, if any.
 */
private fun certDerFromProfile(profile: ServerProfile): ByteArray? =
        if (profile.serverCertBase64.isBlank()) null
        else runCatching { android.util.Base64.decode(profile.serverCertBase64, android.util.Base64.NO_WRAP) }.getOrNull()

/**
 * Ties a dialog built with [com.google.android.material.dialog.MaterialAlertDialogBuilder]
 * to the lifetime of [fragment]'s view.
 *
 * Such a dialog is owned by nothing. When the fragment goes away - a rotation, or the
 * back button pressed while a handshake is still running - the coroutine that would
 * have dismissed it is cancelled along with the fragment, and a dialog created with
 * `setCancelable(false)` is left on screen with no way out at all. Binding dismissal
 * to the lifecycle makes the window leave with the view that opened it.
 */
private fun Fragment.dismissWithView(dialog: Dialog) {
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }
    })
}

/**
 * [MaterialAlertDialogBuilder.show] and [dismissWithView] together, for the dialogs that
 * are only built inline in a click handler and have no variable to attach afterwards.
 */
private fun MaterialAlertDialogBuilder.showTracked(fragment: Fragment): androidx.appcompat.app.AlertDialog {
    val dialog = show()
    fragment.dismissWithView(dialog)
    return dialog
}

/**
 * Runs the GameStream PIN pairing handshake against [hostAddress], used by the
 * editor's prominent "Pair" button.
 *
 * Following Moonlight's flow (PcView.doPair): the client GENERATES a 4-digit PIN and shows
 * it; the user types that PIN into the host (Sunshine web UI / GFE). [GameStreamHost.pair]
 * blocks until the host accepts it. On success the pinned certificate + paired flag are
 * stored on [profile] (persisted when the user taps Save).
 */
private fun launchPairing(
        fragment: Fragment,
        hostAddress: String,
        profilePort: Int,
        profile: ServerProfile,
        anchor: View,
) {
    if (hostAddress.isEmpty()) return
    val context = fragment.requireContext()
    val httpsPort = GameStreamHost.resolveHttpsPort(profilePort)
    val pin = GameStreamHost.generatePairingPin()

    val dialog = MaterialAlertDialogBuilder(context, R.style.AlertDialog_Dimmed)
            .setTitle(R.string.title_pair_host)
            .setMessage(context.getString(R.string.msg_pairing_pin_display, pin))
            .setCancelable(false)
            .show()
    fragment.dismissWithView(dialog)

    fragment.lifecycleScope.launch(Dispatchers.Main) {
        val msg = withContext(Dispatchers.IO) {
            runCatching {
                val outcome = GameStreamHost(context.applicationContext, hostAddress, httpsPort,
                        certDerFromProfile(profile), profile.clientUniqueId)
                        .pair(pin)
                if (outcome.state == com.limelight.stream.PairState.PAIRED) {
                    profile.serverCertBase64 = GameStreamHost(context.applicationContext, hostAddress, httpsPort)
                            .encodeCertForProfile(outcome.pinnedCertDer)
                    profile.paired = true
                    context.getString(R.string.msg_pairing_success)
                } else {
                    profile.paired = false
                    if (outcome.state == com.limelight.stream.PairState.PIN_WRONG)
                        context.getString(R.string.msg_pairing_pin_wrong)
                    else
                        context.getString(R.string.msg_pairing_failed)
                }
            }.getOrElse {
                Log.w("ProfileEditor", "Pairing failed", it)
                context.getString(R.string.msg_pairing_failed)
            }
        }
        if (dialog.isShowing) dialog.dismiss()
        // The result is only worth showing while the editor that asked for it is still
        // on screen; a Snackbar on a detached view throws rather than being ignored.
        if (!fragment.isAdded) return@launch
        FloatingText.show(anchor, msg, FloatingText.LENGTH_LONG)
    }
}


/********************************************************************************
 * Advanced mode
 *******************************************************************************/

class AdvancedProfileEditor : Fragment() {
    private val homeViewModel by activityViewModels<HomeViewModel>()
    private val viewModel by viewModels<EditorViewModel> { EditorViewModelFactory(this) }
    private lateinit var binding: FragmentProfileEditorAdvancedBinding
    private val keyFilePicker = registerForActivityResult(OpenableDocument()) { importPrivateKey(it) }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentProfileEditorAdvancedBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        binding.toolbar.title = getString(getTitle(this))
        binding.saveBtn.setOnClickListener { save() }
        binding.cancelBtn.setOnClickListener { dismiss() }
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.keyImportBtn.setOnClickListener { keyFilePicker.launch(arrayOf("*/*")) }
        binding.pairBtn.setOnClickListener { pairHost() }
        binding.androidUsbBtn.setOnClickListener { pickUsbDevice() }
        binding.androidPairBtn.setOnClickListener { showAndroidPairDialog() }
        binding.androidTcpipBtn.setOnClickListener { showAndroidTcpipDialog() }

        setupMacFields()
        setupBroadcastFillButton()
        setupScanMacButton()
        observeMacScanRequest()
        setupNightModeNavBarColor()
        setupToolbarMenu()
        setupProtocolSelector()
        setupAndroidPairEntry()
        applyProtocolVisibility()
        announceLegacySshTunnel()
        backfillBroadcastOnLoad()

        return binding.root
    }

    /**
     * An entry that was saved as "SSH tunnel" cannot keep that setting: there is no
     * tunnel to open. The form drops it (see [EditorViewModel.hadSshTunnel]), but that
     * has to be said out loud - otherwise a connection that used to go through a
     * bastion host starts going straight at the target, and the user reads it as a
     * password that stopped working.
     */
    private fun announceLegacySshTunnel() {
        if (!viewModel.hadSshTunnel)
            return
        FloatingText.show(binding.root, R.string.msg_ssh_tunnel_unsupported, FloatingText.LENGTH_LONG)
    }

    /**
     * RFB support lives in its own native library, so it can be missing from a
     * build. The editor reflects that instead of letting the user save a host
     * that cannot be reached; GameStream is unaffected either way.
     */
    private val vncAvailable by lazy { NativeLibraryProbe.isVncAvailable() }

    /**
     * Protocol currently chosen in the form (not yet committed to the profile).
     */
    private fun isVncSelected() = (viewModel.protocol.value == ServerProfile.PROTOCOL_VNC)

    /**
     * Protocol currently chosen in the form, for the device-driving protocol.
     */
    private fun isAndroidSelected() = (viewModel.protocol.value == ServerProfile.PROTOCOL_ANDROID)

    /**
     * Wires the protocol choice. This is the user-facing side of the single fork:
     * nothing else in the UI asks "which protocol", it just shows what this says.
     */
    private fun setupProtocolSelector() {
        binding.protocolVnc.text = protocolDisplayName(requireContext(), ServerProfile.PROTOCOL_VNC)
        binding.protocolGameStream.text = protocolDisplayName(requireContext(), ServerProfile.PROTOCOL_GAME_STREAM)
        binding.protocolAndroid.text = protocolDisplayName(requireContext(), ServerProfile.PROTOCOL_ANDROID)

        binding.protocol.check(when (viewModel.protocol.value) {
            ServerProfile.PROTOCOL_VNC -> R.id.protocol_vnc
            ServerProfile.PROTOCOL_ANDROID -> R.id.protocol_android
            else -> R.id.protocol_game_stream
        })

        if (!vncAvailable) {
            binding.protocolVnc.isEnabled = false
            binding.protocolNotice.text = getString(R.string.msg_vnc_unavailable)
            binding.protocolNotice.visibility = View.VISIBLE
        }

        binding.protocol.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.protocol_vnc -> ServerProfile.PROTOCOL_VNC
                R.id.protocol_android -> ServerProfile.PROTOCOL_ANDROID
                else -> ServerProfile.PROTOCOL_GAME_STREAM
            }

            viewModel.onProtocolChanged(selected)?.let { binding.port.setText(it.toString()) }
            applyProtocolVisibility()

            // Switching the protocol of a saved entry discards that entry's
            // protocol-specific options (see EditorViewModel.prepareProfileForSave).
            if (viewModel.profile.isSaved())
                FloatingText.show(binding.root, R.string.msg_protocol_switched, FloatingText.LENGTH_LONG)
        }
    }

    /**
     * Wireless pairing needs a component that is resolved from a third-party repo, so
     * it can be missing from this build. The entry point then explains itself instead
     * of opening a dialog that cannot possibly work - USB and already-authorised TCP
     * stay fully usable either way.
     */
    private fun setupAndroidPairEntry() {
        val pairing = AdbPairingPort.get()
        binding.androidPairBtn.isEnabled = pairing.isAvailable
    }

    /**
     * Field partitioning by protocol.
     *
     * GameStream-only: the pairing button (plus its toolbar entries) and the clipboard
     * bridge's VNC address - only that protocol has no clipboard of its own to use.
     * RFB-only: security type, credentials and local cursor. Android-only: codec,
     * audio, touch routing and the transport pickers. Picture quality is nobody's form
     * field any more - all three protocols get it from the toolbar's quality panel
     * instead, which is also where the RFB resolution lives. Repeater and the SSH
     * tunnel stay hidden for all three - neither is implemented in this build.
     */
    private fun applyProtocolVisibility() {
        val vnc = isVncSelected()
        val android = isAndroidSelected()

        // A USB device name is an address only inside the ADB block (see
        // [updateHostForUsbPick]). Leaving that protocol with the path still in the host
        // field would hand the next protocol an address it cannot dial, so it goes back
        // out here, while the form it was written into is still on screen.
        if (!android) {
            val usb = viewModel.androidUsbDeviceName.value.orEmpty()
            if (usb.isNotEmpty() && binding.host.text.toString().trim() == usb)
                binding.host.setText("")
        }

        binding.pairBtn.isVisible = !vnc && !android

        listOf(binding.clipboardPort, binding.clipboardPassword).forEach { it.isVisible = !vnc && !android }

        listOf(binding.securityLabel, binding.security,
                binding.username, binding.password,
                binding.useLocalCursor
        ).forEach { it.isVisible = vnc }

        listOf(binding.androidCodec, binding.androidAudio,
                binding.androidDirectTouch, binding.androidKeepAwake,
                binding.androidUsbBtn, binding.androidUsbLabel, binding.androidPairBtn,
                binding.androidTcpipBtn
        ).forEach { it.isVisible = android }

        if (vncAvailable && android && !AdbPairingPort.get().isAvailable) {
            binding.protocolNotice.text = getString(R.string.msg_android_pairing_unavailable)
            binding.protocolNotice.isVisible = true
        } else if (vncAvailable) {
            // Only clear it when the notice is not holding the (static) VNC warning.
            binding.protocolNotice.isVisible = false
        }

        // The USB name is chosen, never typed - but it has to be shown, because a cable
        // that was unplugged since leaves an address the user cannot recognise.
        if (android) updateUsbLabel()

        listOf(binding.useRepeater, binding.idOnRepeater,
                binding.resizeRemoteDesktop, binding.useSshTunnel,
                // Moved into the quality panel, where the level sits next to the resolution
                // it trades against instead of being one of eight unrelated sliders.
                binding.imageQualityLabel, binding.imageQuality, binding.rawEncoding
        ).forEach { it.visibility = View.GONE }

        // Pairing and app selection only exist for GameStream hosts; the stream
        // quality entry covers every protocol which has knobs to turn (see setupToolbarMenu).
        binding.toolbar.invalidateMenu()
    }

    private fun updateUsbLabel() {
        val name = viewModel.androidUsbDeviceName.value.orEmpty()
        binding.androidUsbLabel.text =
                if (name.isEmpty()) getString(R.string.label_android_usb_none) else name
    }

    /**
     * Attached USB devices, straight from the stack. No permission is asked for here:
     * the list is free to read, and the grant happens at connection time, where the
     * dialog can be tied to the device actually being used.
     */
    private fun pickUsbDevice() {
        val manager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.toList()

        if (devices.isEmpty()) {
            MsgDialog.show(parentFragmentManager, getString(R.string.action_android_select_usb),
                    getString(R.string.msg_android_no_usb_device))
            return
        }

        val labels = mutableListOf(getString(R.string.label_android_usb_none))
        labels.addAll(devices.map { it.deviceName })

        MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialog_Dimmed)
                .setTitle(R.string.action_android_select_usb)
                .setItems(labels.toTypedArray()) { d, which ->
                    val device = if (which == 0) null else devices[which - 1]
                    val previous = viewModel.androidUsbDeviceName.value.orEmpty()
                    val shownHost = binding.host.text.toString().trim()

                    viewModel.androidUsbDeviceName.value = device?.deviceName.orEmpty()
                    updateUsbLabel()
                    updateHostForUsbPick(device?.deviceName.orEmpty(), previous, shownHost)
                    d.dismiss()
                }
                .setNegativeButton(R.string.title_cancel, null)
                .showTracked(this)
    }

    /**
     * Mirrors the USB pick into the host field, which is the only place this form shows
     * where an entry points at.
     *
     * A wired device is addressed by its own name - `/dev/bus/usb/002/003` - so that is
     * what the field carries while one is picked, and it goes away with the pick: an
     * entry that has just been told to use the network has no address left to dial, and
     * showing the cable's path there would be a lie. Only a value a pick put there is
     * removed ([currentHost] equal to the previous or the saved pick), so opening this
     * dialog and choosing "none" again cannot wipe an address the user typed by hand.
     */
    private fun updateHostForUsbPick(deviceName: String, previousPick: String, currentHost: String) {
        val next = hostForUsbPick(deviceName, previousPick,
                viewModel.profile.androidUsbDeviceName, currentHost) ?: return
        binding.host.setText(next)
        if (next.isNotEmpty()) binding.host.error = null
    }

    /**
     * Wireless debugging pairing. Three values are asked for because all three are only
     * readable on the target's screen: the six-digit code and the pairing port come from
     * the "Pair device with pairing code" dialog, while the port used for the actual
     * connection is shown on the wireless debugging main screen and changes on every
     * toggle of that setting.
     */
    private fun showAndroidPairDialog() {
        val host = binding.host.text.toString().trim()
        if (host.isEmpty()) {
            binding.host.error = getText(R.string.msg_required)
            return
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun input(hint: Int, value: String? = null) = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setHint(hint)
            value?.let { setText(it) }
            setPadding(0, dp(8), 0, dp(8))
        }

        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val codeInput = input(R.string.hint_android_pair_code)
        val pairPortInput = input(R.string.hint_android_pair_port)
        val connectPortInput = input(R.string.hint_android_connect_port,
                ServerProfile.DEFAULT_ANDROID_PORT.toString())
        root.addView(codeInput)
        root.addView(pairPortInput)
        root.addView(connectPortInput)

        MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_android_pair)
                .setView(root)
                .setNegativeButton(R.string.title_cancel, null)
                .setPositiveButton(R.string.title_pair_host) { _, _ ->
                    runAndroidPairing(host, codeInput, pairPortInput, connectPortInput)
                }
                .showTracked(this)
    }

    private fun runAndroidPairing(host: String, code: EditText, pairPort: EditText, connectPort: EditText) {
        val codeText = code.text.toString().trim()
        val pairPortValue = pairPort.text.toString().trim().toIntOrNull()
        val connectPortValue = connectPort.text.toString().trim().toIntOrNull()

        var valid = true
        if (!codeText.matches(Regex("\\d{6}"))) {
            // Digits only: the code is fed to a SPAKE2 exchange, and anything else is a
            // failed handshake the user has to sit through before getting an error.
            code.error = getText(R.string.msg_required)
            valid = false
        }
        if (pairPortValue == null || pairPortValue !in 1..65535) {
            pairPort.error = getText(R.string.msg_invalid_port)
            valid = false
        }
        if (connectPortValue == null || connectPortValue !in 1..65535) {
            connectPort.error = getText(R.string.msg_invalid_port)
            valid = false
        }
        if (!valid) return
        // Both are non-null here (the checks above set `valid`), but the compiler cannot
        // carry that into the coroutine, so it is stated once more in local vals.
        val pairingPort = pairPortValue ?: return
        val connectionPort = connectPortValue ?: return

        val port = AdbPairingPort.get()
        lifecycleScope.launch(Dispatchers.Main) {
            val paired = withContext(Dispatchers.IO) { port.pair(host, pairingPort, codeText) }
            if (!isAdded) return@launch

            if (paired) {
                // The connection port belongs to the profile itself, so it lands in the
                // field above rather than in a hidden preference.
                binding.port.setText(connectionPort.toString())
                FloatingText.show(binding.root, R.string.msg_android_pair_success, FloatingText.LENGTH_LONG)
            } else {
                FloatingText.show(binding.root,
                        getString(R.string.msg_android_pair_failed,
                                port.lastFailureReason ?: ""),
                        FloatingText.LENGTH_LONG)
            }
        }
    }

    /**
     * Cable to wireless. Everything this needs is already on the cable - the device to
     * talk to and the host key it trusts - so the dialog asks for one number only.
     *
     * Worth a button rather than a hint: `adb tcpip` on the device itself needs a second
     * Android with developer options, and the plain "wireless debugging pairing" above
     * needs a six-digit code that expires within a minute.
     */
    private fun showAndroidTcpipDialog() {
        val deviceName = viewModel.androidUsbDeviceName.value.orEmpty()
        if (deviceName.isEmpty()) {
            MsgDialog.show(parentFragmentManager, getString(R.string.title_android_tcpip),
                    getString(R.string.msg_android_tcpip_needs_usb))
            return
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val portInput = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setHint(R.string.hint_android_tcpip_port)
            setText(ServerProfile.DEFAULT_ANDROID_PORT.toString())
            setPadding(0, dp(8), 0, dp(8))
        }
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(portInput)
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_android_tcpip)
                .setView(root)
                .setNegativeButton(R.string.title_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runAndroidTcpipSwitch(deviceName, portInput)
                }
                .showTracked(this)
    }

    /** Outcome of [switchToTcpip]: an address that answered ADB, or why nothing did. */
    private sealed interface TcpipSwitch {
        data class Reached(val address: String, val port: Int) : TcpipSwitch
        data class Failed(val message: String) : TcpipSwitch
    }

    private fun runAndroidTcpipSwitch(deviceName: String, portInput: EditText) {
        val port = portInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            portInput.error = getText(R.string.msg_invalid_port)
            return
        }

        // Resources are grabbed while still attached: the round trip below outlives a
        // dismissed editor, and only the result needs to touch the fragment again.
        val context = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.Main) {
            val outcome = withContext(Dispatchers.IO) { switchToTcpip(deviceName, port, context) }
            if (!isAdded) return@launch

            when (outcome) {
                is TcpipSwitch.Reached -> {
                    binding.host.setText(outcome.address)
                    binding.port.setText(outcome.port.toString())
                    // A USB name in the profile outranks the address on every connect
                    // (see AndroidClient.transport), so the cable pick has to go with it
                    // or the two new fields would never be read.
                    viewModel.androidUsbDeviceName.value = ""
                    updateUsbLabel()
                    FloatingText.show(binding.root,
                            getString(R.string.msg_android_tcpip_success, outcome.address, outcome.port),
                            FloatingText.LENGTH_LONG)
                }
                is TcpipSwitch.Failed ->
                    FloatingText.show(binding.root, outcome.message, FloatingText.LENGTH_LONG)
            }
        }
    }

    /**
     * Asks the cabled device for its address, puts adbd on TCP with it, and then proves
     * the address works before anything is written into the form.
     *
     * The proof is the point. `tcpip:` is answered by an adbd that is about to exit, so
     * its reply says little, and a device left on another subnet is reachable in name
     * only. [Adb.probe] is what separates "wrong address" from "not authorised" here -
     * and a failed probe deliberately means the cable entry is left untouched, because
     * the alternative is saving a profile that cannot connect any more.
     */
    private suspend fun switchToTcpip(deviceName: String, port: Int, context: Context): TcpipSwitch =
            withContext(Dispatchers.IO) {
                val key = "usb:$deviceName"
                var address: String? = null
                try {
                    // Reuses a live cable session if the user has one; the switch is exactly
                    // the moment that session stops being useful.
                    val session = AdbManager.acquire(key, AdbTransport.Usb(deviceName))
                    try {
                        address = deviceIpv4(session)
                        if (address != null) runCatching { session.restartOnTcpip(port) }
                    } finally {
                        // Not a release: the wire is gone, so caching it would hand the next
                        // connect a dead session to fail on.
                        AdbManager.invalidate(key)
                    }
                } catch (e: Exception) {
                    return@withContext TcpipSwitch.Failed(context.getString(
                            R.string.msg_android_tcpip_failed,
                            e.message ?: e.javaClass.simpleName))
                }

                val reachable = address ?: return@withContext TcpipSwitch.Failed(
                        context.getString(R.string.msg_android_tcpip_no_ip))

                if (awaitAdbReachable(reachable, port)) TcpipSwitch.Reached(reachable, port)
                else TcpipSwitch.Failed(context.getString(
                        R.string.msg_android_tcpip_unreachable, reachable, port))
            }

    /**
     * The device's own IPv4, read through the cable. It has to be the *device's*: this
     * phone's network view says nothing about where the target ended up (its Wi-Fi, the
     * car's hotspot). `scope global` keeps the loopback out of the results.
     */
    private fun deviceIpv4(session: Adb): String? = runCatching {
        session.runAdbCmd("ip -4 addr show scope global")
    }.getOrNull()?.let { output ->
        Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(output)?.groupValues?.get(1)
    }?.takeIf { !it.startsWith("127.") }

    /**
     * Retries the reachability test, because adbd restarts itself and the listener takes
     * a moment to come back. A port nothing listens on is refused outright rather than
     * hanging, so the waiting only happens when it can pay off.
     */
    private fun awaitAdbReachable(address: String, port: Int): Boolean {
        val attempts = 4
        val retryDelayMs = 1200L
        for (attempt in 1..attempts) {
            if (runCatching { Adb.probe(address, port, AdbManager.keyPair) }.getOrDefault(false))
                return true
            if (attempt < attempts) runCatching { Thread.sleep(retryDelayMs) }
        }
        return false
    }

    /**
     * The six MAC boxes. One box holds one hexadecimal pair; the colons are drawn by the
     * layout, so the stored value can never be something like `AA-BB-CC-DDD-EE-F`.
     *
     * Typing advances on its own and a whole address dropped into any single box (copied
     * from `getmac`, from a router page, from a firmware readout) is recognised and spread
     * over all six - which is why the boxes have no `maxLength`: the paste has to survive
     * long enough to be seen here.
     */
    private val macFields by lazy {
        listOf(binding.wolMac1, binding.wolMac2, binding.wolMac3,
                binding.wolMac4, binding.wolMac5, binding.wolMac6)
    }

    /** Set while this class writes the boxes itself, to keep the watchers from echoing. */
    private var updatingMac = false

    private fun setupMacFields() {
        for ((index, field) in macFields.withIndex()) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(value: Editable) {
                    if (updatingMac) return

                    val typed = value.toString()
                    val cleaned = typed.filter { it in "0123456789abcdefABCDEF" }
                    if (cleaned.length != typed.length) {
                        value.replace(0, typed.length, cleaned)
                        return
                    }

                    val hex = cleaned.uppercase(Locale.ROOT)
                    if (hex.normalizeMac().isValidMacAddress()) {
                        // A complete address landed in a single box - a paste from
                        // getmac, a router page or a firmware readout.
                        writeMacFields(hex.normalizeMac(), focusAfter = field)
                        return
                    }

                    if (hex.length > 2) {
                        // Several pairs at once (a paste of only part of an address, or
                        // keystrokes arriving before the focus advance took effect): lay
                        // them out over the following boxes instead of throwing them away.
                        spread(hex, index)
                        return
                    }

                    viewModel.profile.wolMAC = currentMac()

                    if (hex.length == 2 && field.hasFocus()) {
                        if (index == macFields.lastIndex) binding.wolBroadcastAddress.requestFocus()
                        else macFields[index + 1].requestFocus()
                    }
                }
            })

            // Backspacing out of an already empty box should undo the last pair instead
            // of leaving the caret stuck. Same gesture on the first box does nothing.
            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN
                        && field.text.isEmpty() && index > 0) {
                    macFields[index - 1].requestFocus()
                }
                false
            }
        }

        writeMacFields(viewModel.profile.wolMAC.normalizeMac())
    }

    /**
     * Colon-separated contents of the six boxes, exactly as it goes into the profile.
     */
    private fun currentMac(): String = macFields.joinToString(":") {
        it.text.toString().trim().uppercase(Locale.ROOT)
    }

    /**
     * Lays [hex] out as pairs over the boxes from [from] on, leaving the earlier boxes
     * alone. Whatever does not fit is dropped - there are only six boxes.
     */
    private fun spread(hex: String, from: Int) {
        val pairs = hex.uppercase(Locale.ROOT).chunked(2)

        updatingMac = true
        for (index in from..macFields.lastIndex) {
            macFields[index].setText(pairs.getOrElse(index - from) { "" })
        }
        updatingMac = false

        viewModel.profile.wolMAC = currentMac()

        macFields.firstOrNull { it.text.isEmpty() }?.requestFocus()
                ?: binding.wolBroadcastAddress.requestFocus()
    }

    /**
     * Shows [mac] (colon form, or empty) in the boxes. Anything unusable is displayed as
     * no address at all rather than as half-filled boxes.
     */
    private fun writeMacFields(mac: String, focusAfter: EditText? = null) {
        val usable = if (mac.isValidMacAddress()) mac else ""
        // Boxes are narrow, so they are shown in upper case like every other MAC readout;
        // the profile keeps the plain colon form.
        val parts = if (usable.isEmpty()) emptyList() else usable.uppercase(Locale.ROOT).split(":")

        updatingMac = true
        for (index in macFields.indices) {
            macFields[index].setText(parts.getOrElse(index) { "" })
        }
        updatingMac = false

        viewModel.profile.wolMAC = usable

        if (usable.isNotEmpty() && focusAfter != null) {
            binding.wolBroadcastAddress.requestFocus()
        }
    }

    /**
     * Derives the broadcast address from the network this device is attached to, the way
     * Duorem's "get broadcast address" does: the target's IPv4 address together with the
     * prefix length of the local subnet. Both halves are read rather than typed, which is
     * what makes this worth a button instead of a hint.
     *
     * Asked by target rather than taken as "the" local network, because a phone that is
     * hosting a hotspot owns two networks at once and the machine being woken sits on the
     * one the platform will not name.
     */
    private fun setupBroadcastFillButton() {
        binding.wolGetBroadcast.setOnClickListener {
            val typed = binding.host.text.toString().trim()
            val local = LocalNetwork.forTarget(requireContext(), typed.ifEmpty { null })
            val target = typed.ifEmpty { local?.address ?: "" }

            when {
                local == null ->
                    binding.wolBroadcastAddress.error = getString(R.string.msg_wol_no_local_network)

                !LocalNetwork.isIpv4(target) ->
                    binding.wolBroadcastAddress.error = getString(R.string.msg_wol_target_not_ipv4)

                else -> {
                    binding.wolBroadcastAddress.setText(LocalNetwork.broadcast(target, local.prefixLength))
                    binding.wolBroadcastAddress.error = null
                }
            }
        }
    }

    /**
     * The broadcast address [target] belongs to, derived exactly the way the "get broadcast"
     * button does - the target's IPv4 plus the prefix length of the local network that can
     * reach it - or null when there is no such network or [target] is not a plain IPv4 host.
     */
    private fun deriveBroadcast(target: String): String? {
        if (!LocalNetwork.isIpv4(target)) return null
        val local = LocalNetwork.forTarget(requireContext(), target) ?: return null
        return LocalNetwork.broadcast(target, local.prefixLength)
    }

    /**
     * Fill the broadcast box from [target] when it is still empty, so a MAC lookup or a
     * one-tap add does not leave the wake half-configured. An address the user already typed
     * is never overwritten.
     */
    private fun fillBroadcastIfEmpty(target: String) {
        if (binding.wolBroadcastAddress.text.toString().isNotBlank()) return
        val broadcast = deriveBroadcast(target) ?: return
        binding.wolBroadcastAddress.setText(broadcast)
        binding.wolBroadcastAddress.error = null
    }

    /**
     * A profile opened for editing that already has WOL on and a hardware address but no
     * broadcast - one saved before this was filled in, say - gets the broadcast derived once
     * on load, the same value its "get broadcast" button would produce.
     */
    private fun backfillBroadcastOnLoad() {
        if (viewModel.enableWol.value != true) return
        if (viewModel.profile.wolMAC.isBlank()) return
        fillBroadcastIfEmpty(binding.host.text.toString().trim())
    }

    /**
     * Fills the six MAC boxes from the host this entry points at.
     *
     * A hardware address is written on a label inside the case, which is the one place the
     * user cannot read it from once the machine is under the desk. Everything that can still
     * say it is asked in order of cost, and the boxes are only written when one of them
     * answers - leaving them untouched is better than filling them with a guess.
     */
    private fun setupScanMacButton() {
        binding.wolScanMac.setOnClickListener {
            val address = binding.host.text.toString().trim()
            if (!LocalNetwork.isIpv4(address)) {
                // The complaint belongs to the host field, because that is what has to change.
                binding.host.error = getString(R.string.msg_wol_target_not_ipv4)
                return@setOnClickListener
            }
            binding.host.error = null

            // Several sources each cost a timeout, so the button leaves the screen's reach
            // while they are being asked rather than queueing a fresh sweep per tap.
            val button = binding.wolScanMac
            button.isEnabled = false
            lifecycleScope.launch(Dispatchers.Main) {
                val mac = withContext(Dispatchers.IO) { macOf(address) }
                if (!isAdded) return@launch
                button.isEnabled = true

                if (mac == null) {
                    FloatingText.show(binding.root, getString(R.string.msg_wol_mac_not_found, address),
                            FloatingText.LENGTH_LONG)
                } else {
                    writeMacFields(mac.normalizeMac(), focusAfter = binding.wolBroadcastAddress)
                    fillBroadcastIfEmpty(address)
                    FloatingText.show(binding.root,
                            getString(R.string.msg_wol_mac_found, mac.normalizeMac().uppercase(Locale.ROOT)),
                            FloatingText.LENGTH_SHORT)
                }
            }
        }
    }

    /**
     * Started from a discovered card whose hardware address no source could name: put the
     * caret in the first MAC box and run this screen's own lookup, which has the host to ask
     * about already filled in. The click is posted rather than made out right, because the
     * address reaches the host field through data binding - reading it a moment too early
     * would have the lookup complain about an empty field it is about to be given.
     */
    private fun observeMacScanRequest() {
        homeViewModel.macScanRequested.observe(viewLifecycleOwner) { requested ->
            if (!requested) return@observe
            binding.wolMac1.requestFocus()
            binding.root.post { binding.wolScanMac.performClick() }
        }
    }

    /**
     * The hardware address of [address] from the first source that can name it, or null when
     * none of them could. These are asked cheapest first: what a scan the user may have
     * just ran already learned, then the kernel's neighbour cache, then the two that need to
     * be dialled - a streaming host answers its own `serverinfo` over plaintext HTTP without
     * pairing, and NetBIOS is only spoken by Windows. Blocking I/O: off the main thread.
     */
    private suspend fun macOf(address: String): String? = withContext(Dispatchers.IO) {
        Discovery.devices.value?.firstOrNull { it.address == address }?.macAddress.usableMac()
                ?.let { return@withContext it }

        // A MAC this device learned in an earlier round - and kept across a restart or a
        // backup - answers the address without touching the network, so try it before
        // provoking a probe on the host.
        Discovery.savedMac(address).usableMac()?.let { return@withContext it }

        // The neighbour cache only holds what a probe has already provoked, and a full walk
        // fills it as a side effect. A single host asked for on demand is usually not in it
        // yet, so touch it first - otherwise readNeighbors() comes back empty for exactly the
        // machine the user pointed at, which is what made the whole MAC button look broken.
        val network = LanScanner.networkFor(LanScanner.subnets(requireContext()), address)
        LanScanner.preProbe(address, network)

        LanScanner.readNeighbors()[address].usableMac()
                ?: gameStreamMacOf(address)
                ?: NetbiosLookup.macOf(address, network).usableMac()
    }

    /**
     * A GameStream / Sunshine host's self-reported address, but only once one of its ports is
     * seen open: asking a machine that does not stream would spend a connect timeout on every
     * entry that has ever been edited with the WoL box ticked.
     */
    private suspend fun gameStreamMacOf(address: String): String? {
        val streaming = LanScanner.openServicePorts(address).any {
            it == GameStreamHost.DEFAULT_HTTPS_PORT || it == GameStreamHost.DEFAULT_HTTP_PORT
        }
        if (!streaming) return null

        return GameStreamHost(requireContext().applicationContext, address, GameStreamHost.DEFAULT_HTTPS_PORT)
                .queryMacAddress().usableMac()
    }

    /** [this] when it names six hex pairs, else null - including when it is null. */
    private fun String?.usableMac(): String? = this?.takeIf { it.isValidMacAddress() }

    /**
     * When Dark theme is active, navigation bar background color should match
     * with the background color of bottom app bar.
     */
    private fun setupNightModeNavBarColor() {
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES)
            return // Night mode is not active

        @Suppress("DEPRECATION")
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            var originalNavBarColor: Int? = null

            private fun getNewColor(): Int {
                val elevation = resources.getDimension(R.dimen.editor_bottom_bar_elevation)
                return ElevationOverlayProvider(requireContext())
                        .compositeOverlayWithThemeSurfaceColorIfNeeded(elevation)
            }

            override fun onStart(owner: LifecycleOwner) {
                requireActivity().window.let {
                    val newColor = getNewColor()
                    originalNavBarColor = it.navigationBarColor
                    it.navigationBarColor = newColor
                    binding.edgeToEdgeWrapper.setCustomNavBarColor(newColor)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                originalNavBarColor?.let {
                    requireActivity().window.navigationBarColor = it
                    originalNavBarColor = null
                    binding.edgeToEdgeWrapper.setCustomNavBarColor(null)
                }
            }
        })
    }

    private fun setupToolbarMenu() {
        binding.toolbar.addMenuProvider(object : MenuProvider {
            private var selectAppItem: MenuItem? = null
            private var streamQualityItem: MenuItem? = null

            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // Pairing lives in a dedicated on-screen button now (see binding.pairBtn);
                // the remaining host setup actions stay in the overflow menu.
                selectAppItem = menu.add(R.string.title_select_app)
                        .setOnMenuItemClickListener { selectApp(); true }
                streamQualityItem = menu.add(R.string.title_stream_quality)
                        .setOnMenuItemClickListener {
                            // Same entry, different panel: a phone has no HDR and no SOPS,
                            // but does have a picture size / frame rate / bitrate that the
                            // helper's encoder is told about in its own units - and an RFB
                            // host has neither of those at all, only a quality level and a
                            // desktop size it can be asked to switch to.
                            if (isAndroidSelected()) showAndroidStreamQualityDialog()
                            else if (isVncSelected()) showRfbQualityDialog()
                            else showStreamQualityDialog()
                            true
                        }
            }

            /**
             * Selecting an app talks to the GameStream HTTPS API, so it is meaningless
             * for an RFB server - and for a phone driven over ADB, which has no such
             * API at all. Stream quality is a different case: every protocol in this app
             * can be told how its picture is made, they just answer to different numbers
             * (see the three panels reached from this entry). [androidx.appcompat.app.AppCompatActivity.invalidateOptionsMenu]
             * equivalent for this toolbar is [androidx.appcompat.widget.Toolbar.invalidateMenu],
             * which is called whenever the protocol choice changes.
             */
            override fun onPrepareMenu(menu: Menu) {
                val android = isAndroidSelected()
                val vnc = isVncSelected()
                val gameStream = !vnc && !android
                selectAppItem?.isVisible = gameStream
                streamQualityItem?.isVisible = gameStream || android || vnc
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner)
    }

    /**
     * httpsPort for the Moonlight host being edited. Reuses the historical VNC
     * port remapping so a raw profile port (or the 5900 default) maps correctly.
     */
    private fun moonlightHttpsPort(): Int =
            GameStreamHost.resolveHttpsPort(viewModel.profile.port)

    /**
     * Runs the GameStream PIN pairing handshake against the currently edited host via the
     * shared [launchPairing] helper (also used by the simple editor's Pair button).
     */
    private fun pairHost() {
        val host = binding.host.text.toString().trim()
        if (host.isEmpty()) {
            binding.host.error = getText(R.string.msg_required)
            return
        }
        launchPairing(this, host, viewModel.profile.port, viewModel.profile, binding.root)
    }

    /**
     * Fetches the host's app list over HTTPS and lets the user pick one. The
     * chosen app id/name is stored on the profile (persisted on Save).
     *
     * The picker opens right away and holds a single "loading" row while the
     * request is running - there is no separate popup. Once the host answers,
     * the row is replaced by the real list; failures are reported only.
     */
    private fun selectApp() {
        val host = binding.host.text.toString().trim()
        if (host.isEmpty()) {
            binding.host.error = getText(R.string.msg_required)
            return
        }
        val httpsPort = moonlightHttpsPort()
        val profile = viewModel.profile

        // Stays null until the host answers, which also makes the placeholder
        // row untappable.
        var apps: List<GameStreamHost.AppInfo>? = null
        val labels = mutableListOf(getString(R.string.msg_stream_apps_loading))
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, labels)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_select_app)
                .setAdapter(adapter) { d, which ->
                    val list = apps ?: return@setAdapter
                    if (which == 0) {
                        profile.preferredAppId = 0
                        profile.preferredAppName = ""
                    } else {
                        val app = list[which - 1]
                        profile.preferredAppId = app.id
                        profile.preferredAppName = app.name
                    }
                    FloatingText.show(binding.root,
                            getString(R.string.msg_selected_app, labels[which]),
                            FloatingText.LENGTH_SHORT)
                    d.dismiss()
                }
                .setNegativeButton(R.string.title_cancel, null)
                .create()
        dialog.show()
        dismissWithView(dialog)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                GameStreamHost(requireContext().applicationContext, host, httpsPort,
                        certDerFromProfile(profile), profile.clientUniqueId).getAppList()
            }
            withContext(Dispatchers.Main) {
                val list = result.getOrNull()
                if (!isAdded) return@withContext
                if (list.isNullOrEmpty()) {
                    if (dialog.isShowing) dialog.dismiss()
                    FloatingText.show(binding.root, R.string.msg_no_apps_found, FloatingText.LENGTH_LONG)
                    return@withContext
                }
                apps = list
                labels.clear()
                labels.add(getString(R.string.title_app_none))
                labels.addAll(list.map { it.name })
                adapter.notifyDataSetChanged()
            }
        }
    }

    /**
     * Simple programmatic dialog for Moonlight stream quality. Edits the profile's
     * resolution / frame-rate / bitrate / HDR / SOPS / local-audio fields.
     */
    private fun showStreamQualityDialog() {
        val profile = viewModel.profile
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val resolutions = listOf("1280x720" to Pair(1280, 720), "1920x1080" to Pair(1920, 1080),
                "2560x1440" to Pair(2560, 1440), "3840x2160" to Pair(3840, 2160))
        val fpsOptions = listOf(30, 60, 90, 120)

        val resSpinner = Spinner(context)
        resSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                resolutions.map { it.first })
        resSpinner.setSelection(resolutions.indexOfFirst { it.second.first == profile.streamWidth
                && it.second.second == profile.streamHeight }.coerceAtLeast(0))

        val fpsSpinner = Spinner(context)
        fpsSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                fpsOptions.map { it.toString() })
        fpsSpinner.setSelection(fpsOptions.indexOf(profile.streamFps).coerceAtLeast(0))

        val bitrateInput = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(profile.bitrateKbps.toString())
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }

        val hdrCheck = CheckBox(context).apply { isChecked = profile.enableHdr; text = getString(R.string.title_enable_hdr) }
        val sopsCheck = CheckBox(context).apply { isChecked = profile.enableSops; text = getString(R.string.title_enable_sops) }
        val audioCheck = CheckBox(context).apply { isChecked = profile.playLocalAudio; text = getString(R.string.title_play_local_audio) }

        root.addView(label(context, R.string.title_stream_resolution))
        root.addView(resSpinner)
        root.addView(label(context, R.string.title_stream_fps))
        root.addView(fpsSpinner)
        root.addView(label(context, R.string.title_stream_bitrate))
        root.addView(bitrateInput)
        root.addView(hdrCheck)
        root.addView(sopsCheck)
        root.addView(audioCheck)

        MaterialAlertDialogBuilder(context, R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_stream_quality)
                .setView(root)
                .setNegativeButton(R.string.title_cancel, null)
                .setPositiveButton(R.string.title_save) { _, _ ->
                    resolutions[resSpinner.selectedItemPosition].second.let {
                        profile.streamWidth = it.first
                        profile.streamHeight = it.second
                    }
                    profile.streamFps = fpsOptions[fpsSpinner.selectedItemPosition]
                    // Free text, unlike the two spinners above. Whatever is stored here is
                    // later multiplied and handed to an encoder, so a typo like an extra
                    // zero is refused rather than kept for the session to trip over.
                    profile.bitrateKbps = bitrateInput.text.toString().trim().toIntOrNull()
                            ?.coerceIn(ServerProfile.MIN_BITRATE_KBPS, ServerProfile.MAX_BITRATE_KBPS)
                            ?: profile.bitrateKbps
                    profile.enableHdr = hdrCheck.isChecked
                    profile.enableSops = sopsCheck.isChecked
                    profile.playLocalAudio = audioCheck.isChecked
                    FloatingText.show(binding.root, R.string.msg_stream_quality_saved, FloatingText.LENGTH_SHORT)
                }
                .showTracked(this)
    }

    /**
     * Quality panel for a phone driven over ADB, reached from the same top-right entry
     * as [showStreamQualityDialog].
     *
     * The three controls are exactly the encoder arguments the helper process is
     * started with (`maxSize`, `maxFps`, `maxVideoBit`) - which is where this
     * protocol's delay is won or lost. The wire is adbd carried by a phone radio, not
     * a PC's network card, so a picture too large for it turns into a queue in front
     * of the decoder, and that queue is the latency the viewer feels. HDR and SOPS are
     * absent on purpose: a phone's MediaCodec encoder is offered neither.
     */
    private fun showAndroidStreamQualityDialog() {
        val profile = viewModel.profile
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        // Long-edge pixel caps. The mirror preserves the device's own aspect ratio, so
        // this single number is the whole picture; the helper rounds each edge to a
        // multiple of 8 on its way into the encoder.
        val sizeOptions = listOf(720, 960, 1080, 1260, 1440, 1600, 1920, 2160)

        // Frame rates a phone display really refreshes at. The encoder paces itself to
        // the source, so asking past it buys a queue rather than frames.
        val fpsOptions = listOf(30, 60, 90, 120)

        // Preselect what the next session would actually use rather than whatever the
        // row happens to hold: an untuned profile still carries another protocol's
        // numbers, and trimming those is exactly what the client does at connect time.
        // The nearest listed value that is not *above* the effective one wins, so
        // opening this panel can never quietly raise the delay by rounding up.
        val current = AndroidStreamConfig.forProfile(profile)
        fun preselect(options: List<Int>, value: Int) =
                options.indexOfLast { it <= value }.coerceAtLeast(0)

        val sizeSpinner = Spinner(context)
        sizeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                sizeOptions.map { it.toString() })
        sizeSpinner.setSelection(preselect(sizeOptions, current.maxSize))

        val fpsSpinner = Spinner(context)
        fpsSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                fpsOptions.map { it.toString() })
        fpsSpinner.setSelection(preselect(fpsOptions, current.maxFps))

        // Megabits, not the kilobits of the GameStream panel: `maxVideoBit` is handed
        // to the helper as megabits, and a phone stream lives in single digits - asking
        // for 4000 here would be refused as 4.
        val bitrateInput = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.bitrateMbps.toString())
            filters = arrayOf(android.text.InputFilter.LengthFilter(3))
        }

        val audioCheck = CheckBox(context).apply {
            isChecked = profile.playLocalAudio
            text = getString(R.string.title_play_local_audio)
        }

        val hint = TextView(context).apply {
            text = getString(R.string.msg_android_quality_hint)
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }

        root.addView(label(context, R.string.title_stream_resolution_long_edge))
        root.addView(sizeSpinner)
        root.addView(label(context, R.string.title_stream_fps))
        root.addView(fpsSpinner)
        root.addView(label(context, R.string.title_stream_bitrate_mbps))
        root.addView(bitrateInput)
        root.addView(audioCheck)
        root.addView(hint)

        MaterialAlertDialogBuilder(context, R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_stream_quality)
                .setView(root)
                .setNegativeButton(R.string.title_cancel, null)
                .setPositiveButton(R.string.title_save) { _, _ ->
                    val longEdge = sizeOptions[sizeSpinner.selectedItemPosition]
                    // The same number in both edges. This protocol reads only the larger
                    // one, and a square pair cannot be mistaken for a desktop display mode
                    // by whatever reads the row after the protocol is switched again.
                    profile.streamWidth = longEdge
                    profile.streamHeight = longEdge
                    profile.streamFps = fpsOptions[fpsSpinner.selectedItemPosition]

                    // Blank or garbage input keeps the previous value rather than becoming
                    // an argument the helper refuses; the range is the helper's own clamp,
                    // applied here so the panel shows afterwards what it will really send.
                    bitrateInput.text.toString().trim().toIntOrNull()?.let { mbps ->
                        profile.bitrateKbps = mbps
                                .coerceIn(AndroidStreamConfig.ADB_MIN_BITRATE_MBPS,
                                        AndroidStreamConfig.ADB_MAX_BITRATE_MBPS) * 1000
                    }
                    profile.playLocalAudio = audioCheck.isChecked

                    // The part that makes the rest real: without it the client would keep
                    // trimming these numbers back to its latency ceiling, and this panel
                    // would look like it does nothing at all.
                    profile.fAndroidStreamTuned = true

                    FloatingText.show(binding.root, R.string.msg_stream_quality_saved, FloatingText.LENGTH_SHORT)
                }
                .showTracked(this)
    }

    private fun label(context: android.content.Context, textRes: Int) = TextView(context).apply {
        setText(textRes)
        setPadding(0, resources.getDimensionPixelSize(R.dimen.margin_small), 0, 0)
    }

    /**
     * Quality panel for an RFB host, reached from the same top-right entry as
     * [showStreamQualityDialog] - with two fewer controls, because VNC has no bitrate
     * and no frame rate. A server sends a new picture whenever its screen changes, and
     * how much wire that costs follows from the encoding both ends settled on, so a
     * number for either here could only ever be decorative. What the protocol does
     * answer to is a quality level and, on hosts which implement it, a request to resize
     * the desktop.
     */
    private fun showRfbQualityDialog() {
        val profile = viewModel.profile
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        // A null size means "ask for nothing" - leave the host on the desktop it already
        // has. The others are modes a desktop server can plausibly switch to.
        val sizes = mutableListOf<Pair<String, Pair<Int, Int>?>>()
        sizes.add(getString(R.string.title_rfb_size_host_native) to null)
        listOf(800 to 600, 1024 to 768, 1280 to 720, 1366 to 768, 1600 to 900,
                1920 to 1080, 2560 to 1440).forEach {
            sizes.add("${it.first}x${it.second}" to it)
        }

        // A size which was chosen here has to come back shown as chosen. A mode which is
        // not on the list is added rather than rounded to a neighbour: rounding would
        // quietly change the desktop the user is about to connect to.
        val current: Pair<Int, Int>? =
                if (profile.fRfbDesktopSize) profile.streamWidth to profile.streamHeight else null
        if (current != null && sizes.none { it.second == current })
            sizes.add(1, "${current.first}x${current.second}" to current)

        val sizeSpinner = Spinner(context)
        sizeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                sizes.map { it.first })
        sizeSpinner.setSelection(sizes.indexOfFirst { it.second == current }.coerceAtLeast(0))

        // Ten levels, not a percentage: the protocol carries a number in [0, 9], so a
        // slider with a hundred stops would show ninety-one positions which the server
        // cannot tell apart.
        val qualityValue = TextView(context)
        val qualitySeek = SeekBar(context).apply {
            max = 9
            progress = profile.imageQuality
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, level: Int, fromUser: Boolean) {
                    qualityValue.text = getString(R.string.title_rfb_quality_level_value, level)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }

        // Raw hands the pixels over unbuilt: nothing to compress and therefore nothing to
        // tune, at the cost of the most bandwidth of any setting here.
        val rawCheck = CheckBox(context).apply {
            isChecked = profile.useRawEncoding
            text = getString(R.string.title_image_quality_raw)
            setOnCheckedChangeListener { _, checked -> qualitySeek.isEnabled = !checked }
        }
        qualityValue.text = getString(R.string.title_rfb_quality_level_value, profile.imageQuality)
        qualitySeek.isEnabled = !rawCheck.isChecked

        val hint = TextView(context).apply {
            text = getString(R.string.msg_rfb_quality_hint)
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }

        root.addView(label(context, R.string.title_stream_resolution))
        root.addView(sizeSpinner)
        root.addView(label(context, R.string.title_image_quality))
        root.addView(qualityValue)
        root.addView(qualitySeek)
        root.addView(rawCheck)
        root.addView(hint)

        MaterialAlertDialogBuilder(context, R.style.AlertDialog_Dimmed)
                .setTitle(R.string.title_stream_quality)
                .setView(root)
                .setNegativeButton(R.string.title_cancel, null)
                .setPositiveButton(R.string.title_save) { _, _ ->
                    val picked = sizes[sizeSpinner.selectedItemPosition].second
                    profile.fRfbDesktopSize = picked != null
                    if (picked != null) {
                        profile.streamWidth = picked.first
                        profile.streamHeight = picked.second
                    }
                    profile.imageQuality = qualitySeek.progress

                    // The one value here which the save path reads from the live field
                    // rather than from the profile - it used to be a check box in the form -
                    // so both have to be moved, or the change is dropped on Save.
                    viewModel.useRawEncoding.value = rawCheck.isChecked
                    profile.useRawEncoding = rawCheck.isChecked

                    FloatingText.show(binding.root, R.string.msg_stream_quality_saved, FloatingText.LENGTH_SHORT)
                }
                .showTracked(this)
    }

    private fun dismiss() = parentFragmentManager.popBackStack()

    private fun save() {
        if (!validate()) {
            highlightFieldWithError()
            return
        }
        homeViewModel.saveProfile(viewModel.prepareProfileForSave())
        dismiss()
    }

    private fun validate(): Boolean {
        var result = validateNotEmpty(binding.host) and validateNotEmpty(binding.port)
        result = result and validateTcpPort(binding.port)

        // The clipboard bridge dials a second, independent socket. A port out of
        // range there does not fail the session - it just quietly never connects,
        // so it has to be caught here while the user is still looking at the field.
        if (binding.clipboardPort.isVisible)
            result = result and validateTcpPort(binding.clipboardPort)

        // RFB repeater / SSH tunnel are not used by the Moonlight kernel; only
        // the Moonlight-relevant fields (host, port, optional WoL) are validated.
        if (binding.wol.isChecked) {
            result = result and validateWolMac() and validateWolBroadcastAddress() and validateWolPort()
        }

        return result
    }

    /**
     * A TCP port has to be a real one. Nothing here stops a hand-edited import from putting
     * a `0` or a `99999` in the database - [ServerProfile.sanitize] does that when the row
     * is read back - but a port outside 1..65535 is a connection that cannot be made, so
     * the user is told while the field is still on screen.
     */
    private fun validateTcpPort(field: EditText): Boolean {
        val text = field.text.toString().trim()
        if (text.isEmpty())
            return true // Covered by validateNotEmpty

        val port = text.toIntOrNull()
        if (port != null && port in 1..65535) {
            field.error = null
            return true
        }
        field.error = getText(R.string.msg_invalid_port)
        return false
    }

    private fun highlightFieldWithError() {
        binding.scrollView.descendants.find { it.isVisible && it is TextView && it.error != null }?.let {
            // Try to focus the View, which would also reveal it.
            // If View isn't focusable, manually reveal  it.
            if (!it.requestFocus())
                scrollToView(it)
        }
    }

    private fun scrollToView(view: View) {
        if (Build.VERSION.SDK_INT >= 29)
            binding.scrollView.scrollToDescendant(view)
        else
            binding.scrollView.requestChildFocus(null, view) // Wierd but effective workaround
    }


    /**
     * Six boxes are only an address when all of them are filled, so the check runs
     * against the joined value and the complaint is placed on the first box - the one
     * the user has to go back to.
     */
    private fun validateWolMac(): Boolean {
        val mac = currentMac()
        viewModel.profile.wolMAC = mac
        if (mac.isValidMacAddress()) {
            macFields.forEach { it.error = null }
            return true
        }
        macFields.first().error = getText(R.string.msg_invalid_mac_address)
        return false
    }

    /**
     * Optional, but whatever is there has to be an address a packet can be sent to.
     * `255.255.255.255` counts: it means "use whatever the system picks".
     */
    private fun validateWolBroadcastAddress(): Boolean {
        val address = binding.wolBroadcastAddress.text.toString().trim()
        if (address.isEmpty() || LocalNetwork.isIpv4(address)) {
            binding.wolBroadcastAddress.error = null
            return true
        }
        binding.wolBroadcastAddress.error = getText(R.string.msg_invalid_ipv4_address)
        return false
    }

    private fun validateWolPort(): Boolean {
        val port = binding.wolPort.text.toString().trim().toIntOrNull()
        if (port != null && port in 1..65535) {
            binding.wolPort.error = null
            return true
        }
        binding.wolPort.error = getText(R.string.msg_invalid_port)
        return false
    }

    private fun importPrivateKey(uri: Uri?) {
        if (uri == null)
            return

        // SSH tunneling was removed from this build, so there is no consumer for a
        // private key. It used to be read into the profile anyway - a real secret
        // written to the database, to backups and to exports for a feature that can
        // never use it. Say so instead of storing it.
        MsgDialog.show(parentFragmentManager, getString(R.string.title_private_key),
                getString(R.string.msg_ssh_key_import_unsupported))
    }

    private class EditorViewModelFactory(private val fragment: Fragment) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = fragment.requireActivity().application
            val handle = extras.createSavedStateHandle()
            val profile = getProfileArg(fragment)
            return modelClass.cast(EditorViewModel(app, handle, profile))!!
        }
    }
}