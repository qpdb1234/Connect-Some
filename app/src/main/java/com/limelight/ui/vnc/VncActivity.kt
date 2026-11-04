/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import com.limelight.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limelight.R
import com.limelight.databinding.ActivityVncBinding
import com.limelight.databinding.NoVideoOverlayBinding
import com.limelight.ui.vnc.input.InputHandler
import com.limelight.util.AccentTheme
import com.limelight.util.DeviceAuthPrompt
import com.limelight.util.EdgeToEdgeHelper
import com.limelight.util.OpenableDocument
import com.limelight.util.OpenableMultipleDocuments
import com.limelight.util.SamsungDex
import com.limelight.util.debugCheck
import com.limelight.util.enableChildLayoutTransitions
import com.limelight.util.isKeyboardVisible
import com.limelight.util.showKeyboard
import com.limelight.util.ImmersiveDialogHost
import com.limelight.util.onScreenImmersiveHost
import com.limelight.util.showSafely
import com.limelight.vnc.RenderMode
import com.limelight.viewmodel.VncViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

/**
 * This activity handles the connection to a VNC server.
 */
class VncActivity : AppCompatActivity(), ImmersiveDialogHost {

    /**
     * The viewer hides the system bars only for a running fullscreen session, so a dialog raised
     * over anything else (a backgrounded stream, a non-fullscreen profile) should not try to keep
     * them hidden. See [com.limelight.util.ImmersiveDialogHost].
     */
    override val immersiveDialogs: Boolean
        get() = viewModel.pref.viewer.fullscreen && viewModel.connected

    @Parcelize
    private data class SavedState(
            val frameX: Float,
            val frameY: Float,
            val zoomScale1: Float,
            val zoomScale2: Float,
            val reconnectDelay: Int?) : Parcelable

    companion object {
        private const val TAG = "VncActivity"
        private const val SAVED_STATE_KEY = "com.limelight.vnc_activity.saved_state"
    }

    val viewModel by viewModels<VncViewModel>()
    lateinit var binding: ActivityVncBinding

    /**
     * Shared with the mini window: it draws the same stream in a smaller box, and a touch
     * there is a touch on the same remote desktop. That only works because the handlers
     * take the coordinates out of the event and the viewport out of [FrameState], never
     * out of the view they were built with.
     */
    val inputHandler = InputHandler()
    val virtualKeys by lazy { VirtualKeys(this, inputHandler) }
    val toolbar by lazy { Toolbar(this) }
    val assistBall by lazy { AssistBall(this) }
    val fullKeyboard by lazy { FullKeyboard(this, inputHandler) }
    val miniWindow by lazy { MiniWindow(this) }
    private val serverUnlockPrompt = DeviceAuthPrompt(this)
    private val layoutManager by lazy { LayoutManager(this) }
    private var oldState: SavedState? = null
    /** Set while a saved frame position still has to be placed after the first frame. */
    private var pendingFrameRestore = false
    private var hasConnectedSuccessfully = false

    /**
     * The GL [FrameView] is the active presenter only when this is non-null (pixel-buffer
     * protocols, or surface protocols with the direct-scan fallback switched off). When the
     * direct-scan [StreamSurfaceView] is used this stays null, so nothing ever points the
     * decoder at the GL `SurfaceTexture` and the two paths cannot bind a surface twice.
     */
    private var glFrameView: FrameView? = null
    private var wasConnectedWhenActivityStopped = false
    private var wasKeyboardVisibleWhenActivityStopped = false
    private var onStartTime = 0L

    /**
     * The files to hand to LocalSend are chosen with the system multi-select picker, which
     * is asynchronous, so the caller's callback is parked here until the pick comes back.
     * Only one send can be in flight at a time, which matches the single panel that offers it.
     */
    private var pendingLocalSendPick: ((List<Uri>) -> Unit)? = null

    /**
     * Set alongside [pendingLocalSendPick] while the SAF picker is on top of us. The activity
     * is stopped for the duration of the pick, which is normally a signal to freeze the
     * framebuffer and reconnect on the way back - but a multi-file browse is not a real trip
     * to the background, and pausing updates for it only risks the connection. [onStop] uses
     * this to skip the pause; [onStart] still runs the socket probe so a connection the OEM
     * dropped anyway is recovered the moment we return.
     */
    private var isPickerInFlight = false

    /**
     * Companion of [isPickerInFlight]: latched by [onStop] when the pause path was skipped
     * because of the picker, and consumed by [onStart] on the way back so the unpause path
     * is skipped in turn. Kept separate from [isPickerInFlight] because the SAF result
     * callback can fire either before or after [onStart] depending on the OEM dispatch.
     */
    private var skippedBackgroundPauseForPicker = false

    private val localSendDocumentPicker = registerForActivityResult(OpenableMultipleDocuments()) { uris ->
        isPickerInFlight = false
        val callback = pendingLocalSendPick
        pendingLocalSendPick = null
        callback?.invoke(uris ?: emptyList())
    }

    fun pickLocalSendDocuments(onPicked: (List<Uri>) -> Unit) {
        if (pendingLocalSendPick != null) return
        pendingLocalSendPick = onPicked
        isPickerInFlight = true
        localSendDocumentPicker.launch(arrayOf("*/*"))
    }

    /**********************************************************************************************
     * Activity Lifecycle
     *********************************************************************************************/

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.attach(this)
        DeviceAuthPrompt.applyFingerprintDialogFix(supportFragmentManager)

        super.onCreate(savedInstanceState)
        bindVolumeKeysToMediaStream()
        startup(savedInstanceState)

        //Main UI
        binding = EdgeToEdgeHelper.setDataBindingContentView(this, R.layout.activity_vnc)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        // The presenter (GL FrameView vs direct-scan StreamSurfaceView) is chosen once the
        // profile is known, in applyPresenter. Until then neither view is active.
        binding.inputView.initialize(viewModel, inputHandler)
        viewModel.attachActivity(this)

        setupLayout()
        setupNoVideoOverlay()
        assistBall.initialize()
        fullKeyboard.initialize()

        //Observers
        binding.reconnectBtn.setOnClickListener { reconnect() }
        viewModel.loginInfoRequest.observe(this) { showLoginDialog() }
        viewModel.confirmationRequest.observe(this) { showConfirmationDialog() }
        viewModel.state.observe(this) { onClientStateChanged(it) }
        viewModel.profileLive.observe(this) { onProfileUpdated() }
        viewModel.capturePointer.observe(this) { updatePointerCapture(it) }

        // A session can fail at something quietly - a host which will not resize its
        // desktop, say - and a toast is the only place left once the picture is up.
        viewModel.sessionNotice.observe(this) { notice ->
            if (notice != null) {
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                viewModel.sessionNotice.value = null
            }
        }

        // A reconnect builds a fresh ViewModel whose framebuffer is still unknown when the
        // session first reports Connected; panning to the saved position that early is
        // re-coerced back to center. Place it as soon as the picture's shape arrives.
        viewModel.frameBufferSize.observe(this) {
            if (pendingFrameRestore) applyFrameState()
        }

        // The letterbox bands around the picture take the color of the container behind the
        // video. Painting it here (rather than via a layout constant) keeps the direct-scan
        // SurfaceView itself transparent - an opaque background on that view is what breaks
        // hardware composition on some OEM devices - while still giving the punched-out area
        // behind it a chosen fill. The GL path covers the whole container, so it cannot show
        // that fill and is handed the same color to clear to instead. LiveData fires on
        // subscribe, so the initial value applies.
        viewModel.streamBarColor.observe(this) { hex ->
            hex?.let {
                val argb = AccentTheme.argbOf(it)
                binding.frameContainer.setBackgroundColor(argb)
                viewModel.frameViewRef.get()?.setLetterboxColor(argb)
            }
        }

        // The input pipeline belongs to the connection, not to this screen. The observer above
        // only speaks while the viewer is in the foreground, and the mini window restarts the
        // session after sending us to the background - leaving a torn-down pipeline behind.
        // Wiring through the ViewModel keeps touches and keystrokes alive for the overlay.
        viewModel.onSessionUp = { inputHandler.onSessionConnected(this) }
        viewModel.onSessionDown = { inputHandler.onSessionDisconnected() }

        oldState = (savedInstanceState ?: intent.extras)?.let {
            BundleCompat.getParcelable(it, SAVED_STATE_KEY, SavedState::class.java)
        }
    }

    override fun onStart() {
        // Timestamp the restart before super.onStart() runs. A session that died while we were
        // backgrounded is reported the instant the state LiveData wakes, and that wake happens
        // inside super.onStart() (the observer only fires once we reach STARTED). The
        // "disconnected during an activity restart" window in [calculateAutoReconnectDelay] is
        // measured from here, so setting this any later would already leave it stale by the
        // time the drop is noticed.
        onStartTime = SystemClock.uptimeMillis()
        super.onStart()

        // Publish the bars-hidden state for dialogs raised over this stream by another window -
        // the floating LocalSend card hops through a transparent Activity of its own that cannot
        // see the session, only this (see com.limelight.util.onScreenImmersiveHost).
        onScreenImmersiveHost = this

        // Reaching the viewer from the recents screen (rather than through the window's own
        // button) has to fold the window away, or it would keep floating over the very
        // stream it is showing, with two views fighting for the same viewport. The window's
        // fullscreen button ends up here too: it only brings this activity forward, and the
        // fold follows the arrival instead of preceding it.
        if (miniWindow.isVisible) miniWindow.hide()

        glFrameView?.onResume()

        // The key layout can have been edited while this window was away - the editor is
        // reachable from the bar itself - so the bar gets a chance to pick up what changed.
        virtualKeys.onActivityStart()

        // Refresh framebuffer on activity restart:
        // - It forces read/write on the socket. This allows us to verify the socket, which might have
        //   been closed by the server while app process was frozen in background
        // - It also attempts to fix some unusual cases of old updates requests being lost while AVNC
        //   was frozen by the system
        // A trip to the LocalSend file picker is not a real background freeze, so [onStop] left
        // the framebuffer un-paused for its duration and there is nothing to un-pause back here.
        // The socket can still have been dropped while the process sat frozen behind the picker
        // (Huawei tears down a backgrounded connection outright), so the pick now gets the same
        // active probe as any other return rather than being told to skip it: on a live
        // connection [refreshFrameBuffer] is only a frame request, but on a dead one it surfaces
        // the failure and the restart window reconnects straight away - instead of leaving the
        // viewer stuck on "Connection aborted" for want of anything noticing the drop.
        if (skippedBackgroundPauseForPicker) {
            skippedBackgroundPauseForPicker = false
            if (wasConnectedWhenActivityStopped)
                viewModel.refreshFrameBuffer()
        } else if (viewModel.pref.viewer.pauseUpdatesInBackground && !viewModel.videoDisabled && !miniWindow.isVisible)
            viewModel.setFrameBufferUpdatesPaused(false)
        else if (wasConnectedWhenActivityStopped)
            viewModel.refreshFrameBuffer()
    }

    override fun onStop() {
        super.onStop()
        // Only ours to clear: a reconnect starts the next viewer before this one is stopped, and
        // an unguarded reset would unregister the newcomer that already published itself.
        if (onScreenImmersiveHost === this) onScreenImmersiveHost = null
        virtualKeys.releaseMetaKeys()
        fullKeyboard.releaseToggles()
        inputHandler.onViewerStopped()
        glFrameView?.onPause()

        // Background is exactly when the mini window is supposed to be showing, so the
        // usual "stop sending me frames while I am away" rule has to make an exception -
        // a paused framebuffer is a frozen picture in the overlay. Ignored by the
        // protocols that decode continuously, which is why this only matters for VNC.
        // The LocalSend picker is another exception: the pick can run long enough that a
        // paused framebuffer tears down the socket, so the pause is skipped outright. onStart
        // is told (via [skippedBackgroundPauseForPicker]) to skip the matching un-pause, but it
        // still probes the socket so a connection dropped behind the picker is reconnected.
        if (isPickerInFlight) {
            skippedBackgroundPauseForPicker = true
        } else if (viewModel.pref.viewer.pauseUpdatesInBackground && !miniWindow.isVisible)
            viewModel.setFrameBufferUpdatesPaused(true)
        wasConnectedWhenActivityStopped = viewModel.connected
        wasKeyboardVisibleWhenActivityStopped = isKeyboardVisible(binding.inputView)
    }

    override fun onDestroy() {
        // The window is only useful while this activity can bring the stream back. Once
        // the ViewModel is gone the session stops with it, and an overlay of a dead
        // session would just be a frozen rectangle that nothing can dismiss.
        if (miniWindow.isVisible) miniWindow.hide(endSession = true)

        // The ViewModel survives a configuration change and these two capture this activity
        // (through the input pipeline). Left in place, a destroyed viewer - with its whole
        // view hierarchy and its GL surface - stays reachable from the surviving ViewModel,
        // and the next session would drive the dead one's handlers.
        viewModel.onSessionUp = null
        viewModel.onSessionDown = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        viewModel.hasWindowFocus.value = hasFocus
        if (hasFocus) {
            // NB: do not push the local clipboard on focus gain - the host would treat the
            // clipboard sync as an automatic paste into whatever window currently has focus.
            restoreKeyboard()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(SAVED_STATE_KEY, prepareSavedState())
        viewModel.profileLive.value?.let { outState.putProfile(it) }
    }

    private fun prepareSavedState(reconnectDelay: Int? = null): SavedState {
        val fs = viewModel.frameState
        return SavedState(
                frameX = fs.frameX,
                frameY = fs.frameY,
                zoomScale1 = fs.zoomScale1,
                zoomScale2 = fs.zoomScale2,
                reconnectDelay = reconnectDelay
        )
    }

    private fun restoreKeyboard() {
        // API 31+ automatically restores the keyboard when app comes back from background
        // For older devices we manually track keyboard visibility
        if (wasKeyboardVisibleWhenActivityStopped && Build.VERSION.SDK_INT <= 30) {
            wasKeyboardVisibleWhenActivityStopped = false
            showKeyboard()
        }
    }


    /**********************************************************************************************
     * Session Control
     *********************************************************************************************/

    private fun startup(savedState: Bundle?) {
        if (viewModel.profileLive.value != null) // todo refactor
            return

        runCatching {
            val startupArg = parseStartupArg(intent, savedState)

            val isSavedServer = startupArg is StartupArg.ProfileId ||
                                (startupArg is StartupArg.Profile && startupArg.profile.isSaved())

            val isUnlockNeeded = isSavedServer && viewModel.pref.server.lockSavedServer
                                 && serverUnlockPrompt.canLaunch()

            if (isUnlockNeeded) {
                serverUnlockPrompt.init(
                        onSuccess = { startSession(startupArg) },
                        onFail = { handleStartupFailure(Exception(it)) })

                if (!serverUnlockPrompt.hasLaunched())
                    serverUnlockPrompt.launch(getString(R.string.title_unlock_dialog))
            } else {
                startSession(startupArg)
            }
        }.onFailure {
            handleStartupFailure(it)
        }
    }

    private fun startSession(startupArg: StartupArg) {
        lifecycleScope.launch {
            runCatching {
                startSession(startupArg, viewModel)
            }.onFailure {
                handleStartupFailure(it)
            }
        }
    }

    private fun handleStartupFailure(cause: Throwable) {
        // These three messages are what the user sees before the viewer closes, so they
        // come from resources. The throwable's own message stays in the log, where the
        // technical wording belongs.
        var msg = cause.message ?: getString(R.string.error_startup_generic)
        when (cause) {
            is MissingStartupArgException -> {
                debugCheck(false) // Crash debug builds
                msg = getString(R.string.error_startup_missing_server_info)
            }
            is InvalidProfileIdException -> {
                msg = getString(R.string.error_startup_invalid_server_id)
            }
        }

        Log.e(TAG, msg, cause)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun reconnect(savedState: SavedState = prepareSavedState()) {
        //We simply create a new activity to force creation of new ViewModel
        //which effectively restarts the connection.
        if (isFinishing || isDestroyed) {
            // Nobody is left to hand the new activity to: the user closed this viewer and
            // went back to the server list. Starting another one here would drag them into
            // a session they had just declined.
            Log.i(TAG, "Not reconnecting: the viewer is going away")
            return
        }

        if (!isFinishing) {
            startActivity(createVncIntent(this, viewModel.profile).also {
                it.putExtra(SAVED_STATE_KEY, savedState)
            })

            if (savedState.reconnectDelay == 0) {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            finish()
        }
    }

    private var autoReconnecting = false
    private fun autoReconnect() {
        // The same rule at the source: a disconnect that lands while this viewer is on its
        // way out is the user's own doing, not a drop worth recovering from.
        if (isFinishing || isDestroyed) {
            Log.i(TAG, "Auto-reconnect skipped: the viewer is going away")
            return
        }

        if (autoReconnecting)
            return

        val reconnectDelay = calculateAutoReconnectDelay() ?: return
        autoReconnecting = true
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Show progress bar
                // Progress intentionally reaches 100% 1 step earlier to allow the animation to complete
                repeat(reconnectDelay) {
                    val progress = if (reconnectDelay <= 1) 100 else (100 * it) / (reconnectDelay - 1)
                    binding.autoReconnectProgress.setProgressCompat(progress, true)
                    delay(1.seconds)
                }

                reconnect(prepareSavedState(reconnectDelay))
            }
        }
    }

    private fun calculateAutoReconnectDelay(): Int? {
        // If disconnected when coming back from background, try to reconnect immediately
        if (wasConnectedWhenActivityStopped && (SystemClock.uptimeMillis() - onStartTime) in 0..2000) {
            Log.i(TAG, "Disconnected during activity restart, reconnecting ...")
            return 0
        }

        if (!viewModel.pref.server.autoReconnect && !viewModel.profile.enableWol)
            return null // Auto-reconnect is disabled

        // Automatic reconnect happens every 5 seconds.
        // But if session had reached Connected state, first attempt happens
        // after 1 second, second attempt after 3 seconds, and then every 5 seconds.
        val previousDelay = oldState?.reconnectDelay
        return when {
            hasConnectedSuccessfully -> 1   // Connection lost, try to reconnect early
            previousDelay == null -> 5
            previousDelay == 0 -> 1
            else -> (previousDelay + 2).coerceAtMost(5)
        }
    }


    /**********************************************************************************************
     * Session lifecycle
     *********************************************************************************************/

    private fun onProfileUpdated() {
        applyPresenter()
        toolbar.initialize()
    }

    /**
     * Pick the render path for the now-known profile and make its view the active presenter.
     *
     * Surface protocols (GameStream, Android device) default to the low-latency direct-scan
     * [StreamSurfaceView]; pixel-buffer protocols (VNC) must keep the GL [FrameView] because
     * it composites the local cursor. The direct-scan choice can be turned off in settings to
     * fall back to the GL surface path.
     */
    private fun applyPresenter() {
        if (!viewModel.hasProfile)
            return

        val useDirectScan = viewModel.pref.viewer.directScanSurfaceView &&
                viewModel.renderMode == RenderMode.SURFACE

        if (useDirectScan) {
            glFrameView = null
            binding.frameView.visibility = View.GONE
            binding.streamSurfaceView.attach(viewModel)
            binding.streamSurfaceView.visibility = View.VISIBLE
        } else {
            binding.streamSurfaceView.visibility = View.GONE
            if (glFrameView == null) {
                binding.frameView.initialize(viewModel)
                viewModel.frameViewRef = WeakReference<FramePresenter>(binding.frameView)
                glFrameView = binding.frameView
            }
            binding.frameView.visibility = View.VISIBLE
        }

        viewModel.refreshFrameView()
    }

    /** The view backing the current presenter, used for visibility and screen-on handling. */
    private fun activePresenterView(): View = glFrameView ?: binding.streamSurfaceView

    private fun onClientStateChanged(newState: VncViewModel.State) {
        val isConnected = newState == VncViewModel.State.Connected

        //todo: Cleanup these too
        layoutManager.onConnectionStateChanged()
        toolbar.onStateChange(isConnected)
        updateStatusContainerVisibility(isConnected)

        when (newState) {
            VncViewModel.State.Connected -> onSessionConnected()
            VncViewModel.State.Disconnected -> onSessionDisconnected()
            else -> return
        }
    }

    private fun onSessionConnected() {
        hasConnectedSuccessfully = true
        // Start the phone-keyboard mirror from nothing: the field on the new host has no
        // relation to whatever the last session was typing into.
        binding.inputView.resetInputModel()

        activePresenterView().let {
            it.isVisible = true
            it.keepScreenOn = viewModel.pref.viewer.keepScreenOn
        }
        virtualKeys.onConnected()
        SamsungDex.setMetaKeyCapture(this, true)

        // A continuing session - an activity restart (ViewModel survived) or a reconnect (new
        // ViewModel, position handed over through the intent) - restores where the frame was
        // left. A brand new connection carries no `oldState` and simply counts the use.
        if (oldState != null)
            restoreFrameState()
        else
            incrementUseCount()
    }

    private fun onSessionDisconnected() {
        // Forget the mirrored field so a reconnect to a different host does not think its
        // text is still on screen and try to "repair" a tail that was never there.
        binding.inputView.resetInputModel()
        activePresenterView().let {
            it.isVisible = false
            it.keepScreenOn = false
        }
        SamsungDex.setMetaKeyCapture(this, false)
        autoReconnect()
    }

    private fun restoreFrameState() = applyFrameState()

    /**
     * Put the frame back at the position and zoom the previous session left it at.
     *
     * The position is applied with an absolute move rather than a pan delta: on an activity
     * restart the surviving [FrameState] already sits at the saved spot, so panning by that
     * same amount as a delta would double it. When the framebuffer is not sized yet (a
     * reconnect), the move is re-coerced to center for now and the [pendingFrameRestore] flag
     * leaves the observer to try again once the first frame defines the shape.
     */
    private fun applyFrameState() {
        val state = oldState ?: return
        viewModel.setZoom(state.zoomScale1, state.zoomScale2)
        viewModel.moveFrameTo(state.frameX, state.frameY)
        pendingFrameRestore = viewModel.frameState.fbWidth == 0f || viewModel.frameState.fbHeight == 0f
    }

    private fun incrementUseCount() {
        viewModel.profile.useCount += 1
        viewModel.saveProfile()
    }


    /************************************************************************************
     * Interface
     ************************************************************************************/
    private fun setupLayout() {
        layoutManager.initialize()

        viewModel.preferredScreenOrientation.observe(this) { requestedOrientation = it }

        if (Build.VERSION.SDK_INT >= 28 && viewModel.pref.viewer.drawBehindCutout) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
            viewModel.inPiPMode.value = true
        }
    }

    private fun setupNoVideoOverlay() {
        viewModel.activeViewMode.observe(this) {
            if (viewModel.videoDisabled) {
                inflateNoVideoOverlay()
                binding.noVideoOverlayStub.root?.isVisible = true
            } else {
                binding.noVideoOverlayStub.root?.isVisible = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateNoVideoOverlay() {
        if (binding.noVideoOverlayStub.isInflated)
            return

        enableChildLayoutTransitions(binding.frameContainer)

        binding.noVideoOverlayStub.viewStub?.inflate()
        val stubBinding = binding.noVideoOverlayStub.binding as NoVideoOverlayBinding
        val rootView = stubBinding.overlayRoot
        val tapIndicator = stubBinding.tapIndicator

        enableChildLayoutTransitions(stubBinding.overlayRoot)

        // Tap indicator should appear immediately, but disappear with animation
        rootView.layoutTransition?.setDuration(LayoutTransition.APPEARING, 0)

        rootView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> tapIndicator.apply {
                    isVisible = true
                    translationX = event.x - (width / 2)
                    translationY = event.y - (height / 2)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> tapIndicator.isVisible = false
            }
            inputHandler.onTouchEvent(event)
        }
    }

    private fun showLoginDialog() {
        // Both dialogs are raised from `LiveData` observers, which wake at STARTED - on the
        // way back to the foreground that is before the FragmentManager accepts ordinary
        // transactions again, and `show()` would throw there.
        LoginFragment().showSafely(supportFragmentManager, "LoginDialog")
    }

    private fun showConfirmationDialog() {
        ConfirmationDialog().showSafely(supportFragmentManager, "ConfirmationDialog")
    }

    fun showKeyboard() {
        showKeyboard(currentFocus ?: binding.inputView)
        virtualKeys.onKeyboardOpen()
    }

    private fun updatePointerCapture(capturePointer: Boolean) {
        if (Build.VERSION.SDK_INT < 26)
            return

        if (capturePointer) {
            binding.inputView.requestFocus()
            binding.inputView.requestPointerCapture()
        } else
            binding.inputView.releasePointerCapture()
    }

    private fun updateStatusContainerVisibility(isConnected: Boolean) {
        binding.statusContainer.isVisible = true
        binding.statusContainer
                .animate()
                .alpha(if (isConnected) 0f else 1f)
                .withEndAction { binding.statusContainer.isVisible = !isConnected }
    }

    /************************************************************************************
     * Picture-in-Picture support
     ************************************************************************************/

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPiPMode()
    }

    @RequiresApi(26)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.inPiPMode.value = isInPictureInPictureMode

        if (!isInPictureInPictureMode) {
            // If user taps the Close button on PiP window, Android will stop the Activity
            // but won't destroy it. This is not a problem for singleTask activities since those
            // are still shown in Recents screen. But AVNC doesn't use singleTask. So VncActivity
            // in PiP mode gets detached into a separate task, which for some reason isn't shown
            // in Recents screen. Hence the activity is effectively leaked.
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                Log.i(TAG, "Finishing activity on PiP Close button click")
                finish()
            }
        }
    }

    private fun enterPiPMode() {
        val canEnter = viewModel.pref.viewer.pipEnabled && viewModel.connected && !viewModel.videoDisabled

        if (canEnter && Build.VERSION.SDK_INT >= 26) {

            // `isFinite` and not just a range check: the frame size is only set once the
            // first video frame arrives, and the field starts out at NaN. NaN fails every
            // comparison (`w <= 0` is false, and it also slips through `coerceIn`), so an
            // early PiP tap would reach `Rational(NaN.toInt(), ...)` == `Rational(0, 0)`,
            // which throws.
            var w = viewModel.frameState.fbWidth
            var h = viewModel.frameState.fbHeight
            if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f)
                return

            // Android require aspect ratio to be less than 2.39
            w = w.coerceIn(1f, 2.3f * h)
            h = h.coerceIn(1f, 2.3f * w)

            val param = try {
                val aspectRatio = Rational(w.toInt(), h.toInt())
                PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
            } catch (e: IllegalArgumentException) {
                // Rounding a very wide/narrow but still legal pair down to 0 would land
                // here; entering PiP with the system default ratio beats crashing.
                Log.w(TAG, "Unusable PiP aspect ratio for ${w}x${h}", e)
                PictureInPictureParams.Builder().build()
            }

            try {
                enterPictureInPictureMode(param)
            } catch (e: Exception) {
                // IllegalStateException (not resumed / wrong state) plus any platform
                // RuntimeException: this is fired from a toolbar click, and a crashed
                // viewer is a much worse outcome than staying full screen.
                Log.e(TAG, "Cannot enter PiP mode", e)
            }
        }
    }

    /************************************************************************************
     * Input
     ************************************************************************************/

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputHandler.onKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputHandler.onKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return inputHandler.onKeyEvent(event) || super.onKeyMultiple(keyCode, repeatCount, event)
    }

    /**
     * The viewer keeps the volume keys for the phone (see
     * [com.limelight.ui.vnc.input.InputHandler.onKeyEvent]), so they have to be pointed at
     * the stream the remote audio actually plays on. Left to itself, the system picks the
     * stream that last had focus - which in a VNC session (RFB carries no audio at all)
     * means the keys can move a volume that nothing is using. This is the same call
     * moonlight-android makes in `Game.java`.
     */
    @Suppress("DEPRECATION") // No player to attach to: the replacement wants a MediaSession.
    private fun bindVolumeKeysToMediaStream() {
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

}
