/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.limelight.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.localsend.LsManager
import com.limelight.localsend.LsFavoritesAdapter
import com.limelight.localsend.LsFloatingWindow
import com.limelight.localsend.LsPeer
import com.limelight.localsend.LsStagedFile
import com.limelight.localsend.LsTransferProgress
import com.limelight.localsend.LsWidgets
import com.limelight.model.ServerProfile
import com.limelight.session.Messenger
import com.limelight.ui.prefs.ColorPickerPanel
import com.limelight.util.AccentTheme
import com.limelight.util.AppPreferences
import com.limelight.util.installTooltips
import com.limelight.util.showImmersiveIfNeeded
import com.limelight.vnc.RenderMode
import com.limelight.viewmodel.VncViewModel
import com.limelight.viewmodel.VncViewModel.Companion.ASSIST_BALL_SIZE_MAX_DP
import com.limelight.viewmodel.VncViewModel.Companion.ASSIST_BALL_SIZE_MIN_DP
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 *
 * Overview of toolbar layout:
 *
 *                DrawerLayout
 *   +-------------------+--------------+
 *   |                   |              |
 *   |   Toolbar Drawer  |              |
 *   |    [drawerView]   |              |
 *   |                   |              |
 *   |+---+              |              |
 *   || B |              |              |
 *   || t |              |              |
 *   || n |+------------+|    Scrim     |
 *   || s ||  Flyouts   ||              |
 *   |+---++------------+|              |
 *   |                   |              |
 *   |                   |              |
 *   |                   |              |
 *   |                   |              |
 *   |                   |              |
 *   +-------------------+--------------+
 *
 * User can align the toolbar to left or right edge.
 *
 */
class Toolbar(private val activity: VncActivity) {
    private val viewModel = activity.viewModel
    private val binding = activity.binding.toolbar
    private val drawerLayout = activity.binding.drawerLayout
    private val drawerView = binding.root
    private val openWithSwipe = viewModel.pref.viewer.toolbarOpenWithSwipe
    private val flyouts = mutableMapOf<ToggleButton, View>()

    /**
     * What we last asked the remote device to do, for the two actions the protocol only
     * ever reports as a request. Neither can be confirmed on the stream, so the buttons
     * alternate; [VncViewModel.queryRemoteScreenOn] and [VncViewModel.queryRemoteNightMode]
     * seed the alternation with the device's real state on every connection, and the
     * `*Override` flags stop a reply that arrives late - after the user has already tapped -
     * from putting the parity back where it started.
     */
    private var remoteScreenOn = true
    private var screenOnOverride = false

    /**
     * Night mode is read back through [VncViewModel.queryRemoteNightMode], so the button
     * gets seeded with the device's real mode on every connection instead of assuming
     * light.
     */
    private var remoteNightMode = false
    private var nightModeOverride = false

    /** Whether the drawer's download-link list is unfolded; the panel header toggles it. */
    private var localSendSharesExpanded = true

    /** The drawer's favorites, dragged to reorder - the same adapter the standalone screen uses. */
    private var localSendFavoritesAdapter: LsFavoritesAdapter? = null

    /**
     * The drawer's buttons that the user can switch off: the view it belongs to, the
     * preference that records the choice, and the name the choice is listed under. Filled
     * once the profile is known (see [setupToolbarItems]).
     *
     * [hideable] false marks the one row the panel lists and reorders but can never tick
     * off - its own opener: a drawer emptied of every button would leave no way back in.
     * Its [prefKey] only identifies it inside the stored order and is never written to the
     * preferences.
     */
    private class Item(val view: View, val prefKey: String, val labelRes: Int,
            val hideable: Boolean = true)

    private val items = mutableListOf<Item>()

    /**
     * Whether a button can be used at all, as opposed to whether the user wants to see it:
     * the overlay, the mute switch and the device shortcuts all need a live client that
     * speaks of the thing they control. A button missing from this map is always usable.
     */
    private val itemAvailable = HashMap<View, Boolean>()

    /**
     * The panel edits a draft rather than the live [items]/preferences: [draft] is the
     * working order and [draftShown] the working visibility, both seeded from the committed
     * state when the panel opens and only written back on OK (see [commitDraft]). [defaults]
     * keeps the built-in order so the panel can offer a 'restore defaults'.
     */
    private var defaults: List<Item> = emptyList()
    private val draft = mutableListOf<Item>()
    private val draftShown = HashMap<String, Boolean>()
    private lateinit var itemsAdapter: ItemRowAdapter
    private var itemsTouchHelper: ItemTouchHelper? = null

    fun initialize() {
        binding.keyboardBtn.setOnClickListener { activity.showKeyboard(); close() }
        binding.zoomResetBtn.setOnClickListener { resetZoomToDefault(); close() }
        binding.zoomLockBtn.setOnCheckedChangeListener { _, checked -> toggleZoomLock(checked); close() }
        binding.zoomSaveBtn.setOnClickListener { saveZoom(); close() }
        binding.miniWindowBtn.setOnClickListener { showMiniWindow() }
        binding.letterboxColorBtn.setOnClickListener { showStreamBarColorDialog() }
        binding.exitBtn.setOnClickListener { close(); activity.finish() }

        // The key bar is switched from here, and from anywhere else that moves it - the soft
        // keyboard raising it under `vk_open_with_keyboard`, picture-in-picture putting it
        // down, the connection restoring what the last session left. So the tick is not written
        // by this tap but read back off the bar itself, which is the only one holding the truth.
        binding.virtualKeysBtn.setOnClickListener {
            if (activity.virtualKeys.visible.value == true)
                activity.virtualKeys.hide(true)
            else
                activity.virtualKeys.show(true)
            close()
        }
        activity.virtualKeys.visible.observe(activity) { visible ->
            binding.virtualKeysBtn.isChecked = visible == true
        }

        // Performance overlay toggle (latency / FPS / dropped frames). Reflects and
        // updates the persisted preference; metrics are always collected by the kernel,
        // so this only flips visibility and takes effect immediately. Close the drawer
        // so the freshly-enabled overlay is visible over the stream.
        binding.statsToggle.setOnCheckedChangeListener { _, checked ->
            viewModel.setStatsOverlayEnabled(checked)
            if (checked) close()
        }

        // Silence the remote audio on this phone. One tap, no slider: the host's volume is
        // not reachable over any protocol, so a level control would only move the phone's own
        // media volume, which the rocker keys already do. The choice is persisted like the
        // overlay above, so the next session comes back the way the user left it.
        binding.audioToggle.setOnCheckedChangeListener { _, checked ->
            viewModel.setAudioMuted(checked)
            updateAudioToggleIcon(checked)
        }

        // The row above is only an opener now: the marker's switch, its shape and its size all
        // live in [setupLocalCursorControls]'s panel. A stray tap down this column should not
        // be able to make a mark appear the user was trying to get rid of. The system arrow of
        // an attached mouse is not any of this button's business; that stays under
        // Settings -> Input.

        // Root view is transparent. Click on it should work just like a click in scrim area
        drawerView.setOnClickListener { close() }

        viewModel.inPiPMode.observe(activity) { if (it) close() }

        setupAlignment()
        setupFlyouts()
        setupFlyoutClose()
        setupViewModeSelection()
        setupGestureStyleSelection()
        setupLocalCursorControls()
        setupMouseSpeedControls()
        setupAssistBallControls()
        setupFullKeyboardControls()
        setupLocalSendControls()
        setupDrawerEdgeControls()
        setupAndroidActions()
        setupToolbarItems()
        setupGestureExclusionRect()
        setupDrawerCloseOnScrimSwipe()

        // Every button here is a bare icon whose only name is its contentDescription; make
        // holding one down say that name. See [installTooltips].
        installTooltips(drawerView)
    }

    fun open() {
        drawerLayout.openDrawer(drawerView)
    }

    fun close() {
        drawerLayout.closeDrawer(drawerView)
    }

    fun onStateChange(isConnected: Boolean) {
        if (Build.VERSION.SDK_INT >= 29)
            updateGestureExclusionRect()

        updateLockMode(isConnected)

        // Several of these only mean something while a client that can answer them is
        // alive: the overlay is fed by decoder metrics that some protocols do not produce,
        // the device shortcuts belong to a directly driven Android device, the mini window
        // has no picture to shrink before a stream exists, and there is nothing to silence
        // on a protocol without audio.
        itemAvailable[binding.miniWindowBtn] = isConnected
        itemAvailable[binding.statsToggle] = isConnected && viewModel.client?.supportsPerfOverlay == true
        itemAvailable[binding.androidActionsToggle] = isConnected && viewModel.androidSession
        itemAvailable[binding.audioToggle] = isConnected && viewModel.client?.supportsAudio == true
        itemAvailable[binding.localCursorToggle] = isConnected
        applyItemVisibility()

        // The mute button's state is a persisted viewer-wide choice rather than a property
        // of the live client, so nothing has to be synced per connection beyond the icon.
        val muted = viewModel.audioMuted.value == true
        binding.audioToggle.isChecked = muted
        updateAudioToggleIcon(muted)

        // One round trip per connection is cheap, and it is the only way the parity of
        // the night and screen buttons can be right from the start: a device already in
        // dark mode, or one that fell asleep and was reconnected to, both make a local
        // guess wrong until the second tap.
        if (isConnected && viewModel.androidSession) {
            nightModeOverride = false
            viewModel.queryRemoteNightMode { mode ->
                if (mode == null || nightModeOverride) return@queryRemoteNightMode
                remoteNightMode = mode == Messenger.NIGHT_MODE_YES
            }

            screenOnOverride = false
            viewModel.queryRemoteScreenOn { screenOn ->
                if (screenOn == null || screenOnOverride) return@queryRemoteScreenOn
                remoteScreenOn = screenOn
            }
        }
    }

    /**
     * The pressed background is a weak answer to "is it silenced right now?", so the speaker
     * icon says it too. [android.widget.ToggleButton] is a CompoundButton rather than an
     * ImageView, which is why the icon is swapped through the compound drawables.
     */
    private fun updateAudioToggleIcon(muted: Boolean) {
        binding.audioToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (muted) R.drawable.ic_audio_off else R.drawable.ic_audio, 0, 0, 0)
    }

    /**
     * Two things are balanced here: keeping the button column centered on the whole screen,
     * and fitting it around a display cutout.
     *
     * The column's `center_vertical` centers it within the drawer, but the drawer only spans the
     * window, and the window can be shorter than the display - a reserved navigation bar, or a
     * resize for the on-screen key row. Centering within that shorter window leaves the column
     * high on the actual screen. Adding the shortfall (the display's height minus the drawer's)
     * as top padding on the drawer moves the centering region down by half that shortfall, so the
     * column's middle lands on the screen's middle. When the window already fills the display the
     * shortfall is 0 and nothing changes; in landscape the bars run along the sides, so it is 0
     * there too.
     *
     * The padding is capped at the slack the column actually leaves inside the drawer (drawer
     * height minus column content height). Without the cap a column that fills or outgrows the
     * drawer would be pushed down by the full shortfall and its last rows would hang off the
     * bottom of the screen - shifting a too-tall column is never a centering, only a clipping.
     *
     * The other concern: if any cutout is found to overlap the toolbar, system window fitting is
     * enabled on the column, which automatically adds the required paddings.
     */
    fun handleInsets(insets: WindowInsetsCompat) {
        val shortfall = (displayHeightPx() - drawerView.height).coerceAtLeast(0)
        val slack = (drawerView.height - binding.toolbarColumn.height).coerceAtLeast(0)
        val pad = minOf(shortfall, slack)
        if (drawerView.paddingTop != pad)
            drawerView.setPaddingRelative(drawerView.paddingStart, pad,
                    drawerView.paddingEnd, drawerView.paddingBottom)

        val cutout = insets.displayCutout
        if (cutout == null || !viewModel.pref.viewer.drawBehindCutout)
            return

        val v = binding.primaryButtons
        val r = getActionableToolbarRect()
        // Rect.intersect mutates its receiver, and these rectangles belong to the insets
        // object that the framework hands out to every other listener as well. Intersecting
        // a copy keeps the cutout's own bounds intact.
        val shouldFit = cutout.boundingRects.find { Rect(it).intersect(r) } != null

        if (v.fitsSystemWindows != shouldFit) {
            v.fitsSystemWindows = shouldFit
            ViewCompat.onApplyWindowInsets(v, WindowInsetsCompat(insets))
        }
    }

    /**
     * The full display height in pixels, including any area the window declines to draw into.
     * `maximumWindowMetrics` reports the physical bounds on API 30+; below that the (deprecated)
     * real size is the equivalent. This is deliberately the whole display rather than the usable
     * height, because the column is to be centered on the screen the user sees, not on the strip
     * the window happens to occupy.
     */
    private fun displayHeightPx(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                activity.windowManager.maximumWindowMetrics.bounds.height()
            else {
                val size = android.graphics.Point()
                @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(size)
                size.y
            }

    private fun toast(@StringRes msgRes: Int) = Toast.makeText(activity, msgRes, Toast.LENGTH_SHORT).show()

    private fun toggleZoomLock(enabled: Boolean) {
        viewModel.toggleZoomLock(enabled)
        toast(if (enabled) R.string.msg_zoom_locked else R.string.msg_zoom_unlocked)
    }

    /** Return to the zoom saved as this host's default (tap on the reset button). */
    private fun resetZoomToDefault() {
        viewModel.resetZoomToDefault()
        toast(R.string.msg_zoom_reset_default)
    }

    private fun saveZoom() {
        viewModel.saveZoom()
        toast(R.string.msg_zoom_saved)
    }

    /**
     * Hand the picture to the floating window. Worth a word about what happens next on
     * the protocols that decode into a surface: their decoder is built around the viewer's
     * own surface, so the stream has to be set up again for the window to have one, which
     * shows as a momentary restart. Plain VNC hands over pixels instead and simply carries
     * on drawing.
     */
    private fun showMiniWindow() {
        close()

        if (!viewModel.connected) return

        // Not a runtime permission: it is special app access, which the user has to turn
        // on in settings. Asking is the only option, and the window is not shown until the
        // answer comes back yes - a silent failure here would look like a dead button.
        if (!Settings.canDrawOverlays(activity)) {
            toast(R.string.msg_mini_no_permission)
            openOverlaySettings()
            return
        }

        if (viewModel.renderMode == RenderMode.SURFACE)
            toast(R.string.msg_mini_reconnect)

        activity.miniWindow.show()
    }

    /**
     * Deep link to this app's entry on the 'display over other apps' list. Some builds
     * reject the package-specific form, so the plain settings screen is the fallback.
     */
    private fun openOverlaySettings() {
        val byPackage = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.packageName))
        val any = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

        if (runCatching { activity.startActivity(byPackage) }.isFailure)
            runCatching { activity.startActivity(any) }
                    .onFailure { Log.e("Toolbar", "No way to the overlay settings", it) }
    }

    private fun setupViewModeSelection() {
        val viewModeButtonMap = mapOf(
                ServerProfile.VIEW_MODE_NORMAL to R.id.view_mode_normal,
                ServerProfile.VIEW_MODE_NO_INPUT to R.id.view_mode_no_input,
                ServerProfile.VIEW_MODE_NO_VIDEO to R.id.view_mode_no_video
        )

        binding.viewModeGroup.let { group ->
            check(group.childCount == viewModeButtonMap.size)

            viewModel.activeViewMode.observe(activity) {
                group.check(viewModeButtonMap[it] ?: R.id.view_mode_normal)
            }

            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    if (viewModel.connected) { // Make sure profile is available
                        // A toggle group can report an id that is not one of ours (it uses
                        // NO_ID when the selection is cleared); `first` would throw on that.
                        val newMode = viewModeButtonMap.entries.firstOrNull { it.value == checkedId }?.key
                        if (newMode != null && viewModel.activeViewMode.value != newMode) {
                            viewModel.setViewMode(newMode)
                            showViewModeChangeToast(newMode)
                        }
                    }
                    close()
                }
            }
        }
    }

    private fun showViewModeChangeToast(newMode: Int) {
        when (newMode) {
            ServerProfile.VIEW_MODE_NORMAL -> toast(R.string.msg_normal_view_mode)
            ServerProfile.VIEW_MODE_NO_INPUT -> toast(R.string.msg_input_disabled)
        }
    }

    /**
     * Display name of a resolved gesture style, for the 'Automatic' label above.
     */
    private fun styleLabel(style: String?): CharSequence = when (style) {
        "touchscreen" -> activity.getString(R.string.pref_gesture_style_touchscreen)
        else -> activity.getString(R.string.pref_gesture_style_touchpad)
    }

    private fun setupGestureStyleSelection() {
        val styleButtonMap = mapOf(
                "auto" to R.id.gesture_style_auto,
                "touchscreen" to R.id.gesture_style_touchscreen,
                "touchpad" to R.id.gesture_style_touchpad
        )

        binding.gestureStyleGroup.let { group ->
            viewModel.activeGestureStyle.observe(activity) {
                // Retrieve gesture style from profile,
                // because activeGestureStyle doesn't contain the 'auto' option
                group.check(styleButtonMap[viewModel.profile.gestureStyle] ?: R.id.gesture_style_auto)

                // The word 'Automatic' says nothing on its own, so name the style it has
                // resolved to. `style` is never 'auto' (see resolveGestureStyle), which is
                // exactly the answer this label is looking for.
                binding.gestureStyleAuto.text = activity.getString(R.string.desc_gesture_style_auto_resolved,
                        styleLabel(it))
            }

            group.setOnCheckedChangeListener { _, id ->
                // Same trap as the view mode group: clearing the selection calls this with
                // View.NO_ID, which no style maps to.
                val newStyle = styleButtonMap.entries.firstOrNull { it.value == id }?.key
                if (newStyle != null && viewModel.connected) { // Make sure profile is available
                    viewModel.setGestureStyle(newStyle)
                }
                close()
            }
        }
    }

    /**
     * Settings of the local pointer marker: whether it is drawn at all, which of the two shapes
     * [InputView] paints and how big that shape is.
     *
     * These write straight to the viewer preference rather than through the viewmodel, because
     * nothing else reads them: the mark is redrawn by the [invalidate] below and by no other
     * component, so there is no second observer to notify. Both the shape and the size are
     * disabled while the marker itself is off, since a choice that cannot be seen invites the
     * question of why the slider does nothing.
     *
     * The values are also edited by the old settings screen for anyone who still has them,
     * hence seeding the widgets from the preference on every open of the drawer rather than
     * trusting that the panel was the last thing touched.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLocalCursorControls() {
        val viewer = viewModel.pref.viewer

        binding.cursorMarkerEnable.setOnCheckedChangeListener { _, checked ->
            viewer.showLocalCursorMarker = checked
            renderLocalCursorAvailability(checked)
            activity.binding.inputView.invalidate()
        }

        binding.cursorMarkerShapeGroup.setOnCheckedChangeListener { _, id ->
            viewer.cursorMarkerShape = if (id == R.id.cursor_marker_shape_dot)
                AppPreferences.CURSOR_MARKER_SHAPE_DOT
            else
                AppPreferences.CURSOR_MARKER_SHAPE_ARROW
            activity.binding.inputView.invalidate()
        }

        // The slider's range is the percentage itself, so there is nothing to map.
        binding.cursorMarkerSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                viewer.cursorMarkerSizePercent = progress.coerceIn(
                        AppPreferences.CURSOR_MARKER_SIZE_MIN, AppPreferences.CURSOR_MARKER_SIZE_MAX)
                renderCursorMarkerSize(viewer.cursorMarkerSizePercent)
                activity.binding.inputView.invalidate()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Same drawer-interception dance as the ball's sliders: a sideways drag towards the
        // closing edge is the drawer's, unless it started on this thumb.
        binding.cursorMarkerSize.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false // The slider handles the gesture itself
        }

        binding.cursorMarkerEnable.isChecked = viewer.showLocalCursorMarker
        binding.cursorMarkerShapeGroup.check(
                if (viewer.cursorMarkerShape == AppPreferences.CURSOR_MARKER_SHAPE_DOT)
                    R.id.cursor_marker_shape_dot else R.id.cursor_marker_shape_arrow)
        renderCursorMarkerSize(viewer.cursorMarkerSizePercent)
        renderLocalCursorAvailability(viewer.showLocalCursorMarker)

        // The marker's own color: a swatch that opens the palette, filled from the stored hex.
        // The mark repaints through InputView.invalidate, which reads the preference every frame,
        // so a picked color lands on the next draw with nothing else to refresh.
        binding.cursorMarkerColor.setOnClickListener { showCursorMarkerColorDialog() }
        paintCursorMarkerSwatch(viewer.cursorMarkerColor)

        // Back to what a fresh install draws for the look of the mark: the default dot at its
        // default size and color. The switch is deliberately left out of it - restoring the
        // defaults should bring back the shipped appearance, not also hide a marker the user had
        // turned on, which would read as the reset having switched the feature off. The widgets'
        // own listeners fire on the programmatic change below and write the same values, so
        // setting the preference first and letting the listeners echo it is both correct and
        // keeps the panel and the preference in step. Asks first: a reset has no undo.
        binding.cursorMarkerReset.setOnClickListener {
            confirmRestoreDefaults {
                viewer.cursorMarkerShape = AppPreferences.CURSOR_MARKER_SHAPE_DEFAULT
                viewer.cursorMarkerSizePercent = AppPreferences.CURSOR_MARKER_SIZE_DEFAULT
                viewer.cursorMarkerColor = AppPreferences.CURSOR_MARKER_COLOR_DEFAULT
                binding.cursorMarkerShapeGroup.check(R.id.cursor_marker_shape_dot)
                renderCursorMarkerSize(AppPreferences.CURSOR_MARKER_SIZE_DEFAULT)
                paintCursorMarkerSwatch(AppPreferences.CURSOR_MARKER_COLOR_DEFAULT)
                renderLocalCursorAvailability(viewer.showLocalCursorMarker)
                activity.binding.inputView.invalidate()
            }
        }
    }

    /** Fill the marker color swatch with the stored hex, so it shows the current choice. */
    private fun paintCursorMarkerSwatch(hex: String?) {
        (binding.cursorMarkerColor.background as? GradientDrawable)
                ?.setColor(AccentTheme.argbOf(hex))
    }

    /**
     * Palette for the pointer marker. Same continuous picker as the ball and the letterbox - the
     * mark is painted verbatim rather than mapped to a theme variant, so the whole square is open
     * and a typed code is honored exactly. 'Restore default' waits for OK, like the others.
     */
    private fun showCursorMarkerColorDialog() {
        val viewer = viewModel.pref.viewer
        val picker = ColorPickerPanel(activity).apply {
            quantized = false
            defaultColor = AccentTheme.argbOf(AppPreferences.CURSOR_MARKER_COLOR_DEFAULT)
            setArgb(AccentTheme.argbOf(viewer.cursorMarkerColor))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.pref_cursor_marker_color)
            .setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewer.cursorMarkerColor = AccentTheme.hexOf(picker.currentColor())
                paintCursorMarkerSwatch(viewer.cursorMarkerColor)
                activity.binding.inputView.invalidate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showImmersiveIfNeeded(activity)
    }

    /**
     * Shape and color only mean something while a mark is actually drawn, so they grey out with the
     * switch. The size is different: it is worth dialing in while the mark is hidden before turning
     * it back on, so the slider and its percentage label stay live even when [on] is false.
     */
    private fun renderLocalCursorAvailability(on: Boolean) {
        binding.cursorMarkerShapeGroup.isEnabled = on
        binding.cursorMarkerColor.isEnabled = on
        binding.cursorMarkerSizeLabel.isEnabled = true
        binding.cursorMarkerSize.isEnabled = true
    }

    private fun renderCursorMarkerSize(percent: Int) {
        binding.cursorMarkerSize.progress = percent
        binding.cursorMarkerSizeLabel.text =
                activity.getString(R.string.label_cursor_marker_size, percent)
    }

    /**
     * How far a swipe drags the remote pointer.
     *
     * One slider, bound to the same key the input settings screen has always used, so either
     * writes the same choice - which is also why the panel offers no on/off of its own: the
     * gesture list under Settings -> Input decides whether a swipe moves the pointer at all,
     * and this only decides how far it goes.
     *
     * The preference is written straight through and nothing is repainted, because the value
     * is read per motion event by [com.limelight.ui.vnc.input.TouchHandler] - unlike a settings
     * screen change, a drag of this slider lands on the very next gesture.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMouseSpeedControls() {
        val gesture = viewModel.pref.input.gesture

        binding.mouseSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val tenths = progress.coerceIn(
                        AppPreferences.SWIPE_SENSITIVITY_MIN, AppPreferences.SWIPE_SENSITIVITY_MAX)
                gesture.swipeSensitivityPercent = tenths
                renderMouseSpeed(tenths)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Same drawer-interception dance as the ball's sliders: a sideways drag towards the
        // closing edge is the drawer's, unless it started on this thumb.
        binding.mouseSpeed.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false // The slider handles the gesture itself
        }

        renderMouseSpeed(gesture.swipeSensitivityPercent)

        binding.mouseSpeedReset.setOnClickListener {
            confirmRestoreDefaults {
                gesture.swipeSensitivityPercent = AppPreferences.SWIPE_SENSITIVITY_DEFAULT
                renderMouseSpeed(AppPreferences.SWIPE_SENSITIVITY_DEFAULT)
            }
        }
    }

    /**
     * The bar runs in tenths (the stored unit of the multiplier, 1..40) while the label says
     * what those tenths mean to the user - a percentage, where 10 is one-to-one - hence the
     * factor here and nowhere else.
     */
    private fun renderMouseSpeed(tenths: Int) {
        binding.mouseSpeed.progress = tenths
        binding.mouseSpeedLabel.text =
                activity.getString(R.string.label_mouse_speed, tenths * 10)
    }

    /**
     * The width of the strip along the drawer's edge from which a swipe opens it.
     *
     * One slider, bound to the same key the toolbar section of the view settings uses, so
     * either writes the same choice. Unlike the ball and marker it has nothing to repaint on
     * the stream: the value only decides how the drawer reacts to the next edge touch, so
     * handing it straight to the [com.limelight.ui.vnc.EdgeWidthDrawerLayout] (which reads the
     * stored dp as-is, clamped) is the whole of applying it - and of the reset below, which
     * therefore has to make that hand-off itself rather than only rewrite the preference.
     * Seeded from the preference here so reopening the viewer comes back to the last choice;
     * the settings screen changes land on the next session.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrawerEdgeControls() {
        val viewer = viewModel.pref.viewer

        binding.drawerEdgeWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dp = progress.coerceIn(
                        AppPreferences.DRAWER_EDGE_WIDTH_MIN, AppPreferences.DRAWER_EDGE_WIDTH_MAX)
                viewer.drawerEdgeWidthDp = dp
                drawerLayout.setEdgeWidthDp(dp)
                renderDrawerEdgeWidth(dp)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Same drawer-interception dance as the ball's sliders: a sideways drag towards the
        // closing edge is the drawer's, unless it started on this thumb.
        binding.drawerEdgeWidth.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false // The slider handles the gesture itself
        }

        val dp = viewer.drawerEdgeWidthDp.coerceIn(
                AppPreferences.DRAWER_EDGE_WIDTH_MIN, AppPreferences.DRAWER_EDGE_WIDTH_MAX)
        renderDrawerEdgeWidth(dp)
        drawerLayout.setEdgeWidthDp(dp)

        binding.drawerEdgeReset.setOnClickListener {
            confirmRestoreDefaults {
                viewer.drawerEdgeWidthDp = AppPreferences.DRAWER_EDGE_WIDTH_DEFAULT
                drawerLayout.setEdgeWidthDp(AppPreferences.DRAWER_EDGE_WIDTH_DEFAULT)
                renderDrawerEdgeWidth(AppPreferences.DRAWER_EDGE_WIDTH_DEFAULT)
            }
        }
    }

    private fun renderDrawerEdgeWidth(dp: Int) {
        binding.drawerEdgeWidth.progress = dp
        binding.drawerEdgeWidthLabel.text = activity.getString(R.string.label_drawer_edge_width, dp)
    }

    /**
     * Settings of the floating assist ball.
     *
     * The values live in [VncViewModel] (backed by preferences) rather than in these
     * widgets, because the ball has to react immediately while this panel is opened and
     * closed freely. The sliders are filled imperatively instead of through two-way data
     * binding: a SeekBar only has room for one change listener, and that one is needed
     * here to write the dragged value back.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAssistBallControls() {
        binding.assistBallEnable.setOnCheckedChangeListener { _, checked ->
            viewModel.setAssistBallEnabled(checked)
        }

        binding.assistBallActionGroup.setOnCheckedChangeListener { _, id ->
            val action = if (id == R.id.assist_ball_action_keyboard)
                VncViewModel.ASSIST_BALL_ACTION_KEYBOARD
            else
                VncViewModel.ASSIST_BALL_ACTION_TOOLBAR
            viewModel.setAssistBallAction(action)
        }

        binding.assistBallOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // The slider reads as transparency - further right means more see-through -
                // while the stored value and the ball's own alpha stay the opacity, so the
                // mapping is simply inverted here. 0% = solid, 100% = invisible.
                if (fromUser) viewModel.setAssistBallOpacity(1f - progress / 100f)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        binding.assistBallSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.setAssistBallSizeDp(fromProgress(progress, ASSIST_BALL_SIZE_MIN_DP.toFloat(), ASSIST_BALL_SIZE_MAX_DP.toFloat()).roundToInt())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // The sliders live inside the drawer, and DrawerLayout reads a drag towards the
        // closing direction as "close me", which would cancel the slider gesture before
        // the thumb ever moves. Claiming the sequence on touch down keeps dragging working.
        for (bar in listOf(binding.assistBallOpacity, binding.assistBallSize)) {
            bar.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent.requestDisallowInterceptTouchEvent(false)
                }
                false // The slider handles the gesture itself
            }
        }

        // Push stored values into the panel. The setters above ignore values that have not
        // changed, and programmatic changes are filtered out by `fromUser`, so this never
        // bounces back.
        viewModel.assistBallEnabled.observe(activity) {
            binding.assistBallEnable.isChecked = it == true
        }

        viewModel.assistBallOpacity.observe(activity) { value ->
            value ?: return@observe
            // Report the inverse of the stored opacity - see the slider's listener.
            val transparency = ((1f - value) * 100).roundToInt()
            binding.assistBallOpacity.progress = transparency
            binding.assistBallOpacityLabel.text = activity.getString(R.string.label_assist_ball_opacity, transparency)
        }

        viewModel.assistBallSizeDp.observe(activity) { value ->
            value ?: return@observe
            binding.assistBallSize.progress = toProgress(value.toFloat(), ASSIST_BALL_SIZE_MIN_DP.toFloat(), ASSIST_BALL_SIZE_MAX_DP.toFloat())
            binding.assistBallSizeLabel.text = activity.getString(R.string.label_assist_ball_size, value)
        }

        viewModel.assistBallAction.observe(activity) { action ->
            binding.assistBallActionGroup.check(when (action) {
                VncViewModel.ASSIST_BALL_ACTION_KEYBOARD -> R.id.assist_ball_action_keyboard
                else -> R.id.assist_ball_action_toolbar
            })
        }

        // The color swatch is filled from the stored hex and opens the palette on tap. The
        // ball reacts live through its own observer in AssistBall, so no explicit refresh
        // is needed here beyond repainting the swatch.
        binding.assistBallColor.setOnClickListener { showAssistBallColorDialog() }
        viewModel.assistBallColor.observe(activity) { hex ->
            (binding.assistBallColor.background as? GradientDrawable)?.setColor(AccentTheme.argbOf(hex))
        }

        // Back to the values a fresh install runs with - every knob but the enable switch, for the
        // same reason the full keyboard's restore leaves it alone: taking the ball away from under
        // the fingers of whoever just asked to restore its settings is not what the button reads as.
        binding.assistBallReset.setOnClickListener {
            confirmRestoreDefaults {
                viewModel.setAssistBallOpacity(AppPreferences.ASSIST_BALL_OPACITY_DEFAULT)
                viewModel.setAssistBallSizeDp(AppPreferences.ASSIST_BALL_SIZE_DEFAULT)
                viewModel.setAssistBallColor(AppPreferences.ASSIST_BALL_COLOR_DEFAULT)
                viewModel.setAssistBallAction(VncViewModel.ASSIST_BALL_ACTION_TOOLBAR)
                // Position counts as one of those knobs, and the ball is usually on screen while the
                // drawer is open, so it moves back right away rather than waiting for a rotation.
                activity.assistBall.restoreDefaultPosition()
            }
        }
    }

    /**
     * Settings of the full keyboard: raise it, fade it, pin it.
     *
     * Like the ball's, these write through the viewmodel's LiveData rather than straight
     * to the preference, because the overlay watches them and reacts while this panel is
     * opened and closed freely - a keyboard faded on the spot teaches the fade better
     * than one that waits for the drawer to shut. The slider reads as transparency (the
     * inverse of the stored opacity) exactly as the ball's does, and runs the full 0-100:
     * fully transparent is a legal answer, and the board keeps its grip bar visible at that
     * setting so it can still be dragged and switched off (see FullKeyboard).
     *
     * The one button at the bottom commits nothing - there is nothing to commit, every knob
     * above is live. Putting the panel away is the toolbar row's own job, the same as for the
     * marker, ball and edge-width panels, so there is no OK row here to duplicate it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupFullKeyboardControls() {
        binding.fullKeyboardEnable.setOnCheckedChangeListener { _, checked ->
            viewModel.setFullKeyboardEnabled(checked)
        }

        binding.fullKeyboardOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.setFullKeyboardOpacity(1f - progress / 100f)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        binding.fullKeyboardDragLock.setOnCheckedChangeListener { _, checked ->
            viewModel.setFullKeyboardDragLocked(checked)
        }

        // The scale slider runs 0..150 for a 0.5x..2.0x board, so its midpoint is the natural
        // size. It resizes the whole grid proportionally; the keyboard rebuilds its keys live.
        binding.fullKeyboardScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.setFullKeyboardScale(.5f + progress / 100f)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Same drawer-interception dance as the ball's sliders: a sideways drag towards
        // the closing edge is the drawer's, unless it started on this thumb.
        binding.fullKeyboardOpacity.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false // The slider handles the gesture itself
        }

        binding.fullKeyboardScale.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        // Push stored values into the panel; unchanged programmatic writes are filtered
        // by the `fromUser` guard and the setters' equality checks, so this never bounces.
        viewModel.fullKeyboardEnabled.observe(activity) {
            binding.fullKeyboardEnable.isChecked = it == true
        }

        viewModel.fullKeyboardOpacity.observe(activity) { value ->
            value ?: return@observe
            val transparency = ((1f - value) * 100).roundToInt()
            binding.fullKeyboardOpacity.progress = transparency
            binding.fullKeyboardOpacityLabel.text = activity.getString(R.string.label_full_keyboard_opacity, transparency)
        }

        viewModel.fullKeyboardDragLocked.observe(activity) {
            binding.fullKeyboardDragLock.isChecked = it == true
        }

        viewModel.fullKeyboardScale.observe(activity) { value ->
            value ?: return@observe
            binding.fullKeyboardScale.progress = ((value - .5f) * 100).roundToInt()
            binding.fullKeyboardScaleLabel.text =
                    activity.getString(R.string.label_full_keyboard_scale, (value * 100).roundToInt())
        }

        // Back to the values a fresh install runs with - every knob but the enable switch.
        // Restoring that too would take the keyboard away from under the fingers of whoever
        // just asked to restore its settings, which is not what the button reads as.
        binding.fullKeyboardReset.setOnClickListener {
            confirmRestoreDefaults {
                viewModel.setFullKeyboardOpacity(AppPreferences.FULL_KEYBOARD_OPACITY_DEFAULT)
                viewModel.setFullKeyboardScale(AppPreferences.FULL_KEYBOARD_SCALE_DEFAULT)
                viewModel.setFullKeyboardDragLocked(false)
                // Position counts as one of those knobs. A raised board is moved back right away,
                // because the drawer is open over a board the user has just dragged and a restore
                // which waits for the next rotation looks like it did nothing at all.
                activity.fullKeyboard.restoreDefaultPosition()
            }
        }
    }

    /**
     * The LocalSend panel: a switch for the whole feature, the two ways to send, the outbox with
     * one removal per file, the four ways to reach a machine and both device lists.
     *
     * The switch is deliberately a session choice rather than a preference: discovery holds
     * a multicast lock and receiving holds a server socket, and neither should keep running
     * after the viewer is gone. The peer table is a StateFlow owned by [LsManager] - the
     * panel only mirrors it, so closing and reopening the drawer never loses a device.
     *
     * Files go through the same outbox the standalone screen shows, and every row and dialog
     * below comes from [LsWidgets] rather than from a copy kept here: the panel is meant to do
     * exactly what the page does, and two descriptions of one action are how the two drift.
     */
    private fun setupLocalSendControls() {
        binding.localsendReceive.isChecked = LsManager.receiveEnabled.value
        binding.localsendReceive.setOnCheckedChangeListener { _, checked ->
            LsManager.setReceiveEnabled(checked)
            renderLocalSendDevices(LsManager.peers.value)
        }
        binding.localsendAddFilesBtn.setOnClickListener { addLocalSendFiles() }
        binding.localsendSendTextBtn.setOnClickListener { LsWidgets.sendTextDialog(activity) }
        binding.localsendRemoveAllBtn.setOnClickListener { LsManager.clearStaged() }
        binding.localsendClearFavoritesBtn.setOnClickListener { confirmClearFavorites() }
        binding.localsendRescanBtn.setOnClickListener {
            // One button, two jobs, mirroring the standalone page: idle it starts a walk, running
            // it stops the one in progress.
            if (LsManager.rescanInFlight.value) LsManager.stopRescan() else LsManager.rescan()
        }
        binding.localsendFloatBtn.setOnClickListener {
            LsFloatingWindow.toggle(activity.applicationContext)
        }
        // Mirrors the standalone screen: the drawer's rescan button renames itself while
        // the walk is running, so "clicked but nothing happened" is not the user's only cue.
        // The empty-list Toast is left to the standalone screen - the drawer is opened over
        // a stream, and a Toast on top of a running session is a poor place to explain a
        // discovery problem the user should really be solving on the LocalSend page.
        activity.lifecycleScope.launch {
            LsManager.rescanInFlight.collect { running ->
                // Stay enabled during the walk so a tap can stop it; the caption carries the cue.
                binding.localsendRescanBtn.isEnabled = true
                binding.localsendRescanBtn.setText(
                        if (running) R.string.action_localsend_rescanning
                        else R.string.action_localsend_rescan)
            }
        }
        // Same progress cue as the standalone screen: the walk's (done, total) pair is folded
        // into the button caption so the drawer's search reads as a percentage, not a stall.
        activity.lifecycleScope.launch {
            LsManager.scanProgress.collect { prog ->
                if (!LsManager.rescanInFlight.value || prog == null) return@collect
                val (done, total) = prog
                val percent = if (total > 0) done * 100 / total else 0
                binding.localsendRescanBtn.text = activity.getString(
                        R.string.msg_localsend_rescan_progress, percent, done, total)
            }
        }
        binding.localsendAddIpBtn.setOnClickListener { LsWidgets.addByIpDialog(activity) }
        binding.localsendShareLinkBtn.setOnClickListener { shareLocalSendLink() }
        binding.localsendStopSharesBtn.setOnClickListener {
            LsManager.stopShares()
            renderLocalSendShares()
        }
        binding.localsendSharesHeader.setOnClickListener {
            localSendSharesExpanded = !localSendSharesExpanded
            renderLocalSendShares()
        }

        activity.lifecycleScope.launch {
            LsManager.staged.collect { renderLocalSendOutbox(it) }
        }
        renderLocalSendOutbox(LsManager.staged.value)

        activity.lifecycleScope.launch {
            LsManager.peers.collect { renderLocalSendDevices(it) }
        }
        renderLocalSendDevices(LsManager.peers.value)

        activity.lifecycleScope.launch {
            LsManager.favorites.collect { renderLocalSendFavorites(it) }
        }
        renderLocalSendFavorites(LsManager.favorites.value)

        activity.lifecycleScope.launch {
            LsManager.transferProgress.collect { renderLocalSendProgress(it) }
        }
        renderLocalSendProgress(LsManager.transferProgress.value)

        activity.lifecycleScope.launch {
            LsManager.shares.collect { renderLocalSendShares() }
        }
        renderLocalSendShares()
    }

    /** Mirror the in-flight transfer (out or in) into the panel's progress bar. */
    private fun renderLocalSendProgress(progress: LsTransferProgress?) {
        val bar = binding.localsendProgress
        val label = binding.localsendProgressLabel
        if (progress == null) {
            bar.isVisible = false
            label.isVisible = false
            return
        }
        val percent = if (progress.totalBytes > 0)
            (progress.doneBytes * 100 / progress.totalBytes).toInt() else 0
        bar.progress = percent
        bar.isVisible = true
        val verb = activity.getString(
            if (progress.incoming) R.string.label_localsend_receiving
            else R.string.label_localsend_sending)
        label.text = if (progress.fileCount > 1)
            activity.getString(R.string.label_localsend_progress_multi,
                verb, progress.fileIndex, progress.fileCount, progress.label)
        else
            activity.getString(R.string.label_localsend_progress, verb, progress.label)
        label.isVisible = true
    }

    /** One device row: a tap sends the outbox, a long press pins or unpins the machine. */
    private fun localSendRow(peer: LsPeer, favorite: Boolean): TextView =
            LsWidgets.deviceRow(activity, peer, favorite,
                    onTap = { LsWidgets.sendStaged(activity, it) },
                    onLongPress = { LsWidgets.deviceOptions(activity, it, favorite) })

    private fun renderLocalSendDevices(peers: List<LsPeer>) {
        val list = binding.localsendList
        list.removeAllViews()

        val enabled = binding.localsendReceive.isChecked
        binding.localsendStatus.isVisible = true
        binding.localsendStatus.text = activity.getString(
                when {
                    !enabled -> R.string.label_localsend_off
                    peers.isEmpty() -> R.string.label_localsend_searching
                    else -> R.string.label_localsend_hint
                })

        // Read once instead of asking per row: the pinned set is small and the answer must
        // not race the discovery thread that is writing it.
        val pinned = LsManager.favorites.value.map { it.fingerprint }.toSet()
        peers.forEach { peer ->
            list.addView(localSendRow(peer, favorite = peer.fingerprint in pinned))
        }
    }

    /**
     * The pinned devices underneath the live ones, so a machine discovery has lost sight of can
     * still be tapped. The rows are a [LsFavoritesAdapter] - the same one the standalone screen
     * uses - so a favorite is reordered by dragging its handle. The heading only appears while
     * there is a list beneath it.
     */
    private fun renderLocalSendFavorites(peers: List<LsPeer>) {
        val list = binding.localsendFavoritesList
        val adapter = localSendFavoritesAdapter ?: LsFavoritesAdapter(activity,
                onTap = { LsWidgets.sendStaged(activity, it) },
                onLongPress = { LsWidgets.deviceOptions(activity, it, favorite = true) })
                .also {
                    localSendFavoritesAdapter = it
                    it.attachTo(list)
                }
        binding.localsendFavoritesLabel.isVisible = peers.isNotEmpty()
        binding.localsendClearFavoritesBtn.isVisible = peers.isNotEmpty()
        adapter.submit(peers)
    }

    /**
     * Guard shared by every 'Restore defaults' button in the drawer (pointer marker, full
     * keyboard, toolbar items, edge width, mouse speed, assist ball). Resetting settings the
     * user spent time dialing in is a one-tap action with no undo, so it asks first: [body] runs
     * only on OK, and a cancel leaves everything exactly as it was.
     */
    private fun confirmRestoreDefaults(body: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.action_restore_defaults)
            .setMessage(R.string.msg_confirm_restore_defaults)
            .setPositiveButton(android.R.string.ok) { _, _ -> body() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showImmersiveIfNeeded(activity)
    }

    /**
     * Clears every pinned device at once, but only after an explicit yes - the drawer shares
     * [LsManager] with the standalone screen, so this empties the same list the page shows and
     * the file behind it. The favorites collect above redraws the panel once the table is empty.
     */
    private fun confirmClearFavorites() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.action_clear_favorites)
            .setMessage(R.string.msg_clear_favorites_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                LsManager.clearFavorites()
                Toast.makeText(activity, R.string.msg_favorites_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showImmersiveIfNeeded(activity)
    }

    /**
     * The outbox: how many files wait there, one row per file with its own removal, and the entry
     * that drops the whole batch. A tap on a device row sends exactly this.
     */
    private fun renderLocalSendOutbox(files: List<LsStagedFile>) {
        val list = binding.localsendStagedList
        list.removeAllViews()
        binding.localsendRemoveAllBtn.isVisible = files.isNotEmpty()
        files.forEach { file ->
            list.addView(LsWidgets.stagedRow(activity, file) { LsManager.removeStaged(it) })
        }

        val label = binding.localsendStagedLabel
        label.isVisible = files.isNotEmpty()
        if (files.isNotEmpty()) {
            label.text = activity.getString(R.string.label_localsend_staged_count, files.size)
        }
    }

    /** Picked files join the outbox and wait there; the row list above is where they are managed. */
    private fun addLocalSendFiles() {
        activity.pickLocalSendDocuments { uris ->
            if (uris.isNotEmpty()) LsManager.addStaged(uris)
        }
    }

    /**
     * Hand the outbox to a browser instead of to a LocalSend device. What is already waiting for a
     * machine that never showed up is precisely what a link is for, so - as on every other host -
     * this links the outbox rather than opening a picker.
     */
    private fun shareLocalSendLink() {
        LsWidgets.shareOutbox(activity) { renderLocalSendShares() }
    }

    /** The undo entry for [shareLocalSendLink], visible only while something is actually served. */
    private fun renderLocalSendShares() {
        binding.localsendStopSharesBtn.isVisible = LsManager.sharesActive()

        val shares = LsManager.shares.value
        val arrow = if (localSendSharesExpanded) "\u25be" else "\u25b8"
        binding.localsendSharesHeader.isVisible = shares.isNotEmpty()
        binding.localsendSharesHeader.text =
                activity.getString(R.string.title_localsend_shares, arrow, shares.size)
        val list = binding.localsendSharesList
        list.removeAllViews()
        list.isVisible = localSendSharesExpanded && shares.isNotEmpty()
        if (!localSendSharesExpanded) return
        shares.forEach { share ->
            list.addView(LsWidgets.shareRow(activity, share) { token ->
                activity.pickLocalSendDocuments { uris ->
                    if (uris.isNotEmpty()) LsManager.addFilesToShare(token, uris)
                }
            })
        }
    }

    /**
     * The ball's palette. Unlike the accent picker this runs in continuous mode - the ball
     * is tinted at runtime rather than mapped to a pre-generated theme variant - so the full
     * square is available, white and grays included, and a typed color code is exact.
     *
     * 'Restore default' only moves the markers: the ball is repainted when OK is pressed,
     * the same as any other change made in here.
     */
    private fun showAssistBallColorDialog() {
        val picker = ColorPickerPanel(activity).apply {
            quantized = false
            defaultColor = AccentTheme.argbOf(AppPreferences.ASSIST_BALL_COLOR_DEFAULT)
            setArgb(AccentTheme.argbOf(viewModel.assistBallColor.value))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.title_assist_ball_color)
            .setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setAssistBallColor(AccentTheme.hexOf(picker.currentColor()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showImmersiveIfNeeded(activity)
    }

    /**
     * Palette for the letterbox bands. Same continuous picker as the ball above - the color
     * is painted verbatim onto the container behind the video rather than mapped to a theme
     * variant, so the whole square is open and a typed code is honored exactly. Like the
     * ball, its 'restore default' waits for OK before anything is repainted.
     */
    private fun showStreamBarColorDialog() {
        val picker = ColorPickerPanel(activity).apply {
            quantized = false
            defaultColor = AccentTheme.argbOf(AppPreferences.STREAM_BAR_COLOR_DEFAULT)
            setArgb(AccentTheme.argbOf(viewModel.streamBarColor.value))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.title_letterbox_color)
            .setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setStreamBarColor(AccentTheme.hexOf(picker.currentColor()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showImmersiveIfNeeded(activity)
    }

    /** Both ball sliders run from 0 to 100 and are mapped onto their value range here. */
    private fun toProgress(value: Float, min: Float, max: Float): Int =
            ((value - min) * 100 / (max - min)).roundToInt()

    /**
     * Shortcuts for a directly driven Android device.
     *
     * Unlike the other flyouts these buttons deliberately do not [close] the drawer: a
     * Back/Home tap is something a user repeats while watching the remote screen, and
     * the panel is narrow enough that this costs little.
     */
    private fun setupAndroidActions() {
        binding.androidBackBtn.setOnClickListener { viewModel.sendAndroidKey(KeyEvent.KEYCODE_BACK) }
        binding.androidHomeBtn.setOnClickListener { viewModel.sendAndroidKey(KeyEvent.KEYCODE_HOME) }
        binding.androidRecentsBtn.setOnClickListener { viewModel.sendAndroidKey(KeyEvent.KEYCODE_APP_SWITCH) }
        binding.androidRotateBtn.setOnClickListener { viewModel.sendAndroidAction(Messenger.AndroidAction.ROTATE) }
        binding.androidPowerBtn.setOnClickListener { viewModel.sendAndroidAction(Messenger.AndroidAction.POWER) }

        binding.androidScreenBtn.setOnClickListener {
            // From this tap onwards our own record outranks any read still in flight.
            screenOnOverride = true
            viewModel.queryRemoteScreenOn { lit ->
                // A device that fell asleep on its own is the one state no local record can
                // ever learn: the picture simply stopped. Waking it is the whole reason this
                // button exists, so a tap must not be spent re-blanking a screen that is
                // already dark. Everywhere else - including a screen this button just
                // blanked, which the power manager has not caught up with yet - keep
                // alternating, because that is what makes turning it off reversible.
                if (lit == false) remoteScreenOn = true else remoteScreenOn = !remoteScreenOn
                viewModel.sendAndroidAction(if (remoteScreenOn)
                    Messenger.AndroidAction.SCREEN_ON else Messenger.AndroidAction.SCREEN_OFF)
            }
        }

        binding.androidNightBtn.setOnClickListener {
            // From the first tap onwards our own record is the better answer than a
            // read that may still be in flight.
            nightModeOverride = true
            remoteNightMode = !remoteNightMode
            viewModel.sendAndroidAction(if (remoteNightMode)
                Messenger.AndroidAction.NIGHT_ON else Messenger.AndroidAction.NIGHT_OFF)
        }

        binding.androidKeepServer.isChecked = viewModel.pref.server.androidKeepServerOnDisconnect
        binding.androidKeepServer.setOnCheckedChangeListener { _, checked ->
            viewModel.pref.server.androidKeepServerOnDisconnect = checked
        }
    }

    /**
     * The panel at the end of the drawer that decides which buttons are in sight.
     *
     * Its ticks are built from [items] rather than written out in the layout, so a button is
     * registered in one place and cannot appear in the column without appearing in the panel
     * to switch it off. Only one row appears in the panel but can never be ticked off - its
     * own opener: a drawer emptied of every button would leave no way back in (see
     * Item.hideable). Every other row - the exit and the edge-width opener included - is
     * listed here, so it is placed by the shared order and can be ticked off like the rest;
     * the bottom-pinning fallback in [applyColumnOrder] now only matters if some future
     * button were left out of this list.
     */
    private fun setupToolbarItems() {
        defaults = listOf(
                // Split by what a row does, not by how often it gets pressed. Everything above
                // changes what the session looks like or what it shows - the picture's mode and
                // colors, the marker, the ball, the keyboard's appearance, the zoom, the perf
                // readout, and the input knobs that decide how a touch reaches the pointer - the
                // gesture style, the mouse speed and the swipe edge width - so those rows fill the
                // top of the column. Everything below is a feature to
                // reach for while working: the keyboard, the key row, the mini window, file
                // transfer, silence, and the device shortcuts. At the very end of the list sit
                // the exit and the panel's own opener: reorderable like everything else, and
                // the exit ticks off too - only the opener stays untickable (see Item.hideable).
                // This is the built-in order applyColumnOrder lays the column into, and what the
                // panel's restore returns to; it has to match the sequence the drawer layout
                // declares, since that layout is where the slots are counted from.
                // This panel is the only editor of these keys: the Viewer settings screen
                // used to carry a duplicate switch for the gesture style button (the older
                // `toolbar_show_gesture_style_toggle`), but that entry has been dropped.
                Item(binding.viewModesToggle, "toolbar_show_view_modes", R.string.desc_view_modes_toggle),
                Item(binding.letterboxColorBtn, "toolbar_show_letterbox_color", R.string.desc_letterbox_color_btn),
                Item(binding.localCursorToggle, "toolbar_show_local_cursor", R.string.desc_local_cursor_toggle),
                Item(binding.assistBallToggle, "toolbar_show_assist_ball", R.string.desc_assist_ball_toggle),
                Item(binding.fullKeyboardToggle, "toolbar_show_full_keyboard", R.string.desc_full_keyboard_toggle),
                Item(binding.zoomOptionsToggle, "toolbar_show_zoom_options", R.string.desc_zoom_options_btn),
                Item(binding.statsToggle, "toolbar_show_stats", R.string.label_toolbar_item_stats),
                Item(binding.gestureStyleToggle, "toolbar_show_gesture_style_toggle", R.string.pref_show_gesture_style_toggle),
                Item(binding.mouseSpeedToggle, "toolbar_show_mouse_speed", R.string.desc_mouse_speed_toggle),
                Item(binding.drawerEdgeToggle, "toolbar_show_drawer_edge", R.string.desc_drawer_edge_toggle),
                Item(binding.keyboardBtn, "toolbar_show_keyboard", R.string.desc_keyboard_btn),
                Item(binding.virtualKeysBtn, "toolbar_show_virtual_keys", R.string.desc_virtual_keys_btn),
                Item(binding.miniWindowBtn, "toolbar_show_mini_window", R.string.desc_mini_window_btn),
                Item(binding.localsendToggle, "toolbar_show_localsend", R.string.desc_localsend_toggle),
                Item(binding.audioToggle, "toolbar_show_audio", R.string.label_toolbar_item_audio),
                Item(binding.androidActionsToggle, "toolbar_show_android_actions", R.string.title_android_actions),
                // The exit and the panel's opener join the list last. The exit is a normal
                // row now - draggable and tickable off like the rest. Only the opener stays
                // pinned checked and dimmed: a drawer emptied of every button would leave no
                // way back into this panel.
                Item(binding.toolbarItemsToggle, "toolbar_show_items_panel", R.string.desc_toolbar_items_toggle, hideable = false),
                Item(binding.exitBtn, "toolbar_show_exit_btn", R.string.desc_exit_btn),
        )

        items.clear()
        items += orderedByPref(defaults)

        applyColumnOrder()
        applyItemVisibility()
        setupItemsPanel()
    }

    /**
     * The default list rearranged to the order stored in [AppPreferences.Viewer.toolbarItemOrder]
     * - keys the app no longer knows are dropped, and missing keys keep their default place.
     */
    private fun orderedByPref(defaults: List<Item>): List<Item> {
        val byKey = defaults.associateBy { it.prefKey }
        val out = LinkedHashMap<String, Item>()
        viewModel.pref.viewer.toolbarItemOrder.orEmpty().split(',')
                .map { it.trim() }
                .forEach { key -> byKey[key]?.let { out[key] = it } }
        defaults.forEach { if (!out.containsKey(it.prefKey)) out[it.prefKey] = it }
        return out.values.toList()
    }

    /**
     * Move the drawer's buttons into the order of [items]. The listed rows are re-attached
     * rather than left where the layout put them, which is what makes a stored order real:
     * their current positions in the column are collected as the set of slots first, then
     * each row takes the slot of the same rank in the new order.
     *
     * A row the panel does not list would keep no slot of its own and be appended after the
     * listed rows, at the very bottom of the column. Every button is listed now, so this
     * fallback only comes into play if some future row is left out of [setupToolbarItems].
     */
    private fun applyColumnOrder() {
        val column = binding.toolbarColumn
        val slots = items.mapNotNull { item ->
            column.indexOfChild(item.view).takeIf { it >= 0 }
        }.sorted()

        items.forEach { column.removeView(it.view) }
        items.forEachIndexed { i, item ->
            if (i < slots.size) column.addView(item.view, slots[i])
        }

        val listed = items.mapTo(mutableSetOf()) { it.view }
        val fixed = (0 until column.childCount).map(column::getChildAt)
                .filter { it !in listed }

        for (view in fixed) {
            column.removeView(view)
            column.addView(view)
        }
    }

    /**
     * Wire the panel: a RecyclerView whose rows are the buttons in [draft], an
     * [ItemTouchHelper] that reorders them when a row's handle is dragged, and the three
     * buttons that restore, discard, or commit the edit. The rows are built by the adapter
     * from the draft, so the column behind the panel stays untouched until [commitDraft].
     */
    private fun setupItemsPanel() {
        itemsAdapter = ItemRowAdapter()
        val list = binding.toolbarItemsList
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = itemsAdapter

        val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

            override fun onMove(recyclerView: RecyclerView,
                    source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                draft.add(to, draft.removeAt(from))
                itemsAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            // Dragging starts from the row's handle (see [ItemRowAdapter]), not a long press
            // anywhere on the row, so a stray hold on the tick still just toggles it.
            override fun isLongPressDragEnabled() = false
        }
        itemsTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(list) }

        binding.toolbarItemsReset.setOnClickListener { confirmRestoreDefaults { resetDraft() } }
        binding.toolbarItemsCancel.setOnClickListener { binding.toolbarItemsToggle.isChecked = false }
        binding.toolbarItemsApply.setOnClickListener { commitDraft() }
    }

    /**
     * Seed the draft from what is currently committed - the order of [items] and the stored
     * visibility - and show it. Called every time the panel opens, so an edit that was
     * cancelled or left uncommitted last time does not linger. The draft reads back from
     * the preferences rather than trusting a stale copy, since the stored visibility can
     * also change while the panel is closed (a restore-all-settings, for one).
     */
    private fun openItemsPanel() {
        draft.clear()
        draft += items
        draftShown.clear()
        items.filter { it.hideable }
                .forEach { draftShown[it.prefKey] = viewModel.pref.viewer.toolbarItemShown(it.prefKey) }
        if (this::itemsAdapter.isInitialized) itemsAdapter.notifyDataSetChanged()
    }

    /** Put the built-in order and an all-visible set into the draft, still awaiting OK. */
    private fun resetDraft() {
        draft.clear()
        draft += defaults
        draftShown.clear()
        defaults.filter { it.hideable }.forEach { draftShown[it.prefKey] = true }
        itemsAdapter.notifyDataSetChanged()
    }

    /**
     * Write the draft back: the order first, then each button's visibility, and only then
     * the column and the buttons themselves follow. Finally the panel closes, because what
     * it held as a draft is now the committed state.
     */
    private fun commitDraft() {
        viewModel.pref.viewer.toolbarItemOrder = draft.joinToString(",") { it.prefKey }
        draftShown.forEach { (key, shown) -> viewModel.pref.viewer.setToolbarItemShown(key, shown) }

        items.clear()
        items += draft

        applyColumnOrder()
        applyItemVisibility()
        binding.toolbarItemsToggle.isChecked = false
    }

    /**
     * One row of the panel: a drag handle that starts an [ItemTouchHelper] drag, and a tick
     * that switches the button. Both touch only the draft, never the preferences directly.
     */
    private inner class ItemRowAdapter : RecyclerView.Adapter<ItemRowAdapter.RowHolder>() {

        inner class RowHolder(val row: LinearLayout, val handle: ImageView, val check: CheckBox)
            : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val rowGap = activity.resources.getDimensionPixelSize(R.dimen.padding_small)
            val size = activity.resources.getDimensionPixelSize(R.dimen.action_btn_size)

            val handle = ImageView(activity).apply {
                setImageResource(R.drawable.ic_drag_handle)
                contentDescription = activity.getString(R.string.desc_toolbar_items_drag)
                setColorFilter(0xFFAAAAAA.toInt())
                // Center the icon at its own size instead of FIT_CENTER, which blows this
                // particular drawable up to fill the whole cell and pushes its centre away
                // from the tick's.
                scaleType = ImageView.ScaleType.CENTER
                // Wrap the cell to the icon's own width: a fixed action-size cell centers the
                // small three-line glyph and leaves a blank column on each side of it, which is
                // exactly the dead space the user wants gone. Height stays the row size so the
                // glyph remains vertically centered against the tick.
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, size)
                lp.gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp
            }

            val check = CheckBox(activity).apply {
                // No vertical padding, and the row height as its own: the tick is a square
                // centered in that height, which puts it on the same axis as the handle next
                // to it. The weight is kept so the label still gets all the leftover width.
                setPadding(rowGap, 0, 0, 0)
                val lp = LinearLayout.LayoutParams(size, size, 1f)
                lp.gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT)
                addView(handle)
                addView(check)
            }
            return RowHolder(row, handle, check)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = draft[position]

            holder.check.setOnCheckedChangeListener(null)
            holder.check.text = activity.getString(item.labelRes)
            // The panel's opener reads as permanently on: a dimmed tick that cannot be
            // unticked (Item.hideable), reorderable but never removable.
            holder.check.isEnabled = item.hideable
            holder.check.isChecked = !item.hideable || (draftShown[item.prefKey] ?: true)
            holder.check.setOnCheckedChangeListener { _, checked ->
                val at = holder.bindingAdapterPosition
                if (at != RecyclerView.NO_POSITION) draftShown[draft[at].prefKey] = checked
            }

            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN)
                    itemsTouchHelper?.startDrag(holder)
                false
            }
        }

        override fun getItemCount() = draft.size
    }

    /**
     * Show the buttons the user asked for and that the session can actually use, and put
     * away the flyout belonging to one that has just been hidden: a panel whose opener
     * vanished would otherwise stay open next to nothing until the drawer is closed.
     */
    private fun applyItemVisibility() {
        items.forEach { item ->
            item.view.isVisible = itemAvailable[item.view] != false &&
                    (!item.hideable || viewModel.pref.viewer.toolbarItemShown(item.prefKey))
        }

        items.forEach { item ->
            val toggle = item.view as? ToggleButton ?: return@forEach
            if (!toggle.isVisible && toggle.isChecked) toggle.isChecked = false
        }
    }

    private fun fromProgress(progress: Int, min: Float, max: Float): Float =
            min + (progress / 100f) * (max - min)

    private fun updateLockMode(isConnected: Boolean) {
        if (isConnected && openWithSwipe)
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNDEFINED)
        else
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    /**
     * Returns a rectangle covering the area occupied by primary buttons in opened state.
     * Returned rectangle is in [drawerLayout]'s coordinate space.
     */
    private fun getActionableToolbarRect(): Rect {
        val v = drawerView
        val r = Rect(v.left, binding.primaryButtons.top, v.right, binding.primaryButtons.bottom)

        if (r.left < 0) r.offset(v.width, 0)                          // closed along left edge
        else if (r.right > drawerLayout.right) r.offset(-v.width, 0)  // closed along right edge

        return r
    }


    /**
     * Setup gravity & layout direction
     */
    @SuppressLint("RtlHardcoded")
    private fun setupAlignment() {
        val gravity = if (viewModel.pref.viewer.toolbarAlignment == "start") GravityCompat.START else GravityCompat.END

        drawerView.layoutParams = (drawerView.layoutParams as DrawerLayout.LayoutParams).apply {
            this.gravity = gravity
        }

        // Before layout pass, layoutDirection should be retrieved from Activity config
        val layoutDirection = activity.resources.configuration.layoutDirection
        val isLeftAligned = Gravity.getAbsoluteGravity(gravity, layoutDirection) == Gravity.LEFT

        // We need the layout direction based on alignment rather than language/locale
        // so that flyouts and button icons are properly ordered.
        drawerView.layoutDirection = if (isLeftAligned) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL

        // Let the gesture group have natural layout as it contains text elements
        binding.gestureStyleScroll.layoutDirection = layoutDirection
        binding.assistBallGroup.layoutDirection = layoutDirection
        binding.localsendGroup.layoutDirection = layoutDirection
        binding.androidActionsGroup.layoutDirection = layoutDirection
        binding.toolbarItemsGroup.layoutDirection = layoutDirection
    }

    /**
     * Setup gesture exclusion updates
     */
    private fun setupGestureExclusionRect() {
        if (Build.VERSION.SDK_INT >= 29) {
            binding.primaryButtons.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateGestureExclusionRect()
            }
        }
    }

    /**
     * Add System Gesture exclusion rects to allow toolbar opening when gesture navigation is active.
     * Note: Some ROMs, e.g. MIUI, completely ignore whatever is set here.
     */
    @RequiresApi(29)
    private fun updateGestureExclusionRect() {
        if (!openWithSwipe || !viewModel.connected) {
            drawerLayout.systemGestureExclusionRects = listOf()
        } else {
            // Area covered by primaryButtons, in drawerLayout's coordinate space
            val rect = getActionableToolbarRect()

            if (viewModel.pref.viewer.fullscreen) {
                // For fullscreen activities, Android does not enforce the height limit of exclusion area.
                // We could use the entire height for opening toolbar, but that will completely disable gestures.
                // So we pad by one-third of available space in each direction
                val padding = (drawerLayout.height - rect.height()) / 6
                if (padding > 0) {
                    rect.top -= padding
                    rect.bottom += padding
                }
            }

            drawerLayout.systemGestureExclusionRects = listOf(rect)
        }
    }

    private fun setupFlyouts() {
        flyouts += binding.viewModesToggle to binding.viewModeGroup
        flyouts += binding.gestureStyleToggle to binding.gestureStyleScroll
        flyouts += binding.zoomOptionsToggle to binding.zoomOptionsGroup
        flyouts += binding.localCursorToggle to binding.localCursorGroup
        flyouts += binding.mouseSpeedToggle to binding.mouseSpeedGroup
        flyouts += binding.assistBallToggle to binding.assistBallGroup
        flyouts += binding.fullKeyboardToggle to binding.fullKeyboardGroup
        flyouts += binding.localsendToggle to binding.localsendGroup
        flyouts += binding.androidActionsToggle to binding.androidActionsGroup
        flyouts += binding.toolbarItemsToggle to binding.toolbarItemsGroup
        flyouts += binding.drawerEdgeToggle to binding.drawerEdgeGroup

        flyouts.values.forEach { it.isVisible = false }

        flyouts.keys.forEach { toggle ->
            toggle.setOnCheckedChangeListener { _, isChecked ->
                val flyout = flyouts[toggle] ?: return@setOnCheckedChangeListener

                if (isChecked) {
                    alignWithToggle(toggle, flyout)
                    if (toggle == binding.toolbarItemsToggle) openItemsPanel()
                }

                flyout.isVisible = isChecked

                if (isChecked) // Close others
                    flyouts.keys.forEach { if (it != toggle) it.isChecked = false }
            }
        }

        // Scrolling the column moves the opener while the panel keeps the margin it was given
        // at the tap - which in landscape leaves it beside the wrong row, or off screen
        // altogether. Only the panel actually open needs following, and only the column has a
        // scroll to follow.
        binding.toolbarColumnScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            flyouts.entries.firstOrNull { (toggle, _) -> toggle.isChecked }?.let { (toggle, flyout) ->
                alignWithToggle(toggle, flyout)
            }
        }
    }

    /**
     * A flyout placed with `layout_gravity="bottom"` lands at the end of the button column,
     * which used to be the row that opened it - while that column was fixed, the XML alone
     * could say where a panel belongs.
     *
     * Buttons can now be hidden in any combination and there are two more of them below the
     * zoom opener, so the distance from this toggle up to the bottom of the drawer is measured
     * on every open and handed to the flyout as its bottom margin. That puts the panel's bottom
     * edge back against the button that asked for it.
     */
    private fun alignWithToggle(toggle: View, flyout: View) {
        val lp = flyout.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.gravity != Gravity.BOTTOM) return

        val offset = offsetFor(toggle, flyout)
        if (lp.bottomMargin != offset) {
            lp.bottomMargin = offset
            flyout.layoutParams = lp
        }

        // Reordering can leave an opener further from the end of the column than its panel
        // is tall, and a panel pushed up by more than the drawer has room for runs off the
        // top. That cap needs the panel's own height, which does not exist until it has been
        // measured - hence a second pass once the first layout is over, and no listener
        // registered when the height is already known.
        if (flyout.height <= 0) {
            flyout.doOnNextLayout { alignWithToggle(toggle, flyout) }
        }
    }

    /**
     * How far above the bottom of the button row the panel has to sit to end against the row
     * that opened it - measured in window coordinates rather than inside the column.
     *
     * The column is the scroller's content, and in landscape it is several times taller than
     * the screen it scrolls through. Measuring the opener against the column therefore asked
     * for a margin larger than the drawer is high and threw the panel out over the top edge,
     * which is exactly the panel the user cannot reach in landscape. Where the two views sit
     * on screen says what the margin has to be whether or not the column is scrolled, and the
     * cap against the free space keeps the panel visible when the button sits too low for it
     * to fit above - then its bottom edge stays put and the row it belongs to is still shown.
     */
    private fun offsetFor(toggle: View, flyout: View): Int {
        val parent = flyout.parent as? View ?: return 0

        val toggleAt = IntArray(2)
        toggle.getLocationInWindow(toggleAt)
        val parentAt = IntArray(2)
        parent.getLocationInWindow(parentAt)

        val desired = (parentAt[1] + parent.height - (toggleAt[1] + toggle.height)).coerceAtLeast(0)
        val room = (parent.height - flyout.height).coerceAtLeast(0)
        return minOf(desired, room)
    }

    /**
     * Close flyouts after drawer is closed.
     *
     * We can't do this in [close] because that will change toolbar width _while_ drawer
     * is closing. This can conflict with close animation, and mess with internal calculations
     * of DrawerLayout, resulting in failure of close operation.
     */
    private fun setupFlyoutClose() {
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(closedView: View) {
                if (closedView == drawerView) {
                    flyouts.keys.forEach { it.isChecked = false }
                }
            }
        })
    }

    /**
     * Normally, drawers in [DrawerLayout] are closed by two gestures:
     * 1. Swipe 'on' the drawer
     * 2. Tap inside Scrim (dimmed region outside of drawer)
     *
     * Notably, swiping inside scrim area does NOT hide the drawer. This can be jarring
     * to users if drawer is relatively small & most of the layout area acts as scrim.
     * The toolbar drawer is affected by this issue.
     *
     * This function attempts to detect these swipe gestures and close the drawer
     * when they happen.
     *
     * Note: It will set a custom TouchListener on [drawerLayout].
     */
    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun setupDrawerCloseOnScrimSwipe() {
        drawerLayout.setOnTouchListener(object : View.OnTouchListener {
            var drawerOpen = false
            var drawerGravity = Gravity.LEFT

            val detector = GestureDetector(drawerLayout.context, object : GestureDetector.SimpleOnGestureListener() {

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                    if ((drawerGravity == Gravity.LEFT && vX < 0) || (drawerGravity == Gravity.RIGHT && vX > 0)) {
                        close()
                        drawerOpen = false
                    }
                    return true
                }
            })

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    drawerOpen = drawerLayout.isDrawerOpen(drawerView)
                    drawerGravity = Gravity.getAbsoluteGravity(
                            (drawerView.layoutParams as DrawerLayout.LayoutParams).gravity,
                            drawerLayout.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
                }

                if (drawerOpen)
                    detector.onTouchEvent(event)

                return false
            }
        })
    }

}