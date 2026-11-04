/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build.VERSION.SDK_INT
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.limelight.util.addOnGlobalLayoutListener
import kotlin.math.max

/**
 * This class is responsible for managing fullscreen layout & insets handling.
 * Layout handling is quite complex because of Android APIs (or lack thereof).
 * It gets way worse here because AVNC shows fullscreen content.
 *
 * Situation is so bad, it's almost funny.
 *
 * Almost every Android version has introduced some new APIs, deprecated others,
 * or downright changed existing behaviour (sometimes just for ONE version).
 * AndroidX compat library has been the only respite. It has at least hidden a
 * bunch of if-else statement from our own code. But all that mess is still there.
 */
class LayoutManager(private val activity: VncActivity) {
    private val viewModel = activity.viewModel
    private val rootView = activity.binding.viewerRoot
    // The viewport the frame is fitted into is the clip container that holds whichever
    // presenter is active (GL FrameView or the direct-scan StreamSurfaceView), not a specific
    // presenter view: the direct-scan view gets resized/moved by the zoom, and the GL view is
    // `gone` there, so measuring either of them would be wrong. For VNC the container is the
    // same match_parent box the GL view fills, so this is equivalent to what it measured before.
    private val frameContainer = activity.binding.frameContainer
    private val window = activity.window
    private val insetController = WindowCompat.getInsetsController(window, window.decorView)


    fun initialize() {
        if (SDK_INT >= 30)
            hookInsetsListener()
        else
            hookSystemUiChangeListener()

        hookGlobalLayoutListener()
    }

    fun onConnectionStateChanged() {
        updateFullscreen()
    }

    @RequiresApi(30)
    private fun hookInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            updateWindowInsets(insets)
            updateNavigationBarVisibility(insets)
            ViewCompat.onApplyWindowInsets(v, insets)
        }
    }

    private fun hookGlobalLayoutListener() {
        addOnGlobalLayoutListener(activity, rootView) {
            // While the mini window is up it owns the geometry: the frame is scaled and
            // positioned for that box, and the remote desktop must not be resized to it.
            // MiniWindow puts these values back when the window folds away.
            if (!activity.miniWindow.isVisible) {
                viewModel.frameState.setWindowSize(rootView.width.toFloat(), rootView.height.toFloat())
                viewModel.frameState.setViewportSize(frameContainer.width.toFloat(), frameContainer.height.toFloat())
            }
            activity.virtualKeys.container?.let { updateVirtualKeyInsets(it) }

            if (SDK_INT < 30)
                manuallyGenerateWindowInsets()

            applyInsets()
            if (!activity.miniWindow.isVisible) {
                viewModel.resizeRemoteDesktop()
                // A viewport change re-fits the frame (base scale / pan clamping). The GL path
                // picks this up on the next decoded frame; a direct-scan surface has no such
                // pull, so it has to be nudged to re-apply its layout here.
                viewModel.refreshFrameView()
            }
        }
    }

    /**
     * System's WindowInsets are basically useless for us on API < 30. Neither are
     * they dispatched in all cases, nor they always include necessary information.
     * So, on these platforms, insets are generated manually.
     *
     * We could apply required padding directly here, but simulating insets means that
     * [applyInsets] has a single implementation for all platforms.
     */
    private fun manuallyGenerateWindowInsets() {
        val decorView = window.decorView
        val visibleFrame = Rect()
        decorView.getWindowVisibleDisplayFrame(visibleFrame)

        // Transform from screen coordinates to window coordinates
        // Both can be different, e.g. when in PiP mode
        val locationOnScreen = intArrayOf(0, 0)
        decorView.getLocationOnScreen(locationOnScreen)
        visibleFrame.offset(-locationOnScreen[0], -locationOnScreen[1])

        // Generate insets (left & top are always 0)
        val right = max(0, decorView.right - visibleFrame.right)
        val bottom = max(0, decorView.bottom - visibleFrame.bottom)
        val insets = WindowInsetsCompat.Builder()
                .setInsets(Type.ime(), Insets.of(0, 0, 0, bottom))
                .setInsets(Type.navigationBars(), Insets.of(0, 0, right, 0))
                .build()

        updateWindowInsets(insets)
    }

    /**
     * Flags to hide system bars are not 'sticky' on API < 30. System will automatically
     * clear them in a lot of situations, e.g. when IME is shown. So we have to keep
     * reminding it that we still want to remain fullscreen.
     * Same reason applies to window focus.
     */
    private fun hookSystemUiChangeListener() {
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { updateFullscreen() }

        viewModel.hasWindowFocus.observe(activity) { if (it == true) updateFullscreen() }
    }


    /************************************************************************************
     * Fullscreen
     ************************************************************************************/
    private val fullscreenEnabled = viewModel.pref.viewer.fullscreen
    private val defaultSystemBarBehaviour = insetController.systemBarsBehavior

    private fun updateFullscreen() {
        if (!fullscreenEnabled)
            return

        if (viewModel.connected)
            enterFullscreen()
        else
            leaveFullscreen()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetController.hide(Type.systemBars())
        insetController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (SDK_INT >= 28) {
            // Without this the window is refused in the notch band and sits below it, so the
            // drawer's centering region is shorter than the screen and its column of buttons
            // can never be centered on the display the user actually sees. SHORT_EDGES lets
            // the immersive window cover the full display; the toolbar's own cutout handling
            // (Toolbar.handleInsets) keeps the buttons out of the notch when asked.
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        if (SDK_INT < 30) {
            // This flag is required to keep the status bar hidden when IME is visible
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun leaveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        insetController.show(Type.systemBars())
        insetController.systemBarsBehavior = defaultSystemBarBehaviour

        if (SDK_INT >= 28) {
            // Undo the notch extension: outside fullscreen the activity should fit its
            // content below the status bar like any ordinary window.
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = lp
        }

        if (SDK_INT < 30) {
            // enterFullscreen() added this flag; it is not cleared by showing the bars,
            // so a disconnect on an old device left the status bar hidden for the rest of
            // the activity's life - including over the toolbar and the virtual keys.
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    @RequiresApi(30)
    private fun updateNavigationBarVisibility(insets: WindowInsetsCompat) {
        // On API 30, Android doesn't automatically show the navigation bar with keyboard.
        // So to hide the keyboard, user has to first swipe to un-hide the navigation bar
        // and then tap on Back button. To avoid this, we manually show the navigation bar
        // whenever keyboard is visible. Although only API 30 seems to be affected, fix is
        // applied on 30+ APIs to ensure consistency.
        if (insets.isVisible(Type.ime()))
            insetController.show(Type.navigationBars())
        else if (fullscreenEnabled && viewModel.connected) {
            insetController.hide(Type.navigationBars())
        }
    }

    /************************************************************************************
     * Insets
     *
     * Insets are Android's way of telling us when our window is obscured by something.
     * To provide optimal experience, insets are divided into two categories:
     *
     * Opaque Insets: These insets don't allow interaction with app's window behind them.
     * IME & nav bar are such insets. These are handled by adding padding to [rootView],
     * effectively resizing [frameView] to visible portion of the screen.
     *
     * Safe Area Insets: These obstructs [frameView] only partially, e.g. display cutout.
     * We want the frame to be rendered behind these cutouts to provide fully immersive
     * experience, but also allow user to pan out of these insets when they need to
     * interact with remote content. These are implemented as 'safe area' in [FrameState].
     *
     * All insets are in window coordinates.
     *
     ************************************************************************************/
    private var windowInsets = WindowInsetsCompat.Builder().build()
    private var virtualKeyInsets = Insets.NONE   // Insets caused by virtual keys

    private fun updateWindowInsets(insetsCompat: WindowInsetsCompat) {
        // Copy constructor of WindowInsetsCompat is partially broken on API below 30.
        // We are manually generating insets on API <30 anyway, so don't need to create a copy.
        windowInsets = if (SDK_INT < 30) insetsCompat else WindowInsetsCompat(insetsCompat)
    }

    private fun shouldGenerateVirtualKeyInsets(): Boolean {
        // Virtual key insets allow to move the frame up to access the area hidden behind keys.
        // But in landscape mode, when touchpad gesture style is active, they cause unnecessary
        // frame movement when pointer moves around. So insets are restricted to following cases:
        return rootView.width < rootView.height || // portrait mode
               viewModel.activeGestureStyle.value == "touchscreen"
    }

    private fun updateVirtualKeyInsets(vkRoot: View) {
        if (vkRoot.isVisible && shouldGenerateVirtualKeyInsets()) {
            val vkLocation = intArrayOf(0, 0)
            vkRoot.getLocationInWindow(vkLocation)
            val b = max(0, window.decorView.height - vkLocation[1])

            if (virtualKeyInsets.bottom != b)
                virtualKeyInsets = Insets.of(0, 0, 0, b)
        } else {
            virtualKeyInsets = Insets.NONE
        }
    }

    private fun applyInsets() {
        val opaqueInsets = listOf(windowInsets.getInsets(Type.ime()), windowInsets.getInsets(Type.navigationBars()))
        val maxOpaqueInsets = opaqueInsets.fold(Insets.NONE) { a, i -> Insets.max(a, i) }
        applyOpaqueInsets(maxOpaqueInsets)

        //val cutoutInsets = windowInsets.getInsets(Type.displayCutout())
        //val cornerInsets = calculateCornerInsets(windowInsets)
        val safeAreaInsets = listOf(maxOpaqueInsets, /*cutoutInsets, cornerInsets,*/ virtualKeyInsets)
        val maxSafeAreaInsets = safeAreaInsets.fold(Insets.NONE) { a, i -> Insets.max(a, i) }
        applySafeAreaInsets(maxSafeAreaInsets)

        activity.toolbar.handleInsets(windowInsets)
        applyStatsOverlayInsets()
    }

    /**
     * The margin the layout asked for the performance readout, remembered before any inset was
     * folded into it. Read off the view rather than repeated here, so the layout stays the one
     * place that decides how much air the overlay gets.
     */
    private var statsOverlayBaseMargin = -1

    /**
     * Keeps the performance readout clear of the front camera.
     *
     * The overlay sits at `top|start` with nothing but its own margin, which on a punch-hole
     * phone puts the numbers straight over the camera: that glass is not ours to draw on, so
     * whatever lands there is simply gone from the user's view. The window reports the region
     * to avoid, and the shift is added to the layout's margin instead of replacing it - the
     * readout keeps its breathing room on a plain display, and a cutout that reaches further
     * than the margin still ends up outside it.
     *
     * Only the two edges the view is actually anchored to are moved. The offset is measured
     * against the parent's own position in the window rather than assumed to be the origin, so
     * a non-fullscreen window (which fits below the status bar) or a container that grows
     * padding shifts nothing, instead of pushing the readout one margin too far down.
     */
    private fun applyStatsOverlayInsets() {
        val overlay = activity.binding.statsOverlay
        val lp = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
        if (statsOverlayBaseMargin < 0) statsOverlayBaseMargin = lp.marginStart

        // The cutout comes from the window's own record rather than from [windowInsets]: below
        // API 30 those insets are generated by hand (see [manuallyGenerateWindowInsets]) and
        // describe only the IME and the navigation bar, so a punch-hole camera would never be
        // reported to this overlay on exactly the devices that have one. `rootWindowInsets`
        // bypasses that, and it is also not affected by whatever an ancestor already consumed.
        val cutout = (ViewCompat.getRootWindowInsets(overlay) ?: windowInsets)
                .getInsets(Type.displayCutout())
        val forbidden = Insets.max(cutout, windowInsets.getInsets(Type.systemBars()))
        val parentLocation = intArrayOf(0, 0)
        (overlay.parent as? View)?.getLocationInWindow(parentLocation)

        val start = statsOverlayBaseMargin + (forbidden.left - parentLocation[0]).coerceAtLeast(0)
        val top = statsOverlayBaseMargin + (forbidden.top - parentLocation[1]).coerceAtLeast(0)
        if (lp.marginStart != start || lp.topMargin != top) {
            lp.marginStart = start
            lp.topMargin = top
            overlay.layoutParams = lp
        }
    }

    private fun applyOpaqueInsets(opaqueInsets: Insets) {
        // Guess if IME is closing
        if (!windowInsets.isVisible(Type.ime()) && rootView.paddingBottom != 0)
            activity.virtualKeys.onKeyboardClose()

        val insets = windowInsetsToViewInsets(opaqueInsets, rootView)
        if (rootView.paddingRight != insets.right || rootView.paddingBottom != insets.bottom)
            rootView.updatePadding(0, 0, insets.right, insets.bottom)
    }

    private fun applySafeAreaInsets(safeAreaInsets: Insets) {
        val insets = windowInsetsToViewInsets(safeAreaInsets, frameContainer)
        val safeArea = Rect(insets.left,
                            insets.top,
                            frameContainer.width - insets.right,
                            frameContainer.height - insets.bottom)

        viewModel.setSafeArea(RectF(safeArea))
    }

    /**
     * Returns insets caused by rounded corners.
     *
     * Android does not provide pre-calculated insets for rounded screen corners.
     * Information about corners is available since API 31. We use this information
     * to calculate effective insets.
     */
    private fun calculateCornerInsets(windowInsetsCompat: WindowInsetsCompat): Insets {
        val windowInsets = windowInsetsCompat.toWindowInsets()
        if (SDK_INT < 31 || windowInsets == null)
            return Insets.NONE

        val wWidth = window.decorView.width
        val wHeight = window.decorView.height

        var l = 0
        var t = 0
        var r = 0
        var b = 0

        windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.center?.let {
            t = max(t, it.y)
            l = max(l, it.x)
        }
        windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.center?.let {
            t = max(t, it.y)
            r = max(r, wWidth - it.x)
        }
        windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.center?.let {
            b = max(b, wHeight - it.y)
            l = max(l, it.x)
        }
        windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.center?.let {
            b = max(b, wHeight - it.y)
            r = max(r, wWidth - it.x)
        }

        // Corner insets are only applied along longer axis. Applying along both axis is unnecessary.
        if (wWidth > wHeight)
            return Insets.of(l, 0, r, 0)
        else
            return Insets.of(0, t, 0, b)
    }

    /**
     * Transforms given insets [insets] from Window's coordinates to [view]'s coordinates
     */
    private fun windowInsetsToViewInsets(insets: Insets, view: View): Insets {
        val viewLocation = intArrayOf(0, 0)
        view.getLocationInWindow(viewLocation)

        // View frame in window coordinates
        val vl = viewLocation[0]
        val vt = viewLocation[1]
        val vr = vl + view.width
        val vb = vt + view.height

        // Insets applicable to given view
        val l = max(0, insets.left - vl)
        val t = max(0, insets.top - vt)
        val r = max(0, insets.right - (window.decorView.width - vr))
        val b = max(0, insets.bottom - (window.decorView.height - vb))

        return Insets.of(l, t, r, b)
    }
}