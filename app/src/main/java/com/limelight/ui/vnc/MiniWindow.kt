/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.limelight.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import com.limelight.R
import com.limelight.databinding.MiniWindowBinding
import com.limelight.databinding.MiniWindowTabBinding
import com.limelight.session.Messenger
import com.limelight.ui.home.HomeActivity
import com.limelight.util.AccentTheme
import com.limelight.util.AppPreferences
import com.limelight.util.installTooltips
import com.limelight.vnc.RenderMode
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The floating mini window: the live stream in a small overlay that keeps playing while the
 * viewer itself is in the background, so the user can watch the remote machine and use
 * another app at the same time.
 *
 * Why an overlay and not [android.app.PictureInPictureMode]: PiP is the lower-maintenance
 * option and the viewer supports it separately, but its window belongs to the system -
 * there is no room in it for the remote-control keys that make this useful when a phone or a
 * car head unit is being mirrored. The price of that freedom is documented on
 * [com.limelight.session.RemoteSession.restart]: a hardware decoder picks its output surface
 * once and never re-binds it, so on the two decoding protocols (GameStream and the ADB
 * device) every round trip between window and viewer costs one reconnect. Plain VNC uploads
 * pixels instead of feeding a surface, so it resizes for free.
 *
 * The window is owned by [VncActivity] on purpose: it only has to survive the activity going
 * to the background. If the system ever destroys the activity, the ViewModel goes with it,
 * the session ends, and keeping the window up would only hide a dead stream.
 *
 * Everything visual is delegated to the viewer's own classes - [FrameView] renders and
 * [com.limelight.ui.vnc.input.InputView] feeds [InputHandler] - because those two need
 * nothing more than a correct viewport to behave identically here. That is the one thing this
 * class has to get right: [FrameState] scales the frame for the window it is drawn in, so
 * while this box is the visible one the sizes have to be re-pointed at it, and [LayoutManager]
 * has to stop pushing the full-screen ones over them. Both are put back on the way out.
 *
 * The chrome follows Mi-Freeform's freeform window: a rounded card whose shape comes from the
 * hand holding the phone - one remembered box per local orientation, so a portrait phone gets an
 * upright card and a sideways one a sideways card, whatever the stream inside it looks like. A
 * stream of a different shape is letterboxed in the card rather than allowed to reshape it, which
 * is the deliberate difference from Mi-Freeform's ratio-locked window. Above the picture is a top
 * bar of three icon slots, beneath it a bottom bar whose middle handle drags the window (and
 * answers a double tap) and whose two end dots resize it; the card's two bottom corners are
 * invisible touch zones that resize it too, pulling the nearer edge with them - no drawn handle,
 * since a mark in the corner read as a stray control. Each kind of grip can be set to keep the
 * box at the stream's ratio or to let its two sides move apart - see [ScaleMode]. The navigation
 * row of Back/Home/Recents is optional under the picture, and an edge tab still carries the folded
 * window. Like Mi-Freeform,
 * what each top slot and the double tap do is not fixed: it comes from [MiniBarAction] and the
 * viewer settings, so the layout says where the buttons are and the user says what they mean.
 * The one deliberate difference from Mi-Freeform is focus: its window becomes focusable the
 * moment it is touched, which is how a hardware keyboard reaches it and how it steals the back
 * key. This window stays passive and borrows focus only while the on-screen keyboard is up
 * - see [showKeyboard].
 */
class MiniWindow(private val activity: VncActivity) {

    private val viewModel get() = activity.viewModel
    private val windowManager get() = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inputMethodManager get() = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private var binding: MiniWindowBinding? = null
    private var params: WindowManager.LayoutParams? = null

    /** The edge tab the window folds into. Null while the window itself is showing. */
    private var tabBinding: MiniWindowTabBinding? = null
    private var tabParams: WindowManager.LayoutParams? = null

    /**
     * What [FrameState] said before this window took it over. The base scale comes from the
     * window size and the zoom from the user's pinch, both of which this box has to replace -
     * so the numbers have to be put back on the way out.
     */
    private data class Geometry(val windowWidth: Float, val windowHeight: Float,
                                val zoom1: Float, val zoom2: Float)

    /** The viewer's own frame view, remembered so it can be made the render target again. */
    private var viewerFrameView: FramePresenter? = null
    private var savedGeometry: Geometry? = null

    /**
     * The presenter actually shown in this window (GL [FrameView] or the direct-scan
     * [StreamSurfaceView]), chosen by render mode in [attachMiniPresenter]. Kept so teardown
     * pauses the right one and viewport tracking watches the right view.
     */
    private var miniPresenter: FramePresenter? = null

    /** Whether the window has reached the screen, which is when layout pushes become legal. */
    private var attached = false

    /** Whether the window is currently folded into its edge tab. */
    private var collapsed = false

    /** Where the window was when it folded, so expanding puts it back there. */
    private var collapsedX = 0

    /**
     * Watches the viewer's letterbox color preference (the palette in the slide-out toolbar)
     * for as long as this window is shown, so the bands around the picture here are the same
     * fill as the full-screen viewer's rather than the layout's black. Held as a field so
     * [giveUp] can un-observe the exact object.
     */
    private var barColorObserver: Observer<String?>? = null

    /** Which memory slot the window is using; see [Placement]. */
    private var slot: Placement.Slot? = null

    /** Time of the last tap on the bottom handle, for the double-tap action. */
    private var lastHandleTapAt = 0L

    /** The label shown on the collapsed ball: server prefix or sequence number. */
    private var ballLabel: String? = null

    /**
     * A rotation of the phone is a change of geometry slot and of screen size at once. This
     * is listened to on the application context rather than through the activity because the
     * activity is in the background while the window is up, and a stopped activity is not
     * promised a callback at all. The same reason makes [newConfig] and [liveMetrics] read that
     * context too: a callback that asked the activity what it had just been told would be told
     * nothing back.
     */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (newConfig.orientation != appliedOrientation) applyPlacement()
        }

        override fun onLowMemory() {}
    }

    private var appliedOrientation = Configuration.ORIENTATION_UNDEFINED

    val isVisible get() = binding != null

    companion object {
        /**
         * Below this a box is useless rather than small: the button panel is five 38dp slots
         * wide, and a window narrower than that cannot show what it is for. Upstream's own
         * resize floor, spelled in dp because the box is stored as a fraction of the screen.
         */
        private const val MIN_BOX_DP = 150f

        /** Height of the collapsed ball including its shadow padding (48dp + 16dp). */
        private const val BALL_SIZE_DP = 64f

        /**
         * Travel under this counts as a tap rather than a drag, so the handle can serve both.
         * A mis-recognised tap would fire the double-tap action the user was not aiming at.
         */
        private const val DRAG_THRESHOLD_PX = 50f

        /** Two taps on the handle closer than this are read as one double tap. */
        private const val DOUBLE_TAP_MS = 300L

        /** The grip has to stop above this line or the window ends up under the status bar. */
        private const val STATUS_BAR_MARGIN = 10

        private const val TAG = "MiniWindow"

        /**
         * The windows currently on screen, by server id, so that reopening a server from
         * the main screen can take its windowed session down instead of streaming to the
         * same machine twice - see [endWindowedSession]. Unsaved servers have no id to be
         * found by and are simply not tracked.
         */
        private val windowedSessions = HashMap<Long, MiniWindow>()

        /**
         * Monotonically increasing counter for unnamed servers: each collapsed ball
         * on a server without a name gets the next number so the user can tell them
         * apart at a glance.
         */
        private var unnamedBallCounter = 0

        /**
         * Takes down the window [id] is streaming into, if one is up, together with its
         * session. Called before a fresh viewer starts for the same server, from
         * [com.limelight.ui.vnc.startVncActivity].
         */
        fun endWindowedSession(id: Long) {
            windowedSessions[id]?.hide(endSession = true)
        }
    }

    /************************************************************************************
     * Window flags
     *
     * The window is not focusable by default: a floating picture must not take the system's
     * back and recents keys away from whatever app the user is working in. It becomes
     * focusable only while the on-screen keyboard is wanted - see [showKeyboard].
     *
     * `FLAG_WATCH_OUTSIDE_TOUCH` is what lets the chrome fold away when the user goes back to
     * the app behind the window, and `FLAG_NOT_TOUCH_MODAL` is what makes that event (and
     * every touch outside the picture) reach the app underneath at all.
     ***********************************************************************************/
    private val baseFlags get() = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    private val passiveFlags get() = baseFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private val focusableFlags get() = baseFlags

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

    /************************************************************************************
     * Show and hide
     ************************************************************************************/

    /**
     * Puts the stream into a floating window and hands the screen over to the server list.
     * Needs the 'display over other apps' grant, which the caller is expected to have
     * checked already - see [Toolbar].
     */
    fun show() {
        if (collapsed) { expand(); return }
        if (isVisible || !viewModel.connected) return

        val root = MiniWindowBinding.inflate(LayoutInflater.from(activity))
        binding = root
        savedGeometry = Geometry(viewModel.frameState.windowWidth, viewModel.frameState.windowHeight,
                viewModel.frameState.zoomScale1, viewModel.frameState.zoomScale2)

        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, overlayType(), passiveFlags,
                PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // A second presenter means a second decode surface (or a second EGL context on the GL
        // path). Handing that new surface over is what makes the session rebind - see
        // VncViewModel.onVideoSurfaceAvailable.
        viewerFrameView = viewModel.frameViewRef.get()
        // The window's own input view takes over the pointer marker as well, which its
        // initialize() does by registering itself; the viewer's gets it back in giveUp().
        root.miniInputView.initialize(viewModel, activity.inputHandler)
        attachMiniPresenter(root)

        configureChrome(root)
        trackViewport(root)
        observeBarColor(root)
        setupTouchRadar(root)
        setupHandleDrag(root)
        setupDotResize(root)
        setupCornerResize(root)
        setupActions(root)

        // The window's chrome is icon buttons whose only label is their contentDescription;
        // holding one down now says its name, as in the toolbar. See [installTooltips].
        installTooltips(root.root)

        applyPlacement()

        runCatching { windowManager.addView(root.root, params) }
                .onFailure {
                    Log.e(TAG, "Cannot add the mini window", it)
                    giveUp(root)
                    return
                }

        attached = true
        appliedOrientation = newConfig().orientation
        activity.applicationContext.registerComponentCallbacks(configCallbacks)
        register()

        // The viewer has to leave the foreground for the overlay to be the only thing of ours
        // on screen. `finish()` is not an option: it would take the session with it. Neither
        // is sending the task to the back: the window exists so that the app stays reachable
        // while the stream floats, so the main screen is pulled to the front of this same
        // task instead. The viewer only stops - below the main screen it waits, session and
        // window alive, and can be returned to or left running under another session, which
        // is how two servers end up under control at once.
        val home = Intent(activity, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (runCatching { activity.startActivity(home) }.isFailure)
            activity.moveTaskToBack(true)
    }

    /**
     * Paints the box behind this window's picture with the user's letterbox color, and keeps
     * it painted when the toolbar palette changes the color with the window already up.
     *
     * This mirrors what [VncActivity] does for `frame_container`: the direct-scan surface is
     * punched out of the window and only covers its own band, so the color has to live on the
     * view behind it - never on the SurfaceView itself, which is what breaks hardware
     * composition on some OEM devices. The GL [FrameView] covers the whole box and clears its
     * own letterbox from the same preference when its surface is created, so the background
     * underneath it is simply the shared answer for every other state.
     */
    private fun observeBarColor(root: MiniWindowBinding) {
        val observer = Observer<String?> { hex ->
            hex?.let {
                val argb = AccentTheme.argbOf(it)
                root.miniPicture.setBackgroundColor(argb)
                // Same two-handed approach as the viewer: the GL path covers this background
                // and has to be told directly, so a palette change lands on the live window
                // rather than only on the next surface creation.
                miniPresenter?.setLetterboxColor(argb)
            }
        }
        barColorObserver = observer
        viewModel.streamBarColor.observeForever(observer)
    }

    /**
     * Make one of this window's two presenter views the active render target, following the
     * same rule the full-screen viewer uses: the direct-scan [StreamSurfaceView] for surface
     * protocols, the GL [FrameView] otherwise. The chosen view becomes the ViewModel's current
     * presenter so incoming surfaces and state changes reach it while this window is shown.
     */
    private fun attachMiniPresenter(root: MiniWindowBinding) {
        val useDirectScan = viewModel.pref.viewer.directScanSurfaceView &&
                viewModel.renderMode == RenderMode.SURFACE

        if (useDirectScan) {
            root.miniFrameView.visibility = View.GONE
            root.miniStreamSurfaceView.attach(viewModel)
            root.miniStreamSurfaceView.visibility = View.VISIBLE
            miniPresenter = root.miniStreamSurfaceView
        } else {
            root.miniStreamSurfaceView.visibility = View.GONE
            root.miniFrameView.initialize(viewModel)
            viewModel.frameViewRef = WeakReference<FramePresenter>(root.miniFrameView)
            root.miniFrameView.visibility = View.VISIBLE
            miniPresenter = root.miniFrameView
        }
    }

    /**
     * Removes the window - and the tab, if the window was folded into one - and hands
     * rendering back to the viewer.
     *
     * [endSession] is for the window's own close button, and for taking a windowed session
     * down when the same server is reopened: with no viewer to return to, dismissing the
     * picture means stopping the stream.
     *
     * Returning to the full picture is deliberately not this function's business: the
     * viewer folds the window itself on reaching the foreground (see VncActivity.onStart),
     * so whoever brings the viewer forward needs no coordination with the fold.
     */
    fun hide(endSession: Boolean = false) {
        val root = binding ?: return
        // A fold leaves the window off the left edge on purpose, so its position must not be
        // recorded here; anything the user moved since the last save is worth keeping.
        if (!collapsed) savePosition()
        removeTab()
        runCatching { windowManager.removeView(root.root) }
        giveUp(root)

        if (endSession) {
            // The ViewModel goes with the activity, which stops the session. There is no
            // 'disconnected but still here' state worth returning to from an overlay. Also
            // reached from onDestroy, where finishing again would be beside the point.
            if (!activity.isFinishing) activity.finish()
        }
    }

    /** Only saved servers can be found again by id, and only by their latest window. */
    private fun register() {
        val id = viewModel.profile.ID
        if (id != 0L) windowedSessions[id] = this
    }

    /** [giveUp] may run on a window that never registered; identity says whose it is. */
    private fun unregister() {
        val id = viewModel.profile.ID
        if (id != 0L && windowedSessions[id] === this) windowedSessions.remove(id)
    }

    /** Undo everything that would otherwise outlive the window. */
    private fun giveUp(root: MiniWindowBinding) {
        unregister()
        binding = null
        params = null
        attached = false
        collapsed = false
        slot = null
        // [releaseFocus] needs the binding, so it cannot clear this once the window is gone;
        // a stale `true` would describe a keyboard this window no longer owns.
        keyboardIsUp = false
        // A retry still in flight would otherwise ask the IME to open a keyboard for a view
        // that has left the window manager - and, if a new window came up within the same
        // 150ms, it would be the fresh one answering to `isVisible`.
        showKeyboardTask?.let { uiHandler.removeCallbacks(it) }
        showKeyboardTask = null

        runCatching { activity.applicationContext.unregisterComponentCallbacks(configCallbacks) }

        barColorObserver?.let { viewModel.streamBarColor.removeObserver(it) }
        barColorObserver = null

        // Rendering goes back to the viewer, or its screen keeps the last frame forever:
        // nothing else will ask it to draw again. The geometry goes back with it, since this
        // box is a fraction of the size the frame was scaled for.
        viewerFrameView?.let { old ->
            viewModel.frameViewRef = WeakReference(old)
            savedGeometry?.let {
                viewModel.frameState.setWindowSize(it.windowWidth, it.windowHeight)
                viewModel.setZoom(it.zoom1, it.zoom2)
            }
            viewModel.frameState.setViewportSize(old.view.width.toFloat(), old.view.height.toFloat())
            old.refreshFrame()
            // And the decoder has to be told about it: this view's surface is no longer the
            // one the session was pointed at, which only matters for the protocols that
            // decode into a surface at all.
            old.rebindSurface()
        }
        viewerFrameView = null
        savedGeometry = null

        // The marker goes back with the picture: it is drawn by whichever input view is on
        // screen, and this window's is about to leave the window manager.
        viewModel.pointerOverlayRef = WeakReference(activity.binding.inputView)

        // Stops this window's presenter: the GL thread for the pixel-buffer path, nothing extra
        // for the direct-scan surface (its view goes with the window below it).
        (miniPresenter as? FrameView)?.onPause()
        miniPresenter = null
    }

    /************************************************************************************
     * Folding into the edge tab
     *
     * The window is not torn down here: its position simply goes off the left edge, which
     * keeps the decode surface alive. Removing and re-adding the view would cost a reconnect
     * on the two decoding protocols every time the user taps the tab, and upstream can get
     * away with tearing down only because its TextureView hands the same SurfaceTexture back
     * on the next attach.
     ************************************************************************************/

    private fun collapse() {
        val p = params ?: return
        if (binding == null) return
        if (collapsed) return

        collapsedX = p.x
        collapsed = true
        if (keyboardIsUp) releaseFocus()
        p.x = -p.width
        pushLayout()

        showTab()
    }

    private fun expand() {
        if (!collapsed) return
        val p = params ?: return
        if (binding == null) return

        collapsed = false
        removeTab()
        p.x = collapsedX.coerceIn(0, xSpan(p.width))
        pushLayout()
    }

    private fun showTab() {
        if (tabBinding != null) return

        val tab = MiniWindowTabBinding.inflate(LayoutInflater.from(activity))
        tabBinding = tab
        tabParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, overlayType(), passiveFlags,
                PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballPositionX()
            y = ballPositionY()
        }

        // Pick a label: server name's first two characters or a sequence number.
        if (ballLabel == null) {
            ballLabel = resolveBallLabel()
        }
        tab.miniCollapseText.text = ballLabel

        setupTabDrag(tab)
        runCatching { windowManager.addView(tab.root, tabParams) }
                .onFailure {
                    Log.e(TAG, "Cannot add the mini window tab", it)
                    tabBinding = null
                    tabParams = null
                }
    }

    /**
     * The ball shows the server's own name (first two characters) when one has been set,
     * or a simple sequence number otherwise so the user can tell collapsed windows apart.
     */
    private fun resolveBallLabel(): String {
        val name = viewModel.profile.name.trim()
        if (name.isNotEmpty()) return name.take(2)
        unnamedBallCounter++
        return unnamedBallCounter.toString()
    }

    private fun removeTab() {
        val tab = tabBinding ?: return
        runCatching { windowManager.removeView(tab.root) }
        tabBinding = null
        tabParams = null
    }

    private fun ballPositionX() =
            (viewModel.pref.runInfo.miniWindowTabXBias * ballPositionSpanX()).toInt()
                    .coerceIn(0, ballPositionSpanX())

    private fun ballPositionY() =
            (viewModel.pref.runInfo.miniWindowTabYBias * ballPositionSpanY()).toInt()
                    .coerceIn(0, ballPositionSpanY())

    private fun ballPositionSpanX() = (screenWidth() - dp(BALL_SIZE_DP)).coerceAtLeast(0)

    private fun ballPositionSpanY() = (screenHeight() - dp(BALL_SIZE_DP)).coerceAtLeast(0)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTabDrag(tab: MiniWindowTabBinding) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        tab.root.setOnTouchListener { _, event ->
            val p = tabParams ?: return@setOnTouchListener true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    dragged = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragged && hypot(dx, dy) < DRAG_THRESHOLD_PX) return@setOnTouchListener true
                    dragged = true
                    p.x = (startX + dx.toInt()).coerceIn(0, ballPositionSpanX())
                    p.y = (startY + dy.toInt()).coerceIn(0, ballPositionSpanY())
                    runCatching { windowManager.updateViewLayout(tab.root, p) }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        val spanX = ballPositionSpanX()
                        val spanY = ballPositionSpanY()
                        if (spanX > 0)
                            viewModel.pref.runInfo.miniWindowTabXBias =
                                    (p.x.toFloat() / spanX).coerceIn(0f, 1f)
                        if (spanY > 0)
                            viewModel.pref.runInfo.miniWindowTabYBias =
                                    (p.y.toFloat() / spanY).coerceIn(0f, 1f)
                    } else expand()
                }
            }
            true
        }
    }

    /************************************************************************************
     * Size and position
     *
     * The window is a box the picture is letterboxed into, and the box is remembered per local
     * orientation: a phone turned sideways comes back to the card it had the last time it was
     * sideways. Fractions throughout, so a box remembered on a phone means the same thing on a
     * head unit; see [com.limelight.util.AppPreferences.RunInfo] for why the values are not
     * pixels.
     ************************************************************************************/

    /**
     * One memory slot: the box the window is sized to, and where it sits.
     */
    private data class Placement(val widthFraction: Float, val heightFraction: Float,
                                 val xBias: Float, val yBias: Float) {

        fun encode(): String = "$widthFraction,$heightFraction,$xBias,$yBias"

        companion object {
            /**
             * Four fifths of the screen, centered: the window a first-timer gets. Upstream's own
             * default, and - because the box is the window - a card with room for black bars
             * whenever the stream's shape differs from the screen's.
             */
            val DEFAULT = Placement(.8f, .8f, .5f, .5f)

            /** Anything unreadable is 'never set', which is what the default is for. */
            fun decode(stored: String?, default: Placement = DEFAULT): Placement {
                val parts = stored?.split(',') ?: return default
                if (parts.size != 4) return default
                val values = parts.map { it.toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: return default }
                return Placement(values[0].coerceIn(.1f, 1f), values[1].coerceIn(.1f, 1f),
                        values[2].coerceIn(0f, 1f), values[3].coerceIn(0f, 1f))
            }
        }

        /** The two slots, named by the orientation of the device holding them. */
        enum class Slot { PORTRAIT, LANDSCAPE }
    }

    /**
     * The orientation the overlay is being drawn into. Not the activity's own: while this window
     * is up the viewer is stopped, and a stopped activity keeps serving the configuration it was
     * last resumed in - it is reconfigured on the way back to the foreground, which for a card
     * meant to outlive the activity would be after the rotation had already gone unhandled. The
     * application context is updated when the change is dispatched, so it answers for the display.
     */
    private fun newConfig() = activity.applicationContext.resources.configuration

    /** Screen measurements of the same live context; see [newConfig] for why not the activity's. */
    private val liveMetrics get() = activity.applicationContext.resources.displayMetrics

    private val localPortrait get() = newConfig().orientation != Configuration.ORIENTATION_LANDSCAPE

    /**
     * The box follows the hand holding the phone, not the picture inside it: what the remote is
     * showing has no say in the window's shape, so a rotation of the phone moves the card between
     * the two slots while a rotation of the remote only changes how much black is left around the
     * stream.
     */
    private fun currentSlot(): Placement.Slot =
            if (localPortrait) Placement.Slot.PORTRAIT else Placement.Slot.LANDSCAPE

    private fun readPlacement(slot: Placement.Slot): Placement = with(viewModel.pref.runInfo) {
        val stored = when (slot) {
            Placement.Slot.PORTRAIT -> miniWindowPortrait
            Placement.Slot.LANDSCAPE -> miniWindowLandscape
        }
        // A slot nobody has resized yet starts fitted to the phone's own remote UI rather
        // than to the remote's framebuffer: what filled the screen edge-to-edge before the
        // window opened keeps filling the card edge-to-edge inside it. Once the user drags
        // a grip the remembered box wins, bars and all - that is a choice then.
        if (stored.isNullOrEmpty()) contentPlacement() else Placement.decode(stored)
    }

    /**
     * The box a first-timer gets, or one handed back by [resetPlacements]: the same shape as the
     * phone's own full-screen remote view, so the picture being controlled simply moves into the
     * card with the bands it already had - a desktop is letterboxed top and bottom exactly as it
     * was on the full screen, and those bands take the user's letterbox colour (see
     * [observeBarColor]). The card is deliberately not re-fitted to the stream's ratio: what the
     * user was looking at before the window opened is what they keep looking at inside it.
     *
     * The card grows by whatever the chrome takes (the top and bottom bars) so the picture area
     * alone carries the phone's content ratio; the width comes out four fifths of the screen,
     * and the height falls out of that ratio plus the chrome.
     */
    private fun contentPlacement(): Placement {
        val screenW = screenWidth().toFloat()
        val screenH = screenHeight().toFloat()
        if (screenW <= 0f || screenH <= 0f) return Placement.DEFAULT

        // Chrome the picture has to share the box with, taken from the same numbers
        // mini_window.xml declares. Horizontal: none - the root carries no side padding since
        // the picture spans the card edge to edge. Vertical: the 26dp top bar and the 30dp
        // bottom bar, each of which absorbed the old root padding into its own height. The
        // optional 30dp navigation row is left out because it is hidden by default and can be
        // turned on at any time; the small extra letterbox that switch causes is on the top and
        // bottom, where the picture is already banding, never on the sides.
        val chromeH = dp(26f + 30f).toFloat()

        val boxW = .8f * screenW
        val pictureW = boxW.coerceAtLeast(dp(MIN_BOX_DP).toFloat())
        val pictureH = pictureW * (screenH / screenW)
        val boxH = pictureH + chromeH

        return Placement((boxW / screenW).coerceIn(.1f, 1f),
                (boxH / screenH).coerceIn(.1f, 1f), .5f, .5f)
    }

    /** The slot's memory as stored text; an empty string is what [resetPlacements] writes. */
    private fun writePlacement(slot: Placement.Slot, stored: String) {
        with(viewModel.pref.runInfo) {
            when (slot) {
                Placement.Slot.PORTRAIT -> miniWindowPortrait = stored
                Placement.Slot.LANDSCAPE -> miniWindowLandscape = stored
            }
        }
    }

    /** Rewrites the slot the window is currently using, from its current placement. */
    private fun updatePlacement(transform: (Placement) -> Placement) {
        val current = slot ?: return
        writePlacement(current, transform(readPlacement(current)).encode())
    }

    private fun screenWidth() = liveMetrics.widthPixels

    private fun screenHeight() = liveMetrics.heightPixels

    private fun dp(value: Float) = (value * liveMetrics.density).toInt()

    private val statusBarHeight: Int by lazy {
        @Suppress("InternalInsetResource", "DiscouragedApi")
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    /** The box of [placement] in pixels, with the floor that keeps the panel usable. */
    private fun boxOf(placement: Placement): Pair<Int, Int> {
        val min = dp(MIN_BOX_DP)
        return (placement.widthFraction * screenWidth()).toInt().coerceIn(min, screenWidth()) to
                (placement.heightFraction * screenHeight()).toInt().coerceIn(min, screenHeight())
    }

    /**
     * How a resize drag turns into a new box. The two kinds of grip answer to their own setting,
     * so the same drag can be a size change on the dots and a shape change on the corners.
     *
     * [ASPECT] scales the card along both of its own axes at the ratio it already has - the
     * picture inside keeps its own shape and is letterboxed if that differs.
     */
    private enum class ScaleMode { ASPECT, FREE }

    /** The stored text of the two settings; anything but `free` means the locked ratio. */
    private fun scaleMode(stored: String?): ScaleMode =
            if (stored == "free") ScaleMode.FREE else ScaleMode.ASPECT

    /**
     * Puts the window into the slot the local orientation says, at the size and position that
     * slot remembers. Used on the way in, on every rotation of the phone, and after every drag.
     *
     * The window is the box rather than a wrapper around the picture: a stream whose shape
     * differs from the box's is letterboxed inside it. That is what lets a drag hold the card
     * to the shape of the hand holding the phone instead of the shape of the stream.
     */
    private fun applyPlacement() {
        val p = params ?: return
        if (binding == null) return

        slot = currentSlot()
        appliedOrientation = newConfig().orientation
        val placement = readPlacement(slot!!)
        val box = boxOf(placement)
        p.width = box.first
        p.height = box.second
        p.x = (placement.xBias * xSpan(box.first)).toInt()
        p.y = placementY(placement, box.second)

        // A rotation of a folded window must not unfold it: the card keeps its new size and
        // stays off the left edge, with the position it would expand to re-derived from the slot
        // the new orientation reads, so the tab still opens onto the card that orientation had.
        if (collapsed) {
            collapsedX = p.x
            p.x = -box.first
        }

        pushLayout()
        applyTabPlacement()
    }

    /** Puts the floating ball back inside the travel the current screen allows. */
    private fun applyTabPlacement() {
        val tab = tabBinding ?: return
        val p = tabParams ?: return
        p.x = ballPositionX()
        p.y = ballPositionY()
        runCatching { windowManager.updateViewLayout(tab.root, p) }
    }

    /**
     * Resizes the window to [box] with its left edge at [newX], and remembers that in the current
     * slot. The position goes through the same fractions [applyPlacement] reads, so the edge the
     * drag was anchored on survives the round trip.
     */
    private fun applyBox(box: Pair<Int, Int>, newX: Int) {
        val p = params ?: return
        val current = slot ?: return
        val placement = readPlacement(current)
        val spanX = xSpan(box.first)
        val spanY = (yLimit(box.second) - statusBarHeight).coerceAtLeast(0)

        p.width = box.first
        p.height = box.second
        p.x = newX.coerceIn(0, spanX)
        p.y = p.y.coerceIn(statusBarHeight, yLimit(box.second))

        writePlacement(current, Placement(box.first.toFloat() / screenWidth(),
                box.second.toFloat() / screenHeight(),
                if (spanX > 0) p.x.toFloat() / spanX else placement.xBias,
                if (spanY > 0) (p.y - statusBarHeight).toFloat() / spanY else placement.yBias).encode())

        applyPlacement()
    }

    /**
     * Horizontal travel of a window [width] wide: the whole box on screen, starting at the left
     * edge. [applyPlacement] maps a remembered fraction into it and [savePosition] maps back out
     * of it, so the two have to agree on the range - see [yLimit] for the vertical case.
     */
    private fun xSpan(width: Int) = (screenWidth() - width).coerceAtLeast(0)

    /** Lowest position the window of [height] can be remembered at: flush with the bottom edge. */
    private fun yLimit(height: Int) = (screenHeight() - height).coerceAtLeast(statusBarHeight)

    private fun placementY(placement: Placement, windowHeight: Int): Int {
        val top = statusBarHeight
        return (top + placement.yBias * (yLimit(windowHeight) - top)).toInt()
    }

    private fun pushLayout() {
        val root = binding ?: return
        val p = params ?: return
        if (!attached) return
        runCatching { windowManager.updateViewLayout(root.root, p) }
    }

    /**
     * Stores where the window now is, in the fractions the slot remembers. The travel is measured
     * against the window's own size, which this class set itself a moment ago (see
     * [applyPlacement]), and the range is the one that placement is restored from - so a position
     * is remembered for exactly what it was, and a resize never moves the card on its own.
     */
    private fun savePosition() {
        val p = params ?: return

        val spanX = xSpan(p.width)
        val top = statusBarHeight
        val spanY = yLimit(p.height) - top
        updatePlacement {
            Placement(it.widthFraction, it.heightFraction,
                    if (spanX > 0) (p.x.toFloat() / spanX).coerceIn(0f, 1f) else it.xBias,
                    if (spanY > 0) ((p.y - top).toFloat() / spanY).coerceIn(0f, 1f) else it.yBias)
        }
    }

    /** Forgets both slots, which is what puts the window back to the default box. */
    private fun resetPlacements() {
        listOf(Placement.Slot.PORTRAIT, Placement.Slot.LANDSCAPE).forEach { writePlacement(it, "") }
    }

    /************************************************************************************
     * Chrome: the top slots, the nav row, the touch radar
     ************************************************************************************/

    /**
     * Fills the three top-bar slots with the icons the user picked for them and dims the ones
     * the connected protocol cannot honour, then shows or hides the navigation row. The chrome
     * is part of the card and stays put - there is no auto-hiding bar any more, so this runs
     * once on the way in and again whenever the settings change underneath a live window.
     */
    private fun configureChrome(root: MiniWindowBinding) {
        // Back, Home and Recents are the Android protocol's keys, not a desktop's: on a VNC
        // or GameStream target there is no transport to carry them, so they are greyed out and
        // eat the tap rather than pretend to send it.
        val isAndroid = viewModel.androidSession
        setKeyEnabled(root.miniBackBtn, isAndroid)
        setKeyEnabled(root.miniHomeBtn, isAndroid)
        setKeyEnabled(root.miniOverviewBtn, isAndroid)
        listOf(root.miniTopSlot0, root.miniTopSlot1, root.miniTopSlot2)
                .forEachIndexed { slot, view -> applySlotIcon(view, topSlotAction(slot), isAndroid) }
        setNavBarVisible(root, navKeysEnabled, persist = false)
    }

    /** Re-renders the top slots after the viewer settings changed while the window is up. */
    fun refreshChrome() {
        binding?.let { configureChrome(it) }
    }

    /**
     * Draws one top-bar slot: its icon and label come from the configured [MiniBarAction], so
     * the layout fixes only where the button is. A None slot keeps its third of the bar but
     * shows nothing and swallows no action; a slot the session cannot honour is dimmed rather
     * than hidden, so the bar never develops a gap to wonder about.
     */
    private fun applySlotIcon(view: ImageButton, action: MiniBarAction, isAndroid: Boolean) {
        if (action.iconRes == 0) {
            view.setImageResource(0)
            view.contentDescription = null
            view.isEnabled = false
            view.alpha = 0f
            return
        }
        view.setImageResource(action.iconRes)
        view.contentDescription = activity.getString(action.labelRes)
        val usable = !needsAndroidSession(action) || isAndroid
        view.isEnabled = usable
        view.alpha = if (usable) 1f else 0.35f
    }

    /** The action configured for one of the three top slots, read fresh from the settings. */
    private fun topSlotAction(slot: Int): MiniBarAction = with(viewModel.pref.runInfo) {
        MiniBarAction.fromKey(when (slot) {
            0 -> miniTopSlot0
            1 -> miniTopSlot1
            2 -> miniTopSlot2
            else -> null
        })
    }

    /** Whether an action only makes sense on an Android-device session. */
    private fun needsAndroidSession(action: MiniBarAction): Boolean = when (action) {
        MiniBarAction.BACK, MiniBarAction.HOME, MiniBarAction.RECENTS, MiniBarAction.ROTATE,
        MiniBarAction.SCREEN_ON, MiniBarAction.SCREEN_OFF, MiniBarAction.POWER -> true
        else -> false
    }

    /**
     * Dims a chrome button that has no effect on the connected protocol. Dimming rather
     * than hiding is the user's call: the buttons always show, and the greyed one explains
     * its own absence instead of leaving a gap to wonder about.
     */
    private fun setKeyEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    /**
     * Shows or hides the row of remote navigation keys under the picture. The row is a fixed slice
     * of the card and the picture takes whatever is left of it, so the window itself neither grows
     * nor moves - only the letterboxed area changes.
     */
    private fun setNavBarVisible(root: MiniWindowBinding, show: Boolean, persist: Boolean = true) {
        root.miniNavBar.isVisible = show
        if (persist) navKeysEnabled = show
    }

    /**
     * The phone-key switch of whichever protocol this window is showing. Three protocols, three
     * switches, because the row is worth its height on a mirrored phone and is dead furniture on
     * a desktop - see the `run_info_mini_nav_keys_*` keys in [AppPreferences.RunInfo]. Reading it
     * is what decides the row on the way in; writing it is what the window's own toggle action
     * does, so the setting and the live window always agree.
     */
    private var navKeysEnabled: Boolean
        get() = with(viewModel.pref.runInfo) {
            when {
                viewModel.androidSession -> miniNavKeysAdb
                viewModel.profile.isGameStream() -> miniNavKeysGameStream
                else -> miniNavKeysVnc
            }
        }
        set(value) {
            with(viewModel.pref.runInfo) {
                when {
                    viewModel.androidSession -> miniNavKeysAdb = value
                    viewModel.profile.isGameStream() -> miniNavKeysGameStream = value
                    else -> miniNavKeysVnc = value
                }
            }
        }

    /**
     * A touch outside the window means the user has gone back to the app behind us, so the
     * borrowed keyboard focus (if any) is let go. The chrome itself is permanent and needs no
     * wake-on-touch.
     */
    private fun setupTouchRadar(root: MiniWindowBinding) {
        root.root.onTouchHandle = { event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && keyboardIsUp) releaseFocus()
        }
    }

    /************************************************************************************
     * Drag, resize, keyboard
     ************************************************************************************/

    /**
     * The bottom handle is the drag surface. A press that goes nowhere is a tap, and two taps
     * in quick succession are a double tap that fires the action configured for the handle -
     * the same one surface for moving the window and for a shortcut, as Mi-Freeform does it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandleDrag(root: MiniWindowBinding) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        root.miniDragHandle.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener true

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
                    if (!dragged && hypot(dx, dy) < DRAG_THRESHOLD_PX) return@setOnTouchListener true
                    dragged = true

                    // Never above the status bar: the handle would end up where only the
                    // system can reach it.
                    if (event.rawY < statusBarHeight + STATUS_BAR_MARGIN) return@setOnTouchListener true

                    // Keep the whole window on screen. A drag that may push two thirds of it
                    // past an edge looks friendlier while the finger is down, but the position is
                    // remembered as a fraction of the on-screen travel (see [savePosition]): an
                    // off-screen offset lands outside 0..1, gets clamped, and the window comes
                    // back nailed to that corner instead of where it was left.
                    p.x = (startX + dx.toInt()).coerceIn(0, xSpan(p.width))
                    p.y = (startY + dy.toInt()).coerceIn(statusBarHeight, yLimit(p.height))
                    pushLayout()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        savePosition()
                        lastHandleTapAt = 0L
                    } else {
                        // Two taps closer than the window are a double tap; the second runs the
                        // configured action and both are swallowed so a double tap is not also
                        // two singles.
                        val now = System.currentTimeMillis()
                        if (now - lastHandleTapAt in 1 until DOUBLE_TAP_MS) {
                            lastHandleTapAt = 0L
                            runAction(MiniBarAction.fromKey(viewModel.pref.runInfo.miniDoubleTap))
                        } else {
                            lastHandleTapAt = now
                        }
                    }
                }
            }
            // Consumed: dragging the handle must not land as a click on the remote desktop.
            true
        }
    }

    /**
     * The two dots at the ends of the bottom bar. Each one drags the box towards its own side of
     * the card - the left dot grows the window leftwards, the right one rightwards, both downwards
     * - and by default keeps the shape it has, because a size change is all a dot is asking for.
     */
    private fun setupDotResize(root: MiniWindowBinding) {
        val mode = { scaleMode(viewModel.pref.runInfo.miniDotScaleMode) }
        setupGrip(root.miniResizeLeft, -1, 1, mode)
        setupGrip(root.miniResizeRight, 1, 1, mode)
    }

    /**
     * The card's own two bottom corners, now invisible hot zones rather than drawn grips. Same
     * travel as the dots, but they live in the corner for a reason: by default they scale the box
     * along its own axes at the ratio it already has, so dragging one grows the card in its
     * current shape - a portrait card stays portrait, its top and bottom bands scaling with it -
     * rather than snapping it back onto the stream's landscape ratio.
     */
    private fun setupCornerResize(root: MiniWindowBinding) {
        val mode = { scaleMode(viewModel.pref.runInfo.miniCornerScaleMode) }
        setupGrip(root.miniResizeCornerLeft, -1, 1, mode)
        setupGrip(root.miniResizeCornerRight, 1, 1, mode)
    }

    /**
     * One resize grip. [growX] says which way the finger has to travel along x for the window to
     * get wider: -1 for a grip on the left edge (drag left = grow), 1 for one on the right; all
     * ours sit at the bottom, so [growY] is always 1. The edge opposite the grip is the anchor,
     * which is why a left grip slides the window left as it grows.
     *
     * [ScaleMode.FREE] lets the two axes move apart, which is how black bars come and go.
     * [ScaleMode.ASPECT] drives both of them from the dominant delta at the *card's own* ratio -
     * the box's height over its width as it stood when the finger went down - so a drag scales
     * the card up or down in whatever shape it already had. It is deliberately not the stream's
     * ratio: locking to that would pull a portrait card back to the desktop's landscape shape and
     * throw away the top and bottom bands the card opens with.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupGrip(view: View, growX: Int, growY: Int, mode: () -> ScaleMode) {
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
                    val dW = (event.rawX - downX) * growX
                    val dH = (event.rawY - downY) * growY
                    val min = dp(MIN_BOX_DP)

                    val box = when (mode()) {
                        ScaleMode.FREE -> (startW + dW).toInt().coerceIn(min, screenWidth()) to
                                (startH + dH).toInt().coerceIn(min, screenHeight())

                        ScaleMode.ASPECT -> {
                            // The card's own shape as it stood when the grip was grabbed. Locked
                            // for the whole gesture (startW/startH are set once on DOWN), so the
                            // drag scales the box proportionally instead of drifting toward the
                            // stream's ratio.
                            val ratio = if (startW > 0) startH.toFloat() / startW else 0.5625f
                            // Whichever axis the finger travelled further along is the one the
                            // drag is read on; the other follows at the card's own ratio.
                            var width = if (abs(dW) >= abs(dH)) (startW + dW).toInt()
                                        else ((startH + dH) / ratio).roundToInt()
                            var height = (width * ratio).roundToInt()
                            // The floors and ceilings are applied on whichever axis ended up
                            // out of bounds, and the other is recomputed from the corrected
                            // value: clamping one side alone would drop the ratio.
                            if (width < min) {
                                width = min; height = (width * ratio).roundToInt()
                            }
                            if (height < min) {
                                height = min; width = (height / ratio).roundToInt()
                            }
                            if (width > screenWidth()) {
                                width = screenWidth(); height = (width * ratio).roundToInt()
                            }
                            if (height > screenHeight()) {
                                height = screenHeight(); width = (height / ratio).roundToInt()
                            }
                            width to height
                        }
                    }

                    applyBox(box, if (growX < 0) startX + startW - box.first else p.x)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePosition()
            }
            // Consumed: resizing must not land as a click on the remote desktop.
            true
        }
    }

    /**
     * The single most important wiring here: scaling, panning and the mapping of a touch onto
     * a remote coordinate are all expressed against the viewport, which the activity's layout
     * manager keeps setting to the full-screen view. While this window is the visible one, its
     * own picture area is the viewport.
     *
     * The window size is deliberately not the picture area: it stays the screen, which is what
     * [FrameState] derives the base scale from. The card's box has the screen's aspect ratio
     * (see [contentPlacement]), so scaling against the screen makes the frame at least as wide
     * as the box - the picture always runs edge to edge and any letterbox can only fall top and
     * bottom, exactly like the viewer. Scaling against the box instead would fit the whole frame
     * inside it and leave the sides black whenever the box was relatively wider than the stream.
     * What does not fit is panned into view, which is also what the fullscreen viewer does.
     *
     * The listener sits on [R.id.mini_picture] rather than on whichever presenter is showing,
     * exactly like [LayoutManager] watches `frame_container`: the direct-scan view is resized by
     * the zoom itself, which would make it its own moving target.
     *
     * Only a real change of size is acted on: the window is dragged around constantly, and
     * treating that as a resize would throw away whatever zoom the user just set.
     */
    private fun trackViewport(root: MiniWindowBinding) {
        var lastWidth = 0
        var lastHeight = 0

        root.miniPicture.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w <= 0 || h <= 0 || (w == lastWidth && h == lastHeight)) return@addOnLayoutChangeListener

            lastWidth = w
            lastHeight = h
            viewModel.frameState.setWindowSize(screenWidth().toFloat(), screenHeight().toFloat())
            viewModel.frameState.setViewportSize(w.toFloat(), h.toFloat())
            // The viewer's zoom and pan are deliberately left alone: what the user was looking at
            // before the window opened is what they expect to keep looking at inside it, only
            // smaller. The frame is re-fitted to the smaller box by FrameState's own base scale.
            miniPresenter?.refreshFrame()
        }
    }

    private var keyboardIsUp = false

    /** The queued second attempt of [showKeyboard], so it can be cancelled with the window. */
    private var showKeyboardTask: Runnable? = null

    /**
     * Typing needs the window to be focusable, which is also what lets it take the back key -
     * so focus is borrowed for the keyboard and handed back when it is gone.
     * [com.limelight.ui.vnc.input.InputView] already reports itself as a text editor and
     * returns a connection that forwards key events to the remote, which is exactly the
     * plumbing this needs.
     */
    private fun showKeyboard() {
        val root = binding ?: return
        val p = params ?: return

        keyboardIsUp = true
        p.flags = focusableFlags
        pushLayout()
        root.miniInputView.requestFocus()

        // Some ROMs ignore the first request when it arrives in the same frame as the flag
        // change, so the retry is queued rather than called again inline.
        if (!inputMethodManager.showSoftInput(root.miniInputView, InputMethodManager.SHOW_IMPLICIT)) {
            showKeyboardTask = Runnable {
                showKeyboardTask = null
                if (isVisible && keyboardIsUp)
                    inputMethodManager.showSoftInput(root.miniInputView, 0)
            }.also { uiHandler.postDelayed(it, 150) }
        }
    }

    /** Give the keys back to the system and put the window to sleep as a picture again. */
    private fun releaseFocus() {
        val root = binding ?: return
        val p = params ?: return

        keyboardIsUp = false
        p.flags = passiveFlags
        pushLayout()
        inputMethodManager.hideSoftInputFromWindow(root.root.windowToken, 0)
    }

    /************************************************************************************
     * Actions
     ************************************************************************************/

    private fun setupActions(root: MiniWindowBinding) {
        // The navigation row is fixed: those are the Android protocol's keys, drawn under the
        // picture precisely so they are always one tap away on a mirrored phone.
        root.miniBackBtn.setOnClickListener { viewModel.sendAndroidKey(KeyEvent.KEYCODE_BACK) }
        root.miniHomeBtn.setOnClickListener { viewModel.sendAndroidKey(KeyEvent.KEYCODE_HOME) }
        root.miniOverviewBtn.setOnClickListener { viewModel.sendAndroidKey(KeyEvent.KEYCODE_APP_SWITCH) }

        // The top slots are whatever the user configured: read the action back at tap time, so
        // a settings change is honored without rebuilding the window chrome.
        listOf(root.miniTopSlot0, root.miniTopSlot1, root.miniTopSlot2)
                .forEachIndexed { slot, view ->
                    view.setOnClickListener { runAction(topSlotAction(slot)) }
                }

        // A touch on the picture is a touch on the remote, but it is also the signal that the
        // user is still here. That part is handled by [setupTouchRadar], which sees the event
        // before the stream input does; returning false leaves it to [InputView], which is
        // what maps it onto the remote screen.
        root.miniInputView.setOnTouchListener { _, _ -> false }
    }

    /**
     * Runs a [MiniBarAction] against the current window. The three top slots and the handle's
     * double tap share this one dispatch, so the same action behaves identically from either
     * entry point - exactly Mi-Freeform's split between the icons and what they do.
     */
    private fun runAction(action: MiniBarAction) {
        val root = binding ?: return
        if (needsAndroidSession(action) && !viewModel.androidSession) return
        when (action) {
            MiniBarAction.NONE -> {}
            MiniBarAction.CLOSE -> {
                releaseFocus()
                hide(endSession = true)
            }
            // Not `hide()`: folding back to the viewer must not strand the stream, so the
            // viewer is brought forward instead and the fold happens on its own in
            // VncActivity.onStart once this activity is genuinely the one shown.
            MiniBarAction.FULL_SCREEN -> {
                releaseFocus()
                bringViewerForward()
            }
            MiniBarAction.MINIMIZE -> collapse()
            MiniBarAction.EXPAND -> expand()
            MiniBarAction.BACK -> viewModel.sendAndroidKey(KeyEvent.KEYCODE_BACK)
            MiniBarAction.HOME -> viewModel.sendAndroidKey(KeyEvent.KEYCODE_HOME)
            MiniBarAction.RECENTS -> viewModel.sendAndroidKey(KeyEvent.KEYCODE_APP_SWITCH)
            MiniBarAction.KEYBOARD -> showKeyboard()
            MiniBarAction.TOGGLE_NAV_BAR -> setNavBarVisible(root, !root.miniNavBar.isVisible)
            MiniBarAction.RESET -> {
                resetPlacements()
                applyPlacement()
            }
            MiniBarAction.ROTATE -> viewModel.sendAndroidAction(Messenger.AndroidAction.ROTATE)
            MiniBarAction.SCREEN_ON -> viewModel.sendAndroidAction(Messenger.AndroidAction.SCREEN_ON)
            MiniBarAction.SCREEN_OFF -> viewModel.sendAndroidAction(Messenger.AndroidAction.SCREEN_OFF)
            MiniBarAction.POWER -> viewModel.sendAndroidAction(Messenger.AndroidAction.POWER)
        }
    }

    private fun bringViewerForward() {
        // Reordering the existing task keeps the session, the profile and the toolbar state.
        // The same helper the drawer's reconnect uses builds the intent, so the startup
        // argument that identifies the server is already in place.
        val intent = createVncIntent(activity, viewModel.profile)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { activity.startActivity(intent) }
                .onFailure { Log.e(TAG, "Cannot bring the viewer forward", it) }
    }
}