/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * Based in part on AVNC (GPL-3.0-or-later), ui/home/ServerList.kt: the RecyclerView
 * scaffolding - lifecycle owner, ListAdapter wiring, context-menu hookup - is its. The
 * discovered-device card content, the scan progress and the wake/save actions are this
 * app's.
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.databinding.DiscoveredDeviceItemBinding
import com.limelight.util.protocolDisplayName
import com.limelight.viewmodel.service.DiscoveredDevice

typealias DeviceItemAction = (device: DiscoveredDevice) -> Unit

/**
 * List of scan results.
 *
 * Separate from [ServerList] on purpose: not every row here is connectable. A card for a
 * machine with no supported service can only be looked at, and wiring that exception into
 * the saved-server widget would have meant teaching it a state it has no business
 * knowing about.
 */
class DiscoveredDeviceList(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
    : RecyclerView(context, attrs, defStyleAttr) {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    /**
     * Actions available on individual items. The connect/save actions only fire for a
     * card that actually has a service behind it.
     */
    var onDeviceClick: DeviceItemAction? = null
    var onSaveDevice: DeviceItemAction? = null
    var onAddDeviceManually: DeviceItemAction? = null
    var onAddDeviceWithWol: DeviceItemAction? = null
    var onCopyDeviceName: DeviceItemAction? = null
    var onCopyDeviceHost: DeviceItemAction? = null
    var onCopyDeviceMac: DeviceItemAction? = null

    var lifecycleOwner: LifecycleOwner? = null; private set

    init {
        setHasFixedSize(true)
        layoutManager = LinearLayoutManager(context)
        adapter = DiscoveredDeviceListAdapter(this)
    }

    fun setSource(owner: LifecycleOwner, source: LiveData<List<DiscoveredDevice>>) {
        lifecycleOwner = owner
        source.observe(owner) { (adapter as DiscoveredDeviceListAdapter).submitList(it) }
    }
}

private class DiscoveredDeviceListAdapter(private val list: DiscoveredDeviceList)
    : ListAdapter<DiscoveredDevice, DiscoveredDeviceViewHolder>(DeviceDiffer) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiscoveredDeviceViewHolder {
        val binding = DiscoveredDeviceItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false)
        binding.lifecycleOwner = list.lifecycleOwner
        return DiscoveredDeviceViewHolder(list, binding)
    }

    override fun onBindViewHolder(holder: DiscoveredDeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class DiscoveredDeviceViewHolder(private val list: DiscoveredDeviceList,
                                         private val binding: DiscoveredDeviceItemBinding)
    : RecyclerView.ViewHolder(binding.root) {

    init {
        binding.root.setOnCreateContextMenuListener { menu, _, _ -> prepareContextMenu(menu) }
        binding.saveBtn.setOnClickListener { device?.let { list.onSaveDevice?.invoke(it) } }
    }

    private var device: DiscoveredDevice? = null

    fun bind(device: DiscoveredDevice) {
        this.device = device
        binding.device = device

        val context = binding.root.context
        val service = device.services.firstOrNull()

        // A machine usually has neither a mDNS name nor a reverse name. Then its model
        // (from an mDNS TXT record) is the most specific thing to title it with, ahead of
        // the vendor behind its hardware address, which in turn beats a bare address.
        val title = device.name.ifBlank { device.model.ifBlank { device.vendor } }
        binding.name.text = title.ifBlank { context.getString(R.string.title_unnamed_device) }

        binding.address.text = if (service != null) {
            // The badge is the protocol's display name; the shared map covers GameStream, RFB
            // and ADB, so an ADB card is labelled ADB rather than falling through to GameStream.
            val protocol = protocolDisplayName(context, service.protocol)
            "$protocol ${service.displayAddress}"
        } else {
            device.address
        }

        // The third line says what model the machine is, who made it, what its hardware
        // address is, and - for a card that cannot be opened - why. A connectable card gets
        // no note: a plain device warning under it would read like a fault. Whatever is
        // already the title is not repeated below it.
        val detail = ArrayList<String>(3)
        if (device.model.isNotBlank() && device.model != title)
            detail.add(context.getString(R.string.label_discovered_model, device.model))
        if (device.name.isNotBlank() && device.vendor.isNotBlank() && device.vendor != title)
            detail.add(device.vendor)
        // The address line is there either way. Hiding it when nothing found the address made
        // the cards that most need explaining look like machines without a network identity,
        // and left the user guessing whether the scan had looked at all.
        detail.add(if (device.macAddress.isBlank()) context.getString(R.string.label_discovered_mac_unknown)
                else context.getString(R.string.label_discovered_mac, device.macAddress))
        // A card that folded one protocol's ports into a single entry says which ports it
        // stands for, so collapsing the duplicates never hides an entry point the user might
        // have wanted to reach directly. Shown whenever the card carries at least one port,
        // so a single-display VNC card states its 5900 the same way a GameStream card states
        // its 47984/47989, rather than leaving the port implicit.
        if (device.ports.isNotEmpty())
            detail.add(context.getString(R.string.label_discovered_ports, device.ports.joinToString(", ")))
        if (service == null) detail.add(context.getString(R.string.msg_discovered_no_service))
        binding.detail.text = detail.joinToString("\n")
        binding.detail.isVisible = detail.isNotEmpty()

        // A card with no service has nothing to open; offering it as tappable would
        // promise a connection that cannot work. The order matters: setting a click
        // listener switches the view clickable again, so the flags go after it. The long
        // press stays available either way - that is where the copy and manual-add
        // actions live.
        binding.root.setOnClickListener {
            if (device.hasService) list.onDeviceClick?.invoke(device)
        }
        binding.root.isClickable = device.hasService
        binding.root.isLongClickable = true
    }

    private fun prepareContextMenu(menu: ContextMenu) {
        val current = device ?: return
        val context = binding.root.context

        if (current.name.isNotBlank() && list.onCopyDeviceName != null)
            menu.add(context.getString(R.string.title_copy_name))
                    .setOnMenuItemClickListener { list.onCopyDeviceName?.invoke(current); true }

        // A discovered card is always an address, never a name someone typed, so it says IP.
        // The saved-server list keeps the looser wording because a saved host can be a hostname.
        if (list.onCopyDeviceHost != null)
            menu.add(context.getString(R.string.title_copy_ip_address))
                    .setOnMenuItemClickListener { list.onCopyDeviceHost?.invoke(current); true }

        // Both entries are offered whether or not the scan found an address: a missing one is
        // an answer the two handlers can state, where a menu that changes shape per card
        // teaches the user nothing about why an option is gone.
        if (list.onCopyDeviceMac != null)
            menu.add(context.getString(R.string.title_copy_mac))
                    .setOnMenuItemClickListener { list.onCopyDeviceMac?.invoke(current); true }

        // Waking a sleeping machine needs the address the magic packet is written to, and a
        // scan card can hand that straight to the new server instead of making the user type
        // it. Without one, the same entry opens the editor with Wake-on-LAN already switched
        // on and its own lookup running, which is still less work than finding the fields.
        if (list.onAddDeviceWithWol != null)
            menu.add(context.getString(R.string.title_add_server_with_wol))
                    .setOnMenuItemClickListener { list.onAddDeviceWithWol?.invoke(current); true }

        // The machine may well offer a service our probes could not reach (a firewall)
        // while announcing nothing: writing it down by hand is the user's call, not ours.
        if (list.onAddDeviceManually != null)
            menu.add(context.getString(R.string.title_add_as_server))
                    .setOnMenuItemClickListener { list.onAddDeviceManually?.invoke(current); true }
    }
}

private object DeviceDiffer : DiffUtil.ItemCallback<DiscoveredDevice>() {
    // The address plus its service identifies the card: the same host showing up as a
    // second VNC display is a second card, and the same card must not animate when only
    // its MAC address arrived a moment later.
    override fun areItemsTheSame(old: DiscoveredDevice, new: DiscoveredDevice) =
            old.address == new.address && old.services == new.services

    override fun areContentsTheSame(old: DiscoveredDevice, new: DiscoveredDevice) = old == new
}