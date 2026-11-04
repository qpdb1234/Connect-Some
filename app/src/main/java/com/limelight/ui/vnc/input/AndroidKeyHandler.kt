/*
 * Android-device keyboard handler.
 *
 * The third protocol does not want keysyms or XT scancodes: the helper process on
 * the controlled device injects a [KeyEvent] from a raw Android key code, so this
 * handler forwards [KeyEvent.keyCode] almost verbatim - the translation work that
 * [KeyHandler] and [MoonlightKeyHandler] do disappears along with the tables they
 * need.
 *
 * The cost is expressiveness. The wire has a single "press" packet: the helper
 * posts a down and an up back to back, which means
 *
 *  - a key can never be *held* (no analog stick, no shift-and-type in the remote
 *    game), and
 *  - Android's auto-repeat would fire one press per repeat event,
 *
 * so repeats are swallowed here and the limitation is spelled out to the user in
 * `msg_android_key_limit`. Characters that have no key code at all (most of
 * Unicode, typed from a soft keyboard) cannot be injected as keys either; those go
 * to the device's clipboard (`msg_android_text_via_clipboard`), which is the only
 * text channel this protocol has.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */
package com.limelight.ui.vnc.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.limelight.R
import com.limelight.App

/**
 * @param dispatcher the session's dispatcher, which reaches the live client
 */
class AndroidKeyHandler(private val dispatcher: Dispatcher) : KeyTransport {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Navigation-bar keys (the software Back button) belong to this Activity;
        // forwarding them would move the *remote* device backwards.
        if (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0)
            return false

        return when (event.action) {
            // There is no release to forward: the helper produces its own.
            KeyEvent.ACTION_DOWN -> handlePress(event)
            KeyEvent.ACTION_MULTIPLE -> handleMultiple(event)
            else -> false
        }
    }

    /**
     * The on-screen keys deliver ordinary [KeyEvent]s, so they need no extra
     * treatment: their key code is already an Android one and their meta state is
     * carried on the event.
     */
    override fun onVkKeyEvent(event: KeyEvent): Boolean = onKeyEvent(event)

    private fun handlePress(event: KeyEvent): Boolean {
        // Android repeats a held key with fresh ACTION_DOWN events. Each of those
        // would become another full press on the device, so only the first goes out.
        if (event.repeatCount > 0)
            return true

        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN)
            return dispatcher.sendAndroidKey(event.keyCode, metaStateOf(event))

        // No key code: a printable character from a soft keyboard.
        val uChar = event.getUnicodeChar()
        if (uChar and KeyCharacterMap.COMBINING_ACCENT != 0)
            return false    // a dead key on its own, nothing to enter yet

        // Zero means "this key types nothing" (function keys, media keys, an unmapped
        // combination). Forwarding it would overwrite the device's clipboard with a NUL.
        if (uChar == 0 || uChar > Char.MAX_VALUE.code)
            return false

        return sendAsText(uChar.toChar().toString())
    }

    /**
     * Software keyboards send a run of characters as a single ACTION_MULTIPLE event
     * with no key code - one packet per character would just lose them.
     */
    private fun handleMultiple(event: KeyEvent): Boolean {
        val chars = event.characters ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN || chars.isEmpty())
            return false

        return sendAsText(chars)
    }

    /**
     * The event's own meta state is exactly what the device expects (it already
     * accounts for both physical modifiers and the shift state an IME reports for a
     * single character event), so it is forwarded unchanged.
     */
    private fun metaStateOf(event: KeyEvent): Int = event.metaState

    /**
     * Text that has no key code goes through the device clipboard. The helper's
     * paste action is a key press away (and the protocol cannot press-and-hold a
     * modifier for a `Ctrl+V`), so the user is told where the text landed.
     */
    private fun sendAsText(text: String): Boolean {
        if (text.isEmpty()) return false

        // A batched IME commit can be arbitrarily long, and each call below is one clipboard
        // write on the wire plus one toast on the screen. Anything of this size is a paste,
        // not typing, and belongs in the clipboard action of the toolbar instead.
        if (text.length > MAX_TEXT_INJECT_CHARS) {
            dispatcher.showMessage(App.appContext.getString(R.string.msg_text_too_long_for_keys))
            return true
        }

        dispatcher.sendClipboardText(text)
        dispatcher.showMessage(App.appContext.getString(R.string.msg_android_text_via_clipboard))
        return true
    }

    private companion object {
        /** Keeps the same budget as the virtual keyboard's text page. */
        const val MAX_TEXT_INJECT_CHARS = 4096
    }
}
