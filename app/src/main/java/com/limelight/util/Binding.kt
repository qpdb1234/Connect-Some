/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.content.Context
import com.limelight.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.SimpleAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import com.limelight.R
import com.limelight.model.ServerProfile


@BindingAdapter("isVisible")
fun visibilityAdapter(view: View, isVisible: Boolean) {
    view.isVisible = isVisible
}

@BindingAdapter("isInvisible")
fun invisibilityAdapter(view: View, isInvisible: Boolean) {
    view.isInvisible = isInvisible
}

@BindingAdapter("backgroundAlpha")
fun backgroundAlphaAdapter(view: View, alpha: Double) {
    view.background?.alpha = (alpha * 255).toInt().coerceIn(0, 255)
}

/**
 * Usually, the default android:enabled attribute is enough.
 * But this adapter also reduces the alpha value of the view when disabled.
 */
@BindingAdapter("isEnabled")
fun isEnabledAdapter(view: TextView, isEnabled: Boolean) {
    view.isEnabled = isEnabled
    view.alpha = if (isEnabled) 1f else .38f
}

/**
 * Protocol badge on a server card.
 *
 * A layout expression cannot do this: a ternary chain over three protocols would have
 * to be edited everywhere a badge appears, and the strings are resources rather than
 * values an expression can name. So the mapping lives here once, and [ServerProfile]
 * stays free of resource ids.
 */

/**
 * Returns the protocol's display name, checking for a user-configured override first.
 * When the custom name in preferences is blank the shipped label (RFB, GameStream, ADB)
 * is used instead.
 */
fun protocolDisplayName(context: Context, protocol: Int): String {
    val prefs = AppPreferences(context)
    val custom = when (protocol) {
        ServerProfile.PROTOCOL_VNC -> prefs.server.protocolVncBadge
        ServerProfile.PROTOCOL_ANDROID -> prefs.server.protocolAndroidBadge
        else -> prefs.server.protocolGameStreamBadge
    }
    return custom?.takeIf { it.isNotBlank() } ?: context.getString(protocolBadgeRes(protocol))
}

@BindingAdapter("protocolBadgeText")
fun protocolBadgeAdapter(view: TextView, protocol: Int) {
    view.text = protocolDisplayName(view.context, protocol)
}

/** The badge string resource for a protocol; the one place the protocol -> display-name map lives. */
fun protocolBadgeRes(protocol: Int): Int = when (protocol) {
    ServerProfile.PROTOCOL_VNC -> R.string.protocol_badge_vnc
    ServerProfile.PROTOCOL_ANDROID -> R.string.protocol_badge_android
    else -> R.string.protocol_badge_game_stream
}

/**************************************************************************************************
 * Spinner value binding
 * These allows the Spinner to be populated using data-binding in XMl layouts.
 * There are 4 attributes used for this:
 *
 * app:value         => Reference to the backing field which actually stores the selected value.
 *                      This can be used with two-way binding to update the backing field whenever
 *                      selection changes in Spinner.
 *                      For now, Only String & Int types are supported for backing field.
 *
 * app:values        => String Array, holds possible values of app:value
 *
 * app:valueLabels   => String Array, Optional. If provided, these labels will be shown in the UI
 *                      instead of raw app:values
 *
 * app:valueDescriptions => String Array, Optional. Short descriptions of values, shown in Spinner
 *                          Popup below each label.
 *
 *
 *************************************************************************************************/
private fun prepareEntryMap(labels: Array<String>, descriptions: Array<String>?) = mutableListOf<Map<String, *>>().apply {
    for (i in labels.indices)
        add(mapOf("label" to labels[i], "description" to descriptions?.getOrNull(i)))
}

private class SpinnerAdapter(context: Context, labels: Array<String>, descriptions: Array<String>?, val values: Array<String>)
    : SimpleAdapter(context, prepareEntryMap(labels, descriptions), android.R.layout.simple_list_item_1,
                    arrayOf("label", "description"), intArrayOf(android.R.id.text1, android.R.id.text2))

@BindingAdapter("valueLabels", "valueDescriptions", "values", "value", requireAll = false)
fun spinnerValueAdapter(spinner: Spinner, labels: Array<String>?, descriptions: Array<String>?, values: Array<String>?, value: Any?) {
    if (spinner.adapter == null && values != null) {
        check(labels == null || labels.size == values.size)
        check(descriptions == null || descriptions.size == values.size)

        val adapter = SpinnerAdapter(spinner.context, labels ?: values, descriptions, values)
        if (descriptions != null)
            adapter.setDropDownViewResource(android.R.layout.simple_list_item_2)

        spinner.adapter = adapter
    }

    if (spinner.adapter != null && value != null) {
        // The adapter can only be ours when `values` was supplied, but a view restored from
        // a saved state may arrive with whatever `android:entries` put there. Casting blindly
        // crashed the editor; skipping leaves the selection at its default instead.
        val adapter = spinner.adapter as? SpinnerAdapter
        if (adapter == null) {
            Log.e("SpinnerValue", "value: $value cannot be applied to a foreign adapter")
        } else {
            val index = adapter.values.indexOf(value.toString())
            if (index != -1)
                spinner.setSelection(index)
            else
                Log.e("SpinnerValue", "value: $value not found in adapter values: ${adapter.values}")
        }
    }
}

@BindingAdapter("valueAttrChanged")
fun spinnerValueChangedAdapter(spinner: Spinner, valueChange: InverseBindingListener) {
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) = valueChange.onChange()
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

/**
 * Value currently selected in the spinner, or null when nothing can be trusted.
 *
 * An unselected AdapterView reports position -1 (empty adapter, or a selection reset by
 * a detach), and indexing straight into `values` then threw. Returning null lets the callers
 * fall back instead of writing garbage into the bound field.
 */
private fun selectedValue(spinner: Spinner): String? {
    val adapter = spinner.adapter as? SpinnerAdapter ?: return spinner.selectedItem?.toString()
    val position = spinner.selectedItemPosition
    return if (position in adapter.values.indices) adapter.values[position] else adapter.values.firstOrNull()
}

@InverseBindingAdapter(attribute = "value")
fun spinnerValueInverseAdapter(spinner: Spinner): String {
    return selectedValue(spinner) ?: ""
}

@InverseBindingAdapter(attribute = "value")
fun spinnerValueInverseAdapterInt(spinner: Spinner): Int {
    // Values are app-provided string arrays, so a non-numeric entry is a configuration slip
    // rather than user input. Still: `toInt` on it used to crash, whereas the first value in
    // the list is the widget's own default.
    return selectedValue(spinner)?.toIntOrNull() ?: 0
}