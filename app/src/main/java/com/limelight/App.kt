/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import com.limelight.localsend.LsManager
import com.limelight.util.AppLogging
import com.limelight.util.AppPreferences
import com.limelight.util.PersistentLog
import java.lang.ref.WeakReference

class App : Application() {

    @Keep
    lateinit var prefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        configureLeakCanary()

        prefs = AppPreferences(this)
        prefs.ui.theme.observeForever { updateNightMode(it) }

        // Track the foreground activity so background threads (LocalSend's receiving
        // server) can decide whether there is a UI to show a dialog on, and route the
        // dialog to it. A weak reference so this never leaks a destroyed activity.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                topActivityRef = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (topActivityRef?.get() === activity) topActivityRef = null
            }

            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (topActivityRef?.get() === activity) topActivityRef = null
            }
        })

        // LocalSend owns a process-wide network identity; start it once at launch so the
        // certificate is minted and discovery/receiving can begin when the user opts in.
        LsManager.initialize(this)

        // The recording switch governs two things from here: whether the app emits log
        // lines at all - Java, Kotlin and the three native libraries - and whether a
        // follow-mode capture keeps the bounded file current. Off therefore really means
        // "no logs are produced", not just "no file is written". The Tools dropdown owns
        // turning either half on; a restart restores an in-progress capture so a
        // debugging session survives the process going away, and "session" starts the
        // file empty so it holds only this launch's log.
        val loggingMode = prefs.viewer.loggingMode
        AppLogging.setEnabled(loggingMode != "off")
        when (loggingMode) {
            "session" -> PersistentLog.start(this, freshFile = true)
            "all" -> PersistentLog.start(this)
        }
    }

    companion object {
        /**
         * Process-wide application context. Used by the protocol client
         * ([com.limelight.vnc.VncClient]) which is created off the UI thread and
         * only receives an observer, mirroring AVNC's original `VncClient`.
         */
        @JvmStatic
        lateinit var appContext: Context
            private set

        @Volatile
        private var topActivityRef: WeakReference<Activity>? = null

        /** The activity currently in the foreground, or null when none is. */
        @JvmStatic
        fun topActivity(): Activity? = topActivityRef?.get()
    }

    private fun updateNightMode(theme: String) {
        val nightMode = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun configureLeakCanary() {
        // LeakCanary is intentionally NOT bundled in this build (no debug source
        // set, no dependency, and no LeakCanaryInitializer class). The original
        // AVNC code reflectively loaded `com.limelight.LeakCanaryInitializer`,
        // which threw an uncaught ClassNotFoundException and crashed every debug
        // launch. Keep this as a no-op.
    }
}