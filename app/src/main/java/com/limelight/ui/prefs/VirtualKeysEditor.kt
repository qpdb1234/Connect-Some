/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.animation.LayoutTransition
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ToggleButton
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.setPadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.limelight.R
import com.limelight.databinding.FragmentVirtualKeysEditorBinding
import com.limelight.ui.vnc.VirtualKey
import com.limelight.ui.vnc.VirtualKeyLayoutConfig
import com.limelight.ui.vnc.VirtualKeyViewFactory
import com.limelight.util.AppPreferences
import com.limelight.util.FloatingText


/**
 * Editor for virtual key layout
 */
@Keep
class VirtualKeysEditor : Fragment() {

    // Wraps a VirtualKey & corresponding View
    private data class KeyWrapper(val vk: VirtualKey, val view: View)

    lateinit var binding: FragmentVirtualKeysEditorBinding
    private val focusOverlay by lazy { AppCompatResources.getDrawable(requireContext(), R.drawable.focus_overlay)!! }
    private val prefs by lazy { AppPreferences(requireContext()) }

    private val keyList = ArrayList<KeyWrapper>()
    private var focusedKey: KeyWrapper? = null

    override fun onResume() {
        super.onResume()
        activity?.setTitle(R.string.pref_key_layout)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentVirtualKeysEditorBinding.inflate(inflater, container, false)

        binding.keyGrid.rowCount = prefs.input.vkRowCount
        binding.keyGrid.orientation = GridLayout.VERTICAL
        binding.keyGrid.layoutTransition.apply {
            setDuration(150)
            setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 150)
            addTransitionListener(object : LayoutTransition.TransitionListener {
                override fun startTransition(t: LayoutTransition, c: ViewGroup, view: View, tt: Int) {}
                override fun endTransition(t: LayoutTransition, c: ViewGroup, view: View, tt: Int) {
                    if (view == focusedKey?.view)
                        refreshFocus()
                }
            })
        }

        keyList.clear()
        focusedKey = null

        // Populate keys
        val focusIndex = savedInstanceState?.getInt("focus_index") ?: -1
        val enabledKeys = restoredKeys(savedInstanceState) ?: VirtualKeyLayoutConfig.getLayout(prefs)
        enabledKeys.forEach { addNewKey(it) }
        if (focusIndex >= 0) {
            keyList.getOrNull(focusIndex)?.let { setFocusedKey(it.view) }
            binding.keyGrid.doOnLayout { refreshFocus() }
        }

        binding.addKeyBtn.setOnClickListener { showNewKeyPopup() }
        binding.moveUpBtn.setOnClickListener { focusedKey?.let { moveKey(it, -1) } }
        binding.moveDownBtn.setOnClickListener { focusedKey?.let { moveKey(it, 1) } }
        binding.deleteBtn.setOnClickListener { focusedKey?.let { removeKey(it) } }
        updateActionButtons()

        binding.saveBtn.setOnClickListener { saveKeys() }
        binding.loadDefaultsBtn.setOnClickListener { loadDefaults() }

        return binding.root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray("key_names", keyList.map { it.vk.name }.toTypedArray())
        outState.putInt("focus_index", keyList.indexOf(focusedKey))
    }

    /**
     * The keys this editor was showing before it got recreated, or null when there is no
     * such state (first open), which leaves [VirtualKeyLayoutConfig.getLayout] in charge.
     *
     * Names are looked up instead of run through `valueOf`, and repeats are folded: this
     * bundle survives an app update, and the enum explicitly allows a key to be renamed
     * later (see the note on [VirtualKey]). Both of the failures that would cause - an
     * unknown name and a duplicated one, which [addNewKey] refuses - used to be thrown
     * straight out of `onCreateView`, so the settings screen would crash on open, before
     * the user could do anything about it, on every attempt.
     */
    private fun restoredKeys(state: Bundle?): List<VirtualKey>? {
        val names = state?.getStringArray("key_names") ?: return null
        return names.mapNotNull { name -> VirtualKey.entries.firstOrNull { it.name == name } }.distinct()
    }

    private fun finish() {
        // Opened from inside settings this screen sits on top of the list, so stepping back
        // returns there. Opened straight from the viewer's key bar it is the whole activity:
        // popping an empty back stack would leave a settings screen with nothing in it.
        if (parentFragmentManager.backStackEntryCount > 0)
            parentFragmentManager.popBackStack()
        else
            requireActivity().finish()
    }

    private fun saveKeys() {
        val enabledKeys = keyList.map { it.vk }
        VirtualKeyLayoutConfig.setLayout(prefs, enabledKeys)
        FloatingText.show(requireView(), R.string.msg_saved, FloatingText.LENGTH_SHORT)
        finish()
    }

    private fun loadDefaults() {
        while (keyList.isNotEmpty())
            removeKey(keyList.last())
        VirtualKeyLayoutConfig.getDefaultLayout(prefs).forEach { addNewKey(it) }
    }

    private fun updateActionButtons() {
        val index = focusedKey?.let { keyList.indexOf(it) } ?: -1
        binding.moveUpBtn.isEnabled = index > 0
        binding.moveDownBtn.isEnabled = index >= 0 && index < (keyList.size - 1)
        binding.deleteBtn.isEnabled = index >= 0 && keyList.size > 1
    }

    private fun addNewKey(vk: VirtualKey): KeyWrapper {
        check(keyList.find { it.vk == vk } == null) { "Duplicate key detected" }

        // Previewed the way the viewer will draw them: modifiers as latching switches, the rest
        // as plain buttons, so the editor matches what the user sees over the stream.
        val view = VirtualKeyViewFactory.create(requireContext(), vk)
        binding.keyGrid.addView(view)
        view.contentDescription = vk.descriptionRes?.let { requireContext().getString(it) }
        view.setOnClickListener {
            if (focusedKey?.view == view) setFocusedKey(null) // Clear focus on 2nd click
            else setFocusedKey(view)
            (view as? ToggleButton)?.isChecked = false  // Keep toggle keys unchecked
        }

        val wrapper = KeyWrapper(vk, view)
        keyList.add(wrapper)

        updateActionButtons()
        return wrapper
    }

    private fun removeKey(key: KeyWrapper) {
        binding.keyGrid.removeView(key.view)
        keyList.remove(key)

        if (focusedKey == key)
            setFocusedKey(null)

        updateActionButtons()
    }

    private fun moveKey(key: KeyWrapper, delta: Int) {
        val currentIndex = keyList.indexOf(key)
        val newIndex = currentIndex + delta
        check(currentIndex == binding.keyGrid.indexOfChild(key.view)) { "View index mismatch" }

        keyList.removeAt(currentIndex)
        keyList.add(newIndex, key)

        // Instead of removing current view, we remove the target view.
        // This gives a better layout animation where the view at new
        // position disappears and current view moves in its place.
        check(delta == 1 || delta == -1)
        val viewAtNew = binding.keyGrid.getChildAt(newIndex)
        binding.keyGrid.removeViewAt(newIndex)
        binding.keyGrid.addView(viewAtNew, currentIndex)

        updateActionButtons()
    }

    private fun setFocusedKey(keyView: View?) {
        focusedKey?.view?.overlay?.clear()
        focusedKey = keyList.find { it.view == keyView }
        focusedKey?.view?.overlay?.add(focusOverlay)
        refreshFocus()

        binding.focusedKeyLabel.text = focusedKey?.view?.contentDescription ?: ""
        updateActionButtons()
    }

    private fun refreshFocus() {
        focusedKey?.view?.let { v ->
            if (v.width > 0 || v.height > 0) {
                focusOverlay.setBounds(0, 0, v.width, v.height)
                val r = Rect()
                v.getDrawingRect(r)
                v.requestRectangleOnScreen(r)
            }
        }
    }

    /**
     * For now, only one instance of a particular [VirtualKey] is added in key list.
     * But this could be changed if required in future.
     */
    private fun selectOrAddKey(virtualKey: VirtualKey) {
        var key = keyList.find { it.vk == virtualKey }
        if (key == null)
            key = addNewKey(virtualKey)
        setFocusedKey(key.view)
    }

    /**
     * The keys of the picker, grouped. Once letters and punctuation are in, the enumeration
     * runs past a hundred entries and a flat grid cannot be scanned; the sections follow the
     * shape of a keyboard instead, and give the two Shift/Ctrl/Alt edges separate homes so a
     * tap can say which side of the host it presses.
     *
     * A key this list forgets is not lost: it lands in the common section, so adding one to
     * [VirtualKey] without touching here still leaves it reachable.
     */
    private fun newKeyGroups(): List<Pair<Int, List<VirtualKey>>> {
        // Every key of a contiguous block of the enum, both ends included. The enum is laid
        // out in the order a keyboard is, so a block is one row of the picker.
        fun block(from: VirtualKey, to: VirtualKey) = VirtualKey.entries.slice(from.ordinal..to.ordinal)

        val punctuation = listOf(VirtualKey.Grave, VirtualKey.Minus, VirtualKey.Equals,
                                 VirtualKey.LeftBracket, VirtualKey.RightBracket, VirtualKey.Backslash,
                                 VirtualKey.Semicolon, VirtualKey.Apostrophe, VirtualKey.Comma,
                                 VirtualKey.Period, VirtualKey.Slash,
                                 VirtualKey.Star, VirtualKey.Plus, VirtualKey.At) +
                                // The shifted forms of those keys, which the enum keeps together
                                // right after At so that they read as one block here too.
                                block(VirtualKey.Tilde, VirtualKey.Question)

        val groups = listOf(
            R.string.group_vk_left_mod to listOf(VirtualKey.LeftShift, VirtualKey.LeftCtrl,
                                                 VirtualKey.LeftAlt),
            R.string.group_vk_right_mod to listOf(VirtualKey.RightShift, VirtualKey.RightCtrl,
                                                  VirtualKey.RightAlt),
            R.string.group_vk_common to listOf(VirtualKey.LeftSuper, VirtualKey.Esc, VirtualKey.Tab,
                                               VirtualKey.Backspace, VirtualKey.Enter, VirtualKey.Space,
                                               VirtualKey.Menu, VirtualKey.Insert, VirtualKey.Delete,
                                               VirtualKey.Home, VirtualKey.End, VirtualKey.PgUp, VirtualKey.PgDn,
                                               VirtualKey.CapsLock, VirtualKey.NumLock, VirtualKey.ScrollLock,
                                               VirtualKey.PrintScr, VirtualKey.PauseBreak,
                                               VirtualKey.Left, VirtualKey.Right, VirtualKey.Up, VirtualKey.Down),
            R.string.group_vk_punctuation to punctuation,
            R.string.group_vk_letters to block(VirtualKey.LetterA, VirtualKey.LetterZ),
            R.string.group_vk_numbers to block(VirtualKey.Digit0, VirtualKey.Digit9),
            R.string.group_vk_function to block(VirtualKey.F1, VirtualKey.F24),
            R.string.group_vk_numpad to block(VirtualKey.Numpad0, VirtualKey.Numpad9) +
                                        listOf(VirtualKey.NumpadAdd, VirtualKey.NumpadSubtract,
                                               VirtualKey.NumpadMultiply, VirtualKey.NumpadDivide,
                                               VirtualKey.NumpadDot, VirtualKey.NumpadEquals,
                                               VirtualKey.NumpadEnter),
            R.string.group_vk_media to listOf(VirtualKey.MediaPlayPause, VirtualKey.MediaStop,
                                              VirtualKey.MediaNext, VirtualKey.MediaPrevious,
                                              VirtualKey.VolumeUp, VirtualKey.VolumeDown,
                                              VirtualKey.VolumeMute),
            // In-app actions rather than keys sent to the host: leaving the stream, and opening the
            // toolbar the user would otherwise reach with an edge swipe. They belong on the bar like
            // any other key, so they get a section of their own instead of dissolving into 'Common'.
            R.string.group_vk_actions to listOf(VirtualKey.ExitSession, VirtualKey.OpenToolbar,
            VirtualKey.HideVirtualKeys),
        )

        val grouped = groups.flatMap { it.second }.toSet()
        val rest = VirtualKey.entries.filter { it != VirtualKey.ToggleKeyboard && it !in grouped }
        if (rest.isEmpty())
            return groups

        return groups.map { (title, keys) ->
            if (title == R.string.group_vk_common) title to (keys + rest) else title to keys
        }
    }

    private fun showNewKeyPopup() {
        var popup: PopupWindow? = null
        val context = requireContext()
        val margin = context.resources.getDimensionPixelSize(R.dimen.margin_small)

        fun section(titleRes: Int, keys: List<VirtualKey>) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setText(titleRes)
                TextViewCompat.setTextAppearance(this, android.R.style.TextAppearance_Material_Caption)
                setTextColor(MaterialColors.getColor(context, android.R.attr.textColorSecondary, Color.GRAY))
                setPadding(0, margin, 0, margin)
            })
            addView(GridLayout(context).apply {
                columnCount = 6
                keys.forEach { virtualKey ->
                    addView(VirtualKeyViewFactory.create(context, virtualKey).apply {
                        setOnClickListener {
                            selectOrAddKey(virtualKey)
                            popup?.dismiss()
                        }
                    })
                }
            })
        }

        val root = ScrollView(context).apply {
            setPadding(context.resources.getDimensionPixelSize(R.dimen.padding_normal))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                newKeyGroups().forEach { (titleRes, keys) -> addView(section(titleRes, keys)) }
            })
        }

        popup = PopupWindow(root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_round_rect))
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.elevation = 30f
        popup.showAsDropDown(binding.addKeyBtn)
    }
}