/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.limelight.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.limelight.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clipboard access is slightly more complex in AVNC, because clip data can be
 * unusually large. It includes stuff coming from server, and app logs.
 * As clipboard access involves binder IPC, it can lead to ANR issues. So we
 * use a background thread for accessing clipboard.
 */

/**
 * How much of a clipboard transfer survives.
 *
 * Both directions need a ceiling. Inbound, the length of an RFB cut-text message is a
 * 32-bit number the server picks, so a hostile or broken host can offer as much text as
 * it likes - and storing it means a binder transaction, which the system itself refuses
 * past roughly a megabyte. Outbound, the local clipboard can already hold more than a
 * wire is meant to carry. Truncating keeps the part a user can actually paste; dropping
 * the whole thing would lose it silently.
 */
const val CLIPBOARD_TRANSFER_LIMIT_CHARS = 512 * 1024

/** Shortens [text] to the transfer limit, saying so when it had to. */
private fun boundClipboardText(direction: String, text: String): String {
    if (text.length <= CLIPBOARD_TRANSFER_LIMIT_CHARS)
        return text
    Log.w("ClipboardUtil", "Clipboard $direction text of ${text.length} chars truncated to $CLIPBOARD_TRANSFER_LIMIT_CHARS")
    return text.take(CLIPBOARD_TRANSFER_LIMIT_CHARS)
}


/**
 * Should be called from Main thread because ClipboardManager can only be created
 * from a Looper thread.
 */
fun getClipboard(context: Context): ClipboardManager {
    // Use application context to create ClipboardManager to
    // - avoid leaking the context
    // - have single instance
    return ContextCompat.getSystemService(context.applicationContext, ClipboardManager::class.java)!!
}

/**
 * Puts given text on the clipboard.
 */
suspend fun setClipboardText(context: Context, text: String): Boolean {
    val bounded = boundClipboardText("inbound", text)
    var success = false
    try {
        withContext(Dispatchers.Main.immediate) { getClipboard(context) }.let {
            withContext(Dispatchers.IO) {
                it.setPrimaryClip(ClipData.newPlainText(null, bounded))
                success = true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e("ClipboardUtil", "Could not copy text to clipboard.", t)
    }
    return success
}


/**
 * Returns current clipboard text.
 */
suspend fun getClipboardText(context: Context): String? {
    var result: String? = null
    try {
        withContext(Dispatchers.Main.immediate) { getClipboard(context) }.let {
            withContext(Dispatchers.IO) {
                result = it.primaryClip?.getItemAt(0)?.text?.toString()
                    ?.let { text -> boundClipboardText("outbound", text) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e("ClipboardUtil", "Could not retrieve text from clipboard.", t)
    }
    return result
}

/**
 * Helper utility to set clip text & show a snack bar with confirmation
 */
fun ComponentActivity.setClipboardTextWithNotification(text: String) {
    lifecycleScope.launch {
        val snackHost = findViewById<View>(android.R.id.content)
        // [ClipData.setPrimaryClip] is fire and forget. It cannot report failure, and from
        // Android 12 on the system quietly throws the write away when the caller does not
        // hold window focus - which is exactly the state a just-dismissed context menu
        // leaves behind. So the only honest confirmation is reading the clip back; without
        // it every one of these Snackbars said "Copied", including the ones that copied
        // nothing, which is why the menu entry looked like it did nothing at all.
        val copied = setClipboardText(this@setClipboardTextWithNotification, text) &&
                getClipboardText(this@setClipboardTextWithNotification) == text
        val message = if (copied) getString(R.string.msg_copied_value_to_clipboard, text)
        else getString(R.string.error_clipboard_copy_failed)
        FloatingText.show(snackHost, message, FloatingText.LENGTH_SHORT)
    }
}