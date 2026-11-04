/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.localsend

import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.limelight.R
import com.limelight.util.LocalNetwork
import com.limelight.util.setClipboardTextWithNotification
import com.limelight.util.showImmersiveIfNeeded

/**
 * The rows, the messages and the actions that the LocalSend screen and the stream drawer's
 * LocalSend panel both need.
 *
 * They live here rather than being written twice because the panel is meant to be as complete
 * as the screen - the same files waiting in the same outbox, the same tap on a device sending
 * them - and two copies of a row or of a dialog drift the moment one of them gains an entry
 * point. Only the containers differ between the two callers, and that stays their business.
 *
 * The actions take an [AppCompatActivity] rather than a plain [Context] because they open
 * dialogs over it and because the link has to be handed to the system share sheet.
 */
object LsWidgets {

    /**
     * One held file: its name and size, and the button that takes just this one away. Removal
     * is left to the caller because the two hosts react to their own list of waiting files.
     * A long press shows the full text in a tooltip, which matters for message rows whose body
     * is the name and gets ellipsized once it is long. [textSizeSp] > 0 replaces the Medium
     * appearance for the compact floating card, which finds 18sp too large.
     */
    fun stagedRow(context: Context, file: LsStagedFile, textSizeSp: Float = 0f,
                  onRemove: (LsStagedFile) -> Unit): LinearLayout {
        val pad = context.resources.getDimensionPixelSize(R.dimen.padding_small)
        val btnSize = removeBtnSizePx(context)
        val borderless = selectableBackground(context, android.R.attr.selectableItemBackgroundBorderless)
        val label = TextView(context).apply {
            text = context.getString(R.string.label_localsend_staged_row, file.name,
                    Formatter.formatShortFileSize(context, file.size))
            if (textSizeSp > 0f) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setTextColor(context.resources.getColor(R.color.colorMiniOnCard))
            } else setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // One line, filled edge to edge. A row allowed to wrap stopped at whatever boundary
            // the line breaker happened to find, so a camelCase or underscored name broke near
            // half across while the space behind it stayed empty. Truncating in the middle costs
            // only the centre of the name and keeps its extension readable; the tooltip below
            // carries the whole text for anyone who wants it.
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            tooltipText = text
            isLongClickable = true
            setPadding(pad, pad, pad, pad)
        }
        val remove = ImageButton(context).apply {
            setImageResource(R.drawable.ic_clear)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(borderless)
            contentDescription = context.getString(R.string.desc_localsend_remove_file)
            setOnClickListener { onRemove(file) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove, LinearLayout.LayoutParams(btnSize, btnSize))
        }
    }

    /**
     * One device row. A tap hands the outbox to that device and a long press opens whatever the
     * caller has on offer for it - which on both hosts is the favorite switch.
     *
     * A pinned row shows the address instead of the model, since the address is the part that
     * may have gone stale while nobody was announcing it. [textSizeSp] > 0 replaces the Medium
     * appearance for the compact floating card (see [stagedRow]).
     */
    fun deviceRow(
            context: Context,
            peer: LsPeer,
            favorite: Boolean,
            textSizeSp: Float = 0f,
            onTap: (LsPeer) -> Unit,
            onLongPress: (LsPeer) -> Unit,
    ): TextView {
        val pad = context.resources.getDimensionPixelSize(R.dimen.padding_small)
        val selectable = selectableBackground(context, android.R.attr.selectableItemBackground)
        val label = when {
            favorite -> context.getString(R.string.label_localsend_device_row, peer.alias, peer.host)
            peer.deviceModel.isNullOrBlank() || peer.deviceModel == peer.alias -> peer.alias
            else -> context.getString(R.string.label_localsend_device_row, peer.alias, peer.deviceModel)
        }
        return TextView(context).apply {
            // Width is set on the view rather than at each addView: both lists are vertical
            // LinearLayouts, and a row that only spans its own text would clip the highlight.
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = label
            if (textSizeSp > 0f) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setTextColor(context.resources.getColor(R.color.colorMiniOnCard))
            } else setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(selectable)
            isClickable = true
            isFocusable = true
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onTap(peer) }
            setOnLongClickListener { onLongPress(peer); true }
        }
    }

    /**
     * The label a pinned device shows in the favorites list: its alias and the address it was
     * saved at. Same text the plain device row uses for a favorite, kept here so the drag list
     * and the tap list never describe a pinned machine differently.
     */
    fun favoriteLabel(context: Context, peer: LsPeer): String =
            context.getString(R.string.label_localsend_device_row, peer.alias, peer.host)

    /** The ripple background a clickable row gets, resolved from the caller's theme. */
    fun rowSelectableBackground(context: Context): Int =
            selectableBackground(context, android.R.attr.selectableItemBackground)

    /**
     * What a finished send is worth telling the user about it. Shared because the two hosts
     * report the same outcomes, and a lapsed read grant is the one case where the only fix -
     * add the file again - has to be said plainly instead of quoting the provider.
     */
    fun sendResultMessage(context: Context, result: LsSendResult): String = when (result) {
        is LsSendResult.Accepted -> context.getString(R.string.msg_localsend_sent)
        is LsSendResult.DeclinedByReceiver -> context.getString(R.string.msg_localsend_declined)
        is LsSendResult.NothingSelected -> context.getString(R.string.msg_localsend_nothing)
        is LsSendResult.PermissionLost -> context.getString(R.string.msg_localsend_uri_expired)
        is LsSendResult.PartiallySkipped -> {
            val skipped = context.getString(R.string.msg_localsend_partial, result.names)
            // The rest went out cleanly: naming the skipped files is the whole of the news.
            // When the part that was sent also came to a bad end, that headline matters too.
            if (result.result is LsSendResult.Accepted) skipped
            else skipped + "\n" + sendResultMessage(context, result.result)
        }
        is LsSendResult.Failed -> context.getString(R.string.msg_localsend_failed, result.message)
    }

    /**
     * Tap on a device row: what the outbox holds goes out and stays held, which is what lets a
     * second tap on a second device carry the same files. With nothing waiting there is nothing
     * to send, and saying so beats opening a chooser that cannot be satisfied.
     *
     * A batch whose read grants have lapsed is refused before the handshake: the receiver would
     * otherwise be shown a dialog for files this app can no longer open.
     */
    fun sendStaged(activity: AppCompatActivity, peer: LsPeer) {
        if (!stagedReady(activity)) return
        LsUi.toast(activity, activity.getString(R.string.msg_localsend_sending))
        LsManager.sendStaged(peer) { report(activity, it) }
    }

    /**
     * Whether the outbox holds something that can still be handed out - to a device or to a
     * browser. Both paths answer it identically before either starts, and name which of the two
     * refusals they hit: nothing is waiting, or what waits can no longer be read. A typed message
     * counts as ready, since it needs no content provider to stay readable.
     */
    private fun stagedReady(activity: AppCompatActivity): Boolean {
        if (LsManager.staged.value.isEmpty()) {
            LsUi.toast(activity, activity.getString(R.string.msg_localsend_nothing_staged))
            return false
        }
        if (!LsManager.stagedReadable()) {
            LsUi.toast(activity, activity.getString(R.string.msg_localsend_uri_expired))
            return false
        }
        return true
    }

    /**
     * The long-press menu on a device row. A discovered (unpinned) row can only be pinned. A
     * pinned row is only offered removal, because reordering the favorites is done by dragging
     * their rows in the list (see LsFavoritesAdapter), not from here - the old move entries are
     * gone so the menu never duplicates what the drag already does.
     */
    fun deviceOptions(
            activity: AppCompatActivity,
            peer: LsPeer,
            favorite: Boolean,
            onDismiss: (() -> Unit)? = null,
    ) {
        if (!favorite) {
            AlertDialog.Builder(activity)
                    .setTitle(peer.alias)
                    .setItems(arrayOf(activity.getString(R.string.action_localsend_favorite))) {
                        _, _ -> LsManager.toggleFavorite(peer)
                    }
                    .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
                    .create()
                    .showImmersiveIfNeeded(activity)
            return
        }

        AlertDialog.Builder(activity)
                .setTitle(peer.alias)
                .setItems(arrayOf(
                        activity.getString(R.string.action_localsend_rename),
                        activity.getString(R.string.action_localsend_remove_favorite),
                )) { _, which ->
                    when (which) {
                        0 -> renameDialog(activity, peer)
                        1 -> LsManager.removeFavorite(peer.fingerprint)
                    }
                }
                .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
                .create()
                .showImmersiveIfNeeded(activity)
    }

    private fun renameDialog(activity: AppCompatActivity, peer: LsPeer) {
        val input = EditText(activity).apply {
            setText(peer.alias)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(activity)
                .setTitle(R.string.title_localsend_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val name = input.text.toString()
                    if (name.isBlank() || name == peer.alias) return@setPositiveButton
                    LsManager.renameFavorite(peer.fingerprint, name)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showImmersiveIfNeeded(activity)
    }

    /**
     * Type a message and drop it into the outbox, exactly where picked files wait. A message is
     * not something to fire off only while a device happens to be present: it joins the queue and
     * goes out to whichever machine the user taps next, the same as any file.
     */
    fun sendTextDialog(activity: AppCompatActivity) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.dialog_localsend_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(activity)
                .setTitle(R.string.action_localsend_send_text)
                .setView(input)
                .setPositiveButton(R.string.action_localsend_add_message) { _, _ ->
                    val text = input.text.toString()
                    if (text.isEmpty()) return@setPositiveButton
                    LsManager.addStagedText(text)
                    LsUi.toast(activity, activity.getString(R.string.msg_localsend_text_staged))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showImmersiveIfNeeded(activity)
    }

    /**
     * Asks for an address and reaches out to it. A port may be typed along with the address,
     * because a device running a second instance - or one whose usual port is taken - is
     * reachable just as easily once the number is known.
     */
    fun addByIpDialog(activity: AppCompatActivity, onDismiss: (() -> Unit)? = null) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.hint_localsend_ip)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(activity)
                .setTitle(R.string.title_localsend_add_by_ip)
                .setMessage(R.string.title_localsend_add_by_ip_summary)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> addPeerAt(activity, input.text.toString()) }
                .setNegativeButton(android.R.string.cancel, null)
                .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
                .create()
                .showImmersiveIfNeeded(activity)
    }

    private fun addPeerAt(activity: AppCompatActivity, value: String) {
        val trimmed = value.trim().removePrefix("https://").removePrefix("http://")
        val host = trimmed.substringBefore(':')
        if (!LocalNetwork.isIpv4(host)) {
            LsUi.toast(activity, activity.getString(R.string.msg_invalid_ipv4_address))
            return
        }
        val port = trimmed.substringAfter(':', "").toIntOrNull() ?: LsConstants.PORT
        // The query runs on the manager's own thread and calls back there, so the only thing
        // either host can still safely do after a rotation or a back press is nothing.
        LsManager.addPeerByAddress(host, port) { peer, error ->
            if (gone(activity)) return@addPeerByAddress
            if (peer == null) {
                LsUi.toast(activity, activity.getString(R.string.msg_localsend_add_failed, host, error))
            } else {
                if (!LsManager.isFavorite(peer.fingerprint)) LsManager.toggleFavorite(peer)
                LsUi.toast(activity, activity.getString(R.string.msg_localsend_added, peer.alias))
            }
        }
    }

    /**
     * Turn the outbox into a download link. This is the one entry the whole app shares: the
     * standalone screen, the stream drawer and the floating card all route "create a link" here
     * rather than opening a picker of their own, so it always means "link what I already added",
     * never "pick files again". [onShared] lets the host surface its 'stop sharing' entry; for the
     * floating card, which has no window of its own once the link dialog closes, [onNoDialog] is
     * the chance to finish the transparent host whenever no dialog was left up.
     */
    fun shareOutbox(
            activity: AppCompatActivity,
            onNoDialog: () -> Unit = {},
            onShared: (String) -> Unit,
    ) {
        if (!stagedReady(activity)) {
            onNoDialog()
            return
        }
        LsManager.shareStaged { link ->
            if (gone(activity)) return@shareStaged
            if (link == null) {
                LsUi.toast(activity, activity.getString(R.string.msg_localsend_link_failed))
                onNoDialog()
            } else {
                onShared(link)
                linkDialog(activity, link, onDismiss = onNoDialog)
            }
        }
    }

    /**
     * The link itself, offered for copying and for the system share sheet. It is shown as the
     * dialog's message so a long-press can select it, which covers the case where the share
     * sheet has no app that takes plain text the way the user wants it.
     *
     * Copying takes the middle slot and dismissal the rightmost one, so the row ends on the way
     * out. Every button here closes the dialog, the one that copies included.
     */
    fun linkDialog(activity: AppCompatActivity, link: String, onDismiss: (() -> Unit)? = null) {
        AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_localsend_link_title)
                .setMessage(link)
                .setNegativeButton(R.string.action_localsend_copy_link) { _, _ ->
                    activity.setClipboardTextWithNotification(link)
                }
                .setNeutralButton(R.string.action_localsend_share) { _, _ ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    activity.startActivity(Intent.createChooser(intent,
                            activity.getString(R.string.dialog_localsend_link_title)))
                }
                .setPositiveButton(android.R.string.ok, null)
                .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
                .create()
                .showImmersiveIfNeeded(activity)
    }

    /** Both hosts report the same outcomes, so both report them the same way. */
    private fun report(activity: AppCompatActivity, result: LsSendResult) {
        LsUi.toast(activity, sendResultMessage(activity, result))
    }

    /**
     * One live download link: its URL with a copy action, a row of per-link actions (expand the
     * file list, add files, revoke this link), and a file list that starts collapsed. Every
     * action talks to [LsManager] directly; only "add files" is handed back to the host, because
     * which picker to open is the one thing the standalone screen and the drawer do differently.
     *
     * The row is rebuilt from scratch whenever the shares flow emits, so its expanded/collapsed
     * state does not survive an edit - acceptable, and it keeps the row a pure function of state.
     */
    fun shareRow(
            activity: AppCompatActivity,
            share: ShareInfo,
            onAddFiles: (String) -> Unit,
    ): LinearLayout {
        val pad = activity.resources.getDimensionPixelSize(R.dimen.padding_small)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(pad, pad, pad, pad)
        }

        // Line 1: the address, truncated in the middle so both ends stay legible, plus a copy.
        val urlRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val urlLabel = TextView(activity).apply {
            text = share.url
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextAppearance(android.R.style.TextAppearance_Small)
            // The middle-ellipsis hides the host part of a long address. Holding the label
            // shows the whole URL, the same long-press tooltip mechanism the toolbar icons use.
            tooltipText = share.url
            isLongClickable = true
        }
        urlRow.addView(urlLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(textAction(activity, activity.getString(R.string.action_localsend_copy)) {
            activity.setClipboardTextWithNotification(share.url)
        })
        root.addView(urlRow)

        // File list, hidden until the toggle opens it. One line per file with its own removal.
        val filesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val rebuildFiles: () -> Unit = {
            filesContainer.removeAllViews()
            for (file in LsManager.shares.value.firstOrNull { it.token == share.token }?.files
                    ?: share.files) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = TextView(activity).apply {
                    val full = activity.getString(R.string.label_localsend_shared_file_row,
                            file.name, Formatter.formatShortFileSize(activity, file.size))
                    text = full
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextAppearance(android.R.style.TextAppearance_Small)
                    // Middle-ellipsis hides the middle of a long file name. Holding the row shows
                    // the whole name and size, the same long-press tooltip the URL above uses.
                    tooltipText = full
                    isLongClickable = true
                }
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(iconAction(activity, R.drawable.ic_clear,
                        activity.getString(R.string.action_localsend_remove_from_share)) {
                    LsManager.removeSharedFile(share.token, file.id)
                })
                filesContainer.addView(row)
            }
        }

        val toggle = textAction(activity,
                activity.getString(R.string.label_localsend_shared_files, share.files.size)) {
            val open = filesContainer.visibility != android.view.View.VISIBLE
            filesContainer.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            if (open) rebuildFiles()
        }

        // Line 2: expand, add files to this link, revoke this link.
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(toggle)
        actionRow.addView(textAction(activity,
                activity.getString(R.string.action_localsend_add_to_share)) { onAddFiles(share.token) })
        actionRow.addView(textAction(activity,
                activity.getString(R.string.action_localsend_revoke)) {
            LsManager.revokeShare(share.token)
        })
        root.addView(actionRow)

        root.addView(filesContainer)
        return root
    }

    /**
     * A compact text button for a row of actions. The highlight is the bounded one rather than
     * the borderless variant the icon buttons use: these labels are barely taller than their own
     * text, and a borderless ripple grows into a circle that reaches far past a row that short.
     */
    private fun textAction(context: Context, label: String, onClick: () -> Unit): TextView {
        val small = context.resources.getDimensionPixelSize(R.dimen.padding_small)
        return TextView(context).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTextColor(context.resources.getColor(android.R.color.holo_blue_light))
            setPadding(small, small, small, small)
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableBackground(context,
                    android.R.attr.selectableItemBackground))
            setOnClickListener { onClick() }
        }
    }

    /** Same, as an icon - used where a wide label would crowd a narrow row. */
    private fun iconAction(context: Context, icon: Int, description: String, onClick: () -> Unit): ImageButton {
        val btnSize = removeBtnSizePx(context)
        return ImageButton(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setBackgroundResource(selectableBackground(context,
                    android.R.attr.selectableItemBackgroundBorderless))
            setOnClickListener { onClick() }
        }
    }

    /** Whether the dialog this callback would open has no window left to open it on. */
    private fun gone(activity: AppCompatActivity): Boolean = activity.isFinishing || activity.isDestroyed

    /**
     * Height of a file row's removal button, shared by the outbox and by the file list under a
     * download link. It is deliberately below the 48dp action default: those rows are a name and
     * an icon and nothing else, so at 48dp the space between two adjacent names reads as a blank
     * line and a handful of files spread over most of the drawer's height.
     */
    private const val REMOVE_BTN_DIP = 32f

    private fun removeBtnSizePx(context: Context): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, REMOVE_BTN_DIP, context.resources.displayMetrics).toInt()

    /** Ripple-only background for a row, resolved from the caller's theme. */
    private fun selectableBackground(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }
}