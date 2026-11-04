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
import androidx.annotation.ArrayRes
import androidx.preference.DialogPreference
import com.limelight.R

class ProtocolBadgePreference(context: Context, attrs: AttributeSet) : DialogPreference(context, attrs) {

    @ArrayRes
    private var entriesRes: Int = 0

    @ArrayRes
    private var entryValuesRes: Int = 0

    val entries: Array<out CharSequence>
        get() = context.resources.getStringArray(entriesRes)

    val entryValues: Array<out CharSequence>
        get() = context.resources.getStringArray(entryValuesRes)

    init {
        context.obtainStyledAttributes(attrs, R.styleable.ProtocolBadgePreference).apply {
            entriesRes = getResourceId(R.styleable.ProtocolBadgePreference_entries, 0)
            entryValuesRes = getResourceId(R.styleable.ProtocolBadgePreference_entryValues, 0)
            recycle()
        }
        positiveButtonText = context.getString(android.R.string.ok)
        negativeButtonText = context.getString(android.R.string.cancel)
    }

    fun selectedValue(): String {
        val ev = entryValues
        val default = if (ev.isNotEmpty()) ev.first().toString() else ""
        return getPersistedString(default)
    }

    fun selectValue(value: String) {
        if (value != selectedValue()) {
            persistString(value)
            notifyChanged()
        }
    }

    override fun getSummary(): CharSequence {
        val v = selectedValue()
        val entries = entries
        return v.ifBlank { if (entries.isNotEmpty()) entries.first() else "" }
    }
}