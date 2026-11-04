/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.home

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.limelight.R
import com.limelight.databinding.ActivityUrlBinding
import com.limelight.ui.vnc.startVncActivity
import com.limelight.util.AccentTheme
import com.limelight.util.EdgeToEdgeHelper
import com.limelight.util.setClipboardTextWithNotification
import com.limelight.viewmodel.HomeViewModel
import com.limelight.viewmodel.UrlBarViewModel

/**
 * Search screen for the saved servers.
 *
 * Typing filters the saved profiles by name, host or SSH host in real time;
 * tapping a result connects to that server. Entering an address to connect
 * directly is intentionally not part of this screen.
 */
class UrlBarActivity : AppCompatActivity() {

    private val viewModel by viewModels<UrlBarViewModel>()
    private val homeViewModel by viewModels<HomeViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.attach(this)
        super.onCreate(savedInstanceState)

        val binding = EdgeToEdgeHelper.setDataBindingContentView<ActivityUrlBinding>(this, R.layout.activity_url)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this

        binding.backBtn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.clearBtn.setOnClickListener {
            if (binding.url.text.isEmpty())
                finish()
            else
                binding.url.setText("")
        }
        binding.searchSettingsBtn.setOnClickListener { showSearchFieldsDialog() }

        binding.servers.setSource(this, viewModel.filteredServers, homeViewModel.rediscoveredProfiles)
        binding.servers.itemAnimator?.addDuration = 0 // Disable "flashing" of added items
        binding.servers.onCopyServerName = { setClipboardTextWithNotification(it.name) }
        binding.servers.onCopyServerHost = { setClipboardTextWithNotification(it.host) }
        binding.servers.onServerClick = {
            startVncActivity(this, it)
            finish()
        }

        binding.url.requestFocus()
    }

    /**
     * The search-settings dialog: one checkbox per searchable aspect of a server. Ticking or
     * unticking writes the preference immediately, and the result list re-filters live, so
     * there is nothing to apply - `OK` only closes. The neutral button flips between select-
     * all and clear-all; because a dialog's buttons are built once, it dismisses and reopens
     * itself so the checkmarks and that button's own label come back in step.
     */
    private fun showSearchFieldsDialog() {
        val definitions = viewModel.fieldDefinitions()
        val labels = definitions.map { getString(it.labelRes) }.toTypedArray()
        val checked = definitions.map { viewModel.isFieldEnabled(it.key) }.toBooleanArray()
        val allSelected = viewModel.isAllEnabled()

        AlertDialog.Builder(this)
                .setTitle(R.string.title_search_fields)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    viewModel.setFieldEnabled(definitions[which].key, isChecked)
                }
                .setNeutralButton(if (allSelected) R.string.title_search_select_none
                                  else R.string.title_search_select_all) { dialog, _ ->
                    viewModel.setAllFields(!allSelected)
                    dialog.dismiss()
                    showSearchFieldsDialog()
                }
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                .show()
    }
}
