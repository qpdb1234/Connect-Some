/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.session

import android.graphics.PointF
import com.limelight.util.Log
import android.view.Display
import com.limelight.vnc.AndroidControlExtras
import com.limelight.vnc.GameStreamExtras
import com.limelight.vnc.PointerButton
import com.limelight.vnc.RfbClipboardBridge
import com.limelight.vnc.VncClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Allows sending different types of messages to remote server.
 */
class Messenger(private val client: VncClient) {

    companion object {
        /**
         * Night modes for [AndroidControlExtras.sendNightMode] and [queryNightMode]: the
         * [android.app.UiModeManager] numbers, which the helper passes through untouched.
         * Auto (0) and custom (3) are equally real on a device but have no button here -
         * the toolbar only flips between the two forced modes.
         *
         * NB: the project this protocol came from labelled 2 as "yes" and 1 as "no", which
         * is the opposite of what the framework means. These follow the framework.
         */
        const val NIGHT_MODE_YES = 1
        const val NIGHT_MODE_NO = 2

        /** How long [shutdown] lets the sender thread wind down before giving up on it. */
        private const val TERMINATION_TIMEOUT_SEC = 5L
    }

    /**
     * Protocol-specific abilities of the live client. Null for a plain RFB
     * connection, which is how the protocol-only calls below disable themselves
     * without knowing anything about the selected protocol.
     */
    private val gameStream get() = client as? GameStreamExtras
    private val android get() = client as? AndroidControlExtras

    /**************************************************************************
     * Sender thread
     **************************************************************************/
    private val sender = Executors.newSingleThreadExecutor()

    private fun execute(action: Runnable): Boolean {
        try {
            if (client.connected && !sender.isShutdown) {
                sender.execute(action)
                return true
            }
        } catch (e: Exception) {
            Log.w("Messenger", "Failed to enqueue action [isShutdown: ${sender.isShutdown}]: ${e.message}")
        }
        return false
    }

    fun shutdown() {
        runCatching {
            // `shutdownNow` rather than `shutdown`, and a short wait rather than a long one:
            // the queue can hold a socket write to a peer that has stopped reading, and that
            // write does not end because we asked nicely. Waiting it out used to stall the
            // session thread for the full timeout - which is exactly the thread [restart]
            // joins, so a mini-window flip looked like a frozen session.
            sender.shutdownNow()
            sender.awaitTermination(TERMINATION_TIMEOUT_SEC, TimeUnit.SECONDS)
        }
        if (!sender.isTerminated)
            Log.w("Messenger", "Unable to shutdown messenger thread")
    }


    /**************************************************************************
     * Input events
     **************************************************************************/

    /**
     * Keeps track of current pointer button state.
     */
    private var pointerButtonMask: Int = 0

    private fun sendPointerEvent(mask: Int, p: PointF) {
        val x = p.x.toInt()
        val y = p.y.toInt()
        client.moveClientPointer(x, y)
        execute { client.sendPointerEvent(x, y, mask) }
    }

    fun sendPointerButtonDown(button: PointerButton, p: PointF) {
        pointerButtonMask = pointerButtonMask or button.bitMask
        sendPointerEvent(pointerButtonMask, p)
    }

    fun sendPointerButtonUp(button: PointerButton, p: PointF) {
        pointerButtonMask = pointerButtonMask and button.bitMask.inv()
        sendPointerEvent(pointerButtonMask, p)
    }

    fun sendPointerButtonRelease(p: PointF) {
        if (pointerButtonMask != 0) {
            pointerButtonMask = 0
            sendPointerEvent(pointerButtonMask, p)
        }
    }

    fun sendKey(keySym: Int, xtCode: Int, isDown: Boolean): Boolean {
        return execute { client.sendKeyEvent(keySym, xtCode, isDown) }
    }

    /**
     * Moonlight/GameStream keyboard event: translated scancode + modifier bitmask.
     * This is the working text-input path for that protocol (see
     * [com.limelight.ui.vnc.input.MoonlightKeyHandler]).
     *
     * @return false when the connected protocol has no scancode transport, so the
     *         caller can fall back to the keysym based [sendKey].
     */
    fun sendKeyboardScancode(scancode: Short, keyDirection: Byte, modifier: Byte, flags: Byte): Boolean {
        val gameStream = gameStream ?: return false
        return execute { gameStream.sendKeyboardScancode(scancode, keyDirection, modifier, flags) }
    }

    /**
     * Injects text as keystrokes on the host (used for characters without a scancode).
     * GameStream-only, for the same reason as [sendKeyboardScancode].
     */
    fun sendText(text: String): Boolean {
        val gameStream = gameStream ?: return false
        return execute { gameStream.sendText(text) }
    }

    fun insertButtonUpDelay() = insertDelay(200)

    /**
     * One pointer of a multi-touch sequence, in the Android protocol's shape:
     * normalized coordinates instead of desktop pixels (see
     * [com.limelight.ui.vnc.input.DirectTouchHandler]).
     *
     * @return false when the connected protocol has no touch-injection transport.
     */
    fun sendAndroidTouch(
            action: Int, pointerId: Int, xNorm: Float, yNorm: Float, offsetTimeMs: Int): Boolean {
        val android = android ?: return false
        return execute { android.sendMultiTouch(action, pointerId, xNorm, yNorm, offsetTimeMs) }
    }

    /**
     * A press of an Android key code. Unlike the keysym path, this carries no
     * direction: the device injects a down and an up itself, so a held key is not
     * expressible (see [AndroidControlExtras.sendAndroidKey]).
     */
    fun sendAndroidKey(keyCode: Int, metaState: Int): Boolean {
        val android = android ?: return false
        return execute { android.sendAndroidKey(keyCode, metaState) }
    }

    /**
     * One of the device-side actions this protocol adds (rotate, power menu,
     * screen power, night mode, config refresh). Every selector is resolved
     * through [which] so this stays a single enqueue site.
     */
    fun sendAndroidAction(which: AndroidAction): Boolean {
        val android = android ?: return false
        return execute { which.send(android) }
    }

    /**
     * The device's actual night mode, for the one toolbar button that can start from
     * truth instead of a guess. Called directly rather than through [execute]: the
     * sender thread is fire-and-forget and has nowhere to put a reply, while this read
     * is a blocking round trip and belongs to the caller's own coroutine.
     *
     * @return an [android.app.UiModeManager] mode number, or null when the connected
     *         protocol cannot report it (plain RFB, a desktop, an unreachable helper).
     */
    suspend fun queryNightMode(): Int? = android?.queryNightMode()

    /**
     * Whether the device's screen is lit, for the one button whose parity cannot be
     * guessed: a session that was reconnected after the device went to sleep starts with
     * the picture dark, and only the device knows that. Null when the protocol is not the
     * Android one or the helper could not say.
     */
    suspend fun queryScreenOn(): Boolean? = android?.queryScreenOn()

    /** Device-side actions of the Android protocol, named once for the callers. */
    enum class AndroidAction {
        ROTATE,
        POWER,
        SCREEN_ON,
        SCREEN_OFF,
        NIGHT_ON,
        NIGHT_OFF,
        CONFIG_CHANGE,
        ;

        internal fun send(android: AndroidControlExtras) {
            when (this) {
                // -1 = "flip between the current orientation and its opposite".
                ROTATE -> android.sendRotate(-1)
                POWER -> android.sendPower()
                SCREEN_ON -> android.sendScreenPower(Display.STATE_ON.toInt())
                SCREEN_OFF -> android.sendScreenPower(Display.STATE_OFF.toInt())
                NIGHT_ON -> android.sendNightMode(NIGHT_MODE_YES)
                NIGHT_OFF -> android.sendNightMode(NIGHT_MODE_NO)
                CONFIG_CHANGE -> android.sendConfigChange(0)
            }
        }
    }

    /**
     * Blocks the sender thread for [delayMs], which keeps every event queued
     * afterwards from reaching the host earlier than intended. Used to shape
     * click timing (hold duration, spacing between the two clicks of a
     * double-click) since the host measures those intervals itself.
     */
    fun insertDelay(delayMs: Long) {
        execute { runCatching { Thread.sleep(delayMs) } }
    }

    /**************************************************************************
     * Misc
     **************************************************************************/

    /**
     * Clipboard of the connected protocol, when the protocol has none of its own.
     * GameStream carries no clipboard message, so the session layer hangs a side RFB
     * connection off this - see [RfbClipboardBridge]. Null for every other protocol,
     * which is how [sendClipboardText] finds its way back to the client's own path.
     */
    var clipboardBridge: RfbClipboardBridge? = null

    /**
     * Puts [text] on the host's clipboard.
     *
     * @return false only when neither the bridge nor the sender thread could take it,
     *         i.e. nothing reached the host at all.
     */
    fun sendClipboardText(text: String): Boolean {
        clipboardBridge?.let { if (it.sendCutText(text)) return true }
        return execute { client.sendCutText(text) }
    }

    fun setDesktopSize(width: Int, height: Int) {
        execute { client.setDesktopSize(width, height) }
    }

    /**
     * Gives up on a [setDesktopSize] which the host never answered. Queued like every
     * other send: the library's resize guard is cleared on the sender thread, which is
     * also the thread the follow-up update request belongs to.
     */
    fun cancelDesktopSizeChange() {
        execute { client.cancelDesktopSizeChange() }
    }

    fun refreshFrameBuffer() {
        execute { client.refreshFrameBuffer() }
    }

    fun setFrameBufferUpdatesPaused(pause: Boolean) {
        execute { client.setFrameBufferUpdatesPaused(pause) }
    }
}