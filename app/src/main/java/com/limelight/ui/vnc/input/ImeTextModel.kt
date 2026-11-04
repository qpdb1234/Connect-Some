/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.input

/**
 * The two timings a phone keyboard can have against a remote machine, chosen in Settings.
 *
 * - [CONFIRM] holds every provisional keystroke on the phone and lets only the finished
 *   word through. This is the safe default and what fixes the reported bug: an IME that
 *   types "work", then offers "workbuddy" as a candidate, used to send both, so the host
 *   read "workworkbuddy". Under this timing the provisional "work" never reaches the
 *   host, and the moment the candidate is chosen the whole "workbuddy" does.
 * - [IMMEDIATE] mirrors every keystroke to the host as it is typed, the way a physical
 *   keyboard behaves, and repairs the host text when the IME later revises the word (a
 *   swipe that turns "wrk" into "work" deletes the stale tail and retypes it). Characters
 *   appear sooner, at the cost of a backspace-and-retype flicker when a candidate replaces
 *   something already sent.
 */
enum class ImeTextTiming {
    CONFIRM,
    IMMEDIATE,
}

/**
 * A single instruction to bring the host's text in line with what this model now believes
 * the field holds. A pure value type so the whole state machine can be tested off-device.
 */
sealed class TextOp {
    /** Send [count] backspaces, removing characters the model no longer wants on the host. */
    data class Delete(val count: Int) : TextOp()

    /** Type [chars] at the cursor. */
    data class Type(val chars: String) : TextOp()
}

/**
 * The editor-mode half of the phone-keyboard fix: a plain text buffer that mirrors what the
 * input method thinks the field contains, plus the run of characters still under composition,
 * and a record of exactly what we have already put on the host.
 *
 * Every edit an IME performs - composing a word, choosing a candidate, deleting a character -
 * updates [local]. The model then works out what should actually be on the host ([target],
 * which depends on [ImeTextTiming]) and compares that against [injected]. Because both are a
 * leading run of the same buffer, the difference is always a shared prefix followed by a
 * rewrite of the tail, so the repair is one [TextOp.Delete] of the stale tail and one
 * [TextOp.Type] of the new one. That single rule is what removes the double typing: an
 * already-sent "work" is a common prefix of "workbuddy", so nothing is deleted and only
 * "buddy" is typed, and in [ImeTextTiming.CONFIRM] "work" was never sent at all.
 *
 * The buffer is deliberately a tail model: composition and backspace always happen at the
 * cursor, and on a phone the cursor is at the end of what is being typed. Reaching into the
 * middle of the buffer to reselect is beyond what any soft keyboard does here, and pretending
 * to support it would only hide desync. What the model does not claim to know - the text the
 * host field already held - is handled by [deleteBackward] passing those deletions straight
 * through as backspaces rather than reconciling them.
 *
 * No Android types appear below - the caller ([RemoteInputConnection]) turns these ops into
 * key events and answers the IME's queries, which keeps this class unit-testable.
 */
class ImeTextModel(private val mode: () -> ImeTextTiming) {

    /** What the input method believes the field holds, composition included. */
    private var local = ""

    /** The run of [local] still under composition - provisional, revisable by the IME. */
    private var composing = ""

    /** Exactly what the host currently shows for this field, per our last instruction. */
    private var injected = ""

    /**
     * The IME says the current composition is [text], replacing whatever was composing
     * before. The text before the composition is untouched.
     */
    fun setComposing(text: String): List<TextOp> {
        local = local.dropLast(composing.length) + text
        composing = text
        return sync()
    }

    /**
     * The IME commits [text] in place of the current composition. This is the moment a
     * candidate word lands, and in [ImeTextTiming.CONFIRM] the first time anything of this
     * word reaches the host.
     */
    fun commit(text: String): List<TextOp> {
        local = local.dropLast(composing.length) + text
        composing = ""
        return sync()
    }

    /**
     * The IME stops composing without changing the text - a space or punctuation that
     * accepts the word as typed. In [ImeTextTiming.CONFIRM] this is when the held-back text
     * finally goes to the host.
     */
    fun finishComposing(): List<TextOp> {
        composing = ""
        return sync()
    }

    /**
     * [count] characters were deleted from before the cursor. With the cursor pinned at the
     * tail this trims the end of the buffer; whatever composition that touches is dropped,
     * because a partially deleted composing run is no longer the run the IME gave us.
     *
     * Only the part of the deletion that this mirror actually covers is [sync]'s business.
     * A field the host already had text in - renaming a file, say, where the old name is
     * sitting there before this connection ever saw a keystroke - starts out empty as far as
     * [local] is concerned, and every one of those characters is one the IME can ask to delete
     * that no bookkeeping of ours accounts for. They are counted separately and returned as
     * their own [TextOp.Delete], because swallowing them once the mirror runs out leaves the
     * backspace key dead over a field that is visibly full.
     */
    fun deleteBackward(count: Int): List<TextOp> {
        if (count <= 0) return emptyList()
        // Measured against [local] and not [injected]: a composition held back under
        // ImeTextTiming.CONFIRM is text the IME believes in but the host never received, so
        // deleting into it is still a deletion this model can settle on its own.
        val unmirrored = count - local.length
        local = local.dropLast(count)
        if (composing.isNotEmpty() && !local.endsWith(composing)) composing = ""

        // First the characters left of everything sync() knows about, then its own cut-back,
        // then whatever it retypes - a delete after the type would eat the new text.
        val ops = ArrayList<TextOp>(3)
        if (unmirrored > 0) ops += TextOp.Delete(unmirrored)
        ops += sync()
        return ops
    }

    /**
     * A character typed with no composition state at all - a plain keystroke from a keyboard
     * that does not use a candidate bar. Commits directly onto the tail.
     */
    fun typeChar(char: Char): List<TextOp> {
        local = local.dropLast(composing.length) + char
        composing = ""
        return sync()
    }

    /** Drops all state. Called when the session ends so a new one starts from nothing. */
    fun reset() {
        local = ""
        composing = ""
        injected = ""
    }

    /** The full mirrored text, for the IME's queries ([android.view.inputmethod.InputConnection]). */
    fun currentText(): String = local

    /** How much of the tail is still provisional composition; the host should not see it in [ImeTextTiming.CONFIRM]. */
    fun composingLength(): Int = composing.length

    /**
     * Reconciles [injected] with what the timing says the host should now hold, and returns
     * the (at most two) ops to get there. The host text and the desired text share everything
     * up to the first difference, so the repair is: cut back to that point, then type the
     * rest. Emitting nothing when they already agree is what keeps an already-correct tail
     * from being retyped.
     */
    private fun sync(): List<TextOp> {
        val target = if (mode() == ImeTextTiming.CONFIRM) local.dropLast(composing.length) else local
        val common = commonPrefixLength(injected, target)
        val ops = ArrayList<TextOp>(2)

        val stale = injected.length - common
        if (stale > 0) ops += TextOp.Delete(stale)

        val fresh = target.substring(common)
        if (fresh.isNotEmpty()) ops += TextOp.Type(fresh)

        injected = target
        return ops
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }
}
