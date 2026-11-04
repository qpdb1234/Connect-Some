/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.limelight.R
import com.limelight.util.deleteTrustedCertificates
import com.limelight.util.FloatingText
import com.limelight.util.forgetKnownHosts
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ForgetKnownHostsDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_forget_known_hosts)
                .setMessage(R.string.pref_forget_known_hosts_question)
                .setPositiveButton(R.string.title_forget) { _, _ -> forget() }
                .setNegativeButton(R.string.title_cancel) { _, _ -> }
                .create()
    }

    private fun forget() {
        val success = forgetKnownHosts(requireContext()) && deleteTrustedCertificates(requireContext())
        val view: View? = parentFragment?.view ?: requireActivity().findViewById(android.R.id.content)
        if (view != null) {
            if (success)
                FloatingText.show(view, R.string.msg_done, FloatingText.LENGTH_SHORT)
            else
                FloatingText.show(view, R.string.error_forget_hosts_failed, FloatingText.LENGTH_LONG)
        }
    }
}