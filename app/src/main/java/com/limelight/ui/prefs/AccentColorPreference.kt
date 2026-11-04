/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference
import com.limelight.R
import com.limelight.util.AccentTheme

/**
 * The "Interface color" setting. The picker dialog itself lives in
 * AccentColorPreferenceDialogFragment; the chosen color is persisted as a hex string
 * and rendered by AccentTheme's nearest pre-generated theme variant.
 */
class AccentColorPreference(context: Context, attrs: AttributeSet) : DialogPreference(context, attrs) {

    fun selectedColor(): Int = AccentTheme.argbOf(getPersistedString(null))

    fun selectColor(argb: Int) {
        if (argb != selectedColor()) {
            persistString(AccentTheme.hexOf(argb))
            notifyChanged()
        }
    }

    override fun getSummary(): CharSequence = AccentTheme.hexOf(selectedColor())
}
