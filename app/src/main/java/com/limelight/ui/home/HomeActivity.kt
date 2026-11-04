/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.home

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.limelight.R
import com.limelight.databinding.ActivityHomeBinding
import com.limelight.model.ServerProfile
import com.limelight.ui.about.AboutActivity
import com.limelight.ui.prefs.PrefsActivity
import com.limelight.ui.vnc.startVncActivity
import com.limelight.util.AccentTheme
import com.limelight.util.EdgeToEdgeHelper
import com.limelight.util.FloatingText
import com.limelight.viewmodel.HomeViewModel
import com.limelight.viewmodel.service.DiscoveredDevice
import com.limelight.viewmodel.service.Discovery

/**
 * Primary activity of the app.
 *
 * It Provides access to saved and discovered servers.
 */
class HomeActivity : AppCompatActivity() {
    val viewModel by viewModels<HomeViewModel>()
    private lateinit var binding: ActivityHomeBinding
    private val tabs = ServerTabs(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.attach(this)
        super.onCreate(savedInstanceState)

        //View Inflation
        binding = EdgeToEdgeHelper.setDataBindingContentView(this, R.layout.activity_home)
        binding.lifecycleOwner = this

        tabs.create(binding.tabLayout, binding.pager)

        binding.aboutBtn.setOnClickListener { showAbout() }
        binding.urlbar.setOnClickListener { showSearch() }
        binding.localsendBtn.setOnClickListener { showLocalSend() }
        binding.settingsBtn.setOnClickListener { showSettings() }

        //Observers
        viewModel.editProfileEvent.observe(this) { showProfileEditor(it) }
        viewModel.profileSavedEvent.observe(this) { onProfileInserted(it) }
        viewModel.profileDeletedEvent.observe(this) { onProfileDeleted(it) }
        viewModel.profilesDeletedEvent.observe(this) { onProfilesDeleted(it) }
        viewModel.wakeResultEvent.observe(this) { onWakeAttempt(it) }
        viewModel.newConnectionEvent.observe(this) { startNewConnection(it) }
        viewModel.discovery.devices.observe(this) { updateDiscoveryBadge(it) }
        viewModel.discovery.roundFinished.observe(this) { showScanResult(it) }
        viewModel.discovery.vpnInterference.observe(this) { showVpnHint() }
        viewModel.serverProfiles.observe(this) { updateShortcuts(this, it) }

        setupSplashTheme()
        maybeAutoConnect(savedInstanceState == null)
    }

    override fun onStop() {
        super.onStop()
        // No onStart counterpart: discovery is manual now, so nothing may begin by
        // itself just because the screen opened.
        if (!isChangingConfigurations)
            viewModel.stopDiscovery()
    }

    private fun startNewConnection(profile: ServerProfile) {
        startVncActivity(this, profile)
    }

    /**
     * Launches Settings activity
     */
    private fun showSettings() {
        startActivity(Intent(this, PrefsActivity::class.java))
    }

    /**
     * Launches About activity (top-left button on the home screen).
     */
    private fun showAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
    }

    /**
     * Launches the search activity for saved servers (tapping the url bar).
     */
    private fun showSearch() {
        startActivity(Intent(this, UrlBarActivity::class.java))
    }

    /**
     * Launches the standalone LocalSend screen (the share icon on the home bar): discovery,
     * receiving and sending live there without needing a running stream session.
     */
    private fun showLocalSend() {
        startActivity(Intent(this, LocalSendActivity::class.java))
    }

    private fun showProfileEditor(profile: ServerProfile) {
        startProfileEditor(this, profile)
    }

    private fun onProfileInserted(profile: ServerProfile) {
        tabs.showSavedServers()

        // Show snackbar for new servers
        if (!profile.isSaved())
            FloatingText.show(binding.root, R.string.msg_server_profile_added, FloatingText.LENGTH_SHORT)
    }

    /**
     * Shows delete confirmation snackbar, allowing the user to Undo deletion.
     */
    private fun onProfileDeleted(profile: ServerProfile) {
        FloatingText.show(binding.root, R.string.msg_server_profile_deleted, FloatingText.LENGTH_LONG,
                actionLabel = getString(R.string.title_undo), action = { viewModel.saveProfile(profile) })
    }

    /**
     * Bulk undo for multi-select deletion. One Snackbar restores all deleted cards.
     */
    private fun onProfilesDeleted(profiles: List<ServerProfile>) {
        val message = resources.getQuantityString(R.plurals.msg_servers_deleted, profiles.size, profiles.size)
        FloatingText.show(binding.root, message, FloatingText.LENGTH_LONG,
                actionLabel = getString(R.string.title_undo), action = { viewModel.saveProfiles(profiles) })
    }

    /**
     * Reports the outcome of the wake button. The packet reaching the network says
     * nothing about the machine actually powering on, so this stays a plain statement
     * of what was attempted rather than a claim of success.
     */
    private fun onWakeAttempt(message: String) {
        FloatingText.show(binding.root, message, FloatingText.LENGTH_LONG)
    }

    private fun updateDiscoveryBadge(list: List<DiscoveredDevice>) {
        // The badge means "there is something you can connect to on the other tab", not
        // "the scan filled a page": a router or a printer is not news worth a dot.
        tabs.updateDiscoveryBadge(list.count { it.hasService })
    }

    /**
     * Says out loud what a finished round found. Its cards appear one by one while the
     * scan runs, so anyone who looked away has no reason to believe anything arrived -
     * and "nothing at all" is an answer that the empty list on screen does not give.
     */
    private fun showScanResult(summary: Discovery.ScanSummary) {
        FloatingText.show(binding.root, viewModel.scanResultText(summary.total, summary.connectable),
                FloatingText.LENGTH_LONG)
    }

    /**
     * Warns that a VPN is hijacking the LAN probes. The scan pins every probe to the
     * physical network and drops what it cannot confirm there, so the fake hosts a VPN's
     * local proxy would otherwise accept are gone - but the list can then be shorter than
     * it looks with the VPN off, which is worth saying rather than leaving the user to guess.
     */
    private fun showVpnHint() {
        FloatingText.show(binding.root, R.string.msg_scan_vpn_hint, FloatingText.LENGTH_LONG)
    }

    /**
     * Updates splash theme to match with app theme
     */
    private fun setupSplashTheme() {
        if (Build.VERSION.SDK_INT < 31)
            return

        viewModel.pref.ui.theme.observe(this) {
            when (it) {
                "light" -> splashScreen.setSplashScreenTheme(R.style.App_SplashTheme_Light)
                "dark" -> splashScreen.setSplashScreenTheme(R.style.App_SplashTheme_Dark)
                else -> splashScreen.setSplashScreenTheme(R.style.App_SplashTheme)
            }
        }
    }

    private fun maybeAutoConnect(isNewStart: Boolean) {
        if (isNewStart)
            viewModel.maybeConnectOnAppStart()
    }
}