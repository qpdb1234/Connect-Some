/*
 * LocalSend's floating overlay, styled after the remote-control mini window: a rounded card the
 * user can drag anywhere, resize from its bottom dots and corners, fold to an edge tab and enlarge
 * back to near full screen, added as a TYPE_APPLICATION_OVERLAY window through the application
 * context's WindowManager so it survives the standalone page finishing and the stream viewer going
 * into the background, and can sit beside the remote-control [com.limelight.ui.vnc.MiniWindow].
 *
 * The card carries the mini window's whole chrome, adapted to a content window: the top bar's three
 * icons are window controls (fold, enlarge/restore, close) rather than the mini window's
 * remote-control actions, and a double tap on the drag handle runs the same enlarge/restore as the
 * top-bar icon. The body is as complete as the standalone page: the receive switch, every action
 * the page offers (add files, send text, create a link, search, add by IP, stop sharing) and all
 * four live lists (discovered devices, favorites with a clear-all, the outbox and the manageable
 * download links), plus the in-flight progress bar.
 *
 * Nothing here speaks the LocalSend protocol: the flows on [LsManager] are what this card and the
 * full page both collect, so opening the float does not start a second peer table or receiver. The
 * actions that genuinely need a real window - raising the system picker, typing a message, the link
 * dialog, a device's option menu, confirming the favorites wipe - hop through the transparent
 * [LsFloatPickerActivity]; the overlay itself stays passive (FLAG_NOT_FOCUSABLE) and never raises a
 * key or steals focus.
 *
 * The box is remembered across shows: one placement (width, height, x, y) kept in a small private
 * preferences file, clamped back on screen on load. Fractions are not used because there is a single
 * orientation-agnostic box here, unlike the mini window's per-orientation slots.
 */
package com.limelight.localsend

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.limelight.R
import com.limelight.util.inAppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot

object LsFloatingWindow {

    /** Default and extreme box sizes; the card is a resizable box, so these bound the grip travel. */
    private const val DEFAULT_W_DP = 280f
    private const val DEFAULT_H_DP = 460f
    private const val MIN_W_DP = 180f
    private const val MIN_H_DP = 260f

    /** A maximized card fills this much of the screen; keeps a sliver of background visible. */
    private const val MAX_FRACTION = 0.92f

    /** Where a never-placed card lands, as a fraction of the screen. */
    private const val INITIAL_X_FRACTION = .05f
    private const val INITIAL_Y_FRACTION = .15f

    /** The ball's own size (56dp disc plus its 10dp padding on each side) for the free-drag clamp. */
    private const val TAB_HEIGHT_DP = 76f
    private const val TAB_WIDTH_DP = 76f
    private const val TAB_X_FRACTION = .0f
    private const val TAB_Y_FRACTION = .3f

    /** Below this finger travel a press is a tap, not a drag; and the double-tap window. */
    private const val DRAG_THRESHOLD_DP = 4f
    private const val DOUBLE_TAP_MS = 300L

    /** Where the box, position and tab position biases are remembered. */
    private const val PREFS = "ls_float"
    private const val KEY_W = "w"
    private const val KEY_H = "h"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_TAB_Y = "tab_y"
    private const val KEY_TAB_X = "tab_x"

    private var app: Context? = null
    private var wm: WindowManager? = null

    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** The folded-to-edge tab, present only while [collapsed]. */
    private var tabView: View? = null
    private var tabParams: WindowManager.LayoutParams? = null

    private var collapsed = false
    private var collapsedX = 0
    private var maximized = false
    private var preMaxBox: IntArray? = null

    /**
     * True while the card is detached for the length of a [LsFloatPickerActivity] hop. The overlay
     * is a TYPE_APPLICATION_OVERLAY window, which the system stacks above every ordinary app window
     * - including the dialog (or the system picker) the hop raises - so an on-screen card would
     * cover the very thing it asked for. Parked state keeps the box, the collectors and the lists
     * intact; the card is re-attached, exactly where it was, once the hop finishes.
     */
    private var parked = false

    /** Whether the download-link list is unfolded; the header is the switch. */
    private var sharesExpanded = true

    /**
     * One scope per shown card: cancelling it in [hide] tears down every flow collector attached to
     * the overlay. Rebuilt on the next show, so a card that was hidden and reopened does not
     * inherit collectors pointing at the previous (already removed) view.
     */
    private var scope: CoroutineScope? = null

    /** True while the card is added, including while it is folded into an edge tab. */
    val isVisible: Boolean get() = rootView != null

    /**
     * Shows the card if the user has already granted the overlay permission, otherwise opens the
     * system settings screen for it (with a Toast first so the jump is not a mystery). Callers do
     * not have to pre-check: this is the single place the grant is resolved.
     */
    fun show(context: Context) {
        if (isVisible) return
        // The card is built on the application context because it has to outlive whichever window
        // opened it, and that context answers in the device's language rather than the one picked
        // in the app's settings - the stored locale only ever reaches AppCompat activities. Putting
        // it back here is what makes every string the card shows, this layout's and the rows the
        // renderers add later, read in the language the user chose.
        val a = context.applicationContext.inAppLanguage()
        if (!Settings.canDrawOverlays(a)) {
            Toast.makeText(a, R.string.msg_localsend_float_needs_overlay,
                    Toast.LENGTH_LONG).show()
            requestPermission(a)
            return
        }
        val manager = a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        app = a
        wm = manager
        parked = false

        val root = LayoutInflater.from(a).inflate(R.layout.ls_floating_window, null)
        val p = loadParams(a)
        params = p
        rootView = root

        // Window controls: fold to an edge tab, enlarge/restore, close.
        root.findViewById<ImageButton>(R.id.ls_float_collapse).setOnClickListener { collapse() }
        root.findViewById<ImageButton>(R.id.ls_float_expand).setOnClickListener { toggleMaximize() }
        root.findViewById<ImageButton>(R.id.ls_float_close).setOnClickListener { hide() }

        val receiveSwitch = root.findViewById<Switch>(R.id.ls_float_receive_switch)
        receiveSwitch.isChecked = LsManager.receiveEnabled.value
        receiveSwitch.setOnCheckedChangeListener { _, checked ->
            if (LsManager.receiveEnabled.value != checked) LsManager.setReceiveEnabled(checked)
        }

        // The three hops that need a real window (picker / IME / link dialog) go through the
        // transparent bridge Activity; the overlay itself stays passive and never raises a key.
        root.findViewById<TextView>(R.id.ls_float_add_files).setOnClickListener {
            LsFloatPickerActivity.launch(a, LsFloatPickerActivity.ACTION_ADD_FILES)
        }
        root.findViewById<TextView>(R.id.ls_float_send_text).setOnClickListener {
            LsFloatPickerActivity.launch(a, LsFloatPickerActivity.ACTION_SEND_TEXT)
        }
        root.findViewById<TextView>(R.id.ls_float_share_link).setOnClickListener {
            LsFloatPickerActivity.launch(a, LsFloatPickerActivity.ACTION_SHARE_LINK)
        }
        root.findViewById<TextView>(R.id.ls_float_add_ip).setOnClickListener {
            LsFloatPickerActivity.launch(a, LsFloatPickerActivity.ACTION_ADD_BY_IP)
        }

        val rescanBtn = root.findViewById<TextView>(R.id.ls_float_rescan)
        rescanBtn.setOnClickListener {
            // Same one-button-two-jobs rule as the full page: tap starts a walk, tap again stops it.
            if (LsManager.rescanInFlight.value) LsManager.stopRescan() else LsManager.rescan()
        }

        val stopShares = root.findViewById<TextView>(R.id.ls_float_stop_shares)
        stopShares.setOnClickListener {
            LsManager.stopShares()
            renderShares(a, root)
        }

        root.findViewById<TextView>(R.id.ls_float_clear_favorites).setOnClickListener {
            LsFloatPickerActivity.launch(a, LsFloatPickerActivity.ACTION_CLEAR_FAVORITES)
        }
        root.findViewById<TextView>(R.id.ls_float_remove_all).setOnClickListener {
            LsManager.clearStaged()
        }
        root.findViewById<TextView>(R.id.ls_float_shares_header).setOnClickListener {
            sharesExpanded = !sharesExpanded
            renderShares(a, root)
        }

        // The chrome gestures: drag to move (double tap enlarges/restores), grips to resize.
        installDragHandle(root.findViewById(R.id.ls_float_drag_handle), p)
        installGrip(root.findViewById(R.id.ls_float_resize_left), growLeft = true)
        installGrip(root.findViewById(R.id.ls_float_resize_right), growLeft = false)
        installGrip(root.findViewById(R.id.ls_float_corner_left), growLeft = true)
        installGrip(root.findViewById(R.id.ls_float_corner_right), growLeft = false)

        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = localScope
        localScope.launch { LsManager.peers.collect { renderPeers(a, root) } }
        localScope.launch { LsManager.favorites.collect { renderPeers(a, root) } }
        localScope.launch { LsManager.receiveEnabled.collect { enabled ->
            if (receiveSwitch.isChecked != enabled) receiveSwitch.isChecked = enabled
        } }
        localScope.launch { LsManager.staged.collect { renderStaged(a, root, it) } }
        localScope.launch { LsManager.transferProgress.collect {
            renderProgress(root, it)
        } }
        localScope.launch { LsManager.shares.collect { renderShares(a, root) } }
        localScope.launch { LsManager.rescanInFlight.collect { running ->
            // The button stays live the whole walk so it can be tapped to stop; it reads "Stop"
            // while searching and "Search again" otherwise. The status line carries the percentage.
            rescanBtn.isEnabled = true
            rescanBtn.setText(
                    if (running) R.string.action_localsend_stop else R.string.action_localsend_rescan)
            root.findViewById<TextView>(R.id.ls_float_status).visibility =
                    if (running) View.VISIBLE else View.GONE
            if (running) root.findViewById<TextView>(R.id.ls_float_status)
                    .setText(R.string.action_localsend_rescanning)
        } }
        localScope.launch { LsManager.scanProgress.collect { prog ->
            if (prog == null || !LsManager.rescanInFlight.value) return@collect
            val (done, total) = prog
            val percent = if (total > 0) done * 100 / total else 0
            root.findViewById<TextView>(R.id.ls_float_status).text =
                    a.getString(R.string.msg_localsend_rescan_progress, percent, done, total)
        } }

        // Prime the views with the current snapshot; the collectors above only fire on the next
        // change, so without this the card would open empty and fill a second later.
        renderPeers(a, root)
        renderStaged(a, root, LsManager.staged.value)
        renderProgress(root, LsManager.transferProgress.value)
        renderShares(a, root)

        runCatching { manager.addView(root, p) }
                .onFailure {
                    // A dead application context, a revoked permission mid-race, or a rotation that
                    // pulled the display out from under us. Nothing here is worth crashing a stream
                    // the user is running the overlay next to, so the card is simply reported as not
                    // shown and any collectors the priming started are dropped.
                    runCatching { localScope.cancel() }
                    scope = null
                    rootView = null
                    params = null
                    app = null
                    wm = null
                    Toast.makeText(a, R.string.msg_localsend_float_needs_overlay,
                            Toast.LENGTH_SHORT).show()
                }
                // The card is live: keep hearing announcements while it is on screen, exactly as the
                // full page does in its onResume, so the passive peer still lands in about a second
                // whether or not receiving is on. Released in [hide] to match this holder.
                .onSuccess { LsManager.startUiDiscovery() }
    }

    fun hide() {
        val root = rootView ?: return
        LsManager.stopUiDiscovery()
        saveBox()
        removeTab()
        val manager = wm ?: root.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope?.cancel()
        scope = null
        runCatching { manager.removeView(root) }
        rootView = null
        params = null
        app = null
        wm = null
        collapsed = false
        maximized = false
        preMaxBox = null
        parked = false
    }

    /**
     * Detach the card for the duration of a [LsFloatPickerActivity] hop so the dialog or system
     * picker that hop raises is not hidden behind this always-on-top overlay. A no-op when the card
     * is not shown or is already parked; the collectors keep running against the detached view, and
     * any layout push they make is swallowed until [unparkAfterDialog] re-attaches it.
     */
    fun parkForDialog() {
        val root = rootView ?: return
        if (parked) return
        parked = true
        runCatching { wm?.removeView(root) }
    }

    /** Re-attach a card parked by [parkForDialog]; a no-op unless one is actually parked. */
    fun unparkAfterDialog() {
        val root = rootView ?: return
        if (!parked) return
        parked = false
        runCatching { wm?.addView(root, params) }
    }

    /** Same toggle semantics the toolbar's collapse buttons use: one entry point, either way. */
    fun toggle(context: Context) {
        if (isVisible) hide() else show(context)
    }

    // region window chrome

    /** Folds the card off the left edge and shows a tab in its place; collectors keep running. */
    private fun collapse() {
        val p = params ?: return
        if (collapsed || rootView == null) return
        collapsedX = p.x
        collapsed = true
        p.x = -p.width
        pushLayout()
        showTab()
    }

    /** Brings the card back from its edge tab. */
    private fun expandFromTab() {
        if (!collapsed) return
        val p = params ?: return
        if (rootView == null) return
        collapsed = false
        removeTab()
        p.x = collapsedX.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
        pushLayout()
    }

    private fun showTab() {
        if (tabView != null) return
        val a = app ?: return
        val manager = wm ?: return
        val tab = LayoutInflater.from(a).inflate(R.layout.ls_float_tab, null)
        val tp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, overlayType(), passiveFlags(),
                PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = tabPositionX(a)
            y = tabPositionY(a)
        }
        setupTabDrag(tab, tp)
        tabView = tab
        tabParams = tp
        runCatching { manager.addView(tab, tp) }
                .onFailure {
                    tabView = null
                    tabParams = null
                }
    }

    private fun removeTab() {
        val tab = tabView ?: return
        runCatching { wm?.removeView(tab) }
        tabView = null
        tabParams = null
    }

    private fun tabPositionX(context: Context) =
            (context.resources.displayMetrics.widthPixels * tabXBias(context))
                    .toInt().coerceIn(0, tabPositionSpanX())

    private fun tabPositionY(context: Context) =
            (context.resources.displayMetrics.heightPixels * tabYBias(context))
                    .toInt().coerceIn(0, tabPositionSpanY())

    private fun tabPositionSpanX() = (screenW() - dp(TAB_WIDTH_DP)).coerceAtLeast(0)

    private fun tabPositionSpanY() = (screenH() - dp(TAB_HEIGHT_DP)).coerceAtLeast(0)

    private fun tabXBias(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getFloat(KEY_TAB_X, TAB_X_FRACTION).coerceIn(0f, 1f)

    private fun tabYBias(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getFloat(KEY_TAB_Y, TAB_Y_FRACTION).coerceIn(0f, 1f)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabDrag(tab: View, tp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        tab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = tp.x
                    startY = tp.y
                    dragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // Free drag: any direction past the threshold moves the ball, not just vertical.
                    if (!dragged && hypot(dx, dy) < dp(DRAG_THRESHOLD_DP)) return@setOnTouchListener true
                    dragged = true
                    tp.x = (startX + dx.toInt()).coerceIn(0, tabPositionSpanX())
                    tp.y = (startY + dy.toInt()).coerceIn(0, tabPositionSpanY())
                    runCatching { wm?.updateViewLayout(tab, tp) }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) saveTabPosition(tp) else expandFromTab()
                }
            }
            true
        }
    }

    private fun saveTabPosition(tp: WindowManager.LayoutParams) {
        val a = app ?: return
        val spanX = tabPositionSpanX()
        val spanY = tabPositionSpanY()
        val biasX = if (spanX > 0) (tp.x.toFloat() / spanX).coerceIn(0f, 1f) else TAB_X_FRACTION
        val biasY = if (spanY > 0) (tp.y.toFloat() / spanY).coerceIn(0f, 1f) else TAB_Y_FRACTION
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_TAB_X, biasX)
                .putFloat(KEY_TAB_Y, biasY).apply()
    }

    /** Toggles the card between its remembered box and a near-full-screen enlarged box. */
    private fun toggleMaximize() {
        val p = params ?: return
        if (!maximized) {
            preMaxBox = intArrayOf(p.width, p.height, p.x, p.y)
            val w = (screenW() * MAX_FRACTION).toInt()
            val h = (screenH() * MAX_FRACTION).toInt()
            p.width = w
            p.height = h
            p.x = ((screenW() - w) / 2).coerceAtLeast(0)
            p.y = ((screenH() - h) / 2).coerceAtLeast(0)
            maximized = true
        } else {
            preMaxBox?.let {
                p.width = it[0]; p.height = it[1]; p.x = it[2]; p.y = it[3]
            }
            maximized = false
        }
        pushLayout()
        saveBox()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDragHandle(handle: View, p: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        var lastTapAt = 0L
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && hypot(dx, dy) < dp(DRAG_THRESHOLD_DP)) return@setOnTouchListener true
                    dragged = true
                    // Keep the whole card on screen: with a resize grip, a top-bar fold and an edge
                    // tab all reachable from inside the box, there is nothing to gain from letting it
                    // hang off an edge where a control could slip out of reach.
                    p.x = (startX + dx.toInt()).coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
                    p.y = (startY + dy.toInt()).coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    pushLayout()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        saveBox()
                        lastTapAt = 0L
                    } else {
                        // Two taps on the handle are a double tap; the second enlarges/restores and
                        // both are swallowed so a double tap is not also read as two singles.
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt in 1 until DOUBLE_TAP_MS) {
                            lastTapAt = 0L
                            toggleMaximize()
                        } else {
                            lastTapAt = now
                        }
                    }
                }
            }
            // Consumed: dragging the handle must not fall through to whatever is under the card.
            true
        }
    }

    /**
     * A resize grip. [growLeft] grips the card's left edge (it moves while the right edge stays) -
     * used on the left dot and bottom-left corner; otherwise the right edge moves. Both grow the
     * bottom edge downward. The new size is clamped between the min box and [MAX_FRACTION] of the
     * screen, and the position is re-clamped so a widened card never runs off the display.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installGrip(view: View, growLeft: Boolean) {
        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0
        var startX = 0
        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startW = p.width
                    startH = p.height
                    startX = p.x
                }

                MotionEvent.ACTION_MOVE -> {
                    val dX = (event.rawX - downX).toInt()
                    val dY = (event.rawY - downY).toInt()
                    val minW = dp(MIN_W_DP)
                    val minH = dp(MIN_H_DP)
                    val maxW = (screenW() * MAX_FRACTION).toInt()
                    val maxH = (screenH() * MAX_FRACTION).toInt()
                    if (growLeft) {
                        val newW = (startW - dX).coerceIn(minW, maxW)
                        p.width = newW
                        p.x = (startX + startW - newW).coerceIn(0, (screenW() - newW).coerceAtLeast(0))
                    } else {
                        p.width = (startW + dX).coerceIn(minW, maxW)
                        p.x = startX.coerceIn(0, (screenW() - p.width).coerceAtLeast(0))
                    }
                    p.height = (startH + dY).coerceIn(minH, maxH)
                    p.y = p.y.coerceIn(0, (screenH() - p.height).coerceAtLeast(0))
                    maximized = false
                    pushLayout()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveBox()
            }
            // Consumed: resizing must not land as a click on the card's content beneath.
            true
        }
    }

    private fun pushLayout() {
        val root = rootView ?: return
        val p = params ?: return
        runCatching { wm?.updateViewLayout(root, p) }
    }

    // endregion

    // region box persistence

    private fun loadParams(context: Context): WindowManager.LayoutParams {
        val d = context.resources.displayMetrics
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxW = (d.widthPixels * MAX_FRACTION).toInt()
        val maxH = (d.heightPixels * MAX_FRACTION).toInt()
        val minW = dp(MIN_W_DP)
        val minH = dp(MIN_H_DP)

        var w = prefs.getInt(KEY_W, dp(DEFAULT_W_DP)).coerceIn(minW, maxW)
        var h = prefs.getInt(KEY_H, dp(DEFAULT_H_DP)).coerceIn(minH, maxH)
        // A never-placed card lands near the top-left; a remembered one is clamped back on screen
        // (the display may have shrunk since it was saved, e.g. after a rotation).
        val maxX = (d.widthPixels - w).coerceAtLeast(0)
        val maxY = (d.heightPixels - h).coerceAtLeast(0)
        val x = (if (prefs.contains(KEY_X)) prefs.getInt(KEY_X, 0)
                 else (d.widthPixels * INITIAL_X_FRACTION).toInt()).coerceIn(0, maxX)
        val y = (if (prefs.contains(KEY_Y)) prefs.getInt(KEY_Y, 0)
                 else (d.heightPixels * INITIAL_Y_FRACTION).toInt()).coerceIn(0, maxY)

        return WindowManager.LayoutParams().apply {
            type = overlayType()
            format = PixelFormat.TRANSLUCENT
            flags = passiveFlags()
            gravity = Gravity.TOP or Gravity.START
            width = w
            height = h
            this.x = x
            this.y = y
        }
    }

    private fun saveBox() {
        val p = params ?: return
        val a = app ?: return
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_W, p.width)
                .putInt(KEY_H, p.height)
                .putInt(KEY_X, p.x)
                .putInt(KEY_Y, p.y)
                .apply()
    }

    private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

    /**
     * Passive by default: taps on the card's own buttons still reach them, but neither the IME nor
     * the back key is claimed, which is what lets the card coexist with the mini window and the
     * assist ball without a fight. LAYOUT_IN_SCREEN + LAYOUT_NO_LIMITS (the same pair the
     * remote-control mini window uses) opt out of the window manager clamping the window to the
     * display cutout / insets.
     */
    private fun passiveFlags(): Int =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun screenW() = app?.resources?.displayMetrics?.widthPixels ?: 0

    private fun screenH() = app?.resources?.displayMetrics?.heightPixels ?: 0

    private fun dp(value: Float): Int =
            (value * (app?.resources?.displayMetrics?.density ?: 1f)).toInt()

    // endregion

    // region rendering

    private fun renderPeers(context: Context, root: View) {
        renderDiscovered(context, root)
        renderFavorites(context, root)
    }

    private fun renderDiscovered(context: Context, root: View) {
        val box = root.findViewById<LinearLayout>(R.id.ls_float_peers)
        val label = root.findViewById<TextView>(R.id.ls_float_devices_label)
        val peers = LsManager.peers.value
        box.removeAllViews()
        label.isVisible = true
        if (peers.isEmpty()) {
            box.addView(TextView(context).apply {
                setText(R.string.label_localsend_searching)
                textSize = 12f
                setTextColor(context.resources.getColor(R.color.colorMiniOnCard))
            })
            return
        }
        val pinned = LsManager.favorites.value.map { it.fingerprint }.toSet()
        peers.forEach { peer ->
            val favorite = peer.fingerprint in pinned
            box.addView(LsWidgets.deviceRow(context, peer, favorite = favorite,
                    textSizeSp = 12f,
                    onTap = { sendStaged(it, context) },
                    onLongPress = { openDeviceOptions(it.fingerprint, favorite, context) }))
        }
    }

    private fun renderFavorites(context: Context, root: View) {
        val box = root.findViewById<LinearLayout>(R.id.ls_float_favorites)
        val label = root.findViewById<TextView>(R.id.ls_float_favorites_label)
        val clear = root.findViewById<TextView>(R.id.ls_float_clear_favorites)
        val favorites = LsManager.favorites.value
        box.removeAllViews()
        label.isVisible = favorites.isNotEmpty()
        clear.isVisible = favorites.isNotEmpty()
        val live = LsManager.peers.value.map { it.fingerprint }.toSet()
        favorites.forEach { peer ->
            box.addView(LsWidgets.deviceRow(context, peer, favorite = true,
                    textSizeSp = 12f,
                    onTap = { sendStaged(it, context) },
                    onLongPress = { openDeviceOptions(it.fingerprint, true, context) }))
            // Mark the ones discovery has not confirmed as away, so an offline bookmark reads as
            // expected rather than a live device. The row already shows its saved address.
            if (peer.fingerprint !in live) {
                (box.getChildAt(box.childCount - 1) as? TextView)?.apply {
                    text = context.getString(R.string.label_localsend_device_row,
                            peer.alias, context.getString(R.string.label_localsend_offline))
                    tooltipText = text
                }
            }
        }
    }

    /**
     * Long press on any device row opens the same option menu the full page shows (pin / unpin),
     * run on the bridge Activity - the overlay cannot host a dialog of its own.
     */
    private fun openDeviceOptions(fingerprint: String, favorite: Boolean, context: Context) {
        LsFloatPickerActivity.launchDeviceOptions(context, fingerprint, favorite)
    }

    /**
     * Sends what the outbox is holding, without leaving the overlay. Any pick / add / edit the user
     * wants next goes through the bridge Activity or the full page - which is what the row's own
     * short toast tells them to open if the outbox is empty.
     */
    private fun sendStaged(peer: LsPeer, context: Context) {
        if (LsManager.staged.value.isEmpty()) {
            Toast.makeText(context, R.string.msg_localsend_nothing_staged,
                    Toast.LENGTH_SHORT).show()
            return
        }
        if (!LsManager.stagedReadable()) {
            Toast.makeText(context, R.string.msg_localsend_uri_expired, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, R.string.msg_localsend_sending, Toast.LENGTH_SHORT).show()
        LsManager.sendStaged(peer) { result ->
            Toast.makeText(context, LsWidgets.sendResultMessage(context, result),
                    Toast.LENGTH_LONG).show()
        }
    }

    /** The outbox: one removable row per file, a header, and the whole-batch removal entry. */
    private fun renderStaged(context: Context, root: View, files: List<LsStagedFile>) {
        val box = root.findViewById<LinearLayout>(R.id.ls_float_staged)
        val label = root.findViewById<TextView>(R.id.ls_float_staged_label)
        val removeAll = root.findViewById<TextView>(R.id.ls_float_remove_all)
        box.removeAllViews()
        label.isVisible = files.isNotEmpty()
        removeAll.isVisible = files.isNotEmpty()
        if (files.isNotEmpty()) label.text = context.getString(R.string.title_localsend_outbox)
        files.forEach { file ->
            box.addView(LsWidgets.stagedRow(context, file, textSizeSp = 12f)
                    { LsManager.removeStaged(it) })
        }
    }

    /** Mirror the in-flight transfer (out or in) into the card's bar and its caption. */
    private fun renderProgress(root: View, progress: LsTransferProgress?) {
        val bar = root.findViewById<ProgressBar>(R.id.ls_float_progress)
        val label = root.findViewById<TextView>(R.id.ls_float_progress_label)
        if (progress == null) {
            bar.isVisible = false
            label.isVisible = false
            return
        }
        val context = bar.context
        val percent = if (progress.totalBytes > 0)
            (progress.doneBytes * 100 / progress.totalBytes).toInt() else 0
        bar.progress = percent
        bar.isVisible = true
        val verb = context.getString(if (progress.incoming) R.string.label_localsend_receiving
        else R.string.label_localsend_sending)
        label.isVisible = true
        label.text = if (progress.fileCount > 1)
            context.getString(R.string.label_localsend_progress_multi,
                    verb, progress.fileIndex, progress.fileCount, progress.label)
        else
            context.getString(R.string.label_localsend_progress, verb, progress.label)
    }

    /**
     * The live download links under the button that made them: a collapsible header carrying the
     * count and one manageable row per link, so a link can be copied, expanded, given more files or
     * revoked without leaving the card. Nothing to show hides both the header and the container.
     */
    private fun renderShares(context: Context, root: View) {
        val header = root.findViewById<TextView>(R.id.ls_float_shares_header)
        val box = root.findViewById<LinearLayout>(R.id.ls_float_shares)
        val stop = root.findViewById<TextView>(R.id.ls_float_stop_shares)
        val shares = LsManager.shares.value

        stop.isVisible = LsManager.sharesActive()

        val arrow = if (sharesExpanded) "\u25be" else "\u25b8"
        header.isVisible = shares.isNotEmpty()
        header.text = context.getString(R.string.title_localsend_shares, arrow, shares.size)
        box.removeAllViews()
        box.isVisible = sharesExpanded && shares.isNotEmpty()
        if (!sharesExpanded) return
        shares.forEach { share -> box.addView(shareRow(context, share)) }
    }

    /** One manageable download link: the URL, a copy, and per-link actions over a collapsible file list. */
    private fun shareRow(context: Context, share: ShareInfo): LinearLayout {
        val pad = context.resources.getDimensionPixelSize(R.dimen.padding_small)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, pad, 0, pad)
        }

        val urlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        urlRow.addView(TextView(context).apply {
            text = share.url
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTextColor(context.resources.getColor(R.color.colorMiniOnCard))
            tooltipText = share.url
            isLongClickable = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(textAction(context, context.getString(R.string.action_localsend_copy)) {
            copyToClipboard(context, share.url)
        })
        root.addView(urlRow)

        val filesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val rebuild = {
            filesContainer.removeAllViews()
            val current = LsManager.shares.value.firstOrNull { it.token == share.token }?.files
                    ?: share.files
            for (file in current) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(context).apply {
                    text = context.getString(R.string.label_localsend_shared_file_row,
                            file.name, Formatter.formatShortFileSize(context, file.size))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextAppearance(android.R.style.TextAppearance_Small)
                    setTextColor(context.resources.getColor(R.color.colorMiniOnCard))
                    tooltipText = text
                    isLongClickable = true
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(textAction(
                        context, context.getString(R.string.action_localsend_remove_from_share)) {
                    LsManager.removeSharedFile(share.token, file.id)
                })
                filesContainer.addView(row)
            }
        }
        // The toggle is the first of the three actions rather than a line of its own, exactly as
        // the full page arranges them: all three are labels of a similar length, and the card is
        // wide enough to carry them side by side under the address.
        val toggle = textAction(
                context, context.getString(R.string.label_localsend_shared_files, share.files.size)) {
            val open = filesContainer.visibility != View.VISIBLE
            filesContainer.visibility = if (open) View.VISIBLE else View.GONE
            if (open) rebuild()
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(toggle)
        actionRow.addView(textAction(
                context, context.getString(R.string.action_localsend_add_to_share)) {
            LsFloatPickerActivity.launchAddToShare(context, share.token)
        })
        actionRow.addView(textAction(
                context, context.getString(R.string.action_localsend_revoke)) {
            LsManager.revokeShare(share.token)
        })
        root.addView(actionRow)

        root.addView(filesContainer)
        return root
    }

    /**
     * A compact blue text button for a row of actions, matching the page's share rows. The
     * highlight is bounded rather than borderless like the icons: these labels are barely taller
     * than their text, and an unbounded ripple grows into a circle that spills over the lines
     * above and below - which the card, drawn over everything, has nowhere to clip it against.
     */
    private fun textAction(context: Context, label: String, onClick: () -> Unit): TextView {
        val small = context.resources.getDimensionPixelSize(R.dimen.padding_small)
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return TextView(context).apply {
            text = label
            setTextAppearance(android.R.style.TextAppearance_Small)
            setTextColor(context.resources.getColor(android.R.color.holo_blue_light))
            setPadding(small, small, small, small)
            isClickable = true
            isFocusable = true
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.dialog_localsend_link_title), text))
        Toast.makeText(context, R.string.action_localsend_copy, Toast.LENGTH_SHORT).show()
    }

    // endregion

    /** Opens the system's per-app "display over other apps" screen; safe to call repeatedly. */
    private fun requestPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Whether the current build allows the card to be shown without a settings hop. */
    fun hasPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}
