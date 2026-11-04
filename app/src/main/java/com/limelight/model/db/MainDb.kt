/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.model.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.limelight.model.ServerProfile

/**
 * Main database.
 *
 * AVNC's historical Room migration chain (v1..v7) targeted the old RFB schema and
 * referenced columns that no longer exist after the Moonlight integration. Since
 * this is a fresh `com.limelight` application (no shipped AVNC database lineage),
 * the schema is restarted at [VERSION] = 1.
 *
 * Later versions must be migrated *forward* - the rows in here are the user's
 * own servers (including paired GameStream hosts), which a destructive fallback
 * would silently wipe. [MIGRATION_1_2] adds the `protocol` column that separates
 * GameStream from VNC profiles, [MIGRATION_2_3] the fields of the third protocol
 * (directly driven Android devices), [MIGRATION_3_4] the RFB endpoint the GameStream
 * clipboard bridge dials, [MIGRATION_4_5] the codec the Android protocol now starts
 * with.
 */
@Database(entities = [ServerProfile::class], version = MainDb.VERSION, exportSchema = false)
abstract class MainDb : RoomDatabase() {
    abstract val serverProfileDao: ServerProfileDao

    companion object {
        /**
         * Current database version
         */
        const val VERSION = 5

        /**
         * v1 -> v2: dual-protocol support. Only adds the discriminator column; every
         * existing row is a GameStream host, which is also what the default selects.
         * Keep the column definition in sync with
         * [ServerProfile.protocol]'s `@ColumnInfo`.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN protocol INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 -> v3: the Android-device protocol. Six columns, all with the same
         * defaults as their `@ColumnInfo` counterparts in [ServerProfile] - a
         * mismatch makes Room's schema check fail on the next open. Existing rows
         * keep their protocol, and the new fields describe a protocol they do not
         * use, so their values are irrelevant until one is edited into an Android
         * profile.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN androidUseH265 INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN androidUseOpus INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN androidAudio INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN androidKeepAwake INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN androidDirectTouch INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE profiles ADD COLUMN androidUsbDeviceName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3 -> v4: the clipboard bridge of a GameStream host. Two columns carrying the
         * defaults [ServerProfile] declares, which means an installed profile starts
         * reaching for RFB's first display as soon as the setting is on - the bridge
         * gives up quietly when nothing answers there, so this stays a non-event for
         * anybody without a VNC server on the streaming box.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN clipboardVncPort INTEGER NOT NULL DEFAULT 5900")
                db.execSQL("ALTER TABLE profiles ADD COLUMN clipboardVncPassword TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v4 -> v5: H.265 becomes the starting codec of the Android protocol, matching
         * [ServerProfile.androidUseH265]'s new default. Only rows that *are* Android
         * devices are touched, and only from the value the old default wrote - so a
         * profile where somebody switched it off deliberately, and every other protocol,
         * are left exactly as they are. [com.limelight.vnc.AndroidClient] still falls back
         * to H.264 on a phone that cannot decode HEVC.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE profiles SET androidUseH265 = 1" +
                        " WHERE protocol = 2 AND androidUseH265 = 0")
            }
        }

        private var instance: MainDb? = null

        /**
         * Returns database singleton.
         * If database is not yet created then it will be created on first call.
         */
        @Synchronized
        fun getInstance(context: Context): MainDb {
            if (instance == null) {
                instance = Room.databaseBuilder(context, MainDb::class.java, "main")
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                        // Kept only as a last resort for unknown versions; the known
                        // steps are explicit migrations so user data survives upgrades.
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .build()
            }
            return instance!!
        }
    }
}
