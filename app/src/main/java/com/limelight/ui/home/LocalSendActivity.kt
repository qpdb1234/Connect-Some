/*
 * The standalone LocalSend screen, reached from the share icon on the home bar. It gives
 * the feature a home outside a running stream: the same receive switch and device list the
 * in-stream drawer offers, plus a progress bar for whichever transfer is moving.
 *
 * Everything here is a mirror of state owned by [LsManager] - the peer table, the receive
 * flag and the in-flight transfer. The screen only subscribes while it is open, so closing
 * it never tears down a session a stream drawer is still using.
 *
 * Three things this screen was the only place to do: reaching a device by typing its address,
 * pinning devices as favorites, and handing picked files out as a plain download link. The
 * first two are about machines that discovery cannot see, the third about machines that do not
 * run LocalSend at all. The stream drawer now offers all three as well, which is why the dialogs
 * and the actions behind them live in [LsWidgets] rather than here.
 *
 * Files are gathered before anyone is chosen: they join the outbox and stay there, because the
 * device they are meant for is routinely not switched on yet and because the same batch usually
 * goes to more than one machine. Tapping a device sends what is waiting; a long press pins it.
 */
package com.limelight.ui.home

import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.limelight.R
import com.limelight.localsend.LsFavoritesAdapter
import com.limelight.localsend.LsFloatingWindow
import com.limelight.localsend.LsManager
import com.limelight.localsend.LsPeer
import com.limelight.localsend.LsStagedFile
import com.limelight.localsend.LsTransferProgress
import com.limelight.localsend.LsWidgets
import com.limelight.localsend.ShareInfo
import com.limelight.util.AccentTheme
import com.limelight.util.OpenableMultipleDocuments
import kotlinx.coroutines.launch

class LocalSendActivity : AppCompatActivity() {

    private lateinit var enableSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var discoveredHeader: TextView
    private lateinit var peersList: LinearLayout
    private lateinit var favoritesHeader: TextView
    private lateinit var favoritesList: RecyclerView
    private lateinit var favoritesAdapter: LsFavoritesAdapter
    private lateinit var clearFavoritesButton: MaterialButton
    private lateinit var outboxHeader: TextView
    private lateinit var stagedList: LinearLayout
    private lateinit var removeAllButton: MaterialButton
    private lateinit var stopSharesButton: MaterialButton
    private lateinit var sharesHeader: TextView
    private lateinit var sharesList: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView

    /** Kept as a field so the rescan flow can rename it while the walk is running. */
    private lateinit var rescanButton: MaterialButton

    /** Peers count captured when a rescan started, so an empty walk can be reported. */
    private var peersAtRescanStart = -1

    /**
     * Set when the user stops a walk themselves, so the completion observer skips the "no devices
     * found" toast: a search cut short on purpose is not an empty result worth warning about.
     */
    private var rescanCancelled = false

    /** Whether the link list is unfolded; the header is the switch and remembers it across edits. */
    private var sharesExpanded = true

    /** The link whose "add files" is waiting on a picker result. Null between picks. */
    private var pendingShareToken: String? = null

    private val documentPicker =
        registerForActivityResult(OpenableMultipleDocuments()) { uris -> onDocumentsPicked(uris) }

    /** A picker for files added to an already-published link rather than to a new one. */
    private val shareAddPicker =
        registerForActivityResult(OpenableMultipleDocuments()) { uris -> onShareFilesAdded(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.attach(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_localsend)

        enableSwitch = findViewById(R.id.localsend_enable)
        statusText = findViewById(R.id.localsend_status)
        discoveredHeader = findViewById(R.id.localsend_section_discovered)
        peersList = findViewById(R.id.localsend_list)
        favoritesHeader = findViewById(R.id.localsend_section_favorites)
        favoritesList = findViewById(R.id.localsend_favorites_list)
        favoritesAdapter = LsFavoritesAdapter(this,
                onTap = { LsWidgets.sendStaged(this, it) },
                onLongPress = { LsWidgets.deviceOptions(this, it, favorite = true) })
        favoritesAdapter.attachTo(favoritesList)
        clearFavoritesButton = findViewById(R.id.localsend_clear_favorites)
        clearFavoritesButton.setOnClickListener { confirmClearFavorites() }
        outboxHeader = findViewById(R.id.localsend_section_outbox)
        stagedList = findViewById(R.id.localsend_staged_list)
        removeAllButton = findViewById(R.id.localsend_remove_all)
        stopSharesButton = findViewById(R.id.localsend_stop_shares)
        sharesHeader = findViewById(R.id.localsend_shares_header)
        sharesList = findViewById(R.id.localsend_shares_list)
        progressBar = findViewById(R.id.localsend_progress)
        progressLabel = findViewById(R.id.localsend_progress_label)
        findViewById<ImageButton>(R.id.back_btn).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.localsend_add_files)
            .setOnClickListener { documentPicker.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.localsend_send_text)
            .setOnClickListener { LsWidgets.sendTextDialog(this) }
        rescanButton = findViewById(R.id.localsend_rescan)
        // One button, two jobs: idle it starts a walk, running it stops the one in progress. The
        // in-flight check is read live so the same listener flips direction as the flag changes.
        rescanButton.setOnClickListener {
            if (LsManager.rescanInFlight.value) {
                rescanCancelled = true
                LsManager.stopRescan()
            } else {
                startRescan()
            }
        }
        findViewById<MaterialButton>(R.id.localsend_float).setOnClickListener {
            LsFloatingWindow.toggle(this)
        }
        findViewById<MaterialButton>(R.id.localsend_add_ip).setOnClickListener { LsWidgets.addByIpDialog(this) }
        findViewById<MaterialButton>(R.id.localsend_share_link)
            .setOnClickListener { LsWidgets.shareOutbox(this) { renderStopButton() } }
        stopSharesButton.setOnClickListener {
            LsManager.stopShares()
            renderStopButton()
        }
        sharesHeader.setOnClickListener {
            sharesExpanded = !sharesExpanded
            renderShares(LsManager.shares.value)
        }
        removeAllButton.setOnClickListener { LsManager.clearStaged() }

        enableSwitch.isChecked = LsManager.receiveEnabled.value
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            LsManager.setReceiveEnabled(checked)
            renderStatus(LsManager.peers.value)
        }

        lifecycleScope.launch { LsManager.peers.collect { renderPeers(it) } }
        lifecycleScope.launch { LsManager.favorites.collect { renderFavorites(it) } }
        lifecycleScope.launch { LsManager.staged.collect { renderStaged(it) } }
        lifecycleScope.launch { LsManager.transferProgress.collect { renderProgress(it) } }
        lifecycleScope.launch { LsManager.shares.collect { renderShares(it) } }
        // The rescan walks the subnet for a couple of seconds before its first hit lands,
        // so the button reads as inert for the whole duration. Renaming it to "Searching…"
        // while the flow is running, and pairing that with an empty-result toast on the way
        // out, is what tells the user the walk is happening and whether it found anything -
        // the two things problem-5 asked to be distinguishable.
        lifecycleScope.launch {
            var wasInFlight = false
            LsManager.rescanInFlight.collect { running ->
                // Stay enabled while running so a tap can stop the walk; the caption flips between
                // "Search again" and the searching/tap-to-stop cue via the two observers below.
                rescanButton.isEnabled = true
                rescanButton.setText(
                        if (running) R.string.action_localsend_rescanning
                        else R.string.action_localsend_rescan)
                if (wasInFlight && !running && !rescanCancelled && LsManager.peers.value.isEmpty()
                        && peersAtRescanStart == 0) {
                    Toast.makeText(this@LocalSendActivity,
                            R.string.msg_localsend_rescan_empty, Toast.LENGTH_LONG).show()
                }
                wasInFlight = running
            }
        }
        // The walk reports how far along it is as a (done, total) pair; fold it into the same
        // button caption so "Searching..." gains a percentage and an address tally instead of
        // reading as an open-ended wait. The plain rescanning label still shows for the moments
        // between a scan starting and its first count landing.
        lifecycleScope.launch {
            LsManager.scanProgress.collect { prog ->
                if (!LsManager.rescanInFlight.value || prog == null) return@collect
                val (done, total) = prog
                val percent = if (total > 0) done * 100 / total else 0
                rescanButton.text =
                        getString(R.string.msg_localsend_rescan_progress, percent, done, total)
            }
        }
        renderPeers(LsManager.peers.value)
        renderFavorites(LsManager.favorites.value)
        renderStaged(LsManager.staged.value)
        renderProgress(LsManager.transferProgress.value)
        renderShares(LsManager.shares.value)
        renderStopButton()
    }

    /**
     * While this screen is up, keep listening for multicast announcements even if receiving is
     * off: that passive path is what surfaces an active peer in about a second, the way the stock
     * app does, instead of leaving discovery to the explicit /24 walk behind the 'Search again'
     * button. Paired with [onPause] so the socket is released once nothing is on screen.
     */
    override fun onResume() {
        super.onResume()
        LsManager.startUiDiscovery()
    }

    override fun onPause() {
        LsManager.stopUiDiscovery()
        super.onPause()
    }

    private fun renderPeers(peers: List<LsPeer>) {
        peersList.removeAllViews()
        renderStatus(peers)
        discoveredHeader.isVisible = peers.isNotEmpty()

        // Read once instead of asking per row: the pinned set is small and the answer must
        // not race the discovery thread that is writing it.
        val pinned = LsManager.favorites.value.map { it.fingerprint }.toSet()
        peers.forEach { peer ->
            peersList.addView(rowOf(peer, favorite = peer.fingerprint in pinned))
        }
    }

    /**
     * The pinned devices, in their own section below the live list. A favorite is a bookmark
     * and not a photograph: it stays listed while the machine is off, which is the whole
     * reason it is not merged into [renderPeers]. The rows are dragged to reorder (see
     * [LsFavoritesAdapter]); the header hides itself with an empty list.
     */
    private fun renderFavorites(peers: List<LsPeer>) {
        favoritesHeader.isVisible = peers.isNotEmpty()
        clearFavoritesButton.isVisible = peers.isNotEmpty()
        favoritesAdapter.submit(peers)
    }

    /**
     * Kicks off a manual rescan and remembers the peer count so the completion in the
     * [LsManager.rescanInFlight] observer can decide whether to warn about an empty result.
     * Only an unchanged empty table produces the toast: a walk that found no *new* device on
     * top of an existing list is not a failure the user needs told about.
     */
    private fun startRescan() {
        peersAtRescanStart = LsManager.peers.value.size
        rescanCancelled = false
        LsManager.rescan()
    }

    /**
     * A favorites list is something nobody means to lose, so the 'clear all' entry asks first.
     * On confirmation [LsManager.clearFavorites] empties both the live table and the file behind
     * it, and the collect above redraws the section out of sight on its own.
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
            .show()
    }

    /**
     * The outbox. Every row can be dropped on its own - a file picked by mistake should not
     * cost the whole batch - and the header and the 'remove all' entry appear only while there
     * is something to remove.
     */
    private fun renderStaged(files: List<LsStagedFile>) {
        stagedList.removeAllViews()
        outboxHeader.isVisible = files.isNotEmpty()
        removeAllButton.isVisible = files.isNotEmpty()
        files.forEach { file ->
            stagedList.addView(LsWidgets.stagedRow(this, file) { LsManager.removeStaged(it) })
        }
    }

    /**
     * One device row. A tap hands the outbox to that device and a long press pins it, which is
     * the pair the screen is used for: send first, bookmark the machine worth sending to again.
     * The row itself comes from [LsWidgets], which the stream drawer draws from as well.
     */
    private fun rowOf(peer: LsPeer, favorite: Boolean): TextView {
        return LsWidgets.deviceRow(this, peer, favorite,
                onTap = { LsWidgets.sendStaged(this, it) },
                onLongPress = { LsWidgets.deviceOptions(this, it, favorite) })
    }

    private fun renderStatus(peers: List<LsPeer>) {
        val enabled = enableSwitch.isChecked
        statusText.text = getString(
            when {
                !enabled -> R.string.label_localsend_off
                peers.isEmpty() -> R.string.label_localsend_searching
                else -> R.string.label_localsend_order_hint
            })
    }

    private fun renderProgress(progress: LsTransferProgress?) {
        if (progress == null) {
            progressBar.isVisible = false
            progressLabel.isVisible = false
            return
        }
        val percent = if (progress.totalBytes > 0)
            (progress.doneBytes * 100 / progress.totalBytes).toInt() else 0
        progressBar.progress = percent
        progressBar.isVisible = true
        val verb = getString(if (progress.incoming) R.string.label_localsend_receiving
        else R.string.label_localsend_sending)
        progressLabel.text = if (progress.fileCount > 1)
            getString(R.string.label_localsend_progress_multi,
                verb, progress.fileIndex, progress.fileCount, progress.label)
        else
            getString(R.string.label_localsend_progress, verb, progress.label)
        progressLabel.isVisible = true
    }

    /** Picked files join the outbox rather than going straight out: the device may not exist yet. */
    private fun onDocumentsPicked(uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) return
        LsManager.addStaged(uris)
    }

    /** The undo entry for a served link, visible only while something is actually served. */
    private fun renderStopButton() {
        stopSharesButton.isVisible = LsManager.sharesActive()
    }

    /**
     * The live links under the button that made them: a collapsible header carrying the count and
     * one manageable row per link. Nothing to show keeps both the header and the container gone,
     * so the section only exists while a link is actually served.
     */
    private fun renderShares(shares: List<ShareInfo>) {
        val arrow = if (sharesExpanded) "\u25be" else "\u25b8"
        sharesHeader.isVisible = shares.isNotEmpty()
        sharesHeader.text = getString(R.string.title_localsend_shares, arrow, shares.size)
        sharesList.removeAllViews()
        sharesList.isVisible = sharesExpanded && shares.isNotEmpty()
        if (!sharesExpanded) return
        shares.forEach { share ->
            sharesList.addView(LsWidgets.shareRow(this, share) { token ->
                pendingShareToken = token
                shareAddPicker.launch(arrayOf("*/*"))
            })
        }
    }

    /** Files picked for an existing link go to that token, not to the outbox. */
    private fun onShareFilesAdded(uris: List<Uri>?) {
        val token = pendingShareToken
        pendingShareToken = null
        if (uris.isNullOrEmpty() || token == null) return
        LsManager.addFilesToShare(token, uris)
    }
}
