/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the RFB performance overlay.
 *
 * What these tests pin down is steadiness, which is what the overlay was wrong about: the
 * same arrival pattern replayed against reports taken at different moments must give the
 * same reading, and a screen that has gone quiet must show up as a long gap rather than as
 * no gap at all. The patterns below are the ones a desktop VNC host actually produces - a
 * steady animation, a drag that ends, a still screen for ten seconds - replayed against a
 * clock the test supplies, so nothing here waits for a host.
 *
 * The reporting timer itself, and the RFB traffic that feeds these stamps, is device
 * territory and is not covered.
 */
class RfbPerfWindowTest {

    private val ms: Long = 1_000_000L
    private val sec: Long = 1_000_000_000L

    /** Records one update every [periodMs] milliseconds, from [from] up to and including [until]. */
    private fun RfbPerfWindow.updates(periodMs: Long, from: Long, until: Long) {
        var at = from
        while (at <= until) {
            record(at)
            at += periodMs
        }
    }

    @Test
    fun aSteadyStreamReadsTheSameWheneverTheReportIsTaken() {
        val window = RfbPerfWindow()
        window.reset(0)
        // A host redrawing at ~60 Hz for five seconds.
        window.updates(16 * ms, from = 16 * ms, until = 5 * sec)

        // Reports do not land on schedule - GC, a busy CPU and a dozing device all move them
        // by tens or hundreds of milliseconds. That used to change the answer.
        val readings = (0L until 40L step 3L).map { offset ->
            window.measure(3 * sec + offset * ms).fps
        } + (0L until 40L step 7L).map { offset ->
            window.measure(4_500 * ms + offset * ms).fps
        }

        val spread = readings.maxOrNull()!! - readings.minOrNull()!!
        assertTrue("spread of $spread across ${readings.size}: $readings", spread < 3f)
        readings.forEach { assertTrue("reading $it", it > 55f && it < 65f) }
    }

    @Test
    fun theFirstSecondsAreDividedByTheTimeActuallyObserved() {
        val window = RfbPerfWindow()
        window.reset(0)
        window.updates(16 * ms, from = 16 * ms, until = 1 * sec)

        // One second of a 60 Hz stream, measured through a three second window, is 60 FPS -
        // not the 20 that dividing by the window's nominal length would have claimed while
        // the window was still filling up.
        assertEquals(60f, window.measure(1 * sec).fps, 5f)
    }

    @Test
    fun aBurstThenSilenceDecaysInsteadOfOscillating() {
        val window = RfbPerfWindow()
        window.reset(0)
        // A dragged window: 100 updates in the first third of a second, then the desktop stops.
        window.updates(3 * ms, from = 3 * ms, until = 300 * ms)

        val readings = (1L..6L).map { window.measure(it * sec).fps }
        readings.zipWithNext().forEach { (earlier, later) ->
            assertTrue("$readings is not decaying", earlier >= later)
        }
        assertEquals(0f, readings.last(), 0f)
    }

    @Test
    fun aStillScreenReportsHowLongSinceThePictureChanged() {
        val window = RfbPerfWindow()
        window.reset(0)
        window.record(500 * ms)

        val reading = window.measure(5 * sec)
        // Nothing has redrawn within the window, so the rate is honestly zero ...
        assertEquals(0f, reading.fps, 0f)
        // ... and the gap carries the whole story: the old reading here was 0 ms, which looks
        // like the fastest link imaginable.
        assertEquals(4_500f, reading.gapMs, 5f)
    }

    @Test
    fun aQuietSpellSurvivesTheUpdateThatEndsIt() {
        val window = RfbPerfWindow()
        window.reset(0)
        window.record(1 * sec)
        window.record(4 * sec)

        // The gap that led up to the last picture is the interesting one: three seconds of
        // nothing and then a change must not read as an instant response just because the
        // update has now arrived.
        assertEquals(3_000f, window.measure(4 * sec + 10 * ms).gapMs, 5f)
    }

    @Test
    fun theRingIsTheCeilingOnHowFastAHostCanRedraw() {
        val window = RfbPerfWindow()
        window.reset(0)
        window.updates(1 * ms, from = 1 * ms, until = 5 * sec)

        // 1000 updates a second is more than 512 stamps can span three seconds, so the rate
        // saturates at the ring instead of running away or falling over.
        val reading = window.measure(5 * sec)
        assertTrue("fps ${reading.fps}", reading.fps > 165f && reading.fps < 175f)
        assertEquals(1f, reading.gapMs, 0.1f)
    }

    @Test
    fun negativeClockReadingsAreStillCounted() {
        val window = RfbPerfWindow()
        // [System.nanoTime] has no defined origin; a device is entitled to run negative.
        window.reset(-10 * sec)
        window.updates(16 * ms, from = -10 * sec + 16 * ms, until = -8 * sec)

        assertEquals(60f, window.measure(-8 * sec).fps, 5f)
    }
}
