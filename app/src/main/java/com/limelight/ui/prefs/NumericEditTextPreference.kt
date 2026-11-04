/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import com.limelight.util.AppPreferences

/**
 * Preference for a single whole number, shown with a numeric keypad and stored inside
 * [min]..[max].
 *
 * AndroidX keeps these preferences as text and offers no validation hook, so the clamp
 * lives in [setText] - the one funnel that every edit, and the initial load, passes
 * through. Input that is not a number (an emptied field most of all) is refused outright
 * and leaves the previous value in place: storing zero instead would quietly switch off
 * whatever the number was limiting, through a preference that does not claim to control
 * that at all.
 */
class NumericEditTextPreference(context: Context, attrs: AttributeSet) : EditTextPreference(context, attrs) {

    var min = AppPreferences.CLIPBOARD_LIMIT_MIN
    var max = AppPreferences.CLIPBOARD_LIMIT_MAX

    init {
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            // Put the cursor after the current value: with a numeric dialog the user is
            // editing a number, not writing a sentence at position zero.
            editText.setSelection(editText.text.length)
        }
    }

    override fun setText(newText: String?) {
        val entered = newText?.trim()?.toIntOrNull() ?: return
        super.setText(entered.coerceIn(min, max).toString())
    }
}
