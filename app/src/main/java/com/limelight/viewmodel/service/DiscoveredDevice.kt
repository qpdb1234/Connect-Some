/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel.service

import com.limelight.model.ServerProfile

/**
 * One card of a scan result.
 *
 * Deliberately not a [ServerProfile]: a scan also turns up the router, a printer or a
 * camera, and those have an address and a hardware address but nothing this app can
 * connect to. Presenting them as servers would offer a connection that cannot work, so
 * they stay devices.
 *
 * [services] holds the connection endpoints this card stands for - currently at most
 * one, because a machine announcing both Sunshine and VNC is listed as two cards, the
 * same way it would be saved as two profiles. The list shape is kept because the
 * address, name and hardware address belong to the machine rather than to either of its
 * services, so both cards are built from one record.
 *
 * [vendor] is the company behind the hardware address, from the IEEE OUI table
 * (ning-style): a machine with no mDNS or reverse name is still recognizable as
 * "Xiaomi Communications" or "Apple, Inc." rather than being an anonymous IP.
 *
 * [model] is the device model an mDNS responder published in a TXT attribute (`model=` /
 * `md=`), the concrete phone or box name ("Pixel 8", "Chromecast") rather than just the
 * maker behind its MAC. Empty when nothing announced one.
 *
 * [ports] records every port the represented protocol answered on for this machine, so a
 * host offering the same service on several of them (a Sunshine server on 47984 and 47989,
 * a VNC display on 5900 and 5901) folds into a single card that can still show the full set.
 * It is empty when there is nothing to expand beyond the representative service.
 */
data class DiscoveredDevice(
        val address: String,
        val name: String,
        val macAddress: String,
        val vendor: String,
        val model: String,
        val services: List<ServerProfile>,
        val ports: List<Int> = emptyList()) {

    /** Whether tapping this card can lead anywhere. */
    val hasService: Boolean get() = services.isNotEmpty()
}
