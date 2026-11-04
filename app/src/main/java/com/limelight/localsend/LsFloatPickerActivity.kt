/*
 * The transparent host the LocalSend floating window hops through for the handful of things a
 * passive TYPE_APPLICATION_OVERLAY cannot do on its own: raise the system document picker and
 * take text typed against an IME. The overlay deliberately runs FLAG_NOT_FOCUSABLE on the
 * application context - dialogs cannot attach a window to that, and raising the keyboard from
 * a never-focused overlay is what made a background window fight the system's own navigation.
 * So the card starts this Activity (NEW_TASK, since an overlay has no task of its own) with an
 * action naming what it wants, and this Activity - an ordinary AppCompatActivity, invisible and
 * never in Recents - performs exactly that one step and finishes. The floating card is parked (its
 * overlay window detached, state and collectors kept) for the length of the hop so it does not cover
 * the dialog or picker raised here, and re-attached unchanged once this Activity finishes.
 */
package com.limelight.localsend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.limelight.R
import com.limelight.util.ImmersiveDialogHost
import com.limelight.util.OpenableMultipleDocuments
import com.limelight.util.keepHiddenBars
import com.limelight.util.onScreenImmersiveDialogs
import com.limelight.util.showImmersiveIfNeeded

class LsFloatPickerActivity : AppCompatActivity(), ImmersiveDialogHost {

    /**
     * The hop runs over whatever was on screen when the card was tapped - most often the stream,
     * which hides the bars and merely sits paused underneath this transparent window. Ask the
     * on-screen host, not ourselves: we own no fullscreen state, but every dialog raised here has
     * to keep the state it landed on (see [ImmersiveDialogHost]).
     */
    override val immersiveDialogs: Boolean
        get() = onScreenImmersiveDialogs

    companion object {
        /** What the caller wants done, carried as a plain string extra. */
        const val EXTRA_ACTION = "action"
        const val ACTION_ADD_FILES = "add_files"
        const val ACTION_SEND_TEXT = "send_text"
        const val ACTION_SHARE_LINK = "share_link"
        const val ACTION_ADD_BY_IP = "add_by_ip"
        const val ACTION_DEVICE_OPTIONS = "device_options"
        const val ACTION_CLEAR_FAVORITES = "clear_favorites"
        const val ACTION_SHARE_ADD_FILES = "share_add_files"

        /** Which device a [ACTION_DEVICE_OPTIONS] request is about, and whether it is pinned. */
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_FAVORITE = "favorite"

        /** Which link a [ACTION_SHARE_ADD_FILES] request feeds. */
        const val EXTRA_TOKEN = "token"

        /** Start [activity]'s flow from an overlay context; safe to call repeatedly. */
        fun launch(context: Context, action: String) {
            context.startActivity(Intent(context, LsFloatPickerActivity::class.java)
                    .putExtra(EXTRA_ACTION, action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Open a device's option menu on a real window: the overlay cannot host a dialog. */
        fun launchDeviceOptions(context: Context, fingerprint: String, favorite: Boolean) {
            context.startActivity(Intent(context, LsFloatPickerActivity::class.java)
                    .putExtra(EXTRA_ACTION, ACTION_DEVICE_OPTIONS)
                    .putExtra(EXTRA_FINGERPRINT, fingerprint)
                    .putExtra(EXTRA_FAVORITE, favorite)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Pick files for an already-published link; the token says which one to feed. */
        fun launchAddToShare(context: Context, token: String) {
            context.startActivity(Intent(context, LsFloatPickerActivity::class.java)
                    .putExtra(EXTRA_ACTION, ACTION_SHARE_ADD_FILES)
                    .putExtra(EXTRA_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * The two file hops share one picker contract, so which of them a returned result belongs to is
     * read straight from the launching intent rather than a field - a field would reset to its
     * default if the OS recreated this Activity while the picker was in front, silently sending an
     * outbox pick to the link publisher or vice versa.
     */
    private val documentPicker =
            registerForActivityResult(OpenableMultipleDocuments()) { uris -> onPicked(uris) }

    /** A separate picker that feeds an existing link rather than the outbox or a new link. */
    private val shareAddPicker =
            registerForActivityResult(OpenableMultipleDocuments()) { uris -> onShareFilesAdded(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Taking focus over the stream is itself enough to re-reveal the nav bars, before any
        // dialog even exists - so this window asks for the same hidden state the stream holds.
        // On any other surface it changes nothing: the bars stay as the system left them.
        if (immersiveDialogs) window.keepHiddenBars()
        // The card is an always-on-top overlay; take it down for the length of this hop so the
        // dialog or system picker raised here is not hidden behind it. Re-attached in onDestroy.
        LsFloatingWindow.parkForDialog()
        // Only kick off the step on a fresh start. If this Activity was stopped while the system
        // picker was in front and the OS recreated it, the pending picker result is redelivered to
        // the callback below - relaunching here would open a second picker on top of that.
        if (savedInstanceState != null) return
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_ADD_FILES -> documentPicker.launch(arrayOf("*/*"))
            ACTION_SHARE_LINK -> LsWidgets.shareOutbox(this, onShared = {}, onNoDialog = { finish() })
            ACTION_SEND_TEXT -> showSendText()
            ACTION_ADD_BY_IP -> LsWidgets.addByIpDialog(this) { finish() }
            ACTION_CLEAR_FAVORITES -> confirmClearFavorites()
            ACTION_SHARE_ADD_FILES -> {
                if (intent.getStringExtra(EXTRA_TOKEN) == null) finish()
                else shareAddPicker.launch(arrayOf("*/*"))
            }
            ACTION_DEVICE_OPTIONS -> showDeviceOptions()
            else -> finish()
        }
    }

    /**
     * The long-press menu for one device, resolved from its fingerprint against the discovered
     * table first and the pinned list second. Nothing matches (the peer went away between the tap
     * and this Activity starting) is a no-op, so the card simply does not open an empty menu.
     */
    private fun showDeviceOptions() {
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT)
        val favorite = intent.getBooleanExtra(EXTRA_FAVORITE, false)
        val peer = LsManager.peers.value.firstOrNull { it.fingerprint == fingerprint }
                ?: LsManager.favorites.value.firstOrNull { it.fingerprint == fingerprint }
        if (peer == null) {
            finish()
            return
        }
        LsWidgets.deviceOptions(this, peer, favorite) { finish() }
    }

    /**
     * A favorites list is something nobody means to lose, so the 'clear all' entry asks first. On
     * confirmation [LsManager.clearFavorites] empties both the live table and the file behind it.
     */
    private fun confirmClearFavorites() {
        AlertDialog.Builder(this)
                .setTitle(R.string.action_clear_favorites)
                .setMessage(R.string.msg_clear_favorites_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    LsManager.clearFavorites()
                    Toast.makeText(this, R.string.msg_favorites_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { finish() }
                .create()
                .showImmersiveIfNeeded(this)
    }

    /** Files picked for an existing link go to that token, not to the outbox. */
    private fun onShareFilesAdded(uris: List<Uri>?) {
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (!uris.isNullOrEmpty() && token != null) LsManager.addFilesToShare(token, uris)
        finish()
    }

    /**
     * Files picked on the add-files hop join the outbox and wait there. The link hop no longer
     * comes through here - it links what is already waiting rather than asking for a fresh pick -
     * so this callback has a single job now.
     */
    private fun onPicked(uris: List<Uri>?) {
        if (!uris.isNullOrEmpty()) LsManager.addStaged(uris)
        finish()
    }

    /**
     * Type a message and drop it into the outbox. The overlay cannot host a text field, so this
     * Activity is where the typing happens; the message then waits for a device just as a picked
     * file does, instead of demanding one up front.
     */
    private fun showSendText() {
        val pad = resources.getDimensionPixelSize(R.dimen.padding_small)
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_localsend_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        // setView puts the raw field edge to edge, so the dialog inset is applied from outside.
        // The EditText keeps its own padding: its bottom reserves the room its underline is drawn
        // in, and overriding it (as a previous revision did) paints typed text right over the line.
        val frame = FrameLayout(this).apply {
            setPadding(pad * 2, 0, pad * 2, 0)
            addView(input, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
                .setTitle(R.string.action_localsend_send_text)
                .setView(frame)
                .setPositiveButton(R.string.action_localsend_add_message) { _, _ ->
                    val text = input.text.toString()
                    if (text.isNotEmpty()) {
                        LsManager.addStagedText(text)
                        Toast.makeText(this, R.string.msg_localsend_text_staged,
                                Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { finish() }
                .create()
                .showImmersiveIfNeeded(this)
    }

    /** The hop is over (dialog dismissed, picker returned, or cancelled): bring the card back. */
    override fun onDestroy() {
        super.onDestroy()
        LsFloatingWindow.unparkAfterDialog()
    }
}
