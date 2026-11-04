/*
 * The thin UI bridge the receiving server needs: it runs on its own socket threads, while
 * dialogs may only be shown from the main thread on a live Activity. Everything routes
 * through the application's current foreground activity ([App.topActivity]); when there is
 * none - the app is backgrounded or not even running with UI - an incoming offer is
 * declined on the spot rather than accepted without the user.
 */
package com.limelight.localsend

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.limelight.App
import com.limelight.R
import com.limelight.util.showImmersiveIfNeeded

object LsUi {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The activity dialogs can attach to, or null when none is in the foreground. */
    fun context(): Context? = App.topActivity()?.takeIf { !it.isFinishing }

    /**
     * Asks the user about an incoming offer. One file gets a plain accept/decline dialog;
     * several are listed with checkboxes and whatever stays ticked is what uploads. The
     * dialog must never strand the waiting server thread, so cancelling it is a decline
     * and even a failure to show one completes the decision.
     */
    fun showIncomingOffer(context: Context, offer: IncomingOffer) {
        val names = offer.files.entries.sortedBy { it.value.fileName }
        mainHandler.post {
            val activity = context as? Activity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                offer.decision.complete(IncomingOffer.DECLINED)
                return@post
            }
            try {
                val title = activity.getString(R.string.dialog_localsend_offer, offer.sender.alias)
                val builder = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setOnCancelListener { offer.decision.complete(IncomingOffer.DECLINED) }
                if (names.size == 1) {
                    builder.setMessage(formatEntry(activity, names.first().value))
                        .setPositiveButton(R.string.action_accept) { _, _ ->
                            offer.decision.complete(setOf(names.first().key))
                        }
                        .setNegativeButton(R.string.action_decline) { _, _ ->
                            offer.decision.complete(IncomingOffer.DECLINED)
                        }
                } else {
                    val checked = BooleanArray(names.size) { true }
                    builder.setMultiChoiceItems(
                        names.map { formatEntry(activity, it.value) }.toTypedArray(), checked
                    ) { _, index, isChecked -> checked[index] = isChecked }
                        .setPositiveButton(R.string.action_accept) { _, _ ->
                            val picked = names.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
                            offer.decision.complete(if (picked.isEmpty()) emptySet() else picked)
                        }
                        .setNegativeButton(R.string.action_decline) { _, _ ->
                            offer.decision.complete(IncomingOffer.DECLINED)
                        }
                }
                // The offer lands wherever the user happens to be - including mid-stream, where
                // a plain show() would steal focus and bring the nav bars back over the picture.
                builder.create().showImmersiveIfNeeded(activity)
            } catch (e: Exception) {
                // A window that cannot open is a decline, not a lost server thread.
                offer.decision.complete(IncomingOffer.DECLINED)
            }
        }
    }

    /**
     * Text messages ride inside a file's `preview` field; once saved, the body is shown
     * like the official app does, so the user learns there was a message and not a file.
     */
    fun showMessage(context: Context?, alias: String, text: String) {
        val activity = context as? Activity ?: return
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            try {
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.dialog_localsend_message, alias))
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .showImmersiveIfNeeded(activity)
            } catch (e: Exception) {
                // Cosmetic only; the file itself was already saved.
            }
        }
    }

    /** Toasts for send results, callable from any thread. */
    fun toast(context: Context?, text: String) {
        val target = context ?: App.appContext
        mainHandler.post {
            android.widget.Toast.makeText(target, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** "name \u00b7 size" for an incoming offer row, mirroring the official app's listing. */
    private fun formatEntry(context: Context, dto: LsFileDto): String =
        context.getString(R.string.label_localsend_offer_row, dto.fileName, formatSize(context, dto.size))

    private fun formatSize(context: Context, bytes: Long): String = android.text.format.Formatter
        .formatShortFileSize(context, bytes)
}
