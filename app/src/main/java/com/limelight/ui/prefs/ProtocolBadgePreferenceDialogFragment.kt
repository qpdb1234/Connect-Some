/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import com.limelight.R

class ProtocolBadgePreferenceDialogFragment : PreferenceDialogFragmentCompat() {

    private var checkedIndex = -1
    private lateinit var editText: EditText

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val pref = preference as? ProtocolBadgePreference ?: return
        val entries = pref.entries
        val values = pref.entryValues
        val currentValue = pref.selectedValue()

        val customIndex = entries.size - 1
        checkedIndex = values.indexOf(currentValue).let { if (it < 0) customIndex else it }

        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val dp16 = (16 * density).toInt()
        val rowHeight = (48 * density).toInt()

        editText = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP)
            setHint(R.string.pref_protocol_badge_edit_hint)
            val current = if (checkedIndex < 0 || checkedIndex == customIndex) currentValue else ""
            setText(current)
            setSelection(text.length)
        }

        // The free-text box sits under the choices and shows only for "Custom". It lives in the
        // same panel as the list instead of a second builder.setView slot: a hidden view in its
        // own panel still reserves a strip of blank space above the buttons - the gap this dialog
        // used to show. Collapsing the container to GONE leaves nothing behind.
        val editContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // No gap of its own above the box: the choice row already ends in the slack a 48dp
            // centred row leaves under its label, and the edit box adds its own internal padding on
            // top of that. Setting the panel's 16dp as well put roughly 50dp of nothing between
            // "Custom" and the field that belongs to it, which read as a separate, unrelated part
            // of the dialog rather than as the same option opened up.
            setPadding(dp16, 0, dp16, 0)
            addView(editText)
            visibility = if (checkedIndex == customIndex) View.VISIBLE else View.GONE
        }

        // The choices are drawn as a plain radio group rather than builder.setSingleChoiceItems,
        // so the whole dialog body is one view. That lets the input box appear and disappear
        // inside it without the framework holding room for a separate list panel.
        val choices = RadioGroup(ctx).apply { orientation = LinearLayout.VERTICAL }
        val rowIds = ArrayList<Int>()
        entries.forEach { label ->
            val row = RadioButton(ctx).apply {
                id = View.generateViewId()
                text = label
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = rowHeight
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP)
                setPadding(dp16, 0, dp16, 0)
            }
            rowIds.add(row.id)
            choices.addView(row)
        }
        choices.check(rowIds[checkedIndex])
        choices.setOnCheckedChangeListener { _, checkedId ->
            val which = rowIds.indexOf(checkedId)
            if (which >= 0) {
                checkedIndex = which
                editContainer.visibility = if (which == customIndex) View.VISIBLE else View.GONE
            }
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(choices)
            addView(editContainer)
        }

        builder.setView(root)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val pref = preference as? ProtocolBadgePreference ?: return
        if (!positiveResult) return

        val customIndex = pref.entries.size - 1
        if (checkedIndex == customIndex) {
            pref.selectValue(editText.text.toString().trim())
        } else {
            val values = pref.entryValues
            if (checkedIndex in values.indices) {
                pref.selectValue(values[checkedIndex].toString())
            }
        }
    }

    companion object {
        /**
         * Size used by both halves of the panel: the choices and the free-text box.
         *
         * Built against the Material theme, a RadioButton inherits 14sp while a bare EditText
         * inherits 18sp, so one dialog showed its list and its input in two different sizes. 18sp
         * is what every other choice dialog in the settings renders (the platform's single-choice
         * rows), and this panel replaced one of those, so it takes that size rather than pulling
         * the input down to the smaller label.
         */
        private const val ROW_TEXT_SP = 18f

        fun newInstance(key: String): ProtocolBadgePreferenceDialogFragment {
            val fragment = ProtocolBadgePreferenceDialogFragment()
            val args = Bundle()
            args.putString(ARG_KEY, key)
            fragment.arguments = args
            return fragment
        }
    }
}