/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import com.limelight.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.limelight.R
import com.limelight.util.ImmersiveDialogHost
import com.limelight.util.holdImmersiveIfNeeded
import com.limelight.util.releaseImmersiveHold
import com.limelight.viewmodel.VncViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * This dialog is used to get user-confirmation about something before continuing.
 */
class ConfirmationDialog : DialogFragment() {
    val viewModel by activityViewModels<VncViewModel>()

    /** Keep the nav bars hidden only when the host is a fullscreen stream (see [ImmersiveDialogHost]). */
    private val immersive get() = (requireActivity() as? ImmersiveDialogHost)?.immersiveDialogs == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null && viewModel.confirmationRequest.value == null) {
            Log.i(javaClass.simpleName, "Activity is being recreated and old ViewModel is gone, removing stale dialog")
            showsDialog = false
            // The host can be restored in a state where its own instance state is already
            // written - a rotation while stopped, a task restored from recents. A plain
            // `dismiss()` commits there and throws.
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val data = viewModel.confirmationRequest.value
        check(data != null) { "Confirmation dialog started without message!" }

        return MaterialAlertDialogBuilder(requireContext())
                .setTitle(data.first)
                .setMessage(data.second)
                .setPositiveButton(R.string.title_continue) { _, _ -> viewModel.confirmationRequest.offerResponse(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> doCancel() }
                .create().apply {
                    setCanceledOnTouchOutside(false)
                    holdImmersiveIfNeeded(immersive)
                }
    }

    override fun onStart() {
        super.onStart()
        if (immersive) dialog?.releaseImmersiveHold()
    }

    override fun onCancel(dialog: DialogInterface) {
        doCancel()
    }

    private fun doCancel() {
        viewModel.confirmationRequest.offerResponse(false)
        requireActivity().finish()
    }
}