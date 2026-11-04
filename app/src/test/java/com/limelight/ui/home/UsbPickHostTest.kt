/**
 * The host field's response to a USB pick.
 *
 * A wired Android device is named `/dev/bus/usb/002/003`, and that name is the only
 * address the entry has while the cable is the transport - so the picker writes it into
 * the host field and takes it back out when the pick is removed. The one rule worth
 * pinning down is the one a test can check and a screenshot cannot: a hand-typed address
 * survives, because only a value a pick put there is ever removed.
 */
package com.limelight.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbPickHostTest {

    private val wire = "/dev/bus/usb/002/003"

    @Test
    fun pickingAWiredDeviceShowsItsNameInTheHostField() {
        assertEquals(wire, hostForUsbPick(wire, "", "", "192.168.1.40"))
    }

    @Test
    fun pickingADeviceOverwritesTheAddressOfTheLastPick() {
        assertEquals(wire, hostForUsbPick(wire, "/dev/bus/usb/001/004", "", "/dev/bus/usb/001/004"))
    }

    @Test
    fun choosingNoneTakesThePickBackOut() {
        assertEquals("", hostForUsbPick("", wire, "", wire))
    }

    @Test
    fun choosingNoneAlsoClearsThePickTheProfileWasOpenedWith() {
        assertEquals("", hostForUsbPick("", "", wire, wire))
    }

    @Test
    fun choosingNoneLeavesAHandTypedAddressAlone() {
        assertNull(hostForUsbPick("", wire, "", "192.168.1.40"))
    }

    @Test
    fun choosingNoneOnAnEmptyFieldChangesNothing() {
        assertNull(hostForUsbPick("", "", "", ""))
    }

    @Test
    fun choosingNoneKeepsAnAddressThePickWasNeverIn() {
        // A profile saved with a pick, but the field now holds something else entirely.
        assertNull(hostForUsbPick("", "", wire, "192.168.1.40"))
    }
}
