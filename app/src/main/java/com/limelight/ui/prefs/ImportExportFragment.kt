/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.ActivityNotFoundException
import android.os.Bundle
import com.limelight.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.limelight.R
import com.limelight.databinding.FragmentImportExportBinding
import com.limelight.util.DeviceAuthPrompt
import com.limelight.util.FloatingText
import com.limelight.util.MsgDialog
import com.limelight.util.OpenableDocument
import com.limelight.viewmodel.PrefsViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup & restore: export of saved server profiles and settings to backup files,
 * and their import back.
 *
 * Note: AVNC's QR-code based import/export has been removed (the ZXing
 * dependency is not part of this build); only file-based import/export remains.
 */
@Keep
class ImportExportFragment : Fragment() {

    private enum class Tag { Import, Export, EnableProtection, DisableProtection }

    private val importFilePicker = registerForActivityResult(OpenableDocument()) { import(it) }
    private val exportFilePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { export(it) }

    private lateinit var binding: FragmentImportExportBinding
    private val viewModel by activityViewModels<PrefsViewModel>()
    private val authPrompt by lazy { DeviceAuthPrompt(requireActivity()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentImportExportBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        binding.importBtn.setOnClickListener { checkAuthAndStart(Tag.Import) }
        binding.exportBtn.setOnClickListener { checkAuthAndStart(Tag.Export) }

        // QR-based import/export is not supported in this build.
        binding.importQrBtn.isVisible = false
        binding.exportQrBtn.isVisible = false

        // Password protection switch: neither direction moves without the device credential.
        // The "password" is the lock-screen PIN / pattern / password / fingerprint, verified
        // through DeviceAuthPrompt - AVNC's model - rather than a separately stored secret.
        binding.backupPasswordSwitch.isChecked = viewModel.backupPasswordEnabled.value ?: false
        // Driven from a click rather than a checked-change listener: the tap has already flipped
        // the check, so it is undone at once and only re-applied from the auth success callback
        // below. There is no programmatic-toggle flag to get stuck and silently swallow a later
        // tap - the switch can never move without the user authenticating.
        binding.backupPasswordSwitch.setOnClickListener {
            val switch = binding.backupPasswordSwitch
            val desired = switch.isChecked
            switch.isChecked = !desired
            val tag = if (desired) Tag.EnableProtection else Tag.DisableProtection
            authPrompt.launch(getString(R.string.msg_backup_password_toggle_auth), tag)
        }

        viewModel.importExportFinishedEvent.observe(viewLifecycleOwner) { handleImportExportResult(it) }

        authPrompt.init(
                onSuccess = { tag ->
                    when (tag as Tag) {
                        Tag.Import, Tag.Export -> start(tag)
                        Tag.EnableProtection -> applyProtectionEnabled(true)
                        Tag.DisableProtection -> applyProtectionEnabled(false)
                    }
                },
                onFail = { error ->
                    showMsg(getString(R.string.error_export_auth_failed, error))
                }
        )

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(R.string.pref_import_export)
    }

    private fun showMsg(msg: CharSequence) {
        // The auth callback can outlive this fragment's view: the biometric prompt runs in
        // its own process, and the system may stop the activity (or the user may leave it)
        // while the prompt is up. `requireView()` would throw right there, on a path whose
        // only job is to report a failure.
        val view = view ?: return
        FloatingText.show(view, msg, FloatingText.LENGTH_LONG)
    }

    /**
     * Generates a name for export file.
     *
     * The date part is only decoration, but it comes from a locale-aware formatter and
     * some locales write dates with slashes (`2026/10/07`) - and a slash is a path
     * separator to the document provider, which turns the suggested name into a request
     * for a directory that does not exist. Anything that cannot appear in a file name is
     * replaced.
     */
    private fun generateFilename(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val ts = sdf.format(Date())
        val name = getString(R.string.app_name).replace(" ", "_")
        return "${name}_Backup_$ts.json"
    }

    /**
     * With backup protection on, the user proves the device credential - the lock-screen PIN /
     * pattern / password / fingerprint, through [DeviceAuthPrompt], exactly as AVNC does - before
     * anything is read out of or written into the settings. With protection off, or on a device
     * that has no lock credential to verify against, the operation runs directly.
     */
    private fun checkAuthAndStart(tag: Tag) {
        if (viewModel.backupPasswordEnabled.value == true && authPrompt.canLaunch())
            authPrompt.launch(getString(R.string.msg_export_auth_required), tag)
        else
            start(tag)
    }

    /**
     * Applies a protection-switch flip that survived the credential check: persist the flag and
     * move the switch to match. Reached only from the auth success callback, never from the tap.
     */
    private fun applyProtectionEnabled(enabled: Boolean) {
        viewModel.setBackupPasswordEnabled(enabled)
        binding.backupPasswordSwitch.isChecked = enabled
    }

    private fun start(tag: Tag) {
        // Same reason as in [showMsg]: a restored activity can replay a pending prompt, and
        // its success callback may arrive before this fragment is attached again.
        if (!isAdded) return
        when (tag) {
            Tag.Import -> launchFilePicker(importFilePicker, arrayOf("*/*"))
            Tag.Export -> launchFilePicker(exportFilePicker, generateFilename())
            // The protection-switch tags never reach here - [applyProtectionEnabled] handles
            // them on auth success. Listed to keep the match honest if a new tag is added.
            Tag.EnableProtection, Tag.DisableProtection -> Unit
        }
    }

    private fun <I> launchFilePicker(picker: ActivityResultLauncher<I>, args: I) {
        try {
            picker.launch(args)
        } catch (e: ActivityNotFoundException) {
            showMsg(getString(R.string.error_no_file_picker))
            Log.e("ImportExport", "no activity found for document picker", e)
        }
    }

    private fun import(uri: android.net.Uri?) {
        if (uri != null)
            viewModel.import(uri)
    }

    private fun export(uri: android.net.Uri?) {
        if (uri != null)
            viewModel.export(uri)
    }

    private fun handleImportExportResult(result: Result<String>) {
        result.onSuccess {
            if (it.isNotEmpty())
                showMsg(it)
        }.onFailure {
            MsgDialog.show(childFragmentManager, getString(R.string.title_error),
                    it.message ?: getString(R.string.error_import_export_failed))
            Log.e(javaClass.simpleName, "Import/Export error", it)
        }
    }
}