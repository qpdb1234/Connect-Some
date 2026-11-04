/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Tools screen's "enable log recording" switch, applied to the app's whole log
 * output. Off means off: the app stops writing its own log calls into the system
 * buffer, and the capture file stops growing. Without this the switch would only
 * silence our file writer while the app kept logging on every code path, so turning
 * it off still "produced logs" in every viewer of the system buffer.
 *
 * The Kotlin/Java side obeys the switch through [Log] below, which reads the flag at
 * the single entry point every call goes through. The three native libraries cannot
 * read a Java flag cheaply per line, so each hands its JNI setter to
 * [registerNative] the moment its library has loaded; every switch then pushes the
 * new state to whichever libraries are loaded. Going through registration is what
 * keeps a toggle from loading a library nobody has asked for yet.
 */
object AppLogging {

    @Volatile
    private var enabled = false

    private val nativeSetters = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** New state of the recording switch; pushed to every loaded native library. */
    fun setEnabled(on: Boolean) {
        enabled = on
        Log.recordingEnabled = on
        for (setter in nativeSetters) {
            // A library whose native call fails is past caring about logging; there
            // is nothing to do beyond not logging the failure itself.
            runCatching { setter(on) }
        }
    }

    /**
     * Called by a native wrapper once its library is loaded. The setter is remembered
     * for future switches and caught up to the current state immediately, so a library
     * loaded after a switch starts obeying it from its first log line.
     */
    fun registerNative(setter: (Boolean) -> Unit) {
        nativeSetters.add(setter)
        runCatching { setter(enabled) }
    }
}

/**
 * Stand-in for [android.util.Log] that obeys [AppLogging]: when recording is off the
 * calls are dropped instead of reaching logcat. Every source file that logs imports
 * this class under the same name - [v], [d], [i], [w] and [e] keep the platform
 * signatures they are called with, so switching an import is the whole edit.
 *
 * A dropped call reports 0 bytes written, which no caller here looks at.
 */
object Log {

    @JvmStatic
    @Volatile
    var recordingEnabled: Boolean = false

    @JvmStatic
    fun v(tag: String, msg: String): Int =
        if (recordingEnabled) android.util.Log.v(tag, msg) else 0

    @JvmStatic
    fun d(tag: String, msg: String): Int =
        if (recordingEnabled) android.util.Log.d(tag, msg) else 0

    @JvmStatic
    fun i(tag: String, msg: String): Int =
        if (recordingEnabled) android.util.Log.i(tag, msg) else 0

    @JvmStatic
    fun w(tag: String, msg: String): Int =
        if (recordingEnabled) android.util.Log.w(tag, msg) else 0

    @JvmStatic
    fun e(tag: String, msg: String): Int =
        if (recordingEnabled) android.util.Log.e(tag, msg) else 0

    // The three-argument forms take a nullable throwable because that is what the call
    // sites hold; a null one degrades to the plain message, as the platform would.
    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable?): Int =
        if (!recordingEnabled) 0
        else if (tr != null) android.util.Log.d(tag, msg, tr) else android.util.Log.d(tag, msg)

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable?): Int =
        if (!recordingEnabled) 0
        else if (tr != null) android.util.Log.i(tag, msg, tr) else android.util.Log.i(tag, msg)

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?): Int =
        if (!recordingEnabled) 0
        else if (tr != null) android.util.Log.w(tag, msg, tr) else android.util.Log.w(tag, msg)

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?): Int =
        if (!recordingEnabled) 0
        else if (tr != null) android.util.Log.e(tag, msg, tr) else android.util.Log.e(tag, msg)

    @JvmStatic
    fun w(tag: String, tr: Throwable): Int =
        if (recordingEnabled) android.util.Log.w(tag, tr) else 0

    // The platform only grew e(String, Throwable) late, so the message-form is what
    // every build answers with here.
    @JvmStatic
    fun e(tag: String, tr: Throwable): Int =
        if (recordingEnabled) android.util.Log.e(tag, "", tr) else 0
}
