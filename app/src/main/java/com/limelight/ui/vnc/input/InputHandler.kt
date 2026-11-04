/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.limelight.ui.vnc.VncActivity
import com.limelight.util.AppPreferences
import com.limelight.vnc.InputPath
import com.limelight.vnc.TouchPath

/**
 * Input handler for remote session.
 * All input events are passed through here.
 *
 * This is a simple hub, used for logging, filtering, notifications etc.
 * Main input processing happens in [TouchHandler] & [KeyTransport].
 */
class InputHandler {
    private var dispatcher: Dispatcher? = null
    private var touchHandler: TouchHandler? = null
    // Which keyboard handler is used depends on what the connected protocol
    // understands; see [KeyTransport]. The choice is made once per session from
    // the client's declared input path, so no protocol test appears below.
    private var keyHandler: KeyTransport? = null

    /**
     * Touch passthrough, present only when the connected protocol carries real
     * multi-touch; see [TouchPath]. [touchHandler] stays alive beside it because
     * the user can switch the profile back to the gesture system.
     */
    private var directTouchHandler: DirectTouchHandler? = null
    private var interceptMouseBack = false

    /**
     * How a phone keyboard's text reaches the host this session - provisional keystrokes
     * held back until the candidate is confirmed, or mirrored as typed. Read once when the
     * session comes up (see [AppPreferences.Input.imeTextTiming]) and handed to
     * [RemoteInputConnection]; the default holds text back, which is the one that fixes a
     * typed word being sent twice alongside its candidate.
     */
    var imeTextMode = ImeTextTiming.CONFIRM
        private set

    /**
     * List of listeners to be notified after a [KeyEvent] is handled
     */
    val onAfterKeyEventListeners = mutableListOf<(KeyEvent) -> Unit>()

    fun onSessionConnected(activity: VncActivity) {
        val viewModel = activity.viewModel
        dispatcher = Dispatcher(activity)
        touchHandler = TouchHandler(activity.binding.inputView, dispatcher!!, viewModel.pref)
        val client = viewModel.client
        keyHandler = when (client?.inputPath) {
            // RFB wants X key symbols (AVNC's original handler)
            InputPath.RFB_KEYSYM -> KeyHandler(dispatcher!!, viewModel.pref)
            // The remote Android device wants plain key codes
            InputPath.ANDROID_KEYCODE -> AndroidKeyHandler(dispatcher!!)
            // GameStream wants XT scancodes + UTF-8 text injection
            else -> MoonlightKeyHandler(dispatcher!!)
        }
        // A direct-touch protocol bypasses the gesture layer entirely. It can still
        // be turned off per profile, which is the escape hatch when the remote screen
        // needs a zoom/pan that AVNC's own gestures do better.
        directTouchHandler = if (client?.touchPath == TouchPath.DIRECT
                && viewModel.hasProfile && viewModel.profile.androidDirectTouch)
            DirectTouchHandler(dispatcher!!, viewModel)
        else null
        interceptMouseBack = viewModel.pref.input.interceptMouseBack
        imeTextMode = if (viewModel.pref.input.imeTextTiming == AppPreferences.IME_TEXT_TIMING_IMMEDIATE)
            ImeTextTiming.IMMEDIATE
        else
            ImeTextTiming.CONFIRM
    }

    fun onSessionDisconnected() {
        dispatcher = null
        touchHandler = null
        keyHandler = null
        directTouchHandler?.reset()
        directTouchHandler = null
        interceptMouseBack = false
        imeTextMode = ImeTextTiming.CONFIRM
    }

    /**
     * Called when the viewer leaves the foreground. Keys held on a physical keyboard are
     * released by the system, but the *release* event can be dropped on the way to us - and
     * what we remember of it is attached to every later event we send.
     */
    fun onViewerStopped() {
        keyHandler?.onInputStateLost()
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        // The volume rocker belongs to the phone, in every session. All three protocols can
        // map it to a remote key (RFB has the Audio* key syms, GameStream and the Android
        // device both have key codes for it), and forwarding it consumes the event - which
        // leaves the phone's own speaker unreachable from the viewer screen. Refusing it
        // here hands it back to the system, which then shows the volume slider that
        // [com.limelight.ui.vnc.VncActivity] has pinned to the media stream.
        if (keyEvent.keyCode in LOCAL_ONLY_KEYS)
            return false

        val handled = keyHandler?.onKeyEvent(keyEvent) == true || interceptBackPressFromMouse(keyEvent)
        onAfterKeyEventListeners.forEach { it(keyEvent) }
        return handled
    }

    fun onVkKeyEvent(keyEvent: KeyEvent): Boolean {
        val handled = keyHandler?.onVkKeyEvent(keyEvent) == true
        onAfterKeyEventListeners.forEach { it(keyEvent) }
        return handled
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val direct = directTouchHandler
        if (direct != null)
            return direct.onTouchEvent(event)

        return touchHandler?.onTouchEvent(event) == true
    }

    /**
     * The pointer-related events below belong to the desktop gesture system (mouse
     * emulation, trackpad scrolling, hover). With fingers going straight to a remote
     * touchscreen they would only add a phantom cursor on top of real touches, so
     * they are refused.
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (directTouchHandler != null) return false
        return touchHandler?.onGenericMotionEvent(event) == true
    }

    fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (directTouchHandler != null) return false
        return touchHandler?.onCapturedPointerEvent(event) == true
    }

    fun onHoverEvent(event: MotionEvent): Boolean {
        if (directTouchHandler != null) return false
        return touchHandler?.onHoverEvent(event) == true
    }

    /*********************************************************************************************/

    private fun interceptBackPressFromMouse(keyEvent: KeyEvent): Boolean {
        //It seems that some device manufacturers are hell-bent on making developers'
        //life miserable. In their infinite wisdom, they decided that Android apps don't
        //need Mouse right-click events. It is hardcoded to act as back-press, without
        //giving apps a chance to handle it. For better or worse, they set the 'source'
        //for such key events to Mouse, enabling the following workarounds.
        if (directTouchHandler == null && touchHandler != null && interceptMouseBack &&
            keyEvent.keyCode == KeyEvent.KEYCODE_BACK &&
            keyEvent.scanCode == 0 &&
            keyEvent.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY == 0 &&
            InputDevice.getDevice(keyEvent.deviceId)?.supportsSource(InputDevice.SOURCE_MOUSE) == true) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN)
                touchHandler!!.onMouseBack()
            return true
        }
        return false
    }

    private companion object {
        /**
         * Hardware keys that must never leave the phone. On-screen keys enter through
         * [onVkKeyEvent] instead, so a volume key added to the virtual keyboard later would
         * still reach the remote.
         */
        val LOCAL_ONLY_KEYS = setOf(
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
        )
    }
}