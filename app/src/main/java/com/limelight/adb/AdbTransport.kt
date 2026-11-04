/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

/**
 * How the ADB host reaches the controlled device.
 *
 * USB is the primary path: it needs no prior authorization record and no pairing,
 * the target simply asks the user to trust this computer once. TCP is the wireless
 * path and requires that [AdbKeyPair]'s public key already be in the device's
 * allowed list - which is why the UI offers "plug in once, then go wireless"
 * via [Adb.restartOnTcpip].
 */
sealed interface AdbTransport {

    /** USB-OTG cable to the device with this `UsbDevice.deviceName`. */
    data class Usb(val deviceName: String) : AdbTransport

    /** An `adbd` listening on [host]:[port] (5555 for `adb tcpip` mode). */
    data class Tcp(val host: String, val port: Int) : AdbTransport
}
