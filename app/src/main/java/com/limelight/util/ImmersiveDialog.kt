/*
 * Shared handling for the dialogs that appear on top of a fully immersive surface.
 *
 * A dialog is its own focused window. The instant it takes focus, Android re-reveals the system
 * bars - so on a device that hides the navigation bar for a fullscreen stream (the viewer), a
 * plain [AlertDialog.show] makes the nav keys jump back into view. Hiding them only after the
 * dialog is shown is too late on some OEMs (Huawei in particular), which is why the window is
 * first made non-focusable - it cannot steal focus, so the host's hidden state holds - carries
 * the same immersive state itself, and is only handed focus once it is on screen.
 *
 * [ImmersiveDialogHost] is the single signal for "this activity is currently immersive, so keep
 * its dialogs immersive too". [com.limelight.ui.vnc.VncActivity] is the surface that hides the
 * bars, and [onScreenImmersiveHost] is how it says so from behind another window: the floating
 * LocalSend card raises its dialogs through a transparent Activity of its own, which must answer
 * for the stream underneath rather than for itself. The standalone screens leave both unset, so
 * their dialogs behave normally.
 */
package com.limelight.util

import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** A host that can raise dialogs over a system-bar-hidden surface and wants them to keep it hidden. */
interface ImmersiveDialogHost {
    /** Whether dialogs raised right now should preserve the hidden system bars. */
    val immersiveDialogs: Boolean
}

/**
 * The host whose bars-hidden surface is currently on screen, published between its `onStart` and
 * `onStop` - deliberately not the narrower focus window. The viewer keeps its state for a dialog
 * raised while it merely sits paused under a transparent host: that host steals the stream's
 * focus without ever covering it, and the bars have to stay hidden through the visit.
 */
var onScreenImmersiveHost: ImmersiveDialogHost? = null

/** Whether a dialog raised right now lands on an on-screen surface that keeps the bars hidden. */
val onScreenImmersiveDialogs: Boolean
    get() = onScreenImmersiveHost?.immersiveDialogs == true

/**
 * Makes [this] window hide the system bars for as long as it lives, the same state the host's
 * window holds. A window that takes focus while not asking for them makes the system re-reveal
 * them the moment it wins focus - even a fully transparent one that only ever carries dialogs.
 */
fun Window.keepHiddenBars() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    val controller = WindowInsetsControllerCompat(this, decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

/**
 * Puts [this] dialog's window into the pre-focus immersive state: non-focusable (so it does not
 * yet take focus and re-reveal the bars) and itself hiding the system bars. Pair with
 * [releaseImmersiveHold] once the dialog is on screen to hand it focus back.
 */
private fun Dialog.enterImmersiveHold() {
    val w = window ?: return
    w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    WindowCompat.setDecorFitsSystemWindows(w, false)
    val controller = WindowInsetsControllerCompat(w, w.decorView)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}

/** Hands a dialog held by [enterImmersiveHold] its focus back, now that it is visible. */
fun Dialog.releaseImmersiveHold() {
    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/**
 * Shows this dialog, keeping the host's hidden system bars hidden when [immersive] is set. With
 * [immersive] false this is an ordinary [AlertDialog.show].
 */
fun AlertDialog.showImmersiveIfNeeded(immersive: Boolean) {
    if (window == null || !immersive) {
        show()
        return
    }
    enterImmersiveHold()
    setOnShowListener { releaseImmersiveHold() }
    show()
}

/** [showImmersiveIfNeeded] driven by whether [activity] is an immersive [ImmersiveDialogHost]. */
fun AlertDialog.showImmersiveIfNeeded(activity: Activity) =
        showImmersiveIfNeeded((activity as? ImmersiveDialogHost)?.immersiveDialogs == true)

/**
 * The [DialogFragment] form: call from `onCreateDialog` to hold the window before it is shown,
 * and from `onStart` (via [releaseImmersiveHold]) to release it. No-op when [immersive] is false.
 */
fun Dialog.holdImmersiveIfNeeded(immersive: Boolean) {
    if (immersive) enterImmersiveHold()
}
