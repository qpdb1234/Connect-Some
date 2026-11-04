/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.input

import android.os.SystemClock
import android.text.Editable
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import com.limelight.ui.vnc.input.ImeTextModel

/**
 * The editor-mode input connection behind [InputView], the fix for a phone keyboard whose
 * typed text and candidate word were both delivered to the host.
 *
 * It replaces the old [BaseInputConnection] fallback (`BaseInputConnection(view, false)`).
 * In fallback the framework keeps no real picture of the field: every `commitText`/
 * `setComposingText` collapses to "type this anew as key events" and nothing undoes the
 * keystrokes already sent, so "work" followed by the candidate "workbuddy" reached the host
 * as "workworkbuddy". Here the connection answers the IME as a genuine text editor -
 * [getEditable] returns null so the framework itself never edits anything, and every edit
 * instead updates an [ImeTextModel]. The model works out what the host should now hold and
 * emits only the difference (see [TextOp]), which is what makes the double typing go away
 * whichever timing the user picked.
 *
 * @param inputHandler where synthesized key events are pushed; protocol-agnostic like every
 *        other source of [KeyEvent]s in the viewer
 * @param modeProvider reads the chosen timing once per edit, so a Settings change is picked
 *        up by the next keystroke without rebuilding the connection
 */
class RemoteInputConnection(
        target: View,
        private val inputHandler: InputHandler,
        modeProvider: () -> ImeTextTiming,
) : BaseInputConnection(target, /* fullEditor = */ true) {

    private val model = ImeTextModel(modeProvider)
    private val keyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /** Clears the mirror. Called when a session ends so the next one starts from nothing. */
    fun reset() = model.reset()

    /*****************************************************************************************
     * Edits from the IME: each feeds the model, and the model's ops go to the host.
     *****************************************************************************************/

    override fun getEditable(): Editable? = null

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        emit(model.commit(text.toString()))
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        emit(model.setComposing(text.toString()))
        return true
    }

    override fun finishComposingText(): Boolean {
        emit(model.finishComposing())
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        emit(model.deleteBackward(beforeLength))
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        emit(model.deleteBackward(beforeLength))
        return true
    }

    /**
     * A phone keyboard delivers its structural keys here rather than through the text edits:
     * Backspace trims the mirror - and when it runs past what the mirror holds, the rest of the
     * deletion goes to the host as the backspaces the user pressed for, since the characters
     * being removed are the ones the field arrived with (see [ImeTextModel.deleteBackward]).
     * Enter first lets any held-back composition through and
     * only then reaches the host as a newline. Every other key - arrows, Escape, a modifier - is
     * not text at all and is forwarded untouched, exactly as [InputView] used to forward all
     * key events.
     */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    emit(model.deleteBackward(1))
                    return true
                }

                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    emit(model.finishComposing())
                    return inputHandler.onKeyEvent(event)
                }

                else -> {
                    val typed = typedCharOf(event)
                    if (typed != null) {
                        emit(model.typeChar(typed))
                        return true
                    }
                }
            }
        }
        return inputHandler.onKeyEvent(event)
    }

    /** The IME's view of the field: everything up to the cursor, which lives at the tail. */
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val text = model.currentText()
        val len = n.coerceAtMost(text.length)
        return if (len <= 0) "" else text.substring(text.length - len)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    override fun getSelectedText(flags: Int): CharSequence? = null

    /**
     * No selection tracking in the tail model, so a cursor placement is acknowledged but
     * dropped; the IME keeps composing at the end either way.
     */
    override fun setSelection(start: Int, end: Int): Boolean = true

    override fun clearMetaKeyStates(states: Int): Boolean = true

    /** Not supported: returning false sends the IME back to commit/setComposing, which we do model. */
    override fun replaceText(
            start: Int, end: Int, text: CharSequence, newCursorPosition: Int,
            textAttribute: android.view.inputmethod.TextAttribute?,
    ): Boolean = false

    /**
     * Marking an existing span as composing has no meaning in a tail model, but accepting the
     * call keeps the framework from editing the (absent) editable behind our back.
     */
    override fun setComposingRegion(start: Int, end: Int): Boolean = true

    /*****************************************************************************************
     * Turning the model's instructions into host keystrokes.
     *****************************************************************************************/

    private fun emit(ops: List<TextOp>) {
        for (op in ops) {
            when (op) {
                is TextOp.Delete -> repeat(op.count) { sendDelete() }
                is TextOp.Type -> sendText(op.chars)
            }
        }
    }

    private fun sendDelete() {
        val now = SystemClock.uptimeMillis()
        inputHandler.onKeyEvent(keyEvent(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        inputHandler.onKeyEvent(keyEvent(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    /**
     * Types [text] on the host. [KeyCharacterMap.getEvents] turns the whole run into the real
     * key sequence - shift presses for capitals and symbols included - so it is tried first.
     * Characters it cannot reach on a US layout (most of Unicode, typed from a soft keyboard)
     * come back as null; those are handed over whole in a single [KeyEvent.ACTION_MULTIPLE],
     * the shape all three protocol handlers already understand as text.
     */
    private fun sendText(text: String) {
        if (text.isEmpty()) return
        val events = keyCharacterMap.getEvents(text.toCharArray())
        if (events != null) {
            events.forEach { inputHandler.onKeyEvent(it) }
        } else {
            val now = SystemClock.uptimeMillis()
            // This constructor sets action = ACTION_MULTIPLE, code = KEYCODE_UNKNOWN and
            // source = SOURCE_KEYBOARD for us, carrying [text] as the event's characters -
            // the shape all three protocol handlers already understand as a run of text.
            inputHandler.onKeyEvent(KeyEvent(now, text, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    KeyEvent.FLAG_SOFT_KEYBOARD))
        }
    }

    private fun keyEvent(downTime: Long, action: Int, keyCode: Int): KeyEvent =
            KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD)

    /**
     * The character a key event would print, if it is a plain text key. Dead (combining)
     * accents are left to the normal key path - the model has no notion of building them up.
     */
    private fun typedCharOf(event: KeyEvent): Char? {
        val uChar = event.getUnicodeChar(event.metaState)
        if (uChar == 0 || uChar and KeyCharacterMap.COMBINING_ACCENT != 0) return null
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
        return uChar.toChar()
    }
}
