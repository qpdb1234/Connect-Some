/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.input

import android.view.KeyEvent

/**
 * Common shape of the two keyboard handlers, so [InputHandler] can hold one
 * field without knowing which remote protocol is live.
 *
 * A protocol declares which implementation it wants through
 * [com.limelight.vnc.VncClient.inputPath]: RFB needs X keysyms
 * ([KeyHandler]), GameStream needs XT scancodes ([MoonlightKeyHandler]).
 * Nothing else in the input stack looks at the protocol.
 */
interface KeyTransport {

    /** Handles a key event coming from a physical keyboard or the system. */
    fun onKeyEvent(event: KeyEvent): Boolean

    /** Handles a key event synthesized by the on-screen virtual keys. */
    fun onVkKeyEvent(event: KeyEvent): Boolean

    /**
     * Forgets whatever key state was inferred from earlier events rather than carried on
     * one. Called when the viewer goes away: a key whose release was lost (focus stolen,
     * activity stopped) would otherwise stay pressed in our own bookkeeping forever, and
     * every later event would be sent with that phantom modifier attached.
     */
    fun onInputStateLost() {}
}
