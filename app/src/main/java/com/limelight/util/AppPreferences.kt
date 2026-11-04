/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.preference.PreferenceManager
import kotlin.math.abs
import kotlin.reflect.KProperty

/**
 * Utility class for accessing app preferences
 */
class AppPreferences(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        /**
         * Clipboard payload ceiling of the Android-device protocol, in UTF-8 bytes.
         *
         * 5000 is what the encoder process on the controlled device applies when it is
         * given no argument at all, so it doubles as the protocol default and as the
         * largest payload an *older* helper jar can ever emit. Raising the limit is only
         * honest because the number is handed to the helper too - see
         * [com.limelight.vnc.AndroidStreamConfig.clipboardMaxBytes].
         *
         * The upper bound is a 16-bit payload length, and it is safe over ADB in both
         * directions: each side splits one write into WRTE messages no larger than the
         * peer's advertised max payload (see Adb.createNewStream), so a long paste simply
         * arrives as several packets instead of one.
         */
        const val CLIPBOARD_LIMIT_DEFAULT = 5000
        const val CLIPBOARD_LIMIT_MIN = 1
        const val CLIPBOARD_LIMIT_MAX = 65535

        /**
         * Which aspects of a saved server the search screen matches a query against, as a
         * comma-separated list of field keys (see [com.limelight.viewmodel.UrlBarViewModel]).
         * Every key present means "searched", a key left out means ignored; the default
         * lists them all, so a fresh install searches on every field. Deselecting all
         * options stores an empty string, which yields no results by design.
         */
        const val SEARCH_FIELDS_DEFAULT =
                "name,host,protocol,port,mac,broadcastAddress,broadcastPort,sshHost,username"

        /**
         * Pinch-zoom bounds, stored as a percentage: 50 means 0.5x, 1000 means 10x.
         *
         * No preference screen writes these any more, so the only way to set them is an
         * imported backup file. They are clamped on the way out because
         * [com.limelight.ui.vnc.FrameState] passes them straight to `coerceIn(min, max)`,
         * which throws for a non-positive or absurd range.
         */
        const val ZOOM_PCT_LIMIT_MIN = 10
        const val ZOOM_PCT_LIMIT_MAX = 1000
        const val ZOOM_PCT_DEFAULT_MIN = 50
        const val ZOOM_PCT_DEFAULT_MAX = 500

        /**
         * The assist ball's untouched state: a black disc at 30% opacity - which the drawer's
         * slider, being a *transparency* control, presents as 70% - and 48dp across. These are
         * the values the ball itself falls back to when a stored one cannot be used, so they
         * live here rather than in the viewmodel.
         */
        const val ASSIST_BALL_OPACITY_DEFAULT = .3f
        const val ASSIST_BALL_SIZE_DEFAULT = 48
        const val ASSIST_BALL_COLOR_DEFAULT = "#FF000000"

        /**
         * Where a fresh install leaves the ball, and what the drawer's restore button puts it
         * back to: on the left edge, vertically centered - clear of the thumb and of the
         * toolbar's own edge. Stored as a share of the free travel in each axis (see
         * [com.limelight.ui.vnc.AssistBall]'s bias handling).
         */
        const val ASSIST_BALL_X_BIAS_DEFAULT = 0f
        const val ASSIST_BALL_Y_BIAS_DEFAULT = .5f

        /**
         * The default earlier versions shipped: bottom-right. It only lets the migration in
         * [RunInfo] recognise a ball nobody has dragged; a ball parked anywhere else belongs
         * to the user and is left alone.
         */
        private const val PREVIOUS_ASSIST_BALL_X_BIAS = .88f
        private const val PREVIOUS_ASSIST_BALL_Y_BIAS = .7f

        /** The letterbox behind the picture starts out black, the conventional choice. */
        const val STREAM_BAR_COLOR_DEFAULT = "#FF000000"

        /**
         * The full keyboard's untouched state, and the range the drawer's transparency slider
         * can move it across. Stored as opacity but edited as transparency, so the default of
         * 0.2 opacity is offered as 80% transparent: a board meant to be typed on while the
         * stream shows through it starts out mostly see-through. It reaches full transparency,
         * where only the grip bar stays visible (see FullKeyboard's own floor) so the keyboard
         * can still be dragged and switched off.
         */
        const val FULL_KEYBOARD_OPACITY_DEFAULT = .2f
        const val FULL_KEYBOARD_OPACITY_MIN = 0f
        const val FULL_KEYBOARD_OPACITY_MAX = 1f

        /**
         * The full keyboard's proportional size. One is the board's natural footprint; the
         * drawer scales it between half and double. The same value applies in portrait and
         * landscape - the board is a fixed overlay the user drags, never one folded to width.
         */
        const val FULL_KEYBOARD_SCALE_DEFAULT = 1f
        const val FULL_KEYBOARD_SCALE_MIN = .5f
        const val FULL_KEYBOARD_SCALE_MAX = 2f

        /**
         * Where a fresh install leaves the board: its own bottom-left corner on the bottom-left
         * corner of the display. Both values are sentinels rather than shares of the travel.
         * The travel runs sideways all the way to "most of the board is off the left edge" and
         * vertically all the way to "only the grip shows", and where the corner of a board
         * actually is depends on its own size at the current scale - not known until it has been
         * laid out. A negative bias therefore means "never dragged: sit me in that corner", and
         * [com.limelight.ui.vnc.FullKeyboard] works out the coordinates; a drag always stores a
         * 0..1 share on the axis it moved, so the sentinel never comes back on its own.
         */
        const val FULL_KEYBOARD_X_BIAS_DEFAULT = -1f
        const val FULL_KEYBOARD_Y_BIAS_DEFAULT = -1f

        /**
         * The defaults earlier versions shipped: bottom-left, then top-center, then bottom-center.
         * They only let the migration in [RunInfo] recognise a board nobody has dragged; a board
         * at any other spot belongs to the user and is left alone.
         */
        private const val LEGACY_FULL_KEYBOARD_X_BIAS = .0f
        private const val LEGACY_FULL_KEYBOARD_Y_BIAS = .75f
        private const val PREVIOUS_FULL_KEYBOARD_X_BIAS = .5f
        private const val PREVIOUS_FULL_KEYBOARD_Y_BIAS = .0f

        /**
         * The local pointer marker's size, as a percentage of the arrow the viewer draws by
         * default (16x20dp). The slider in the stream toolbar's panel moves it from a bare
         * speck (1%) up to double. The default is a fifth of the largest the slider goes:
         * small enough to sit on the click without covering it, big enough to find on a
         * busy desktop without hunting for a speck. The floor is 1 rather than a fifth
         * because "make the mark almost invisible" is a legitimate ask once the marker is on.
         */
        const val CURSOR_MARKER_SIZE_DEFAULT = 20
        const val CURSOR_MARKER_SIZE_MIN = 1
        const val CURSOR_MARKER_SIZE_MAX = 200

        /**
         * The two shapes the marker can take, and the default. The dot is the default: the whole
         * point of the feature is knowing where the pointer is, and a dot marks that exact spot -
         * it sits on the click without covering it. The arrow is offered for anyone who wants the
         * mark to read as the pointer itself, following the shape Windows draws; its tip is a
         * corner rather than a center, so any disagreement between the stream's size and the
         * desktop's shows up as a mark that sits beside the click instead of on it. See
         * InputView.onDraw, which draws the dot only for [CURSOR_MARKER_SHAPE_DOT].
         */
        const val CURSOR_MARKER_SHAPE_ARROW = "arrow"
        const val CURSOR_MARKER_SHAPE_DOT = "dot"

        /** The shape a fresh install, and the panel's restore button, start from. */
        const val CURSOR_MARKER_SHAPE_DEFAULT = CURSOR_MARKER_SHAPE_DOT

        /**
         * The marker's fill, as #AARRGGBB hex. Blue by default - a color that reads on top of
         * both a light and a dark desktop, unlike the accent the marker used to inherit. Chosen
         * from the toolbar's cursor panel with the same unpinned picker the assist ball uses,
         * so like the ball it is applied verbatim rather than quantized. See InputView.setupMarker.
         */
        const val CURSOR_MARKER_COLOR_DEFAULT = "#FF2196F3"

        /**
         * How wide a strip, in dp, along the toolbar's edge a horizontal swipe has to start in
         * before it is taken as an intent to open the drawer. The default matches what the
         * gesture has always been (about this wide), and it is measured from the edge the
         * toolbar is aligned to, so it follows `toolbar_alignment`. Raised toward [MAX] the
         * swipe is easier to catch from further off the edge; lowered toward [MIN] it is
         * harder to open by accident. [EdgeWidthDrawerLayout] applies the range; a value off
         * the ends (an imported backup) is clamped there rather than here.
         */
        const val DRAWER_EDGE_WIDTH_DEFAULT = 20
        const val DRAWER_EDGE_WIDTH_MIN = 12
        const val DRAWER_EDGE_WIDTH_MAX = 120

        /**
         * How far a finger swipe drags what it is driving, as a percentage of the finger's own
         * travel: [SWIPE_SENSITIVITY_DEFAULT] moves the remote pointer exactly as far as the
         * finger moved, a smaller number makes it crawl and a larger one makes it overshoot.
         *
         * Two places edit this one value: the drawer's mouse-sensitivity panel and the older
         * "Swipe sensitivity" slider under Settings -> Input. That slider used to stop at 5..15
         * (50%..150%), which the panel widens in both directions because "the pointer barely
         * moves" and "it flies past what I aimed at" are equally common complaints, and because
         * a slow drag is the usual cure on a large desktop scaled down to a phone. The ends are
         * kept off zero and off a negative on purpose: one freezes every swipe gesture and the
         * other silently reverses the direction the invert switch below is meant to control.
         */
        const val SWIPE_SENSITIVITY_DEFAULT = 10
        const val SWIPE_SENSITIVITY_MIN = 1
        const val SWIPE_SENSITIVITY_MAX = 40

        /**
         * How see-through the message that floats up over the bottom of a screen is, in percent,
         * read by [com.limelight.util.FloatingText]. Spelled as transparency rather than opacity
         * because that is what the slider says: [FLOATING_TEXT_TRANSPARENCY_MIN] draws it exactly
         * as it has always been drawn, [FLOATING_TEXT_TRANSPARENCY_MAX] makes it invisible. Solid
         * is the shipped default - the message is there to be read.
         */
        const val FLOATING_TEXT_TRANSPARENCY_DEFAULT = 0
        const val FLOATING_TEXT_TRANSPARENCY_MIN = 0
        const val FLOATING_TEXT_TRANSPARENCY_MAX = 100

        /**
         * When a phone keyboard's text reaches the host. [IME_TEXT_TIMING_CONFIRM] holds
         * every provisional keystroke back until the candidate is confirmed, so a word typed
         * and then widened by a candidate ("work" -> "workbuddy") lands once instead of as
         * "workworkbuddy" - the fix for the reported double-typing, and the shipped default.
         * [IME_TEXT_TIMING_IMMEDIATE] mirrors each keystroke as it is typed, like a physical
         * keyboard, and repairs the host text when the IME later revises the word. See
         * [com.limelight.ui.vnc.input.ImeTextModel]. An unknown stored value reads as the
         * default.
         */
        const val IME_TEXT_TIMING_CONFIRM = "confirm"
        const val IME_TEXT_TIMING_IMMEDIATE = "immediate"
        const val IME_TEXT_TIMING_DEFAULT = IME_TEXT_TIMING_CONFIRM
    }

    inner class UI {
        val theme = StringLivePref("theme", "system")

        // Accent color of the whole UI (see AccentTheme), stored as hex by the
        // color picker. The plain read backs the theme picked at activity creation;
        // the live one rebuilds open activities when the user changes it in settings.
        // A champagne gold is the default - the same color App.Theme carries, so an install that
        // never opened the picker looks like the picker's own default swatch. Kept in sync with
        // AccentTheme.DEFAULT_ARGB and app:defaultValue of the preference, which are the other
        // two ways this value gets read.
        var accentColor by StringPref("accent_color", "#FFEECE6D")
        val accentColorLive = StringLivePref("accent_color", "#FFEECE6D")

        // The one-beep VNC bell (see VncViewModel.onBell). Off by default: an unexpected
        // beep is more startling than useful; the Interface settings screen offers the row.
        // Must match app:defaultValue of bell_enabled in pref_appearance.xml.
        var bell by BooleanPref("bell_enabled", false)
        val sortServerList = BooleanLivePref("sort_server_list", false)

        /**
         * The transparency above, edited on the interface settings screen. Read where a message
         * is put on screen and clamped there, so a backup that names any number at all still
         * draws something.
         */
        var floatingTextTransparencyPercent by IntPref("floating_text_transparency_percent",
                FLOATING_TEXT_TRANSPARENCY_DEFAULT)
    }

    inner class Viewer {
        val orientation; get() = prefs.readString("viewer_orientation", "auto")

        /**
         * The same choice as [orientation], announced when it changes. The viewer needs that:
         * it is a live activity that the settings screen is opened on top of, so a new
         * orientation arrived with nothing telling the stream about it - the choice was
         * stored, shown in the picker, and only ever took effect on the next connection.
         */
        val orientationLive = StringLivePref("viewer_orientation", "auto")
        val fullscreen; get() = prefs.readBoolean("fullscreen_display", true)
        val pipEnabled; get() = prefs.readBoolean("pip_enabled", false)
        val drawBehindCutout; get() = fullscreen && prefs.readBoolean("viewer_draw_behind_cutout", false)
        val keepScreenOn; get() = prefs.readBoolean("keep_screen_on", true)
        val toolbarAlignment; get() = prefs.readString("toolbar_alignment", "start")
        val toolbarOpenWithSwipe; get() = prefs.readBoolean("toolbar_open_with_swipe", true)
        val zoomMax; get() = prefs.readInt("zoom_max", ZOOM_PCT_DEFAULT_MAX)
                .coerceIn(ZOOM_PCT_LIMIT_MIN, ZOOM_PCT_LIMIT_MAX) / 100F
        val zoomMin; get() = prefs.readInt("zoom_min", ZOOM_PCT_DEFAULT_MIN)
                .coerceIn(ZOOM_PCT_LIMIT_MIN, ZOOM_PCT_LIMIT_MAX) / 100F
        val perOrientationZoom; get() = prefs.readBoolean("per_orientation_zoom", true)

        /**
         * Whether one button of the stream toolbar is in sight. The toolbar keeps the list
         * of buttons and hands out their keys (`toolbar_show_<name>`; the gesture style one
         * is the older `toolbar_show_gesture_style_toggle`), and its own panel is the only
         * place that edits them - the Viewer settings screen used to carry a duplicate
         * switch for that one key until it was dropped.
         *
         * Functions rather than delegates because the key is built from the button that is
         * being asked about, which is only known at runtime. Everything is shown until the
         * user says otherwise.
         */
        fun toolbarItemShown(key: String): Boolean = prefs.readBoolean(key, true)

        fun setToolbarItemShown(key: String, shown: Boolean) = prefs.edit {
            putBoolean(key, shown)
        }

        /**
         * The order of the toolbar's buttons, as a comma-separated list of the same
         * `toolbar_show_*` keys above. Empty means nobody moved anything: every button
         * keeps the place the drawer's layout gave it. Keys the app no longer knows are
         * dropped when the order is read back.
         */
        var toolbarItemOrder by StringPref("toolbar_item_order", "")

        val pauseUpdatesInBackground; get() = prefs.readBoolean("pause_fb_updates_in_background", false)

        /**
         * GameStream / Android-device only: render decoded frames straight into a
         * [com.limelight.ui.vnc.StreamSurfaceView] instead of sampling them back through the
         * GL `SurfaceTexture` pipeline. Skipping the extra composite saves a few milliseconds
         * of end-to-end latency, matching upstream moonlight-android. On by default; the
         * Interface settings screen offers a fallback to the GL path for the rare device where
         * a punched-through surface misbehaves. Ignored by VNC, which must stay on GL to draw
         * its local cursor.
         */
        val directScanSurfaceView; get() = prefs.readBoolean("direct_scan_surface_view", true)
        // Live latency / FPS / dropped-frame overlay toggled from the viewer toolbar.
        // Persisted so the choice survives across sessions.
        var showPerfOverlay by BooleanPref("show_perf_overlay", false)

        // Silence the remote audio on this phone, also toggled from the viewer toolbar.
        // Persisted for the same reason, and with more reason still: someone whose host is
        // permanently silent would otherwise reach for the button in every single session.
        // See VncClient.audioMuted and RemoteSession.setAudioMuted.
        var audioMuted by BooleanPref("audio_muted", false)

        /**
         * Draw a marker on the picture at the place the computer's pointer actually is
         * (see InputView.onDraw). Toggled from the viewer toolbar, so it persists like the
         * overlay and the mute above.
         *
         * Off by default: the mark is an opt-in diagnostic, and a pointer drawn over someone's
         * desktop without being asked for is noise. The toolbar's panel and its restore
         * button both return to this value rather than force the mark on.
         *
         * This is not [Input.hideLocalCursor]: that one only decides whether the system arrow
         * of a connected mouse is drawn, and it stays where the input settings put it.
         */
        var showLocalCursorMarker by BooleanPref("show_local_cursor_marker", false)

        /**
         * How big that marker is drawn, in percent of its own base size. Clamped where it is
         * read, because a backup can name any number and this one multiplies coordinates
         * handed to a Path. See [CURSOR_MARKER_SIZE_DEFAULT] for the range the slider offers.
         */
        var cursorMarkerSizePercent by IntPref("cursor_marker_size_percent", CURSOR_MARKER_SIZE_DEFAULT)

        /**
         * Which of the two shapes above is drawn. The dot is the shipped default (see
         * [CURSOR_MARKER_SHAPE_DEFAULT]); InputView draws the dot for [CURSOR_MARKER_SHAPE_DOT]
         * and the arrow for anything else, so a hand-edited or unrecognized value falls back to
         * the arrow rather than drawing nothing.
         */
        var cursorMarkerShape by StringPref("cursor_marker_shape", CURSOR_MARKER_SHAPE_DEFAULT)

        /**
         * The marker's fill as #AARRGGBB hex, read by [InputView] when it paints the mark. Blue
         * by default; the picker in the cursor panel is not quantized, so a stored value is used
         * verbatim, exactly like the assist ball's fill above.
         */
        var cursorMarkerColor by StringPref("cursor_marker_color", CURSOR_MARKER_COLOR_DEFAULT)

        /**
         * The edge strip that opens the toolbar drawer, in dp (see [DRAWER_EDGE_WIDTH_DEFAULT]).
         * Edited from the toolbar section of the view settings and from the drawer's own panel,
         * both through this key, and handed to
         * [com.limelight.ui.vnc.EdgeWidthDrawerLayout] when a viewer starts. The
         * value is read straight through; the view clamps it to the offered range, so a backup
         * naming anything outside it is corrected where it is used.
         */
        var drawerEdgeWidthDp by IntPref("drawer_edge_trigger_dp", DRAWER_EDGE_WIDTH_DEFAULT)

        //
        // Floating assist ball on top of the stream. Most of it is edited from the toolbar
        // drawer; the on/off additionally mirrors a switch in the viewer settings (same key,
        // kept in sync through the shared SharedPreferences). See ui/vnc/AssistBall.kt.
        //
        var assistBallEnabled by BooleanPref("assist_ball_enabled", true)

        // Stored as 0.0-1.0 but edited as a percentage in the UI - and inverted there, because
        // the slider reads as a transparency: the default 30% opacity is shown as 70%.
        var assistBallOpacity by FloatPref("assist_ball_opacity", ASSIST_BALL_OPACITY_DEFAULT)

        // Ball diameter in dp, clamped to the slider's range.
        var assistBallSizeDp by IntPref("assist_ball_size_dp", ASSIST_BALL_SIZE_DEFAULT)

        // What a tap on the ball does: "toolbar" or "keyboard".
        var assistBallAction by StringPref("assist_ball_action", "toolbar")

        // Ball fill as #AARRGGBB hex. Black by default, matching bg_assist_ball; the ball
        // is tinted with this at runtime, so unlike the accent color it is not quantized.
        var assistBallColor by StringPref("assist_ball_color", ASSIST_BALL_COLOR_DEFAULT)

        // Letterbox color of the streaming view - the bands left above/below (or beside)
        // the picture when its aspect does not match the screen. Painted onto the container
        // *behind* the video surface, so like the ball it is used verbatim rather than
        // quantized, and needs an opaque ARGB default: black, the conventional choice.
        // Applied live by VncActivity; chosen from the toolbar (see Toolbar).
        var streamBarColor by StringPref("stream_bar_color", STREAM_BAR_COLOR_DEFAULT)

        //
        // A full QWERTY keyboard over the stream, reachable from its own panel in the
        // toolbar drawer. Enabled/disabled, faded and pinned from there; the overlay
        // itself reacts through the viewmodel. See ui/vnc/FullKeyboard.kt.
        //
        var fullKeyboardEnabled by BooleanPref("full_keyboard_enabled", false)

        // Stored as opacity 0.0-1.0, edited as an inverted transparency percentage in the
        // drawer like the ball's slider above; see FULL_KEYBOARD_OPACITY_DEFAULT for where
        // a fresh install starts.
        var fullKeyboardOpacity by FloatPref("full_keyboard_opacity", FULL_KEYBOARD_OPACITY_DEFAULT)

        // On means the keyboard answers its grip bar with nothing: it stays where it was
        // last dragged, so a swipe that meant the stream cannot shove it aside.
        var fullKeyboardDragLocked by BooleanPref("full_keyboard_drag_locked", false)

        // Proportional size multiplier for the whole board, edited as a percentage in the
        // drawer. Held the same in portrait and landscape: the board is a fixed-size overlay
        // the user drags, so it is never resized to the screen. See ui/vnc/FullKeyboard.kt.
        var fullKeyboardScale by FloatPref("full_keyboard_scale", FULL_KEYBOARD_SCALE_DEFAULT)

        /**
         * Log recording (see [PersistentLog] and [AppLogging]), chosen from the Tools
         * screen's dropdown: "off" neither emits app log lines nor records anything,
         * "session" keeps only what was logged since the capture started at this launch,
         * "all" appends across launches. Off by default: emission aside, capture keeps a
         * follow-mode `logcat` process alive and writes to storage, so it stays off until
         * someone opts in to debug a hard-to-reproduce problem. Read at launch to restart
         * an in-progress capture, and by the dropdown to switch it live.
         *
         * There used to be a `logging_enabled` switch here. The dropdown's "off" covers it,
         * so the stored key is simply ignored rather than migrated away.
         */
        val loggingMode; get() = prefs.readString("logging_mode", "off")
                ?.takeIf { it == "session" || it == "all" } ?: "off"
    }

    inner class Gesture {
        // These fallbacks are what a fresh install actually runs on - they have to
        // match the app:defaultValue of the same keys in res/xml/pref_input.xml,
        // otherwise the settings screen would show one thing and behave like another.
        //
        // `style` is the one of these that is not merely looked up in a table: it is fed
        // to `check(isNotBlank())` before a session starts, so a backup holding `""` for
        // this key would abort every connection attempt. An empty answer is treated as no
        // answer, like every other unreadable value in this file.
        val style; get() = prefs.readString("gesture_style", "touchpad")?.takeIf { it.isNotBlank() } ?: "touchpad"
        val tap1 = "left-click" //Preference UI was removed
        val tap2; get() = prefs.readString("gesture_tap2", "right-click")!!
        // Three fingers is the gesture nobody's thumb is aiming with, which makes it the right
        // one to raise the keyboard with - and, like 'long press' below, this fallback is the
        // whole of that default until the input screen is opened, because nothing calls
        // PreferenceManager.setDefaultValues.
        val tap3; get() = prefs.readString("gesture_tap3", "open-keyboard")!!
        val doubleTap; get() = prefs.readString("gesture_double_tap", "double-click")!!
        // 'left-press' here and the app:defaultValue in pref_input.xml must agree: nothing
        // calls PreferenceManager.setDefaultValues, so until the input screen is opened this
        // fallback is the only answer. It also pairs with 'Long press and swipe': staying
        // still presses and releases the left button, starting to drag performs that gesture.
        val longPress; get() = prefs.readString("gesture_long_press", "left-press")!!
        val swipe1; get() = prefs.readString("gesture_swipe1", "none")!!
        val swipe2; get() = prefs.readString("gesture_swipe2", "remote-scroll")!!
        val swipe3; get() = prefs.readString("gesture_swipe3", "none")!!
        val doubleTapSwipe; get() = prefs.readString("gesture_double_tap_swipe", "none")!!
        val longPressSwipe; get() = prefs.readString("gesture_long_press_swipe", "remote-drag")!!
        // Whether this gesture exists is decided by its own action alone. It used to also
        // require that 'Long press' wasn't 'left-press', assuming a held left button and a
        // left-button drag can't coexist. They can: once a swipe is configured, the long
        // press is confirmed on finger-up, so holding still presses and releases the button
        // at the touch point, and only an actual drag becomes the swipe action.
        val longPressSwipeEnabled; get() = (longPressSwipe != "none")
        val longPressDetectionEnabled; get() = (longPress != "none" || longPressSwipeEnabled)
        val swipeSensitivity get() = swipeSensitivityValue / 10f

        /**
         * The same value as [swipeSensitivity], in the percent form both editors work in: the
         * drawer's slider and the `SeekBarPreference` on the input screen. Writing clamps, so
         * neither can store something that would freeze or invert the gestures.
         */
        var swipeSensitivityPercent: Int
            get() = swipeSensitivityValue
            set(value) = prefs.edit {
                putInt("gesture_swipe_sensitivity",
                        value.coerceIn(SWIPE_SENSITIVITY_MIN, SWIPE_SENSITIVITY_MAX))
            }

        /**
         * The stored percentage, already clamped to what the sliders offer. A backup file can
         * name anything, and this ends up as a multiplier on every scroll delta - zero freezes
         * the wheel, a huge number turns a flick into a page jump, and a negative one silently
         * reverses the direction the switch below is meant to control.
         */
        private val swipeSensitivityValue
            get() = prefs.readInt("gesture_swipe_sensitivity", SWIPE_SENSITIVITY_DEFAULT)
                    .coerceIn(SWIPE_SENSITIVITY_MIN, SWIPE_SENSITIVITY_MAX)
        val invertVerticalScrolling; get() = prefs.readBoolean("invert_vertical_scrolling", false)
        val quickTap1Enabled; get() = ((doubleTap == "none" || doubleTap == "double-click") && doubleTapSwipe == "none")
    }

    inner class Input {
        val gesture = Gesture()

        val vkOpenWithKeyboard; get() = prefs.readBoolean("vk_open_with_keyboard", false)
        val vkShowAll; get() = prefs.readBoolean("vk_show_all", false)
        var vkLayout by StringPref("vk_keys_layout", null)
        /**
         * Rows of the virtual key grid. It is a [android.widget.GridLayout] row count, so
         * the only sensible values are the ones the picker offers; anything else is a
         * preference file talking to a layout manager.
         */
        val vkRowCount
            get() = (prefs.readString("vk_row_count", null)?.toIntOrNull() ?: 2).coerceIn(1, 3)

        /**
         * Which edge of the stream the key bar floats against: the bottom (the shipped default,
         * where it has always sat) or the top. Chosen on the virtual-keys settings screen and
         * applied by [com.limelight.ui.vnc.VirtualKeys] as the bar's vertical gravity inside the
         * frame; horizontal centering is unchanged. An unknown stored value (a hand-edited backup)
         * reads as the bottom rather than throwing.
         */
        val vkPosition; get() = prefs.readString("vk_position", "bottom")
                ?.takeIf { it == "bottom" || it == "top" } ?: "bottom"

        val vkUseSuperWithSingleTap; get() = prefs.readBoolean("vk_use_super_with_single_tap", false)

        /**
         * Modifier keys (Ctrl/Alt/Shift/Super) latch on a single tap: they stay pressed until
         * they are tapped again. A key typed after them still fires once (down-and-up) and
         * leaves the modifier held, so a chord like Ctrl+C can be built one tap at a time.
         * With this off, a tapped modifier releases automatically once the next key is sent -
         * the one-shot behaviour - unless it was locked with a long press.
         */
        val vkHoldKeys; get() = prefs.readBoolean("vk_hold_keys", false)

        val mousePassthrough; get() = prefs.readBoolean("mouse_passthrough", true)
        val capturePointer; get() = mousePassthrough && prefs.readBoolean("capture_pointer", false)
        var hideLocalCursor by BooleanPref("hide_local_cursor", false)
        val hideRemoteCursor; get() = prefs.readBoolean("hide_remote_cursor", false)
        val mouseBack; get() = prefs.readString("mouse_back", "right-click")!!
        val interceptMouseBack; get() = mouseBack != "default"

        val kmLanguageSwitchToSuper; get() = prefs.readBoolean("km_language_switch_to_super", false)
        val kmRightAltToSuper; get() = prefs.readBoolean("km_right_alt_to_super", false)
        val kmBackToEscape; get() = prefs.readBoolean("km_back_to_escape", false)

        /**
         * When a phone keyboard's text reaches the host (see [IME_TEXT_TIMING_CONFIRM]). The
         * value is read once per session in [com.limelight.ui.vnc.input.InputHandler], so a
         * change here takes effect on the next connection rather than mid-typing. A stored
         * value the app does not know falls back to the default.
         */
        val imeTextTiming
            get() = prefs.readString("ime_text_timing", IME_TEXT_TIMING_DEFAULT)
                    ?.takeIf { it == IME_TEXT_TIMING_CONFIRM || it == IME_TEXT_TIMING_IMMEDIATE }
                    ?: IME_TEXT_TIMING_DEFAULT
    }

    inner class Server {
        val clipboardSync; get() = prefs.readBoolean("clipboard_sync", true)

        /**
         * VNC only: handle the classic cut-text field exactly as RFB defines it, instead
         * of working out from the bytes that a server ignored the Latin-1 promise and sent
         * UTF-8 or a Windows ANSI codepage. The guess is what makes Chinese survive a
         * Windows server; this switch is the escape hatch for the rare server it reads
         * wrong - see ClipboardTextCodec.decode.
         */
        val clipboardStrictLatin1; get() = prefs.readBoolean("clipboard_strict_latin1", false)

        /**
         * GameStream only: carry the clipboard over a side RFB connection to the same
         * host, because the streaming protocol has no clipboard message at all - without
         * the bridge, a `copy` from the phone gets typed out as keystrokes instead.
         * On by default: it costs one short-lived socket and changes nothing about video.
         */
        val gamestreamClipboardViaVnc; get() = prefs.readBoolean("gamestream_clipboard_via_vnc", true)

        /**
         * Android-device protocol only: override the clipboard payload ceiling. Off keeps
         * [CLIPBOARD_LIMIT_DEFAULT]; on sends the edited number to the helper as `clipMax`
         * so that trimming on the way out and dropping on the way in agree.
         */
        val clipboardCustomLimit; get() = prefs.readBoolean("clipboard_custom_limit", false)

        /**
         * The configured ceiling, already clamped. Kept as text by EditTextPreference and
         * parsed here, exactly like `vk_row_count` above - a stored value that is not a
         * number falls back to the protocol default rather than throwing.
         *
         * The XML default must say the same thing as [CLIPBOARD_LIMIT_DEFAULT].
         */
        val clipboardLimitBytes; get() = (prefs.readString("clipboard_max_bytes", null)?.toIntOrNull()
                ?: CLIPBOARD_LIMIT_DEFAULT).coerceIn(CLIPBOARD_LIMIT_MIN, CLIPBOARD_LIMIT_MAX)

        val lockSavedServer; get() = prefs.readBoolean("lock_saved_server", false)
        val backupPasswordEnabled; get() = prefs.readBoolean("backup_password_enabled", false)
        val autoReconnect; get() = prefs.readBoolean("auto_reconnect", false)

        /**
         * Android-device protocol only: leave the encoder process running on the
         * controlled device after a disconnect. It exits on its own a few seconds
         * after its sockets close, so keeping it merely makes an immediate
         * reconnect skip the process startup. Exposed from the session toolbar.
         */
        var androidKeepServerOnDisconnect by BooleanPref("android_keep_server_on_disconnect", false)

        // There used to be a `discovery_autorun` switch here. Scanning is a manual
        // action now, so the stored key is simply ignored rather than migrated away.
        val rediscoveryIndicator = BooleanLivePref("rediscovery_indicator", true)

        /*
         * Fields the saved-server search matches against, as a comma-separated list of keys
         * (see [SEARCH_FIELDS_DEFAULT]). The live variant drives the result list so ticking
         * a box in the search dialog re-filters at once; the plain one is what the dialog
         * writes. Both share the `search_fields` key.
         */
        val searchFieldsLive = StringLivePref("search_fields", SEARCH_FIELDS_DEFAULT)
        var searchFields by StringPref("search_fields", SEARCH_FIELDS_DEFAULT)

        // Custom display names for protocol badges. When empty (the default) each protocol
        // uses its shipped label (RFB, GameStream, ADB). A non-blank value replaces the
        // shipped label everywhere a badge appears: server list, discover cards, profile
        // editor, and the session toolbar.
        var protocolVncBadge by StringPref("protocol_vnc_badge", "")
        var protocolGameStreamBadge by StringPref("protocol_gamestream_badge", "")
        var protocolAndroidBadge by StringPref("protocol_android_badge", "")
    }

    /**
     * These are used for one-time features/tips, or UI state .
     * These are not exposed to user.
     */
    inner class RunInfo {
        /**
         * Whether the key bar rises on its own when a session connects. It starts down: the bar
         * covers part of the remote picture, and a first run should show the stream rather than
         * the tools over it. The drawer's button raises it and the choice then persists, so this
         * only speaks for a session that has never been given one.
         */
        var showVirtualKeys by BooleanPref("run_info_show_virtual_keys", false)
        var assistBallXBias by FloatPref("run_info_assist_ball_x_bias", ASSIST_BALL_X_BIAS_DEFAULT)
        var assistBallYBias by FloatPref("run_info_assist_ball_y_bias", ASSIST_BALL_Y_BIAS_DEFAULT)
        var fullKeyboardXBias by FloatPref("run_info_full_keyboard_x_bias", FULL_KEYBOARD_X_BIAS_DEFAULT)
        var fullKeyboardYBias by FloatPref("run_info_full_keyboard_y_bias", FULL_KEYBOARD_Y_BIAS_DEFAULT)

        /**
         * One-time step from the defaults earlier versions shipped to the bottom-aligned one.
         * A board still sitting where any of them put it is a board no one has moved, so it
         * follows the newest default; a board the user dragged keeps the spot they chose. The
         * comparison leaves a margin because clamping and rounding turn those defaults into
         * something like 0.06 / 0.74 on real screens, and the flag stops the step from running
         * again after someone drags the board back towards an old spot.
         */
        private var fullKeyboardBottomDefault by BooleanPref("run_info_full_keyboard_bottom_default", false)

        /**
         * The next step of the same kind, one release later: the bottom-aligned center became
         * the bottom corner, which is a change of the horizontal value only. The board still
         * carries the negative sentinel vertically when nobody has dragged it, and that is what
         * tells this apart from a board the user parked in the middle by hand - a drag stores a
         * 0..1 share on the axis it moved.
         */
        private var fullKeyboardCornerDefault by BooleanPref("run_info_full_keyboard_corner_default", false)

        /**
         * One-time step of the same kind for the ball: the bottom-right default became the
         * left-edge, vertically centered one. A ball still sitting at the old default's spot
         * is a ball no one has moved, so it follows the newest default; the margin covers the
         * clamping and rounding that turn the stored pair into something nearby on real
         * screens, and the flag keeps a later manual drag back to that spot from being
         * overridden.
         */
        private var assistBallLeftDefault by BooleanPref("run_info_assist_ball_left_default", false)

        init {
            if (!fullKeyboardBottomDefault) {
                fullKeyboardBottomDefault = true
                val atBottomLeft = abs(fullKeyboardXBias - LEGACY_FULL_KEYBOARD_X_BIAS) < .1f &&
                        abs(fullKeyboardYBias - LEGACY_FULL_KEYBOARD_Y_BIAS) < .1f
                val atTopCenter = abs(fullKeyboardXBias - PREVIOUS_FULL_KEYBOARD_X_BIAS) < .1f &&
                        abs(fullKeyboardYBias - PREVIOUS_FULL_KEYBOARD_Y_BIAS) < .1f
                if (atBottomLeft || atTopCenter) {
                    fullKeyboardXBias = FULL_KEYBOARD_X_BIAS_DEFAULT
                    fullKeyboardYBias = FULL_KEYBOARD_Y_BIAS_DEFAULT
                }
            }

            if (!fullKeyboardCornerDefault) {
                fullKeyboardCornerDefault = true
                if (abs(fullKeyboardXBias - PREVIOUS_FULL_KEYBOARD_X_BIAS) < .1f &&
                        fullKeyboardYBias < 0f)
                    fullKeyboardXBias = FULL_KEYBOARD_X_BIAS_DEFAULT
            }

            if (!assistBallLeftDefault) {
                assistBallLeftDefault = true
                if (abs(assistBallXBias - PREVIOUS_ASSIST_BALL_X_BIAS) < .1f &&
                        abs(assistBallYBias - PREVIOUS_ASSIST_BALL_Y_BIAS) < .1f) {
                    assistBallXBias = ASSIST_BALL_X_BIAS_DEFAULT
                    assistBallYBias = ASSIST_BALL_Y_BIAS_DEFAULT
                }
            }
        }

        /*
         * Floating mini window. One window geometry per local orientation: a phone held
         * upright comes back to the card it had the last time it was upright, and the same
         * phone turned sideways to the one it was dragged into sideways. The card's shape
         * follows the hand holding the phone rather than the picture inside it, which is why
         * a stream of a different shape is letterboxed rather than allowed to reshape the
         * window. Two slots - `portrait`, `landscape` - each holding `width,height,x,y`.
         *
         * The values are fractions of the screen rather than upstream's raw pixels: this
         * memory is shared by every server the app connects to, from a phone to a car head
         * unit, and a box stored in pixels from one of them is nonsense to the next.
         *
         * Unset means "never dragged", which [com.limelight.ui.vnc.MiniWindow] reads as its
         * default - four fifths of the screen, centered. The four keys of the earlier
         * (local orientation x remote orientation) memory (`run_info_mini_window_pp`, `_pl`,
         * `_lp`, `_ll`) and the three before those (`_x_bias`, `_y_bias`, `_width_fraction`)
         * are simply ignored, like `discovery_autorun` above.
         */
        var miniWindowPortrait by StringPref("run_info_mini_window_portrait", "")
        var miniWindowLandscape by StringPref("run_info_mini_window_landscape", "")

        /** Where the collapsed floating ball sits, as a fraction of its screen travel. */
        var miniWindowTabXBias by FloatPref("run_info_mini_window_tab_x_bias", .85f)
        var miniWindowTabYBias by FloatPref("run_info_mini_window_tab_y_bias", .3f)

        /*
         * How the two kinds of resize grip turn a drag into a new box, per grip because they
         * are asked for different things. `aspect` keeps the box at the stream's ratio, which
         * is what Mi-Freeform's corner handles do and what leaves no black bars; `free` lets
         * the width and the height move apart, which is the only way to make the card a shape
         * of its own. The viewer-settings ListPreferences write these; the values must match
         * [com.limelight.ui.vnc.MiniWindow.ScaleMode].
         *
         * Both handles default to `free`: a size change should follow the finger on both
         * axes, whether it comes from the bottom-bar dots or the card's corners. `aspect`
         * (keep the card's shape, forced back to the stream's ratio - see
         * MiniWindow.setupGrip) stays available as an explicit choice.
         */
        val miniDotScaleMode; get() = prefs.readString("run_info_mini_dot_scale_mode", "free")
        val miniCornerScaleMode; get() = prefs.readString("run_info_mini_corner_scale_mode", "free")

        /*
         * Whether the mini window draws the row of phone keys (Back / Home / Recents) under
         * the picture, one switch per protocol. The row only has a transport on a device
         * driven over ADB, so a GameStream or RFB session starts with it hidden rather than
         * showing three keys that can never fire. The toggle action in the window writes the
         * switch of whichever session it is running in, which is why these are user-facing
         * (they appear in the viewer settings) even though they live with the rest of the
         * window's state.
         *
         * The single `run_info_mini_window_nav_bar` switch these replace is simply ignored,
         * like `discovery_autorun` above.
         */
        var miniNavKeysAdb by BooleanPref("run_info_mini_nav_keys_adb", true)
        var miniNavKeysGameStream by BooleanPref("run_info_mini_nav_keys_gamestream", false)
        var miniNavKeysVnc by BooleanPref("run_info_mini_nav_keys_vnc", false)

        /*
         * Mini window bar actions, in the style of Mi-Freeform: the top bar is three equal
         * slots and the bottom handle answers a double tap, each firing the action stored
         * here. The value is a MiniBarAction key; the viewer-settings ListPreferences write
         * it and [com.limelight.ui.vnc.MiniBarAction.fromKey] reads it back, defaulting to
         * close / full screen / minimize on top and keyboard on the handle - Mi-Freeform's
         * own defaults. Unset (never visited settings) yields the same through these defaults.
         */
        val miniTopSlot0; get() = prefs.readString("run_info_mini_top_slot_0", "close")
        val miniTopSlot1; get() = prefs.readString("run_info_mini_top_slot_1", "fullscreen")
        val miniTopSlot2; get() = prefs.readString("run_info_mini_top_slot_2", "minimize")
        val miniDoubleTap; get() = prefs.readString("run_info_mini_double_tap", "keyboard")
    }

    val ui = UI()
    val viewer = Viewer()
    val input = Input()
    val server = Server()
    val runInfo = RunInfo()

    /****************************** Helpers *******************************/
    open inner class Pref<T>(private val getter: SharedPreferences.() -> T, private val setter: SharedPreferences.Editor.(T) -> Unit) {
        operator fun getValue(a: Any, kp: KProperty<*>) = getter(prefs)
        operator fun setValue(a: Any, kp: KProperty<*>, value: T) = prefs.edit { setter(this, value) }
    }

    inner class BooleanPref(val key: String, default: Boolean) : Pref<Boolean>({ readBoolean(key, default) }, { putBoolean(key, it) })
    inner class StringPref(val key: String, default: String?) : Pref<String?>({ readString(key, default) }, { putString(key, it) })
    inner class IntPref(val key: String, default: Int) : Pref<Int>({ readInt(key, default) }, { putInt(key, it) })
    inner class FloatPref(val key: String, default: Float) : Pref<Float>({ readFloat(key, default) }, { putFloat(key, it) })

    /**
     * For some preference changes we want to provide live feedback to user.
     * This class is used for such scenarios. Based on [LiveData], it notifies
     * the observers whenever the value of given preference is changed.
     */
    open inner class LivePref<T>(val key: String, private val getter: SharedPreferences.() -> T) : LiveData<T>() {
        private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (key == changedKey)
                value = getter(prefs)
        }

        override fun onActive() {
            if (!isInitialized) {
                value = getter(prefs)
                prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
            }
        }
    }

    inner class BooleanLivePref(key: String, default: Boolean) : LivePref<Boolean>(key, { readBoolean(key, default) })
    inner class StringLivePref(key: String, default: String) : LivePref<String>(key, { readString(key, default)!! })


    /****************************** Migrations *******************************/
    init {
        if (!prefs.readBoolean("gesture_direct_touch", true)) prefs.edit {
            remove("gesture_direct_touch")
            putString("gesture_style", "touchpad")
        }

        if (!prefs.readBoolean("natural_scrolling", true)) prefs.edit {
            remove("natural_scrolling")
            putBoolean("invert_vertical_scrolling", true)
        }

        prefs.readString("gesture_drag", null)?.let {
            prefs.edit {
                remove("gesture_drag")
                putString("gesture_long_press_swipe", it)
            }
        }

        if (!prefs.readBoolean("run_info_right_meta_keys_migrated", false)) prefs.edit {
            prefs.readString("vk_keys_layout", null)?.let { old ->
                val new = old
                        .replace("RightShift", "LeftShift")
                        .replace("RightCtrl", "LeftCtrl")
                        .replace("RightAlt", "LeftAlt")
                        .replace("RightSuper", "LeftSuper")
                putString("vk_keys_layout", new)
            }
            putBoolean("run_info_right_meta_keys_migrated", true)
        }

        /*
         * The gesture defaults were re-tuned around the touchpad style. Once the user
         * has visited the input screen the framework has copied every app:defaultValue
         * into these shared preferences, so a changed default alone would never reach
         * an existing install. Therefore each pair below is migrated only while the key
         * still holds the old default - a deliberately chosen action is left untouched.
         */
        if (!prefs.getBoolean("run_info_gesture_defaults_retuned", false)) prefs.edit {
            listOf(
                    Triple("gesture_style", "touchscreen", "touchpad"),
                    Triple("gesture_tap2", "open-keyboard", "right-click"),
                    Triple("gesture_long_press", "right-click", "none"),
                    Triple("gesture_swipe1", "pan", "none"),
                    Triple("gesture_swipe2", "pan", "remote-scroll"),
                    Triple("gesture_swipe3", "pan", "none"),
                    Triple("gesture_double_tap_swipe", "remote-drag", "none"),
                    Triple("gesture_long_press_swipe", "none", "remote-drag"),
            ).forEach { (key, previousDefault, newDefault) ->
                if (prefs.readString(key, null) == previousDefault) putString(key, newDefault)
            }
            putBoolean("run_info_gesture_defaults_retuned", true)
        }

        /*
         * 'Long press' now defaults to 'left-press' instead of 'None'. Opening the input
         * screen used to pin the old default into these preferences, so for anybody who
         * has been there the stored value would beat the new default forever. Hence this
         * one-time pass, which - like the retune above - only touches a key that still
         * holds the old default. A deliberate 'None' cannot be told apart from that and
         * gets switched once.
         */
        if (!prefs.readBoolean("run_info_long_press_defaults_to_press", false)) prefs.edit {
            if (prefs.readString("gesture_long_press", null) == "none") putString("gesture_long_press", "left-press")
            putBoolean("run_info_long_press_defaults_to_press", true)
        }

        /*
         * 'Three finger tap' gained an 'Open keyboard' action and defaults to it. Same situation
         * as the pass above: anybody who has been to the input screen has the old 'none' stored,
         * and a changed default alone would never reach them - so a key still holding 'none' is
         * moved once. A deliberate 'None' cannot be told apart from that and gets switched too,
         * which is what the previous pass accepted as well.
         */
        if (!prefs.readBoolean("run_info_tap3_defaults_to_keyboard", false)) prefs.edit {
            if (prefs.readString("gesture_tap3", null) == "none") putString("gesture_tap3", "open-keyboard")
            putBoolean("run_info_tap3_defaults_to_keyboard", true)
        }

        /*
         * The corner resize handle's scale-mode default moved from 'aspect' to 'free', so both
         * handles now follow the finger on both axes. Same situation as the passes above: anybody
         * who has opened the viewer settings has the old 'aspect' persisted, and a changed default
         * alone would never reach them - so a key still holding 'aspect' is moved once. A
         * deliberately chosen 'aspect' cannot be told apart from that and gets switched too,
         * which is what the previous passes accepted as well.
         */
        if (!prefs.readBoolean("run_info_mini_corner_defaults_to_free", false)) prefs.edit {
            if (prefs.readString("run_info_mini_corner_scale_mode", null) == "aspect")
                putString("run_info_mini_corner_scale_mode", "free")
            putBoolean("run_info_mini_corner_defaults_to_free", true)
        }
    }
}

/****************************** Type tolerant reads *******************************/
/*
 * `SharedPreferences` is strictly typed: asking for an `int` about a key that holds a
 * string is answered with a ClassCastException, which is an Error-adjacent exception that
 * no amount of `catch (Exception)` at the call site would have made *look* like a bad
 * preference file. Every key below can have been written by an imported backup - for a key
 * this app has never stored, the import has to guess the type off the JSON literal (see
 * [com.limelight.viewmodel.PrefsViewModel.preferenceValue]), so `zoom_max` can arrive as
 * text and `fullscreen_display` as a number.
 *
 * The fast path is the framework accessor; only when it refuses do we look at what is
 * actually stored. Unreadable means "use the default", never "crash the viewer".
 */

private fun SharedPreferences.readBoolean(key: String, default: Boolean): Boolean = try {
    getBoolean(key, default)
} catch (e: ClassCastException) {
    when (val stored = all[key]) {
        is String -> stored.toBooleanStrictOrNull() ?: default
        else -> default
    }
}

private fun SharedPreferences.readInt(key: String, default: Int): Int = try {
    getInt(key, default)
} catch (e: ClassCastException) {
    when (val stored = all[key]) {
        is Number -> stored.toDouble().let { if (it.isFinite()) it.toInt() else default }
        is String -> stored.toIntOrNull() ?: default
        else -> default
    }
}

private fun SharedPreferences.readFloat(key: String, default: Float): Float = try {
    getFloat(key, default)
} catch (e: ClassCastException) {
    when (val stored = all[key]) {
        is Number -> stored.toFloat()
        is String -> stored.toFloatOrNull() ?: default
        else -> default
    }
}

private fun SharedPreferences.readString(key: String, default: String?): String? = try {
    getString(key, default)
} catch (e: ClassCastException) {
    all[key] as? String ?: default
}