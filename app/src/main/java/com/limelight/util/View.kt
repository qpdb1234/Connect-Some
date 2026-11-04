/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.animation.LayoutTransition
import android.view.View
import android.view.ViewGroup


/**
 * Enables animations for layout changes inside [viewGroup].
 *
 * Same as setting android:animateLayoutChanges="true" in XML,
 * but ensures parents of [viewGroup] are not affected by any animation.
 * Parent animations can cause wierd artifacts & possibly ANRs in certain scenarios.
 *
 * This should NOT be used if [viewGroup] size depends on layout of child views.
 */
fun enableChildLayoutTransitions(viewGroup: ViewGroup) {
    debugCheck(viewGroup.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT)
    debugCheck(viewGroup.layoutParams?.width != ViewGroup.LayoutParams.WRAP_CONTENT)

    viewGroup.layoutTransition = LayoutTransition().apply {
        setAnimateParentHierarchy(false)
    }
}

/**
 * Give every labeled control a long-press tooltip that names it.
 *
 * The toolbar and the mini window are columns of bare icons: the only place a button says
 * what it does is the [View.contentDescription] written for screen readers, which a touch
 * user never hears. Copying it into [View.tooltipText] makes the platform show that same name
 * as a tooltip when the control is held down.
 *
 * minSdk is 26, so this leans on the platform's own tooltip handling rather than
 * [androidx.core.view.TooltipCompat] - whose pre-26 fallback installs a long-click listener of
 * its own and would take the long press away from any control that still uses it for a real
 * action. A control that is not already long-clickable is made so here,
 * because the platform only runs its long-press detection - and therefore the tooltip - for
 * long-clickable views, and a plain [android.widget.ImageButton] is not one by default.
 *
 * Recurses through [root]. An existing [View.tooltipText] is left untouched, so a control can
 * still be given a more specific hint in XML.
 */
fun installTooltips(root: View) {
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) installTooltips(root.getChildAt(i))
    }

    val name = root.contentDescription
    if (!name.isNullOrEmpty() && root.tooltipText.isNullOrEmpty()) {
        root.tooltipText = name
        root.isLongClickable = true
    }
}