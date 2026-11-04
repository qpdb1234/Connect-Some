/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceDialogFragmentCompat
import com.limelight.util.AccentTheme

/**
 * The color picker dialog shown when AccentColorPreference is tapped. Dragging the
 * palette only moves the markers and updates the in-dialog preview; the choice is
 * persisted when OK is pressed, which recreates every activity through the live
 * preference. Cancel simply drops it.
 */
class AccentColorPreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private var picker: ColorPickerPanel? = null

    // Continuous mode: the whole color space is reachable (by dragging or by typing a code),
    // so any color code can be chosen. AccentTheme renders every gray (a neutral, black and
    // white included) and the gold default exactly, and any other color as its nearest
    // pre-generated variant.
    //
    // The panel's 'restore default' row is offered here because the theme does have one:
    // tapping it puts the champagne gold back on the markers, and nothing is repainted until
    // OK applies the choice like any other edit made in the dialog.
    override fun onCreateDialogView(context: Context): View =
        ColorPickerPanel(context).apply {
            quantized = false
            defaultColor = AccentTheme.DEFAULT_ARGB
            picker = this
        }

    // The stock dialog width is capped below the full screen width, which leaves the page
    // behind peeking out next to its right edge - a switch thumb reads as a stray second
    // marker circle floating beside the palette. Go full width; the dialog background's own
    // shadow inset provides the visible margin, which still covers any row widget behind it.
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            resources.displayMetrics.widthPixels,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        val pref = preference as AccentColorPreference
        picker?.setArgb(pref.selectedColor())
    }

    // PreferenceDialogFragmentCompat.onDialogClosed is abstract with no super behavior.
    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            picker?.let { (preference as AccentColorPreference).selectColor(it.currentColor()) }

            // Dismissing a dialog never pauses the activity, so AccentTheme's compare-on-resume
            // hook gets no chance to run: without this the screen behind the dialog would go on
            // painting with the old accent until the user left settings and came back. Posted
            // rather than called inline, to let the fragment finish its own teardown first - and
            // on an activity grabbed now, because by the time the runnable runs this fragment is
            // already detached.
            val host = requireActivity()
            host.window.decorView.post { host.recreate() }
        }
    }

    companion object {
        fun newInstance(key: String): AccentColorPreferenceDialogFragment {
            return AccentColorPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply { putString(ARG_KEY, key) }
            }
        }
    }
}
