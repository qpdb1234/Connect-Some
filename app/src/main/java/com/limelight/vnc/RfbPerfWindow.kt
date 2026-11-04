/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.vnc

import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max

/**
 * The update rate and picture gap shown in the RFB performance overlay, measured over a
 * sliding window of arrival times.
 *
 * Kept apart from [RfbClientNative] because that class cannot be loaded off a device (its
 * companion links a native library the moment it is touched), and the whole reason this
 * arithmetic exists is that the numbers used to swing: the overlay used to divide whatever
 * a counter had collected since the last report by the time since that report, which made
 * the reading a measurement of the scheduler as much as of the stream. A report that was
 * 40ms late and one that caught up 960ms later then showed the same picture as 0 FPS and
 * 25 FPS. Counting arrivals inside a window that is wider than the reporting interval
 * removes that coupling: the timer decides when a number is shown, the window decides what
 * it says.
 *
 * All timestamps are nanoseconds on the caller's clock ([System.nanoTime]), passed in rather
 * than read here, so a test can replay an arrival pattern without waiting for one.
 */
class RfbPerfWindow(private val windowNs: Long = DEFAULT_WINDOW_NS) {

    /** A rate and a gap, both over the window that ended when they were taken. */
    class Reading(val fps: Float, val gapMs: Float)

    /**
     * Arrival stamps, in a ring just long enough for the window at a rate no RFB server
     * sustains. Past that the oldest updates fall out of the ring, which caps the reported
     * rate rather than letting it run away.
     */
    private val stamps = AtomicLongArray(RING_SLOTS)

    /** Slot for the next stamp. Written only by the receiving thread, never read here. */
    private var slot = 0

    /**
     * Start of the current measurement, in nanoseconds; the floor of the window. Until the
     * window has filled, a rate is computed over the time actually observed - otherwise a
     * stream that has been running at a steady 60 FPS for one second would report 20.
     */
    private var openedAt = 0L

    /** Forgets everything observed so far; the next [measure] spans [windowNs] from [now]. */
    fun reset(now: Long) {
        for (i in 0 until RING_SLOTS)
            stamps.set(i, NO_STAMP)
        slot = 0
        openedAt = now
    }

    /** Notes that the picture changed at [arrivedAt]. */
    fun record(arrivedAt: Long) {
        // Masked rather than modulo, so [RING_SLOTS] must stay a power of two - and so the
        // wrap of the counter itself, 2^31 updates later, still lands inside the ring.
        stamps.set(slot and (RING_SLOTS - 1), arrivedAt)
        slot++
    }

    /** The updates that landed in the window ending at [now], as a rate and a gap. */
    fun measure(now: Long): Reading {
        val from = max(now - windowNs, openedAt)

        var count = 0
        var earliest = Long.MAX_VALUE // oldest update inside the window
        var latest = Long.MIN_VALUE   // newest update inside the window
        var newest = Long.MIN_VALUE   // newest update the ring holds, in the window or not
        var beforeNewest = Long.MIN_VALUE // and the one ahead of it

        // The bounds are sentinels rather than zeros: [System.nanoTime] is allowed to run
        // through negative values, and a scan that quietly treated 0 as "no stamp" would
        // report a stream of negative stamps as a screen that has never drawn at all.
        for (i in 0 until RING_SLOTS) {
            val stamp = stamps.get(i)
            // Stamps past [now] are not part of a window that ends at [now]. Live, the clock
            // makes that impossible; replayed against a test clock it is the difference
            // between measuring an arrival pattern and merely totalling it up.
            if (stamp == NO_STAMP || stamp > now) continue

            if (stamp > newest) {
                beforeNewest = newest
                newest = stamp
            } else if (stamp > beforeNewest) {
                beforeNewest = stamp
            }

            if (stamp >= from) {
                count++
                if (stamp < earliest) earliest = stamp
                if (stamp > latest) latest = stamp
            }
        }

        val spanMs = max((now - from) / NANOS_PER_MS, 1L)
        val fps = count * 1000f / spanMs

        // The gap between pictures, as the longest of three readings, so that a host which
        // has gone quiet shows as a long gap instead of hiding in the average of the updates
        // that came before it:
        //  - the mean gap between the updates in the window;
        //  - the gap that led up to the last update, which is what a burst after a still
        //    screen really was, and which may reach back past the window;
        //  - the time since the last update, the only reading while the screen is still, and
        //    one that keeps growing so a stall is visible rather than reported as no gap.
        // Differences are formed in nanoseconds and only then narrowed to Float, which has
        // nowhere near enough mantissa to hold two nanosecond timestamps.
        val meanInWindow = if (count > 1) (latest - earliest).toFloat() / (count - 1) else 0f
        val leadingTheLast = if (newest != Long.MIN_VALUE)
            (newest - if (beforeNewest != Long.MIN_VALUE) beforeNewest else openedAt).toFloat() else 0f
        val sinceLast = if (newest != Long.MIN_VALUE)
            (now - newest).toFloat() else (now - openedAt).toFloat()

        return Reading(fps, max(meanInWindow, max(leadingTheLast, sinceLast)) / NANOS_PER_MS)
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        /** An unused ring slot. A stamp of exactly this value is one update in a long run
         *  that goes uncounted, which is what a fixed-size ring of raw clock readings costs. */
        private const val NO_STAMP = 0L

        /**
         * How far back the rate and the gap are measured. Several times the overlay's refresh
         * interval, so that the arrivals of one second - which on this protocol can be a
         * whole burst or nothing at all, depending on what the remote screen happened to do -
         * move the reading by a fraction of itself rather than replacing it.
         */
        const val DEFAULT_WINDOW_NS = 3_000_000_000L

        /** Stamps kept: covers the default window at ~170 updates per second. */
        private const val RING_SLOTS = 512
    }
}
