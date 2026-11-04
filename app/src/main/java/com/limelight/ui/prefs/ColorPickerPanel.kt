/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.limelight.R
import com.limelight.util.AccentTheme

/**
 * [ColorPickerView] plus a hex field, kept in sync in both directions: dragging the palette
 * rewrites the code, and typing a valid color code moves the markers. Accepting a typed code
 * is what lets the picker reach *every* color rather than only the ones a finger can land on
 * precisely - [#RGB], [#RRGGBB] and [#AARRGGBB], with or without the leading '#'.
 *
 * Callers get the exact chosen color from [currentColor]; how faithfully the app can render
 * it is their concern (the accent theme quantizes to its grid except for grays and the gold
 * default, which it renders literally, while the ball reproduces any color directly).
 *
 * A caller that has a default color can set [defaultColor], which shows the panel's extra
 * row. That row only moves the selection - the caller still applies it when the user
 * confirms, so a mis-tap on 'restore' cannot repaint anything behind the dialog.
 *
 * The three rows live inside a scroll view rather than directly in the panel, because a dialog
 * hands its custom view whatever height is left over - which in landscape is less than a palette
 * of any usable size needs, and everything under the wheel was simply cut off. Scrolling is the
 * caller's business no more: the panel always shows all of itself.
 *
 * In landscape it does better than that, because the wheel cannot shrink enough to leave room
 * for the rows under it and still be worth pointing at: the wide, short dialog puts the hex field
 * and the 'restore default' row beside the palette instead of under it, where they cost no height
 * at all. Portrait keeps the stack it always had.
 */
class ColorPickerPanel(context: Context) : LinearLayout(context) {

    private val picker = ColorPickerView(context)
    private val hex = EditText(context)
    private val restore = Button(context)

    // Guards the two-way wiring so reflecting a drag into the field does not bounce back
    // as an edit, and vice versa.
    private var syncing = false

    var quantized: Boolean
        get() = picker.quantized
        set(value) {
            picker.quantized = value
        }

    init {
        orientation = VERTICAL
        val pad = (resources.displayMetrics.density * 8).toInt()
        val beside = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // The two rows share one column so that the landscape layout has a single thing to put
        // next to the palette, and the portrait layout has a single thing to put under it.
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val column = LinearLayout(context).apply {
            orientation = if (beside) HORIZONTAL else VERTICAL
            gravity = if (beside) Gravity.CENTER_VERTICAL else Gravity.TOP or Gravity.START
            addView(picker, LayoutParams(
                    if (beside) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT))
            addView(rows, LayoutParams(
                    if (beside) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT).apply {
                if (beside) leftMargin = pad else topMargin = pad
            })
        }

        hex.apply {
            setSingleLine()
            hint = "#RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(9)) // #AARRGGBB is the longest form
            setHorizontallyScrolling(true)
            setPadding(pad, pad, pad, pad)
        }
        rows.addView(hex, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Hidden until a caller names the color it considers the default (see [defaultColor])
        // - a picker without a default must not show an empty gap.
        restore.isVisible = false
        restore.text = context.getString(R.string.action_restore_default_color)
        restore.setOnClickListener { defaultColor?.let { setArgb(it) } }
        rows.addView(restore, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // fillViewport off: the column keeps the height the palette asks for instead of being
        // stretched to the scroll view, which would leave a gap under the last row. The layout
        // parameters are a scroll view's own, which it insists on being frame parameters; only the
        // landscape pair wraps to its content, so it can be centered instead of hugging the left
        // edge, while portrait keeps the full width the palette has always been measured against.
        addView(ScrollView(context).apply {
            isFillViewport = false
            addView(column, FrameLayout.LayoutParams(
                    if (beside) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        picker.onColorChanged = { argb -> writeHex(argb) }
        hex.doAfterTextChanged { if (!syncing) readHex() }
    }

    /**
     * The color offered by the panel's 'restore default' row, or null while no such row is
     * wanted. Assigning a value shows the row; the row never applies the color by itself.
     */
    var defaultColor: Int? = null
        set(value) {
            field = value
            restore.isVisible = value != null
        }

    fun currentColor(): Int = picker.currentColor()

    /** Selects [argb] on the palette and shows its code. */
    fun setArgb(argb: Int) {
        picker.setArgb(argb)
        writeHex(argb)
    }

    private fun writeHex(argb: Int) {
        syncing = true
        hex.setText(formatHex(argb))
        hex.setSelection(hex.text.length)
        syncing = false
    }

    /**
     * Moves the markers onto a typed code, but only once it is complete and valid - a half
     * typed "#12" leaves the last good selection alone rather than jumping to a wrong color.
     */
    private fun readHex() {
        val argb = parseHex(hex.text.toString()) ?: return
        syncing = true
        picker.setArgb(argb)
        syncing = false
    }

    /** #RRGGBB when opaque, #AARRGGBB otherwise - the shortest form that is unambiguous. */
    private fun formatHex(argb: Int): String =
        if (Color.alpha(argb) == 255) String.format("#%06X", 0xFFFFFF and argb) else AccentTheme.hexOf(argb)

    /** Parses #RGB / #RRGGBB / #AARRGGBB (leading '#' optional) into an ARGB int, or null. */
    private fun parseHex(raw: String): Int? {
        var s = raw.trim().removePrefix("#")
        if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
        if (s.length == 6) s = "FF$s"
        if (s.length != 8) return null
        return s.toLongOrNull(16)?.toInt()
    }
}
