/*
 * Moonlight/GameStream keyboard handler.
 *
 * AVNC's original [KeyHandler] emits RFB "X key symbols", which the Moonlight kernel
 * cannot use: GameStream expects a USB-HID style *scancode* ("key map") plus a modifier
 * bitmask, and for characters that have no scancode mapping it injects UTF-8 text so the
 * on-screen keyboard can type anything. This class mirrors moonlight-android Game.java
 * (handleKeyDown / handleKeyUp / handleKeyMultiple) as closely as the AVNC event plumbing
 * allows, translating the incoming [KeyEvent] through [KeyboardTranslator] and forwarding
 * the result to the kernel via [Dispatcher].
 *
 * The previous bridging relied on the raw hardware scan code (which is 0 for every soft
 * keyboard event), so no character ever reached the host - that is why typing appeared to
 * do nothing. Here we always derive a real scancode from the key code, exactly like
 * Moonlight does.
 */

package com.limelight.ui.vnc.input

import android.annotation.SuppressLint
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.jni.MoonBridge

class MoonlightKeyHandler(private val dispatcher: Dispatcher) : KeyTransport {

    private val translator = KeyboardTranslator()

    // Tracks physically-reported modifier keys (Ctrl/Shift/Alt/Meta). Some IMEs never send
    // standalone modifier events and instead set the meta flags on the character event, so
    // this complements (rather than replaces) the per-event modifier state below.
    private var modifierFlags = 0

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Navigation-bar / virtual hard keys (e.g. the software Back button) are handled
        // locally by the activity; they must not be forwarded to the host.
        if (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0)
            return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleKey(event, true)
            KeyEvent.ACTION_UP -> handleKey(event, false)
            KeyEvent.ACTION_MULTIPLE -> handleMultiple(event)
            else -> false
        }
    }

    /**
     * The on-screen keys are just another source of [KeyEvent]s for this transport:
     * the scancode is derived from the key code and the modifiers are read off the
     * event itself, so no extra bookkeeping is needed (unlike RFB's [KeyHandler],
     * which has to synthesize Shift/Ctrl/Alt presses).
     */
    override fun onVkKeyEvent(event: KeyEvent): Boolean = onKeyEvent(event)

    /**
     * [modifierFlags] mirrors physically held modifier keys, built one event at a time. A
     * modifier whose ACTION_UP never arrived - because the window lost focus mid-press -
     * would keep the host in a shifted state until the user happened to press and release
     * that same key again. Nothing on the phone is held any more once the viewer stops, so
     * the mirror simply starts over.
     */
    override fun onInputStateLost() {
        modifierFlags = 0
    }

    /**
     * Software keyboards deliver keys with no KEYCODE equivalent as a single
     * ACTION_MULTIPLE event carrying the typed characters. Send those verbatim as UTF-8,
     * just like Game.java's handleKeyMultiple.
     */
    private fun handleMultiple(event: KeyEvent): Boolean {
        val chars = event.characters
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN && !chars.isNullOrEmpty()) {
            // One character becomes a keystroke burst on the host, and the whole string is
            // handed to the kernel as a single allocation. A paste through an IME arrives
            // exactly this way and can be megabytes long, which is a self-inflicted stall of
            // the session rather than typing. Keep the bound generous: it only has to stop
            // a pathological paste, not a paragraph.
            if (chars.length > MAX_INJECTED_TEXT) return true
            dispatcher.sendTextInput(chars)
            return true
        }
        return false
    }

    @SuppressLint("InlinedApi")
    private fun handleKey(event: KeyEvent, isDown: Boolean): Boolean {
        val keyCode = event.keyCode
        updateModifierFlags(keyCode, isDown)

        val scancode = translator.translate(keyCode, event.deviceId)
        if (scancode.toInt() == 0) {
            // No raw-key mapping available: fall back to UTF-8 text injection for a
            // printable, non-dead character (mirrors Game.java's translated == 0 branch).
            val uChar = event.getUnicodeChar()
            if (isDown && uChar and KeyCharacterMap.COMBINING_ACCENT == 0 &&
                uChar and KeyCharacterMap.COMBINING_ACCENT_MASK != 0) {
                dispatcher.sendTextInput(uChar.toChar().toString())
                return true
            }
            return false
        }

        // The host auto-repeats a held key after the initial press, so eat the synthetic
        // repeat DOWN events Android keeps sending while the key is held.
        if (isDown && event.repeatCount > 0)
            return true

        val direction = if (isDown) KeyboardPacket.KEY_DOWN else KeyboardPacket.KEY_UP
        val modifier = getModifierState(event, keyCode)
        val flags: Byte = if (translator.hasNormalizedMapping(keyCode, event.deviceId))
            0 else MoonBridge.SS_KBE_FLAG_NON_NORMALIZED

        return dispatcher.sendKeyboardScancode(scancode, direction, modifier, flags)
    }

    private fun updateModifierFlags(keyCode: Int, isDown: Boolean) {
        val mask = modifierMaskFor(keyCode)
        if (mask == 0) return
        modifierFlags = if (isDown) modifierFlags or mask else modifierFlags and mask.inv()
    }

    /**
     * Combines the accumulated physical modifier state with the meta flags carried on the
     * event itself. IMEs that do not emit standalone Shift presses still mark the character
     * event as shift-pressed, which is what lets uppercase letters work.
     */
    private fun getModifierState(event: KeyEvent, keyCode: Int): Byte {
        var modifier = modifierFlags
        if (event.isShiftPressed) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        if (event.isCtrlPressed) modifier = modifier or KeyboardPacket.MODIFIER_CTRL.toInt()
        if (event.isAltPressed) modifier = modifier or KeyboardPacket.MODIFIER_ALT.toInt()
        if (event.isMetaPressed) modifier = modifier or KeyboardPacket.MODIFIER_META.toInt()
        // '+' shares the US '=' virtual key, so it needs Shift to be reported.
        if (keyCode == KeyEvent.KEYCODE_PLUS) modifier = modifier or KeyboardPacket.MODIFIER_SHIFT.toInt()
        return modifier.toByte()
    }

    private fun modifierMaskFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyboardPacket.MODIFIER_CTRL.toInt()
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyboardPacket.MODIFIER_SHIFT.toInt()
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyboardPacket.MODIFIER_ALT.toInt()
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyboardPacket.MODIFIER_META.toInt()
        else -> 0
    }

    private companion object {
        /** Longest string an IME may hand over in one ACTION_MULTIPLE event. */
        const val MAX_INJECTED_TEXT = 4096
    }
}
