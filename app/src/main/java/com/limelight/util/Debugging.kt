/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.os.Looper
import com.limelight.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Utilities to aid in debugging
 */

/**
 * [check] variant which is only active in debug builds.
 */
fun debugCheck(condition: Boolean) {
    if (BuildConfig.DEBUG)
        check(condition)
}

fun <T : Any> debugCheckNotNull(value: T?) {
    if (BuildConfig.DEBUG)
        checkNotNull(value)
}

fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper())
}

fun checkNotMainThread() {
    check(Looper.myLooper() != Looper.getMainLooper())
}


object Debugging {

    /**
     * Ceiling for [logcat], in lines. The device's buffer is not ours to hold: an
     * unbounded `readText()` of a chatty radio buffer is a multi-megabyte String, and
     * the screen then turns it into a `lines()` list plus one TextView per line. Keeping
     * the tail is both cheaper and what the user actually wants - the interesting part of
     * a log is at the end.
     */
    private const val MAX_LOG_LINES = 8_000

    /**
     * Returns logcat output.
     * Should not be called from main thread.
     */
    fun logcat(): String {
        try {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "*")
                    .redirectErrorStream(true)
                    .start()
            try {
                return process.inputStream.use { stream ->
                    val tail = ArrayDeque<String>()
                    stream.bufferedReader().lineSequence().forEach { line ->
                        if (tail.size >= MAX_LOG_LINES) tail.removeFirst()
                        tail.addLast(line)
                    }
                    val dropped = if (tail.size >= MAX_LOG_LINES) "[.. older lines dropped ..]\n" else ""
                    dropped + tail.joinToString("\n")
                }
            } finally {
                // Reading the pipe to EOF does not retire the child. Without this the process
                // object and its file descriptors stay reachable until the next GC of a
                // throwaway debug screen, and every repeat of the export leaks another one.
                process.destroy()
                process.waitFor()
            }
        } catch (t: Throwable) {
            return "Error getting logs: ${t.message}"
        }
    }

    fun clearLogs() {
        try {
            val process = ProcessBuilder("logcat", "-c").start()
            process.waitFor()
        } catch (_: Throwable) {
            //Ignore
        }
    }

    /**
     * Wraps given logs into a <details> element.
     * This is useful for GitHub comments.
     */
    fun wrapLogs(title: String, logs: String): String {
        return """
            <details>
            <summary>$title</summary>
            <p>
            
            ```python
            {logs}
            ```
            
            </p>
            </details>
            """.trimIndent().replace("{logs}", logs)
    }
}

/**
 * Optional log capture for the Tools screen's "enable log recording" dropdown. While a
 * recording mode is on, the app spawns a follow-mode `logcat` process and appends its
 * output to a bounded file under [android.content.Context.getFilesDir], so the most recent
 * logs survive being backgrounded - or a crash. Switching to "off" stops the capture but
 * keeps the file, so the user can still export what was recorded up to that point; the
 * callers in App and PrefsActivity decide which mode restarts the file clean.
 *
 * The file is capped at [MAX_LOG_BYTES]: a follow-mode `logcat` on a chatty
 * device grows without bound, so past the ceiling we rewind it to its trailing
 * [KEEP_BYTES]. The append stream is opened in append mode, which means the OS
 * seeks to the (now shorter) end before every write, so truncating the file out
 * from under it is safe.
 */
object PersistentLog {

    private const val MAX_LOG_BYTES = 2L * 1024 * 1024

    private const val KEEP_BYTES = MAX_LOG_BYTES / 2

    @Volatile
    private var process: Process? = null

    @Volatile
    private var captureThread: Thread? = null

    /**
     * Begin capturing. [freshFile] is the "only logs since this launch" shape: it
     * empties the previous record first, and starts the follow-mode `logcat` with
     * `-T 1` so the backlog still resident in the device's ring buffer is skipped -
     * the file then holds exactly what was logged from this capture onwards, nothing
     * inherited from before the app started. The appending mode keeps the default
     * start, where logcat first replays whatever the buffer still holds.
     */
    @Synchronized
    fun start(context: Context, freshFile: Boolean = false) {
        if (process != null) return
        val appContext = context.applicationContext
        val file = file(appContext)
        file.parentFile?.mkdirs()
        if (freshFile) clearFile(file)
        try {
            val builder = if (freshFile)
                ProcessBuilder("logcat", "-T", "1", "-v", "threadtime", "*")
            else
                ProcessBuilder("logcat", "-v", "threadtime", "*")
            val proc = builder
                .redirectErrorStream(true)
                .start()
            process = proc
            val t = Thread {
                var out: FileOutputStream? = null
                try {
                    val input = proc.inputStream.bufferedReader()
                    out = FileOutputStream(file, true)
                    var written = file.length()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (process !== proc) break
                        val bytes = (line + "\n").toByteArray()
                        out.write(bytes)
                        written += bytes.size
                        if (written > MAX_LOG_BYTES) {
                            out.flush()
                            written = trimTail(file)
                        }
                    }
                } catch (_: Throwable) {
                    // Stopping destroys the process, which closes the pipe mid-read. That is
                    // the normal shutdown path, not something worth surfacing to the user.
                } finally {
                    try {
                        out?.close()
                    } catch (_: Throwable) {
                    }
                    synchronized(this) {
                        if (process === proc) {
                            process = null
                            captureThread = null
                        }
                    }
                }
            }
            t.isDaemon = true
            captureThread = t
            t.start()
        } catch (_: Throwable) {
            process = null
        }
    }

    @Synchronized
    fun stop() {
        val proc = process ?: return
        process = null
        try {
            proc.destroy()
        } catch (_: Throwable) {
        }
    }

    val isCapturing: Boolean
        get() = process != null

    /** The capture file, or null when nothing has been recorded. */
    fun existing(context: Context): File? {
        val f = file(context.applicationContext)
        return if (f.isFile && f.length() > 0) f else null
    }

    /**
     * Empties the capture file without stopping a running capture. The writer holds the
     * file open in append mode, which seeks to its end before every write, so truncating
     * from here is safe: the next line lands at the new, empty end.
     */
    fun clear(context: Context) {
        clearFile(file(context.applicationContext))
    }

    private fun clearFile(file: File) {
        try {
            if (file.isFile) RandomAccessFile(file, "rw").use { it.setLength(0) }
        } catch (_: Throwable) {
            // A log file that cannot be emptied is not worth crashing over.
        }
    }

    fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, "logs"), "app.log")

    /**
     * Rewind [file] to its trailing [KEEP_BYTES] and return the new size.
     */
    private fun trimTail(file: File): Long {
        return try {
            val length = file.length()
            if (length <= KEEP_BYTES) return length
            val tail = ByteArray(KEEP_BYTES.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - KEEP_BYTES)
                raf.readFully(tail)
            }
            FileOutputStream(file, false).use { it.write(tail) }
            KEEP_BYTES
        } catch (_: Throwable) {
            file.length()
        }
    }
}