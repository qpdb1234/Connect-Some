/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * The vendor database this opens, assets/mac_devices.db, is the IEEE OUI export
 * csicar/Ning generates with createMacVendorDB.go (LGPL-3.0-or-later). The copy-then-open
 * and the prefix cache are this app's.
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.limelight.util.Log
import java.io.File
import java.util.Locale

/**
 * Maps a hardware address to the vendor that bought its OUI prefix, using the IEEE
 * company list shipped as `assets/mac_devices.db` (ning's `mac_devices.db`).
 *
 * This is what turns "192.168.1.31 · 52:55:0a:…" into "that's a Xiaomi phone" on a
 * scan card: the first three bytes of a MAC are registered to the manufacturer, and
 * the table resolves them to a company name. Machines we cannot name any other way
 * (no mDNS announcement, no reverse DNS) are still recognizable by their vendor.
 *
 * The asset database is not on a path SQLite can open directly, so it is copied to
 * [Context.getNoBackupFilesDir] once and opened read-only afterwards. Everything is
 * best effort: a device whose copy failed simply gets no vendor names.
 */
object MacVendorLookup {
    private val TAG = MacVendorLookup::class.java.name
    private const val DB_FILE = "mac_devices.db"

    /** Guarded by itself: lookups arrive from scan threads one round at a time. */
    private var db: SQLiteDatabase? = null
    private var broken = false

    /** Prefix to name, including misses: a round re-resolves the same vendors. */
    private val cache = HashMap<String, String?>()

    /**
     * Prepares the database, copying the asset on first use. Safe to call per scan
     * round; only the first call does anything.
     */
    fun init(context: Context) {
        synchronized(this) {
            if (db != null || broken) return
            try {
                val target = File(context.noBackupFilesDir, DB_FILE)
                if (!target.exists() || target.length() == 0L) {
                    context.assets.open(DB_FILE).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                db = SQLiteDatabase.openDatabase(target.path, null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                Log.i(TAG, "MAC vendor database ready")
            } catch (e: Exception) {
                broken = true
                Log.w(TAG, "Cannot open MAC vendor database; vendors will stay unknown", e)
            }
        }
    }

    /**
     * Vendor name for [mac] ("aa:bb:cc:dd:ee:ff"), or null when the prefix is not a
     * registered OUI - a locally administered address (many VMs and randomised MACs)
     * simply has no vendor.
     */
    fun vendor(mac: String?): String? {
        if (mac.isNullOrBlank() || mac.length < 8) return null
        val prefix = mac.uppercase(Locale.ROOT).take(8)
        return synchronized(this) {
            // Not cached before the database is up: a pre-init miss would stick forever.
            val handle = db
            if (handle == null) return null
            cache.getOrPut(prefix) {
                try {
                    handle.rawQuery("SELECT name FROM macvendor WHERE mac = ?", arrayOf(prefix))
                            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
                } catch (e: Exception) {
                    Log.d(TAG, "Vendor lookup failed for $prefix: $e")
                    null
                }
            }
        }
    }
}
