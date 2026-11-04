/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.home

import android.view.View
import android.view.ViewGroup
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.limelight.R
import com.limelight.databinding.TabDiscoveredServersBinding
import com.limelight.databinding.TabSavedServersBinding
import com.limelight.model.ServerProfile
import com.limelight.util.FloatingText
import com.limelight.util.LocalNetwork
import com.limelight.util.setClipboardTextWithNotification
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * This class creates and manages tabs in [HomeActivity].
 * Tabs:
 *        1. Saved servers
 *        2. Discovered servers
 */
class ServerTabs(val activity: HomeActivity) {

    private lateinit var savedServersTab: TabLayout.Tab
    private lateinit var discoveredServersTab: TabLayout.Tab

    /**
     * Creates and initializes tabs
     *
     * [tabLayout] Hosts tabs
     * [pager] Hosts actual content views
     */
    fun create(tabLayout: TabLayout, pager: ViewPager2) {
        pager.adapter = PagerAdapter()
        pager.offscreenPageLimit = 1  // Tell pager to initialize & keep both tabs in memory

        TabLayoutMediator(tabLayout, pager) { _, _ -> }
                .attach()

        savedServersTab = tabLayout.getTabAt(0)!!
        savedServersTab.setIcon(R.drawable.ic_computer)
        savedServersTab.setContentDescription(R.string.desc_saved_servers_tab)

        discoveredServersTab = tabLayout.getTabAt(1)!!
        discoveredServersTab.setIcon(R.drawable.ic_search)
        discoveredServersTab.setContentDescription(R.string.desc_discovered_servers_tab)

        //ViewPager2 uses a RecyclerView internally, and sets it's DescendantFocusability
        //to 'FOCUS_BEFORE_DESCENDANTS', effectively breaking navigation via D-pad or arrow keys.
        pager.forEach { (it as ViewGroup).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
    }

    fun showSavedServers() {
        savedServersTab.select()
    }

    fun updateDiscoveryBadge(count: Int) {
        //Currently, we are not showing the actual count in the badge.
        //But maybe we could implement a preference???
        discoveredServersTab.getOrCreateBadge().isVisible = (count != 0)
    }


    /**
     * Adapter for our pager.
     *
     * We have fixed number of static views so our implementation is really simple.
     * We override [getItemViewType] to use the given position itself as view type.
     * Then, in [onCreateViewHolder], that view type (i.e. position) is used to
     * generate corresponding view.
     *
     * As a result, nothing else needs to be done by [ViewHolder] & [bindViewHolder].
     */
    private inner class PagerAdapter : RecyclerView.Adapter<PagerAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = 2
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return when (viewType) {
                0 -> ViewHolder(createSavedServersView(parent))
                1 -> ViewHolder(createDiscoveredServersView(parent))
                else -> throw IllegalStateException("Unexpected view type: [$viewType]")
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
    }

    private fun createSavedServersView(parent: ViewGroup): View {
        val viewModel = activity.viewModel
        val binding = TabSavedServersBinding.inflate(activity.layoutInflater, parent, false)
        binding.lifecycleOwner = activity
        binding.viewModel = viewModel

        binding.servers.onServerClick = { viewModel.startConnection(it) }
        binding.servers.onWakeServer = { viewModel.wakeProfile(it) }
        binding.servers.onEditServer = { viewModel.onEditProfile(it) }
        binding.servers.onDuplicateServer = { viewModel.onDuplicateProfile(it) }
        binding.servers.onDeleteServer = { viewModel.deleteProfile(it) }
        binding.servers.onCopyServerHost = { activity.setClipboardTextWithNotification(it.host) }

        // Multi-select delete
        binding.servers.onDeleteSelected = { viewModel.deleteProfiles(it) }
        binding.servers.selectionActive.observe(activity) { active ->
            binding.selectionBar.isVisible = active
            binding.addFab.isVisible = !active
        }
        binding.servers.selectionCount.observe(activity) { count ->
            binding.selectionCount.text = activity.getString(R.string.msg_selected_count, count)
            binding.btnDeleteSelected.isEnabled = count > 0
        }
        binding.btnDeleteSelected.setOnClickListener { binding.servers.deleteSelection() }
        binding.btnCancelSelection.setOnClickListener { binding.servers.clearSelection() }

        binding.servers.setSource(activity, viewModel.serverProfiles, viewModel.rediscoveredProfiles)
        return binding.root
    }

    private fun createDiscoveredServersView(parent: ViewGroup): View {
        val viewModel = activity.viewModel
        val binding = TabDiscoveredServersBinding.inflate(activity.layoutInflater, parent, false)
        binding.lifecycleOwner = activity
        binding.viewModel = viewModel

        // A discovered host may not be paired with us yet, and connecting without
        // pairing always fails on GameStream/Sunshine. If an equivalent profile is
        // already saved (and presumably paired), connect with it; otherwise open a
        // prefilled editor so the user can pair and save first.
        // "Equivalent" includes the protocol: a VNC server on the same machine as a
        // paired Sunshine host must not borrow that host's GameStream profile.
        //
        // Only a card with a service behind it reaches this callback - see
        // DiscoveredDeviceList - which is why the service is looked for rather than assumed.
        binding.servers.onDeviceClick = { device ->
            val discovered = device.services.firstOrNull()
            if (discovered != null) {
                val saved = viewModel.serverProfiles.value?.firstOrNull {
                    it.host == discovered.host && it.protocol == discovered.protocol
                }
                if (saved != null) viewModel.startConnection(saved)
                else viewModel.onNewProfile(discovered)
            }
        }
        binding.servers.onSaveDevice = { device -> device.services.firstOrNull()?.let { viewModel.onNewProfile(it) } }

        // The scan reports machines we could not reach a service on, but the address is
        // solid: writing it down by hand is the user's call. The editor opens on the protocol
        // the port already tells us - a card whose probe found a service carries that service's
        // protocol and port (a Sunshine answer is GameStream, a 590x answer is RFB) - and only a
        // card with no service, hence no port to match, falls back to RFB.
        binding.servers.onAddDeviceManually = { device ->
            val service = device.services.firstOrNull()
            viewModel.onNewProfile(ServerProfile(host = device.address,
                    protocol = service?.protocol ?: ServerProfile.PROTOCOL_VNC,
                    port = service?.port ?: ServerProfile.defaultPortFor(ServerProfile.PROTOCOL_VNC)))
        }

        // Same hand-written add, but carrying the hardware address the scan learned: waking
        // the machine is then a matter of pressing the switch instead of reading a label off
        // the box and typing six pairs of hex digits. When no source found the address, the
        // editor is opened anyway - WoL on, host filled in - and asked to run its own lookup
        // with that host, because making the user hunt for the scan button afterwards is the
        // longer way round for the same click.
        binding.servers.onAddDeviceWithWol = { device ->
            // The one-click card already knows the address and, usually, the MAC; deriving the
            // broadcast from the subnet this device can see means the created profile can wake
            // the machine without the user pressing anything. Left blank when the target is not
            // a plain IPv4 host on a known local network - the editor's own fill button then
            // remains the way to set it.
            val local = LocalNetwork.forTarget(activity.applicationContext, device.address)
            val broadcast = if (local != null && LocalNetwork.isIpv4(device.address))
                LocalNetwork.broadcast(device.address, local.prefixLength) else ""
            // Same protocol choice as the plain add: the port the scan reached fixes the
            // protocol, and only a card with no service - no port to match - falls back to RFB.
            val service = device.services.firstOrNull()
            val profile = ServerProfile(host = device.address,
                    protocol = service?.protocol ?: ServerProfile.PROTOCOL_VNC,
                    port = service?.port ?: ServerProfile.defaultPortFor(ServerProfile.PROTOCOL_VNC),
                    enableWol = true, wolMAC = device.macAddress,
                    wolBroadcastAddress = broadcast)
            if (device.macAddress.isBlank()) viewModel.onNewProfileWithMacScan(profile)
            else viewModel.onNewProfile(profile)
        }

        binding.servers.onCopyDeviceName = { activity.setClipboardTextWithNotification(it.name) }
        binding.servers.onCopyDeviceHost = { activity.setClipboardTextWithNotification(it.address) }
        binding.servers.onCopyDeviceMac = { device ->
            // The entry stays on the menu even when the scan came up empty, so that a missing
            // address is something the app says rather than an option that quietly vanished.
            if (device.macAddress.isBlank())
                FloatingText.show(binding.root, R.string.msg_mac_not_found, FloatingText.LENGTH_LONG)
            else
                activity.setClipboardTextWithNotification(device.macAddress)
        }

        binding.servers.setSource(activity, viewModel.discovery.devices)
        return binding.root
    }
}