package com.limelight.ui.vnc.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-device tests for [ImeTextModel], the state machine behind the phone-keyboard duplicate
 * fix. [ImeTextModel] reaches the host only through the [TextOp] list it returns, so a host can
 * be simulated exactly: [Type] appends and [Delete] backspaces. Feeding an IME sequence through
 * the model and applying its ops reproduces, character for character, what the remote machine
 * would end up holding - which is precisely the surface the reported bug lived on.
 *
 * Every sequence runs under both [ImeTextTiming.CONFIRM] and [ImeTextTiming.IMMEDIATE]; each
 * must land the intended text and must never produce the doubled prefix ("workworkbuddy") that
 * the old fallback input connection generated.
 */
class ImeTextModelTest {

    /** Applies a whole run of op batches to a fake host buffer, the way key events would. */
    private class Host {
        val sb = StringBuilder()
        fun apply(ops: List<TextOp>) {
            for (op in ops) when (op) {
                is TextOp.Delete -> repeat(op.count) {
                    if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                }
                is TextOp.Type -> sb.append(op.chars)
            }
        }
        fun text(): String = sb.toString()
    }

    /** Total number of backspaces emitted across a run - used to pin the "exactly one delete" cases. */
    private fun deleteCount(ops: List<TextOp>): Int =
            ops.filterIsInstance<TextOp.Delete>().sumOf { it.count }

    private fun run(mode: ImeTextTiming, body: (ImeTextModel, Host) -> Unit): Host {
        val model = ImeTextModel { mode }
        val host = Host()
        body(model, host)
        return host
    }

    // 1. Type "work" letter by letter (each becomes the growing composition), then pick the
    //    candidate "workbuddy". The host must end with exactly "workbuddy", never "workworkbuddy".
    @Test fun typedWordThenCandidate_confirm() {
        val host = run(ImeTextTiming.CONFIRM) { m, h ->
            var comp = ""
            for (c in "work") { comp += c; h.apply(m.setComposing(comp)) }
            h.apply(m.commit("workbuddy"))
        }
        assertEquals("workbuddy", host.text())
    }

    @Test fun typedWordThenCandidate_immediate() {
        val host = run(ImeTextTiming.IMMEDIATE) { m, h ->
            var comp = ""
            for (c in "work") { comp += c; h.apply(m.setComposing(comp)) }
            h.apply(m.commit("workbuddy"))
        }
        assertEquals("workbuddy", host.text())
        assertFalse(host.text().contains("workwork"))
    }

    // 2. Type a single "w", then let the candidate expand it to "work".
    @Test fun singleLetterThenCandidate() {
        for (mode in ImeTextTiming.entries) {
            val host = run(mode) { m, h ->
                h.apply(m.setComposing("w"))
                h.apply(m.commit("work"))
            }
            assertEquals(mode.toString(), "work", host.text())
        }
    }

    // 3. Swipe input: the composition "wrk" is revised in place to "work". The host must not
    //    keep the abandoned "wrk" glued in front of the corrected word.
    @Test fun swipeRevision() {
        for (mode in ImeTextTiming.entries) {
            val host = run(mode) { m, h ->
                h.apply(m.setComposing("wrk"))
                h.apply(m.setComposing("work"))
                h.apply(m.commit("work"))
            }
            assertEquals(mode.toString(), "work", host.text())
            assertFalse(mode.toString(), host.text().contains("wrkwork"))
        }
    }

    // 4. Pinyin: compose "zhong", choose the candidate "中文". Only the finished word belongs on
    //    the host, in either timing.
    @Test fun pinyinCandidate() {
        for (mode in ImeTextTiming.entries) {
            val host = run(mode) { m, h ->
                h.apply(m.setComposing("zhong"))
                h.apply(m.commit("中文"))
            }
            assertEquals(mode.toString(), "中文", host.text())
        }
    }

    // 5. Backspacing a single already-injected character must emit exactly one Delete, no more.
    @Test fun backspaceOneInjectedChar() {
        for (mode in ImeTextTiming.entries) {
            val model = ImeTextModel { mode }
            val opsType = model.typeChar('a')
            val opsDel = model.deleteBackward(1)
            assertTrue(mode.toString(), opsType.any { it is TextOp.Type && it.chars == "a" })
            assertEquals(mode.toString(), 1, deleteCount(opsDel))
            val host = Host().apply { apply(opsType); apply(opsDel) }
            assertEquals(mode.toString(), "", host.text())
        }
    }

    // 6. After a finalized commit, a trailing space typed by the IME appends in order; nothing
    //    of the committed word is lost or duplicated.
    @Test fun spaceAfterFinalizedCommit() {
        for (mode in ImeTextTiming.entries) {
            val host = run(mode) { m, h ->
                var comp = ""
                for (c in "work") { comp += c; h.apply(m.setComposing(comp)) }
                h.apply(m.commit("work"))
                h.apply(m.typeChar(' '))
            }
            assertEquals(mode.toString(), "work ", host.text())
        }
    }

    // 7. A field the host already had text in - renaming a file, where the old name sits there
    //    before this connection ever sees a keystroke. The mirror is empty, so every press falls
    //    on text no bookkeeping of ours covers, and each one must still reach the host.
    @Test fun backspaceOverPreexistingHostText() {
        for (mode in ImeTextTiming.entries) {
            val model = ImeTextModel { mode }
            repeat(3) { assertEquals(mode.toString(), 1, deleteCount(model.deleteBackward(1))) }
        }
    }

    // 8. One delete request longer than the mirror - a whole word erased at once - must ask for
    //    exactly its own length in backspaces: the tail of ours, plus the characters in front.
    @Test fun deleteSpanningMirrorAndHost() {
        for (mode in ImeTextTiming.entries) {
            val model = ImeTextModel { mode }
            model.commit("ab")
            assertEquals(mode.toString(), 5, deleteCount(model.deleteBackward(5)))
        }
    }

    // 9. Deleting into a composition that CONFIRM held back must send no backspace at all: none
    //    of it ever reached the host, so the only repair is to type what is left of the word.
    @Test fun backspaceInsideHeldBackComposition() {
        val model = ImeTextModel { ImeTextTiming.CONFIRM }
        model.setComposing("abc")
        val ops = model.deleteBackward(1)
        assertEquals(0, deleteCount(ops))
        assertEquals("ab", Host().apply { apply(ops) }.text())
    }
}
