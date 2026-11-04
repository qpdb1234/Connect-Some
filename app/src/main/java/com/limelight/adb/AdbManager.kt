/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import com.limelight.util.Log
import com.limelight.App
import com.limelight.R
import java.io.IOException

/**
 * Owns the process-wide ADB sessions and the host key pair.
 *
 * A session is kept open after its last client releases it, because the expensive
 * parts - authentication, the jar push, the resident RPC process - are per *device*
 * rather than per connection. Reconnecting within [IDLE_TIMEOUT_MS] is then almost
 * instant; past that the ADB wire is dropped. The helper process on the device exits
 * on its own accord (see `Scrcpy.release`), so nothing leaks if we do not say goodbye.
 */
object AdbManager {

    private const val TAG = "ConnectSomeAdb"

    /** How long an unreferenced session is kept for a quick reconnect. */
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    /**
     * Patience for the liveness round trip a cached session is held to before it is
     * reused. A healthy adbd answers an OPEN in milliseconds on the LAN; anything
     * slower here means the wire went away quietly rather than being busy, and waiting
     * it out only delays the reconnect that has to happen over a fresh socket anyway.
     */
    private const val REUSE_PROBE_TIMEOUT_MS = 3_000L

    private class Entry(val key: String, val adb: Adb) {
        var references = 0
        var lastReleasedAt = System.currentTimeMillis()
    }

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * One lock per device key, so that opening a session serializes against other opens
     * of the *same* device and nothing else.
     *
     * The monitor of [entries] cannot be used for that: an ADB handshake waits on the
     * controlled device - indefinitely while its user has an authorization prompt open -
     * and a USB teardown ends with a bulk transfer that is allowed two seconds to fail.
     * Either of those held against the global monitor would freeze [release],
     * [hasLiveSession] and [invalidate] for every other session, and those are called on
     * the UI thread.
     */
    private val openLocks = LinkedHashMap<String, Any>()

    /**
     * The host key pair. Loading is lazy on purpose: generating RSA-2048 takes a
     * moment, and only the Android-device protocol ever needs it.
     */
    @JvmStatic
    val keyPair: AdbKeyPair by lazy { AdbKeyPair.loadOrCreate(App.appContext.filesDir) }

    /**
     * Returns a live session for [key], opening one through [transport] if necessary.
     *
     * The caller must pair every call with [release] - the ADB wire stays shared
     * between the RPC process and any number of encoders otherwise.
     */
    @Throws(IOException::class)
    fun acquire(key: String, transport: AdbTransport): Adb {
        val lock = synchronized(entries) { openLocks.getOrPut(key) { Any() } }

        // Collected inside the monitors, closed after leaving them: see [openLocks].
        var toClose: List<Adb> = emptyList()
        val session: Adb = try {
            synchronized(lock) {
                val claim = synchronized(entries) { claimOrClearLocked(key) }
                toClose = claim.second
                val cached = claim.first
                val reused = cached?.takeIf { isResponsiveLocked(it) }
                if (cached != null && reused == null) {
                    Log.i(TAG, "cached adb session $key stopped answering; reopening")
                    synchronized(entries) {
                        // Remove only our own session, and hand back the reference the
                        // claim took - the open below registers a fresh one.
                        val entry = entries[key]
                        if (entry?.adb === cached) entries.remove(key)
                    }
                    toClose = toClose + cached
                }
                reused ?: run {
                    val opened = try {
                        Adb.open(transport, keyPair)
                    } catch (e: Exception) {
                        // A failed handshake must not leave a half-authenticated device behind.
                        throw IOException(e.message
                                ?: App.appContext.getString(R.string.error_adb_connect_failed), e)
                    }
                    val attached = synchronized(entries) { attachLocked(key, opened) }
                    toClose = toClose + attached.second
                    attached.first
                }
            }
        } finally {
            for (adb in toClose) runCatching { adb.close() }
        }
        Log.i(TAG, "adb session for $key is ready")
        return session
    }

    /**
     * Whether a claimed cached session still deserves the reference taken in
     * [claimOrClearLocked]. Runs off the global monitor but inside the per-device lock,
     * so the probe serializes against this device's own opens and nothing else. An
     * interrupted caller aborts [acquire] instead of condemning the wire: the session
     * and its claim stay cached for whoever clears the cancellation.
     */
    @Throws(InterruptedException::class)
    private fun isResponsiveLocked(adb: Adb): Boolean = try {
        adb.isResponsive(REUSE_PROBE_TIMEOUT_MS)
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Takes a reference on a live session for [key]. When the recorded one is dead it is
     * removed here and reported for closing, so the caller can open a fresh session.
     */
    private fun claimOrClearLocked(key: String): Pair<Adb?, List<Adb>> {
        val doomed = reapIdleEntriesLocked()
        val existing = entries[key] ?: return null to doomed
        if (existing.adb.isAlive) {
            existing.references++
            return existing.adb to doomed
        }
        Log.i(TAG, "dropping dead adb session $key")
        entries.remove(key)
        return null to (doomed + existing.adb)
    }

    /**
     * Registers a freshly opened session under [key].
     *
     * A live entry can still be there: adbd serves one client per address, so a session
     * that another thread completed while this handshake was running is the one to keep -
     * the fresh one has already been answered by [claimOrClearLocked] at the time it was
     * opened, and whichever of the two survives, the other has to go.
     */
    private fun attachLocked(key: String, opened: Adb): Pair<Adb, List<Adb>> {
        val existing = entries[key]
        if (existing != null && existing.adb.isAlive) {
            existing.references++
            return existing.adb to listOf(opened)
        }
        if (existing != null) entries.remove(key)
        val entry = Entry(key, opened)
        entry.references = 1
        entries[key] = entry
        return opened to (if (existing == null) emptyList() else listOf(existing.adb))
    }

    /** Marks one user of [key] as gone; the session itself survives until it goes idle. */
    fun release(key: String) {
        synchronized(entries) {
            val entry = entries[key] ?: return
            entry.references = (entry.references - 1).coerceAtLeast(0)
            entry.lastReleasedAt = System.currentTimeMillis()
        }
    }

    /** Drops a session right away, e.g. after the transport itself went away. */
    fun invalidate(key: String) {
        val removed = synchronized(entries) { entries.remove(key)?.adb }
        if (removed != null) runCatching { removed.close() }
    }

    fun hasLiveSession(key: String): Boolean =
            synchronized(entries) { entries[key]?.adb?.isAlive == true }

    /** Called on app teardown so no device keeps a shell session from a dead client. */
    fun closeAll() {
        val all = synchronized(entries) {
            val values = entries.values.toList()
            entries.clear()
            openLocks.clear()
            values.map { it.adb }
        }
        for (adb in all) runCatching { adb.close() }
    }

    /**
     * Forgets sessions nobody is using any more, and returns everything that has to be
     * closed - outside the monitor, which is what [closeAll] and [acquire] both do.
     */
    private fun reapIdleEntriesLocked(): List<Adb> {
        val now = System.currentTimeMillis()
        val doomed = mutableListOf<Adb>()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val idle = entry.references == 0 && now - entry.lastReleasedAt > IDLE_TIMEOUT_MS
            if (idle || !entry.adb.isAlive) {
                if (idle) Log.i(TAG, "reaping idle adb session ${entry.key}")
                doomed.add(entry.adb)
                iterator.remove()
            }
        }
        return doomed
    }
}
