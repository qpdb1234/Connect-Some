/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.viewmodel

import android.app.Application
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.media.ToneGenerator
import com.limelight.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.limelight.R
import com.limelight.model.LoginInfo
import com.limelight.model.ServerProfile
import com.limelight.session.Messenger
import com.limelight.session.RemoteSession
import com.limelight.ui.vnc.FrameScroller
import com.limelight.ui.vnc.FrameState
import com.limelight.ui.vnc.FramePresenter
import com.limelight.ui.vnc.FrameView
import com.limelight.util.AppPreferences
import com.limelight.util.LiveRequest
import com.limelight.util.Tones
import com.limelight.util.debugCheck
import com.limelight.util.getClipboardText
import com.limelight.util.getKnownHostsFile
import com.limelight.util.getUnknownCertificateMessage
import com.limelight.util.isCertificateTrusted
import com.limelight.util.isTrue
import com.limelight.util.monitor
import com.limelight.util.setClipboardText
import com.limelight.util.trustCertificate
import com.limelight.vnc.AndroidControlExtras
import com.limelight.vnc.RenderMode
import com.limelight.vnc.GameStreamExtras
import com.limelight.vnc.RfbClipboardBridge
import com.limelight.vnc.UserCredential
import com.limelight.vnc.VideoSink
import com.limelight.vnc.VncClient
import com.limelight.vnc.VncClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import java.lang.ref.WeakReference
import java.security.cert.X509Certificate

/**
 * ViewModel for VncActivity
 *
 * Connection
 * ==========
 *
 * At construction, we instantiate a [VncClient] referenced by [client]. Then
 * activity starts the connection by calling [initConnection] which starts a coroutine to
 * handle connection setup.
 *
 * After successful connection, we continue to operate normally until the remote
 * server closes the connection, or an error occurs. Once disconnected, we
 * wait for the activity to finish and then cleanup any acquired resources.
 *
 * Currently, lifecycle of [client] is tied to this view model. So one [VncViewModel]
 * manages only one [VncClient].
 *
 *
 * Threading
 * =========
 *
 * Receiver thread :- This thread is started in [launchConnection].
 * It handles the protocol initialization, and after that processes incoming messages.
 * Most of the callbacks of [VncClient.Observer] are invoked on this thread. In most
 * cases it is stopped when activity is finished and this view model is cleaned up.
 *
 * Sender thread :- This thread is created (as an executor) by [messenger]. It is
 * used to send messages to remote server. We use this dedicated thread instead
 * of coroutines to preserve the order of sent messages.
 *
 * UI thread :- Main thread of the app. Used for updating UI and controlling other
 * Threads. This is where [frameState] is updated.
 *
 * Renderer thread :- This is managed by [FrameView] and used for rendering frame
 * via OpenGL ES. [frameState] is read from this thread to decide how/where frame
 * should be drawn.
 */
class VncViewModel(app: Application) : BaseViewModel(app) {

    /**
     * Connection lifecycle:
     *
     *            Created
     *               |
     *               v
     *          Connecting ----------+
     *               |               |
     *               v               |
     *           Connected           |
     *               |               |
     *               v               |
     *          Disconnected <-------+
     *
     */
    enum class State {
        Created,
        Connecting,
        Connected,
        Disconnected;
    }

    companion object {
        /** A tap on the assist ball opens the toolbar drawer. */
        const val ASSIST_BALL_ACTION_TOOLBAR = "toolbar"

        /** A tap on the assist ball raises the on-screen keyboard. */
        const val ASSIST_BALL_ACTION_KEYBOARD = "keyboard"

        // Slider ranges. They live here because the sliders, the stored values and the
        // ball itself all have to agree on the same limits. The opacity range is the
        // full 0-1: the drawer shows it inverted as a transparency (0 = solid, 100 =
        // invisible), and that slider must be able to reach both ends.
        const val ASSIST_BALL_OPACITY_MIN = 0f
        const val ASSIST_BALL_OPACITY_MAX = 1f
        const val ASSIST_BALL_SIZE_MIN_DP = 36
        const val ASSIST_BALL_SIZE_MAX_DP = 72

        /**
         * Opacity within the slider range. NaN is rejected rather than clamped: a float
         * that is not a number passes straight through [coerceIn] (both comparisons are
         * false), so clamping alone cannot be trusted with a value read from disk.
         */
        fun clampAssistBallOpacity(opacity: Float): Float =
                if (opacity.isFinite()) opacity.coerceIn(ASSIST_BALL_OPACITY_MIN, ASSIST_BALL_OPACITY_MAX)
                else AppPreferences.ASSIST_BALL_OPACITY_DEFAULT

        fun clampAssistBallSize(sizeDp: Int): Int =
                sizeDp.coerceIn(ASSIST_BALL_SIZE_MIN_DP, ASSIST_BALL_SIZE_MAX_DP)

        /**
         * The full keyboard's opacity range. Its floor is above the ball's because the
         * keyboard swallows every touch inside its frame: a keyboard faded close to nothing
         * would keep pressing keys on a stream the user can no longer see under it.
         */
        fun clampFullKeyboardOpacity(opacity: Float): Float =
                if (opacity.isFinite()) opacity.coerceIn(AppPreferences.FULL_KEYBOARD_OPACITY_MIN, AppPreferences.FULL_KEYBOARD_OPACITY_MAX)
                else AppPreferences.FULL_KEYBOARD_OPACITY_DEFAULT

        /**
         * The full keyboard's scale multiplier, clamped to the drawer's range. Clamped on the
         * way in too: a restored backup can hold any float, and a zero or NaN unit size would
         * collapse the board into an invisible, untappable speck.
         */
        fun clampFullKeyboardScale(scale: Float): Float =
                if (scale.isFinite()) scale.coerceIn(AppPreferences.FULL_KEYBOARD_SCALE_MIN, AppPreferences.FULL_KEYBOARD_SCALE_MAX)
                else AppPreferences.FULL_KEYBOARD_SCALE_DEFAULT
    }

    lateinit var profile: ServerProfile

    /**
     * [profile] is filled asynchronously (a saved server is loaded from the
     * database by id), while the GL surface can be ready much earlier. Code
     * that runs on the render thread must check this before touching [profile].
     */
    val hasProfile get() = ::profile.isInitialized

    /**
     * Whether the incoming image is decoded into a [Surface] or uploaded as a
     * pixel buffer. Decided by the protocol, but queried here so the renderer
     * never has to look at the profile itself.
     */
    val renderMode get() = VncClientFactory.renderModeFor(profile)

    /**
     * Live version of [profile], allows easier access from UI layer
     */
    val profileLive = MutableLiveData<ServerProfile>()

    var client: VncClient? = null

    private val session = RemoteSession(RemoteSessionObserver())

    /**
     * We have two places for connection state (both are synced):
     *
     * [VncClient.connected] - Simple boolean state, used most of the time
     * [state]               - More granular, used by observers & data binding
     */
    val state = MutableLiveData(State.Created)

    /**
     * User-visible name of a connection state. The enum names are code identifiers, and the
     * viewer prints this to the screen, so the label has to come from resources.
     */
    fun stateLabel(state: State): String = app.getString(when (state) {
        State.Created -> R.string.state_created
        State.Connecting -> R.string.state_connecting
        State.Connected -> R.string.state_connected
        State.Disconnected -> R.string.state_disconnected
    })

    /**
     * Session wiring that must not wait for the viewer to come back to the foreground.
     *
     * [state] reaches the activity through a lifecycle-bound observer, so a connection that
     * ends and is rebuilt while the viewer is stopped never reaches it until the user
     * returns - and the floating mini window does exactly that: taking the picture away
     * hands the decoder a new surface, which restarts the session (see
     * [onVideoSurfaceAvailable]). Whatever the overlay itself needs from a live connection -
     * the input pipeline first of all, since touches keep arriving while the viewer is in the
     * background - is therefore hooked in here instead, fired from the same place that
     * publishes the state and on the same thread.
     */
    var onSessionUp: (() -> Unit)? = null
    var onSessionDown: (() -> Unit)? = null

    val connected get() = state.value == State.Connected

    /**
     * Reason for disconnecting.
     */
    val disconnectReason = MutableLiveData("")

    /**
     * Live connection progress. The Moonlight kernel reports each setup stage; we
     * translate it into a localized label and show it (next to the spinner) while
     * [State.Connecting]. Cleared once streaming starts or the attempt ends.
     */
    val connectionStage = MutableLiveData<String>()

    /**
     * Latest performance snapshot (network latency, FPS, dropped frames, decode time)
     * pushed by the video decoder about once per second while streaming.
     */
    val connectionStats = MutableLiveData<String>()

    /**
     * Something the live session did not manage, which the user has to be told about
     * because the picture gives no sign of it. The viewer shows it as a toast; the value
     * returns to null once it has been read, so a rotation does not repeat the message.
     */
    val sessionNotice = MutableLiveData<String?>()

    /**
     * Whether the performance overlay is currently shown. Backed by
     * [AppPreferences.Viewer.showPerfOverlay] so the choice persists across sessions.
     * Metrics are always collected by the kernel, so toggling this only flips visibility.
     */
    val statsOverlayEnabled = MutableLiveData(pref.viewer.showPerfOverlay)

    fun setStatsOverlayEnabled(enabled: Boolean) {
        if (statsOverlayEnabled.value != enabled) {
            pref.viewer.showPerfOverlay = enabled
            statsOverlayEnabled.value = enabled
        }
    }

    /**
     * Whether the remote audio is silenced on this phone. Backed by
     * [AppPreferences.Viewer.audioMuted], so a host that is permanently silent does not need
     * the button pressed again in every session. Unlike the overlay above, the value is not
     * cleared for protocols that carry no audio: VNC hides the control and leaves the choice
     * intact for the next GameStream or Android-device connection.
     */
    val audioMuted = MutableLiveData(pref.viewer.audioMuted)

    fun setAudioMuted(muted: Boolean) {
        if (audioMuted.value != muted) {
            pref.viewer.audioMuted = muted
            audioMuted.value = muted
        }
        // Written even when unchanged: the session may have been rebuilt since the last tap
        // and needs the current state for the next client.
        session.setAudioMuted(muted)
    }

    /**
     * The floating assist ball: on/off, opacity (0.0-1.0), diameter in dp, fill color
     * (#AARRGGBB hex), and what a tap does ([ASSIST_BALL_ACTION_TOOLBAR] or
     * [ASSIST_BALL_ACTION_KEYBOARD]). All five are persisted so the ball comes back the way
     * the user left it; the ball itself reacts through data binding and is positioned by
     * drag gestures. See ui/vnc/AssistBall.kt.
     */
    val assistBallEnabled = MutableLiveData(pref.viewer.assistBallEnabled)

    // Clamped on the way in, not just on the way out: these two come straight from
    // SharedPreferences, which a restored backup may have filled with anything. An
    // opacity of NaN leaves the ball unrenderable and a diameter of 0 leaves it
    // untappable - both of which the user can only undo by guessing at the drawer.
    val assistBallOpacity = MutableLiveData(clampAssistBallOpacity(pref.viewer.assistBallOpacity))
    val assistBallSizeDp = MutableLiveData(clampAssistBallSize(pref.viewer.assistBallSizeDp))
    val assistBallAction = MutableLiveData(pref.viewer.assistBallAction)
    val assistBallColor = MutableLiveData(pref.viewer.assistBallColor)

    fun setAssistBallEnabled(enabled: Boolean) {
        if (assistBallEnabled.value != enabled) {
            pref.viewer.assistBallEnabled = enabled
            assistBallEnabled.value = enabled
        }
    }

    fun setAssistBallOpacity(opacity: Float) {
        val clamped = clampAssistBallOpacity(opacity)
        if (assistBallOpacity.value != clamped) {
            pref.viewer.assistBallOpacity = clamped
            assistBallOpacity.value = clamped
        }
    }

    fun setAssistBallSizeDp(sizeDp: Int) {
        val clamped = clampAssistBallSize(sizeDp)
        if (assistBallSizeDp.value != clamped) {
            pref.viewer.assistBallSizeDp = clamped
            assistBallSizeDp.value = clamped
        }
    }

    fun setAssistBallAction(action: String) {
        if (assistBallAction.value != action) {
            pref.viewer.assistBallAction = action
            assistBallAction.value = action
        }
    }

    fun setAssistBallColor(hex: String) {
        if (assistBallColor.value != hex) {
            pref.viewer.assistBallColor = hex
            assistBallColor.value = hex
        }
    }

    /**
     * Color of the letterbox bands around the streamed picture. Unlike the assist ball, which
     * is its own view, this is painted onto [com.limelight.databinding.ActivityVncBinding.frameContainer]
     * - the surface *behind* the direct-scan video. The picture punches its own hole through
     * that background, so the color only ever shows in the bars, never over the video, and an
     * opaque fill here does not disturb the hardware composition (see the Kirin note on
     * StreamSurfaceView). VncActivity observes this and repaints the container; the toolbar
     * raises the palette.
     */
    val streamBarColor = MutableLiveData(pref.viewer.streamBarColor)

    fun setStreamBarColor(hex: String) {
        if (streamBarColor.value != hex) {
            pref.viewer.streamBarColor = hex
            streamBarColor.value = hex
        }
    }

    /**
     * The full QWERTY keyboard over the stream: on/off, opacity (0.0-1.0, edited as an
     * inverted transparency in the drawer), whether its grip bar answers drags, and its
     * proportional scale. All four are persisted, so the keyboard comes back next session faded
     * and pinned the
     * way the user left it; the overlay itself watches these and reacts live, mirroring
     * the assist ball above. See ui/vnc/FullKeyboard.kt.
     */
    val fullKeyboardEnabled = MutableLiveData(pref.viewer.fullKeyboardEnabled)
    val fullKeyboardOpacity = MutableLiveData(clampFullKeyboardOpacity(pref.viewer.fullKeyboardOpacity))
    val fullKeyboardDragLocked = MutableLiveData(pref.viewer.fullKeyboardDragLocked)
    val fullKeyboardScale = MutableLiveData(clampFullKeyboardScale(pref.viewer.fullKeyboardScale))

    fun setFullKeyboardEnabled(enabled: Boolean) {
        if (fullKeyboardEnabled.value != enabled) {
            pref.viewer.fullKeyboardEnabled = enabled
            fullKeyboardEnabled.value = enabled
        }
    }

    fun setFullKeyboardOpacity(opacity: Float) {
        val clamped = clampFullKeyboardOpacity(opacity)
        if (fullKeyboardOpacity.value != clamped) {
            pref.viewer.fullKeyboardOpacity = clamped
            fullKeyboardOpacity.value = clamped
        }
    }

    fun setFullKeyboardDragLocked(locked: Boolean) {
        if (fullKeyboardDragLocked.value != locked) {
            pref.viewer.fullKeyboardDragLocked = locked
            fullKeyboardDragLocked.value = locked
        }
    }

    fun setFullKeyboardScale(scale: Float) {
        val clamped = clampFullKeyboardScale(scale)
        if (fullKeyboardScale.value != clamped) {
            pref.viewer.fullKeyboardScale = clamped
            fullKeyboardScale.value = clamped
        }
    }

    /**
     * Fired when we need some credentials from user.
     * It will trigger the Login dialog.
     */
    val loginInfoRequest = LiveRequest<LoginInfo.Type, LoginInfo>()

    /**
     * List of saved profiles.
     * Used by login-autocompletion.
     */
    val savedProfiles by lazy { serverProfileDao.getLiveList() }

    /**
     * Holds a weak reference to the active [FramePresenter] - the GL [FrameView] for the
     * pixel-buffer path, or a [com.limelight.ui.vnc.StreamSurfaceView] for the direct-scan
     * surface path.
     *
     * This is used to tell the presenter to re-render its content when VncClient's
     * framebuffer is updated. Instead of using LiveData/LiveEvent, we keep a
     * weak reference because:
     *
     *      1. It avoids a context-switch to UI thread. A render request can be sent
     *         from any thread (the presenter marshals to the UI thread when it must).
     *
     *      2. We don't have to invoke the whole ViewModel machinery just for
     *         a single call to the presenter.
     */
    var frameViewRef = WeakReference<FramePresenter>(null)

    /**
     * The [android.view.View] that draws over the picture - today the input view, which also
     * marks where the remote pointer is. Kept as a weak reference for the same reason as
     * [frameViewRef], and refreshed by the same call: whatever moves the frame moves the
     * marker with it. The mini window registers its own view while it is up and hands this
     * one back when it goes away.
     */
    var pointerOverlayRef = WeakReference<View?>(null)

    /**
     * Holds information about scaling, translation etc.
     */
    val frameState = with(pref.viewer) { FrameState(zoomMin, zoomMax, perOrientationZoom) }

    /**
     * The size the remote last sent, as reported by [onFramebufferSizeChanged].
     *
     * [frameState] knows this too, but it is not observable - the renderer only reads it
     * while drawing. Something has to react when the picture *changes shape*: the mini
     * window frames the stream with its own size, so a remote that rotates, or a phone that
     * is turned while the window is up, needs the window refitted or moved to the geometry
     * slot of its new orientation pair.
     */
    val frameBufferSize = MutableLiveData<Size>()

    /**
     * Used for scrolling/animating the frame.
     */
    val frameScroller = FrameScroller(this)

    /**
     * Currently active gesture style
     */
    val activeGestureStyle = MutableLiveData<String>()

    val activeViewMode = MutableLiveData<Int>()
    val videoDisabled get() = (activeViewMode.value == ServerProfile.VIEW_MODE_NO_VIDEO)

    /**
     * Used for sending events to remote server.
     */
    var messenger: Messenger? = null

    /**
     * Used to confirm something with user before continuing.
     * This is mostly used to warn about unknown SSH host, x509 certificates etc.
     * This request accepts two strings: First is used as title, second contains the message.
     */
    val confirmationRequest = LiveRequest<Pair<String, String>, Boolean>()

    /**
     * If user has asked to remember credentials in login dialog, we need to
     * save them to database. But we don't want to save them immediately
     * because user might have mistyped them. So, we wait until successful
     * connection before saving them.
     */
    var loginInfoToBeRemembered = mutableListOf<LoginInfo>()

    /**
     * Whether activity window is currently focused
     */
    val hasWindowFocus = MutableLiveData<Boolean>()

    /**
     * Whether activity is in Picture-in-Picture mode
     */
    val inPiPMode = MutableLiveData<Boolean>()

    /**
     * Orientation of the activity, recomputed whenever either input changes. The per-server
     * choice wins; `auto` falls through to the global viewer setting. Both have to be
     * monitored here: [monitor] only re-evaluates when one of its dependencies emits, so
     * watching the profile alone made the viewer setting a write-only preference - it was
     * stored, shown in the picker, and never applied until the next connection.
     */
    val preferredScreenOrientation = monitor(profileLive, pref.viewer.orientationLive) { resolveScreenOrientation() }

    /**
     * A ball that opens the toolbar is useless before the session exists, and in a
     * picture-in-picture window there is no room for it (nor a drawer to open).
     */
    val assistBallVisible = monitor(state, inPiPMode, assistBallEnabled) {
        connected && !inPiPMode.isTrue && assistBallEnabled.isTrue
    }

    val capturePointer = monitor(state, hasWindowFocus) { resolveCapturePointer() }

    override fun onCleared() {
        super.onCleared()
        session.stop()
    }


    /**************************************************************************
     * Connection management
     **************************************************************************/

    /**
     * Initialize VNC connection.
     * It can be called multiple times due to activity restarts.
     */
    fun initConnection(profile: ServerProfile) {
        if (state.value == State.Created) {
            // This is the one point every session passes through, and the profile reached it
            // from a database row, a restored activity state, a shortcut or a tapped link -
            // only the first of those has been looked at by anything. The values below are
            // used as ports, pixel formats, encoder settings and zoom factors.
            val sanitized = profile.sanitize()
            this.profile = sanitized
            profileLive.value = sanitized
            state.value = State.Connecting
            // A previous failure must not be quoted as the reason for this attempt.
            disconnectReason.value = ""
            connectionStage.value = app.getString(R.string.msg_connecting_stage, app.getString(R.string.stage_none))
            frameState.setZoom(sanitized.zoom1, sanitized.zoom2)
            setViewMode(sanitized.viewMode)
            resolveGestureStyle()
            inPiPMode.observeForever { if (it) resetZoom() }
            // Hand over the persisted mute before connecting: the client builds its playback
            // device during setup, so this is the last point where silence can start free.
            session.setAudioMuted(audioMuted.value == true)
            session.start(sanitized)
            // The GL surface may have been created before the profile was known,
            // in which case Renderer skipped its assembly. FrameView renders on
            // demand only, so nudge it once: the next draw now sees a profile.
            refreshFrameView()
        }
    }

    /**
     * Attach the hosting activity so a hardware decoder can render into the GL
     * surface. Safe to call before or after the session has started.
     * A no-op for protocols that upload pixels instead of decoding into a surface.
     */
    fun attachActivity(activity: Activity?) {
        session.attachActivity(activity)
        (client as? VideoSink)?.attachActivity(activity)
    }

    /**
     * Provide the decoded-video render surface produced by [FrameView]'s GL
     * SurfaceTexture (external OES). Forwarded to the live session / client.
     *
     * When a *second* surface shows up while a session is already running - which is what
     * switching to or from the mini window does, since each GL surface brings its own - the
     * running decoder would keep writing into the surface that is already gone and the
     * picture would stay black forever. Hardware decoders are therefore asked to re-point
     * themselves at the new surface ([VideoSink.swapVideoSurface]). A protocol that cannot
     * do that has no choice but to build the connection again, which is what
     * [com.limelight.session.RemoteSession.restart] does here.
     *
     * Protocols that upload pixels rather than decode into a surface (plain VNC) never
     * reach this: their frames are re-uploaded on the next draw, so resizing is free.
     *
     * [rebind] forces the re-point even when the incoming [surface] is the *same object*
     * as the one already in use. A `SurfaceView` reuses one long-lived `Surface` instance
     * whose native buffer queue is torn down and rebuilt whenever the view is resized,
     * so reference equality alone cannot tell a dead hand-over from a no-op; the direct-scan
     * view passes `rebind = true` on every re-creation for exactly that reason.
     */
    fun onVideoSurfaceAvailable(surface: Surface?, rebind: Boolean = false) {
        session.setVideoSurface(surface)
        (client as? VideoSink)?.setVideoSurface(surface)

        val surfaceIsNew = surface != null && (rebind || surface !== lastVideoSurface)
        if (surfaceIsNew && connected && renderMode == RenderMode.SURFACE) {
            val sink = client as? VideoSink
            if (surface != null && sink?.swapVideoSurface(surface) == true) {
                Log.i("VncViewModel", "Video surface replaced mid-session; decoder re-pointed at the new one")
            } else {
                Log.i("VncViewModel", "Video surface replaced mid-session; restarting the connection")
                session.restart(profile)
            }
        }
        if (surface != null) lastVideoSurface = surface
    }

    /** The surface the live decoder was pointed at, to tell a new one from a repeat. */
    private var lastVideoSurface: Surface? = null

    /**
     * Can be used to persist any changes made to [profile]
     */
    fun saveProfile() {
        if (profile.isSaved())
            launchMain { serverProfileDao.update(profile) }
    }

    suspend fun getProfileById(id: Long) = serverProfileDao.getByID(id)

    /**************************************************************************
     * Frame management
     **************************************************************************/

    fun updateZoom(scaleFactor: Float, fx: Float, fy: Float) {
        if (profile.fZoomLocked || videoDisabled) return

        frameState.updateZoom(scaleFactor, fx, fy)
        refreshFrameView()
    }

    fun resetZoom() {
        frameState.setZoom(1f, 1f)
        refreshFrameView()
    }

    fun resetZoomToDefault() {
        frameState.setZoom(profile.zoom1, profile.zoom2)
        refreshFrameView()
    }

    fun setZoom(zoom1: Float, zoom2: Float) {
        frameState.setZoom(zoom1, zoom2)
        refreshFrameView()
    }

    fun panFrame(deltaX: Float, deltaY: Float) {
        frameState.pan(deltaX, deltaY)
        refreshFrameView()
    }

    fun moveFrameTo(x: Float, y: Float) {
        frameState.moveTo(x, y)
        refreshFrameView()
    }

    fun toggleZoomLock(enabled: Boolean) {
        profile.fZoomLocked = enabled
        saveProfile()
    }

    fun saveZoom() {
        profile.zoom1 = frameState.zoomScale1
        profile.zoom2 = frameState.zoomScale2
        saveProfile()
    }

    fun setSafeArea(safeArea: RectF) {
        frameState.setSafeArea(safeArea)
        refreshFrameView()
    }

    /**************************************************************************
     * Miscellaneous
     **************************************************************************/

    fun sendClipboardText() {
        if (pref.server.clipboardSync && connected) launchIO {
            getClipboardText(app)?.let { messenger?.sendClipboardText(it) }
        }
    }

    private var clipReceiverJob: Job? = null
    private fun receiveClipboardText(text: String) {
        if (!pref.server.clipboardSync)
            return

        // This is a protective measure against servers which send every 'selection' made on the server.
        // Setting clip text involves IPC, so these events can exhaust Binder resources, leading to ANRs.
        if (clipReceiverJob?.isActive == true) {
            Log.w(javaClass.simpleName, "Dropping clip text received from server, previous text is still pending")
            return
        }

        clipReceiverJob = launchIO {
            setClipboardText(app, text)
        }
    }

    /**
     * Clipboard over a side RFB connection, for the one protocol that has no clipboard
     * message of its own. Lives exactly as long as the session does.
     */
    private var clipboardBridge: RfbClipboardBridge? = null

    /**
     * What the settings and this profile ask for: a bridge, and where to point it. Kept in
     * one place so that starting the bridge and explaining its absence can never disagree.
     */
    private val clipboardBridgeRequested: Boolean
        get() = pref.server.clipboardSync && pref.server.gamestreamClipboardViaVnc &&
                profile.clipboardVncPort > 0

    /**
     * Whether a copy sent right now is being typed onto the host even though the user asked
     * for the clipboard bridge - nothing answering the port, the wrong password, or the
     * bridge still connecting. A session that was never meant to have one is not a fault,
     * so switching the bridge off stays quiet. A capability probe on the live client rather
     * than a protocol comparison, like [androidSession].
     */
    val clipboardWouldType: Boolean
        get() = client is GameStreamExtras && clipboardBridgeRequested &&
                clipboardBridge?.live != true

    /**
     * Opens the clipboard bridge, and hands it to the messenger so that a copy goes there
     * instead of through the keystroke path. Started beside the stream rather than inside
     * it: nothing about video, audio or input changes, and a bridge that cannot connect
     * costs a background socket attempt and nothing else.
     */
    private fun startClipboardBridge(client: VncClient, messenger: Messenger) {
        if (client !is GameStreamExtras) return
        if (!clipboardBridgeRequested) return

        clipboardBridge = RfbClipboardBridge(
                host = profile.host,
                port = profile.clipboardVncPort,
                password = profile.clipboardVncPassword,
                strictLatin1 = pref.server.clipboardStrictLatin1,
                onCutText = ::receiveClipboardText,
        ).also {
            messenger.clipboardBridge = it
            it.start()
        }
    }

    private fun stopClipboardBridge() {
        clipboardBridge?.stop()
        clipboardBridge = null
    }

    fun getLoginInfo(type: LoginInfo.Type): LoginInfo {
        val li = LoginInfo.fromProfile(profile, type)

        // If info is already available, return it
        if ((type == LoginInfo.Type.VNC_PASSWORD && li.password.isNotBlank()) ||
            (type == LoginInfo.Type.VNC_CREDENTIAL && li.username.isNotBlank() && li.password.isNotBlank()) ||
            (type == LoginInfo.Type.SSH_PASSWORD && li.password.isNotBlank()))
            return li

        // Something is missing, so we have to ask the user
        return loginInfoRequest.getResponseFor(type, viewModelScope)  // Blocking call
    }

    /**
     * Resize remote desktop to match with local window size (if requested by user).
     * In portrait mode, safe area is used instead of window to exclude the keyboard.
     */
    fun resizeRemoteDesktop() {
        if (connected && profile.resizeRemoteDesktop && !videoDisabled)
            frameState.let {
                if (it.windowWidth > it.windowHeight)
                    messenger?.setDesktopSize(it.windowWidth.toInt(), it.windowHeight.toInt())
                else
                    messenger?.setDesktopSize(it.safeArea.width().toInt(), it.safeArea.height().toInt())
            }
    }

    /**
     * Asks an RFB host for the desktop size picked in the editor's quality panel.
     *
     * Two unrelated things can keep that size from ever arriving, and from here they look
     * identical: libvncclient refuses to send the request until the server has described
     * its screens (see SendExtDesktopSize), and a server is free to ignore the request
     * afterwards. So it is repeated for a few seconds - the description can arrive late -
     * and the answer is read back from the picture itself, which is the only thing the
     * protocol can actually point at.
     *
     * Giving up is not cosmetic. An unanswered request leaves the library's resize guard
     * raised, and that guard drops every framebuffer update request, so the viewer would
     * keep showing one still frame; [Messenger.cancelDesktopSizeChange] lowers it again.
     *
     * The answer is reported as the size the picture actually has, because a host is free
     * to pick something other than both the request and its own previous desktop.
     *
     * A picture which already has the requested shape is the host's own desktop, not a
     * resize: there is nothing to ask for, nothing that could fail, and nothing worth
     * announcing - the numbers in the warning would be the request's own.
     */
    private fun requestRfbDesktopSize() {
        if (profile.protocol != ServerProfile.PROTOCOL_VNC || !profile.fRfbDesktopSize || videoDisabled)
            return

        val width = profile.streamWidth
        val height = profile.streamHeight

        launchMain {
            if (frameBufferSize.value.matches(width, height))
                return@launchMain

            var applied = false
            var attempt = 0
            while (attempt++ < 5 && connected) {
                messenger?.setDesktopSize(width, height)
                delay(1.seconds)

                if (frameBufferSize.value.matches(width, height)) {
                    applied = true
                    break
                }
            }

            if (!applied && connected) {
                messenger?.cancelDesktopSizeChange()

                // A slow host can confirm after the last poll, and it can confirm with
                // exactly the size which was asked for. That is the request having been
                // taken, not refused, so it must not be reported as a refusal.
                val actual = frameBufferSize.value
                if (actual != null && !actual.matches(width, height))
                    sessionNotice.postValue(app.getString(R.string.msg_rfb_size_unsupported, actual.width, actual.height))
            }
        }
    }

    /**
     * Whether [this] is a known size and is exactly [width] x [height].
     *
     * An unknown shape (no frame received yet) never matches: it says nothing about what
     * the desktop is, and treating it as a match would skip a request which is still owed.
     */
    private fun Size?.matches(width: Int, height: Int) =
            this != null && this.width == width && this.height == height

    fun setFrameBufferUpdatesPaused(paused: Boolean) {
        messenger?.setFrameBufferUpdatesPaused(paused)
    }

    fun refreshFrameBuffer() {
        messenger?.refreshFrameBuffer()
    }

    /**
     * Whether the live connection is a directly driven Android device, i.e. whether the
     * device-side shortcuts in the toolbar mean anything. A capability probe on the
     * client, not a protocol comparison - see [AndroidControlExtras].
     */
    val androidSession get() = (client is AndroidControlExtras)

    /** One of the device's own keys (Back, Home, Recents) as a single press. */
    fun sendAndroidKey(keyCode: Int) {
        messenger?.sendAndroidKey(keyCode, 0)
    }

    /** One of the stateless device actions (rotate, power, screen, night mode). */
    fun sendAndroidAction(action: Messenger.AndroidAction) {
        messenger?.sendAndroidAction(action)
    }

    /**
     * Asks the device for its real night mode and delivers it on the main thread.
     * The read crosses the wire, so it is asynchronous by nature; [onResult] gets null
     * when the helper did not answer, which leaves the caller on its own guess.
     */
    fun queryRemoteNightMode(onResult: (Int?) -> Unit) {
        launchIO {
            val mode = messenger?.queryNightMode()
            launchMain { onResult(mode) }
        }
    }

    /**
     * Whether the device's screen is lit right now, delivered on the main thread. Same
     * shape as [queryRemoteNightMode] and the same null contract: no answer leaves the
     * caller on its own assumption.
     */
    fun queryRemoteScreenOn(onResult: (Boolean?) -> Unit) {
        launchIO {
            val screenOn = messenger?.queryScreenOn()
            launchMain { onResult(screenOn) }
        }
    }

    /**
     * Sets gesture style of profile to given value.
     * Any change will be reflected in [activeGestureStyle].
     */
    fun setGestureStyle(newStyle: String) {
        if (profile.gestureStyle != newStyle) {
            profile.gestureStyle = newStyle
            saveProfile()
        }

        resolveGestureStyle()
    }

    private fun resolveGestureStyle() {
        var gestureStyle = profile.gestureStyle

        if (gestureStyle == "auto")
            gestureStyle = pref.input.gesture.style

        if (videoDisabled)
            gestureStyle = "touchpad"

        check(gestureStyle.isNotBlank())
        if (activeGestureStyle.value != gestureStyle)
            activeGestureStyle.value = gestureStyle
    }

    fun setViewMode(newMode: Int) {
        if (profile.viewMode != newMode) {
            profile.viewMode = newMode
            saveProfile()
        }

        if (activeViewMode.value != newMode) {
            activeViewMode.value = newMode
            client?.setInputDisabled(newMode == ServerProfile.VIEW_MODE_NO_INPUT)
            setFrameBufferUpdatesPaused(newMode == ServerProfile.VIEW_MODE_NO_VIDEO)
            resolveGestureStyle()
        }
    }

    /**
     * Ask the active presenter to bring itself up to date with [frameState]. Public so the
     * layout layer (which changes the viewport/window the frame is fitted into) can nudge a
     * direct-scan surface the same way a GL view gets nudged by an incoming frame.
     */
    fun refreshFrameView() {
        frameViewRef.get()?.refreshFrame()
        pointerOverlayRef.get()?.invalidate()
    }

    private fun rememberLoginInfo() {
        if (loginInfoToBeRemembered.isNotEmpty()) {
            debugCheck(profile.isSaved())
            loginInfoToBeRemembered.forEach { it.applyTo(profile) }
            loginInfoToBeRemembered.clear()
            saveProfile()
        }
    }

    private fun resolveScreenOrientation(): Int {
        var choice = profileLive.value?.screenOrientation
        if (choice == null || choice == "auto")
            choice = pref.viewer.orientation


        return when (choice) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun resolveCapturePointer(): Boolean {
        return connected && pref.input.capturePointer &&
               hasWindowFocus.isTrue
    }


    /**************************************************************************
     * Session observer
     **************************************************************************/
    private inner class RemoteSessionObserver : RemoteSession.Observer {

        override fun onConnecting() {
            state.postValue(State.Connecting)
        }

        override fun onConnected(vncClient: VncClient, messenger: Messenger) {
            launchMain {
                this@VncViewModel.client = vncClient
                this@VncViewModel.messenger = messenger
                state.value = State.Connected
                connectionStage.value = ""

                // A protocol without telemetry can never fill the overlay, so switch it
                // off for this session. The persisted preference is left alone: the next
                // connection (or another protocol) restores it from AppPreferences.
                if (!vncClient.supportsPerfOverlay) statsOverlayEnabled.value = false

                rememberLoginInfo()

                startClipboardBridge(vncClient, messenger)

                // The size chosen in the editor is a property of the host's screen, so it
                // is asked for exactly once per connection - unlike the window-following
                // resize above, which the layout calls on every geometry change.
                requestRfbDesktopSize()

                // NB: no initial clipboard sync on connect - sending our clipboard makes the
                // host paste it automatically. Use the dedicated clipboard button to send
                // clipboard text manually when actually needed.

                onSessionUp?.invoke()
            }
        }

        override fun onDisconnected() {
            stopClipboardBridge()

            // Block until main thread is synchronised with disconnected state
            runBlocking(Dispatchers.Main) {
                state.value = State.Disconnected
                client = null
                messenger = null
                onSessionDown?.invoke()
            }
        }

        override fun onConnectionError(error: Throwable) {
            // Whatever arrives here ends the session, so it has to be explained. The
            // branches with a sentence of their own keep it; everything else - a cancelled
            // credential prompt, a coroutine we did not expect to die, a lock that timed out -
            // used to fall through and leave the viewer on a black screen with a stale reason.
            val reason = when (error) {
                is UnsatisfiedLinkError -> app.getString(R.string.error_native_library_missing)
                else -> error.message?.takeIf { it.isNotBlank() }
                        ?: app.getString(R.string.error_session_interrupted_unknown)
            }
            disconnectReason.postValue(reason)
        }



        /**************************** [VncClient.Observer] *******************/

        override fun getVncPassword(): String {
            return getLoginInfo(LoginInfo.Type.VNC_PASSWORD).password
        }

        override fun getVncCredentials(): UserCredential {
            return getLoginInfo(LoginInfo.Type.VNC_CREDENTIAL).let { UserCredential(it.username, it.password) }
        }

        override fun verifyVncServerCertificate(certificate: X509Certificate): Boolean {
            if (isCertificateTrusted(app, certificate))
                return true

            val title = app.getString(R.string.title_unknown_certificate)
            val message = getUnknownCertificateMessage(certificate)
            if (confirmationRequest.getResponseFor(Pair(title, message), viewModelScope)) {
                trustCertificate(app, certificate)
                return true
            }

            return false
        }

        override fun onFramebufferUpdated() {
            refreshFrameView()
        }

        override fun onCutTextReceived(text: String) {
            receiveClipboardText(text)
        }

        override fun onFramebufferSizeChanged(width: Int, height: Int) {
            launchMain {
                frameState.setFramebufferSize(width.toFloat(), height.toFloat())
                frameBufferSize.value = Size(width, height)
                // A GL presenter picks the new shape up on the next frame; a direct-scan
                // surface is laid out (and its buffer fixed) explicitly, so poke it here.
                refreshFrameView()
            }
        }

        override fun onPointerMoved(x: Int, y: Int) {
            refreshFrameView()
        }

        override fun onBell() {
            if (pref.ui.bell) {
                Tones.notify(ToneGenerator.TONE_PROP_BEEP)
            }
        }

        override fun onStageStarting(stage: String) {
            connectionStage.postValue(app.getString(R.string.msg_connecting_stage, localizeStage(stage)))
        }

        override fun onPerfOverlayUpdate(text: String) {
            connectionStats.postValue(text)
        }
    }

    /**
     * Map the raw (English, lowercase) stage identifiers reported by moonlight-common-c -
     * and by our own Android-device protocol - to user-facing labels. Unknown stages fall
     * back to the raw name so a future codec / stage addition still surfaces something
     * sensible instead of nothing.
     */
    private fun localizeStage(stage: String): String {
        val label = when (stage.lowercase()) {
            "none" -> R.string.stage_none
            "platform initialization" -> R.string.stage_platform_init
            "name resolution" -> R.string.stage_name_resolution
            "audio stream initialization" -> R.string.stage_audio_init
            "rtsp handshake" -> R.string.stage_rtsp_handshake
            "control stream initialization" -> R.string.stage_control_init
            "video stream initialization" -> R.string.stage_video_init
            "input stream initialization" -> R.string.stage_input_init
            "control stream establishment" -> R.string.stage_control_up
            "video stream establishment" -> R.string.stage_video_up
            "audio stream establishment" -> R.string.stage_audio_up
            "input stream establishment" -> R.string.stage_input_up
            "handshake" -> R.string.stage_handshake
            "launch" -> R.string.stage_launch
            "android_adb" -> R.string.stage_android_adb
            "android_server" -> R.string.stage_android_server
            "android_encoder" -> R.string.stage_android_encoder
            "android_socket" -> R.string.stage_android_socket
            else -> return stage
        }
        return app.getString(label)
    }
}
