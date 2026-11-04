/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import com.limelight.R

/**
 * An action the mini window's top-bar slots or the bottom handle's double tap can be set to,
 * in the style of Mi-Freeform's freeform window: its top bar is three equal segments whose
 * icons come from a preference rather than from the layout, so every button is whatever the
 * user decided it should be.
 *
 * Values are stored as the preference key (a string, the way this app stores every
 * ListPreference choice), not as an ordinal - a reordering of [values] must not silently
 * remap somebody's settings.
 */
enum class MiniBarAction(val key: String, val iconRes: Int, val labelRes: Int) {

    /** Draws nothing in its slot: the position stays empty but still swallows taps. */
    NONE("none", 0, R.string.desc_mini_none),

    CLOSE("close", R.drawable.ic_clear, R.string.desc_mini_close),
    FULL_SCREEN("fullscreen", R.drawable.ic_mini_fullscreen, R.string.desc_mini_fullscreen),

    /** Folds the window into the edge tab, which is what Mi-Freeform calls minimize. */
    MINIMIZE("minimize", R.drawable.ic_mini_collapse, R.string.desc_mini_collapse),

    /** Returns to the full-screen viewer, leaving the stream where it was. */
    EXPAND("expand", R.drawable.ic_mini_window, R.string.desc_mini_expand),

    BACK("back", R.drawable.ic_arrow_back, R.string.desc_mini_back),
    HOME("home", R.drawable.ic_mini_home, R.string.desc_mini_home),
    RECENTS("recents", R.drawable.ic_mini_overview, R.string.desc_mini_overview),
    KEYBOARD("keyboard", R.drawable.ic_keyboard, R.string.desc_mini_keyboard),

    /** Shows or hides the remote navigation row under the picture. */
    TOGGLE_NAV_BAR("toggle-navbar", R.drawable.ic_mini_navbar_toggle, R.string.desc_mini_navbar),

    /** Restores the remembered size and position of this window shape. */
    RESET("reset", R.drawable.ic_mini_reset, R.string.desc_mini_reset),
    ROTATE("rotate", R.drawable.ic_screen_rotation, R.string.desc_mini_rotate),
    SCREEN_ON("screen-on", R.drawable.ic_lightbulb, R.string.desc_mini_screen_on),
    SCREEN_OFF("screen-off", R.drawable.ic_lightbulb_off, R.string.desc_mini_screen_off),
    POWER("power", R.drawable.ic_power, R.string.desc_mini_power);

    companion object {
        /** The slots a fresh install starts with, matching Mi-Freeform's own defaults:
         *  close / full screen / minimize on top, keyboard on the double-tapped handle. */
        val DEFAULT_TOP_SLOTS = listOf(CLOSE, FULL_SCREEN, MINIMIZE)
        val DEFAULT_DOUBLE_TAP = KEYBOARD

        /** Anything unparseable is 'no action', never a accidental misfire. */
        fun fromKey(stored: String?): MiniBarAction =
                entries.firstOrNull { it.key == stored } ?: NONE
    }
}
