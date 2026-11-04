/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import com.limelight.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ToggleButton
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.viewpager.widget.PagerAdapter
import com.google.android.material.color.MaterialColors
import com.limelight.R
import com.limelight.databinding.VirtualKeysBinding
import com.limelight.ui.prefs.PrefsActivity
import com.limelight.ui.vnc.input.InputHandler
import com.limelight.util.AppPreferences
import com.limelight.util.addOnGlobalLayoutListener
import com.limelight.util.isTrue
import com.limelight.util.toggleKeyboard
import kotlin.math.min
import kotlin.math.sign


/**
 * Virtual keys allow the user to input keys which are not normally found on
 * keyboards but can be useful for controlling remote server.
 *
 * This class manages the inflation & visibility of virtual keys.
 */
class VirtualKeys(private val activity: VncActivity, private val inputHandler: InputHandler) {

    private val viewModel = activity.viewModel
    private val pref = activity.viewModel.pref
    private val inputView = activity.binding.inputView
    private val stub = activity.binding.virtualKeysStub
    private val toggleKeys = mutableSetOf<ToggleButton>()
    private val lockedToggleKeys = mutableSetOf<ToggleButton>()

    private val _visible = MutableLiveData(false)

    /**
     * Whether the bar is on screen. The toolbar's virtual-keys button mirrors this: the bar
     * can be raised or lowered from several places (the keyboard, picture-in-picture, the
     * connection), and a tick that only moved on its own tap would drift away from the truth.
     */
    val visible: LiveData<Boolean> get() = _visible

    /**
     * What the grid currently holds, so coming back from the key editor can tell whether the
     * cells have to be rebuilt at all.
     */
    private var shownLayout: List<VirtualKey> = emptyList()
    private var shownRowCount = -1

    private var openedWithKb = false
    private var closedByPiPMode = false

    val container: View? get() = stub.root

    fun show(saveVisibility: Boolean = false) {
        init()
        applyPosition()
        container?.visibility = View.VISIBLE
        _visible.value = true
        if (saveVisibility) pref.runInfo.showVirtualKeys = true
    }

    fun hide(saveVisibility: Boolean = false) {
        container?.visibility = View.GONE
        _visible.value = false
        openedWithKb = false //Reset flag
        // A modifier the user left held would otherwise keep pressing: the bar is gone, so
        // there is no second tap left to lift it. Ordinary keys already fire on a single tap.
        releaseMetaKeys()
        if (saveVisibility) pref.runInfo.showVirtualKeys = false
    }

    fun onKeyboardOpen() {
        if (pref.input.vkOpenWithKeyboard && container?.visibility != View.VISIBLE) {
            show()
            openedWithKb = true
        }
    }

    fun onKeyboardClose() {
        if (openedWithKb) {
            hide()
            openedWithKb = false
        }
    }

    fun onConnected() {
        if (pref.runInfo.showVirtualKeys && !viewModel.inPiPMode.isTrue)
            show()
    }

    /**
     * The key layout can have changed while the viewer was in the background - the editor is
     * reachable from the bar itself - so the grid is rebuilt whenever what is stored no longer
     * matches what is on screen. Only the cells are replaced: the bar, its pages and the size
     * the user arranged are left where they are.
     */
    fun onActivityStart() {
        if (!stub.isInflated)
            return

        // The position can have changed while the editor/settings were on top of the viewer, and
        // a layout that is otherwise unchanged must still move if the user asked for the other
        // edge, so this runs before the early return below.
        applyPosition()

        val binding = stub.binding as? VirtualKeysBinding ?: return
        if (VirtualKeyLayoutConfig.getLayout(pref) == shownLayout && pref.input.vkRowCount == shownRowCount)
            return

        releaseMetaKeys()
        toggleKeys.clear()
        lockedToggleKeys.clear()
        binding.keys.removeAllViews()
        initKeys(binding)
    }

    fun releaseMetaKeys() {
        toggleKeys.forEach {
            if (it.isChecked)
                it.isChecked = false
        }
    }

    /**
     * Lift the modifiers that are down only because they were tapped once in one-shot mode.
     * Locked ones (long-pressed) are left alone.
     */
    private fun releaseUnlockedMetaKeys() {
        toggleKeys.forEach {
            if (it.isChecked && !lockedToggleKeys.contains(it))
                it.isChecked = false
        }
    }

    private fun onAfterKeyEvent(event: KeyEvent) {
        // In hold mode a tapped modifier latches until it is tapped again, so a key typed after
        // it must leave it pressed. Only the one-shot (sticky) mode lifts a modifier once the
        // following character has been sent.
        if (!pref.input.vkHoldKeys &&
                event.action == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(event.keyCode))
            releaseUnlockedMetaKeys()
    }

    private fun onPiPModeChanged(inPiPMode: Boolean) {
        if (inPiPMode && container?.isVisible == true) {
            hide()
            closedByPiPMode = true
        } else if (!inPiPMode && closedByPiPMode) {
            show()
            closedByPiPMode = false
        }
    }

    private fun init() {
        if (stub.isInflated)
            return

        stub.viewStub?.inflate()
        val binding = stub.binding as VirtualKeysBinding
        initKeys(binding)
        initPager(binding)
        inputHandler.onAfterKeyEventListeners += ::onAfterKeyEvent
        viewModel.inPiPMode.observe(activity) { onPiPModeChanged(it) }
    }

    /**
     * To keep everything in single XML layout file, things are done in a slightly weird way.
     * The key page is initially attached to a temporary View. After inflation, it is detached
     * and passed onto the ViewPager adapter. Adapter will insert it at proper place.
     */
    private fun initPager(binding: VirtualKeysBinding) {
        val root = binding.root
        val keys = binding.keys
        val pager = binding.pager
        val pages = listOf(binding.keysPage)

        binding.tmpPageHost.apply {
            removeAllViews()
            (parent as ViewGroup).removeView(this)
        }

        // Setup pager
        pager.offscreenPageLimit = pages.size
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(view: View, obj: Any) = (view === obj)
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                pages[position].let {
                    container.addView(it)
                    return it
                }
            }

            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                container.removeView(obj as View)
            }
        }

        // Setup Layout. Keys grid is the primary View used for deciding size of Virtual keys.
        // All keys are shown if screen is wide enough. Otherwise width is limited to FrameView,
        // and HorizontalScrollView is relied upon to access all keys.
        // NOTE: Paddings in root/pager view is NOT handled by this code.

        // Start with something sane
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED).let { keys.measure(it, it) }

        // The bar is the grid plus the fixed button standing beside it (`vk_edit_keys_btn`,
        // which the pager gives up room for through its margin). Counting only the grid would
        // put that button on top of the last key as soon as everything fits on the screen.
        val edgeBtnWidth = root.resources.getDimensionPixelSize(R.dimen.vk_edge_btn_size)

        root.layoutParams = root.layoutParams.apply {
            width = keys.measuredWidth + edgeBtnWidth
            height = keys.measuredHeight
        }

        // Update size after layout changes
        addOnGlobalLayoutListener(activity, keys) {
            val w = min(keys.width + edgeBtnWidth, inputView.width)
            val h = keys.height
            if (w > 0 && h > 0 && (root.width != w || root.height != h))
                root.layoutParams = root.layoutParams.apply { width = w; height = h }
        }
    }

    private fun initKeys(binding: VirtualKeysBinding) {
        val layout = VirtualKeyLayoutConfig.getLayout(pref)
        shownLayout = layout
        shownRowCount = pref.input.vkRowCount

        binding.keys.rowCount = shownRowCount
        layout.forEach { vk ->
            val view = VirtualKeyViewFactory.create(binding.root.context, vk)
            binding.keys.addView(view)

            if (vk.keyCode != null) {
                // Only real modifiers come back as toggles; every other key fires on a single
                // tap. Whether a tapped modifier latches or is one-shot is decided by
                // `vk_hold_keys` in [onAfterKeyEvent], not by the view type.
                if (view is ToggleButton)
                    initToggleKey(view, vk.keyCode)
                else
                    initNormalKey(view, vk)
            } else if (vk.action != null) {
                // A special-action key sends nothing to the host; a tap just runs the action.
                initActionKey(view, vk.action)
            }
        }

        // The phone-keyboard toggle and the settings gear are fixed controls on the bar's left
        // column, not grid keys - the user can neither delete nor rearrange them. See the column
        // in virtual_keys.xml and the filtering of ToggleKeyboard out of the layout in
        // VirtualKeyLayoutConfig.
        binding.vkToggleKeyboardBtn.setOnClickListener { toggleKeyboard(inputView) }

        // It opens the settings screen rather than the grid straight away: the button is a
        // gear, and a gear that skipped past the switches beside the layout would promise one
        // thing and deliver another.
        binding.vkEditKeysBtn.setOnClickListener {
            activity.startActivity(Intent(activity, PrefsActivity::class.java)
                    .putExtra(PrefsActivity.EXTRA_START_FRAGMENT, PrefsActivity.VirtualKeys::class.java.name))
        }
    }


    private fun initToggleKey(key: ToggleButton, keyCode: Int) {
        key.setOnCheckedChangeListener { _, isChecked ->
            sendKeyState(keyCode, isChecked)
            if (!isChecked) lockedToggleKeys.remove(key)
        }
        key.setOnLongClickListener {
            // Long-press locks a modifier so it survives the one-shot auto-release; a plain tap
            // only holds it for the next key (unless hold mode keeps it down).
            key.toggle()
            if (key.isChecked) lockedToggleKeys.add(key)
            true
        }

        if ((keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT) && pref.input.vkUseSuperWithSingleTap)
            key.setOnClickListener {
                key.isChecked = true
                key.isChecked = false
            }

        toggleKeys.add(key)
    }

    private fun initNormalKey(key: View, vk: VirtualKey) {
        check(key !is ToggleButton) { "use initToggleKey()" }
        val keyCode = vk.keyCode
        checkNotNull(keyCode)
        key.setOnClickListener { sendKey(keyCode, vk.needsShift) }
        makeKeyRepeatable(key)
    }

    /**
     * A special-action key: a plain tap, no repeat, nothing sent to the host. Leaving the stream
     * just closes the viewer (which stops the session), matching the toolbar's own exit button;
     * opening the toolbar slides the drawer out the same way the assist ball does.
     */
    private fun initActionKey(key: View, action: VirtualAction) {
        key.setOnClickListener {
            when (action) {
                VirtualAction.ExitSession -> activity.finish()
                VirtualAction.OpenToolbar -> activity.toolbar.open()
                VirtualAction.HideVirtualKeys -> hide()
            }
        }
    }

    /**
     * Park the bar on the edge the user chose on the virtual-keys screen: the bottom (where it
     * has always sat) or the top. The ViewStub hands its own layout gravity to the inflated bar,
     * so rewriting that gravity here is what moves it; horizontal centering is left alone. Called
     * both when the bar is first raised and when the viewer comes back to the foreground (the
     * position can change from the settings screen while the editor sits on top of it), and a bar
     * that has not been inflated yet simply has nothing to move.
     */
    private fun applyPosition() {
        val root = container ?: return
        val lp = root.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.gravity = Gravity.CENTER_HORIZONTAL or
                (if (pref.input.vkPosition == "top") Gravity.TOP else Gravity.BOTTOM)
        root.layoutParams = lp
    }

    /**
     * When a View is touched, we schedule a callback to to simulate a click.
     * As long as finger stays on the view, we keep repeating this callback.
     */
    private fun makeKeyRepeatable(keyView: View) {
        keyView.setOnTouchListener(object : View.OnTouchListener {
            private var doRepeat = false

            private fun repeat(v: View) {
                if (doRepeat) {
                    v.performClick()
                    v.postDelayed({ repeat(v) }, ViewConfiguration.getKeyRepeatDelay().toLong())
                }
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        doRepeat = true
                        v.postDelayed({ repeat(v) }, ViewConfiguration.getKeyRepeatTimeout().toLong())
                    }

                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        doRepeat = false
                    }
                }
                return false
            }
        })
    }

    /**
     * Press and release the key in one go. [shifted] holds Shift for the pair, which is how a
     * key that only exists in a key's shifted form - `!`, `{`, `~` - is typed without asking the
     * user to latch a modifier first.
     */
    private fun sendKey(keyCode: Int, shifted: Boolean = false) {
        sendKeyState(keyCode, true, shifted)
        sendKeyState(keyCode, false, shifted)
    }

    private fun sendKeyState(keyCode: Int, isDown: Boolean, shifted: Boolean = false) {
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val meta = if (shifted) KeyEvent.META_SHIFT_ON else 0
        // (downTime, time, action, code, metaState): the only public constructor that takes a
        // meta state at all. The bare (action, code, meta) shape does not exist - its third
        // number is read as an event time - so a shifted key has to go through the 5-arg form.
        val now = android.os.SystemClock.uptimeMillis()
        inputHandler.onVkKeyEvent(KeyEvent(now, now, action, keyCode, meta))
    }
}

/**
 * NOTE: Names of these enums may be persisted in app preferences. So if any key name
 *       is ever modified, add a migration to handle old name.
 */

/**
 * Actions a virtual key can run inside the app instead of sending a keycode to the host. Like
 * the [VirtualKey] names these may be persisted, so a rename needs a migration; the set is kept
 * small and unambiguous because each one is a button the user places on the key bar.
 */
enum class VirtualAction {
    /** Leave the stream: closes the viewer, which ends the session behind it. */
    ExitSession,

    /** Slide open the toolbar drawer (the right-swipe menu). */
    OpenToolbar,

    /** Hide the virtual key bar. */
    HideVirtualKeys,
}

enum class VirtualKey(
        /**
         * [KeyEvent] keycode to be generated when this key is pressed.
         */
        val keyCode: Int? = null,

        /**
         * If key name is not appropriate for UI, use this to set the label.
         */
        val label: String? = null,

        /**
         * If icon is set, this key will be rendered as an ImageButton.
         */
        val icon: Int? = null,

        /**
         * Short description of the key, if the label itself isn't sufficient. Only available
         * as a resource: these are spoken by screen readers, so they must follow the language.
         */
        @StringRes val descriptionRes: Int? = null,

        val isToggle: Boolean = false,

        /**
         * Whether this key is the shifted form of its [keyCode]: the event is sent with Shift
         * held for its duration. Only punctuation entries use it - letters and digits type
         * themselves, and their case is the held modifier's business.
         */
        val needsShift: Boolean = false,

        /**
         * An in-app action to run instead of sending a keycode. Only the special-action keys set
         * this; it is what lets a key with no [keyCode] still do something when tapped (see
         * [VirtualKeys.initKeys]).
         */
        val action: VirtualAction? = null,
) {

    // Special actions
    ToggleKeyboard(descriptionRes = R.string.desc_vk_toggle_keyboard, icon = R.drawable.ic_keyboard),

    // In-app actions that live on the bar like any other key: leaving the stream, and opening
    // the toolbar the user would otherwise reach with an edge swipe.
    ExitSession(action = VirtualAction.ExitSession, icon = R.drawable.ic_exit,
            descriptionRes = R.string.desc_vk_exit),
    OpenToolbar(action = VirtualAction.OpenToolbar, icon = R.drawable.ic_toolbar,
            descriptionRes = R.string.desc_vk_open_toolbar),
    HideVirtualKeys(action = VirtualAction.HideVirtualKeys, icon = R.drawable.ic_clear,
            descriptionRes = R.string.desc_vk_close),

    // Meta keys. The labels carry an L/R prefix because the picker shows both edges of the
    // keyboard: 'Shift' alone cannot say which one a tap will press on the host. Win is a
    // single key by convention - the two Super codes are the same key to a user.
    LeftShift(keyCode = KeyEvent.KEYCODE_SHIFT_LEFT, label = "LShift", isToggle = true),
    LeftCtrl(keyCode = KeyEvent.KEYCODE_CTRL_LEFT, label = "LCtrl", isToggle = true),
    LeftAlt(keyCode = KeyEvent.KEYCODE_ALT_LEFT, label = "LAlt", isToggle = true),
    LeftSuper(keyCode = KeyEvent.KEYCODE_META_LEFT, label = "Win", icon = R.drawable.ic_super_key, isToggle = true),
    RightShift(keyCode = KeyEvent.KEYCODE_SHIFT_RIGHT, label = "RShift", isToggle = true),
    RightCtrl(keyCode = KeyEvent.KEYCODE_CTRL_RIGHT, label = "RCtrl", isToggle = true),
    RightAlt(keyCode = KeyEvent.KEYCODE_ALT_RIGHT, label = "RAlt", isToggle = true),

    Esc(keyCode = KeyEvent.KEYCODE_ESCAPE),
    Tab(keyCode = KeyEvent.KEYCODE_TAB),
    // DEL is what a PC keyboard calls Backspace; the key named Delete below is its
    // forward-deleting neighbour.
    Backspace(keyCode = KeyEvent.KEYCODE_DEL, label = "Bksp"),
    Enter(keyCode = KeyEvent.KEYCODE_ENTER, label = "Enter"),
    Space(keyCode = KeyEvent.KEYCODE_SPACE, label = "Space"),
    Home(keyCode = KeyEvent.KEYCODE_MOVE_HOME),
    End(keyCode = KeyEvent.KEYCODE_MOVE_END),
    PgUp(keyCode = KeyEvent.KEYCODE_PAGE_UP),
    PgDn(keyCode = KeyEvent.KEYCODE_PAGE_DOWN),
    Insert(keyCode = KeyEvent.KEYCODE_INSERT),
    Delete(keyCode = KeyEvent.KEYCODE_FORWARD_DEL),

    // Latch keys. A real keyboard toggles these on the host, so a single down+up is
    // enough here - unlike the meta keys above they must not stay sticky locally.
    CapsLock(keyCode = KeyEvent.KEYCODE_CAPS_LOCK, label = "Caps"),
    NumLock(keyCode = KeyEvent.KEYCODE_NUM_LOCK, label = "Num"),
    ScrollLock(keyCode = KeyEvent.KEYCODE_SCROLL_LOCK, label = "ScrLk"),
    PrintScr(keyCode = KeyEvent.KEYCODE_SYSRQ, label = "PrtSc"),
    PauseBreak(keyCode = KeyEvent.KEYCODE_BREAK, label = "Break"),
    Menu(keyCode = KeyEvent.KEYCODE_MENU, label = "Menu"),

    // Arrow keys
    Left(keyCode = KeyEvent.KEYCODE_DPAD_LEFT, icon = R.drawable.ic_keyboard_arrow_left),
    Right(keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, icon = R.drawable.ic_keyboard_arrow_right),
    Up(keyCode = KeyEvent.KEYCODE_DPAD_UP, icon = R.drawable.ic_keyboard_arrow_up),
    Down(keyCode = KeyEvent.KEYCODE_DPAD_DOWN, icon = R.drawable.ic_keyboard_arrow_down),

    F1(keyCode = KeyEvent.KEYCODE_F1),
    F2(keyCode = KeyEvent.KEYCODE_F2),
    F3(keyCode = KeyEvent.KEYCODE_F3),
    F4(keyCode = KeyEvent.KEYCODE_F4),
    F5(keyCode = KeyEvent.KEYCODE_F5),
    F6(keyCode = KeyEvent.KEYCODE_F6),
    F7(keyCode = KeyEvent.KEYCODE_F7),
    F8(keyCode = KeyEvent.KEYCODE_F8),
    F9(keyCode = KeyEvent.KEYCODE_F9),
    F10(keyCode = KeyEvent.KEYCODE_F10),
    F11(keyCode = KeyEvent.KEYCODE_F11),
    F12(keyCode = KeyEvent.KEYCODE_F12),
    F13(keyCode = KeyEvent.KEYCODE_F13),
    F14(keyCode = KeyEvent.KEYCODE_F14),
    F15(keyCode = KeyEvent.KEYCODE_F15),
    F16(keyCode = KeyEvent.KEYCODE_F16),
    F17(keyCode = KeyEvent.KEYCODE_F17),
    F18(keyCode = KeyEvent.KEYCODE_F18),
    F19(keyCode = KeyEvent.KEYCODE_F19),
    F20(keyCode = KeyEvent.KEYCODE_F20),
    F21(keyCode = KeyEvent.KEYCODE_F21),
    F22(keyCode = KeyEvent.KEYCODE_F22),
    F23(keyCode = KeyEvent.KEYCODE_F23),
    F24(keyCode = KeyEvent.KEYCODE_F24),

    // Main-keyboard number row (above the letters). Labels are the bare digits, since these
    // are the keys a user thinks of as "the numbers"; the numpad below carries a Num prefix
    // so the two rows never look alike in the picker.
    Digit0(keyCode = KeyEvent.KEYCODE_0, label = "0"),
    Digit1(keyCode = KeyEvent.KEYCODE_1, label = "1"),
    Digit2(keyCode = KeyEvent.KEYCODE_2, label = "2"),
    Digit3(keyCode = KeyEvent.KEYCODE_3, label = "3"),
    Digit4(keyCode = KeyEvent.KEYCODE_4, label = "4"),
    Digit5(keyCode = KeyEvent.KEYCODE_5, label = "5"),
    Digit6(keyCode = KeyEvent.KEYCODE_6, label = "6"),
    Digit7(keyCode = KeyEvent.KEYCODE_7, label = "7"),
    Digit8(keyCode = KeyEvent.KEYCODE_8, label = "8"),
    Digit9(keyCode = KeyEvent.KEYCODE_9, label = "9"),

    // Letters. The full QWERTY overlay is not always what the user wants over the stream - a
    // single letter that must sit next to Ctrl on the key bar is the reason these are here.
    // Labels are capitals because that is how the keycap reads; the case the host sees is the
    // shift state's business, not the label's.
    LetterA(keyCode = KeyEvent.KEYCODE_A, label = "A"),
    LetterB(keyCode = KeyEvent.KEYCODE_B, label = "B"),
    LetterC(keyCode = KeyEvent.KEYCODE_C, label = "C"),
    LetterD(keyCode = KeyEvent.KEYCODE_D, label = "D"),
    LetterE(keyCode = KeyEvent.KEYCODE_E, label = "E"),
    LetterF(keyCode = KeyEvent.KEYCODE_F, label = "F"),
    LetterG(keyCode = KeyEvent.KEYCODE_G, label = "G"),
    LetterH(keyCode = KeyEvent.KEYCODE_H, label = "H"),
    LetterI(keyCode = KeyEvent.KEYCODE_I, label = "I"),
    LetterJ(keyCode = KeyEvent.KEYCODE_J, label = "J"),
    LetterK(keyCode = KeyEvent.KEYCODE_K, label = "K"),
    LetterL(keyCode = KeyEvent.KEYCODE_L, label = "L"),
    LetterM(keyCode = KeyEvent.KEYCODE_M, label = "M"),
    LetterN(keyCode = KeyEvent.KEYCODE_N, label = "N"),
    LetterO(keyCode = KeyEvent.KEYCODE_O, label = "O"),
    LetterP(keyCode = KeyEvent.KEYCODE_P, label = "P"),
    LetterQ(keyCode = KeyEvent.KEYCODE_Q, label = "Q"),
    LetterR(keyCode = KeyEvent.KEYCODE_R, label = "R"),
    LetterS(keyCode = KeyEvent.KEYCODE_S, label = "S"),
    LetterT(keyCode = KeyEvent.KEYCODE_T, label = "T"),
    LetterU(keyCode = KeyEvent.KEYCODE_U, label = "U"),
    LetterV(keyCode = KeyEvent.KEYCODE_V, label = "V"),
    LetterW(keyCode = KeyEvent.KEYCODE_W, label = "W"),
    LetterX(keyCode = KeyEvent.KEYCODE_X, label = "X"),
    LetterY(keyCode = KeyEvent.KEYCODE_Y, label = "Y"),
    LetterZ(keyCode = KeyEvent.KEYCODE_Z, label = "Z"),

    // Punctuation and symbols, each one the bare unshifted key - the same key a PC keyboard
    // has next to the letters. The label is the glyph it types without Shift; the shifted
    // glyphs follow below, after the three the dialpad codes already carry.
    Grave(keyCode = KeyEvent.KEYCODE_GRAVE, label = "`"),
    Minus(keyCode = KeyEvent.KEYCODE_MINUS, label = "-"),
    Equals(keyCode = KeyEvent.KEYCODE_EQUALS, label = "="),
    LeftBracket(keyCode = KeyEvent.KEYCODE_LEFT_BRACKET, label = "["),
    RightBracket(keyCode = KeyEvent.KEYCODE_RIGHT_BRACKET, label = "]"),
    Backslash(keyCode = KeyEvent.KEYCODE_BACKSLASH, label = "\\"),
    Semicolon(keyCode = KeyEvent.KEYCODE_SEMICOLON, label = ";"),
    Apostrophe(keyCode = KeyEvent.KEYCODE_APOSTROPHE, label = "'"),
    Comma(keyCode = KeyEvent.KEYCODE_COMMA, label = ","),
    Period(keyCode = KeyEvent.KEYCODE_PERIOD, label = "."),
    Slash(keyCode = KeyEvent.KEYCODE_SLASH, label = "/"),
    // The three the dialpad codes carry, which are the asterisk/plus/at of a keyboard row.
    Star(keyCode = KeyEvent.KEYCODE_STAR, label = "*"),
    Plus(keyCode = KeyEvent.KEYCODE_PLUS, label = "+"),
    At(keyCode = KeyEvent.KEYCODE_AT, label = "@"),

    // The shifted forms of the keys above: the same physical key with Shift held, listed as
    // their own entries because the key bar has no shift state of its own to borrow from a
    // label. Tapping one sends its base [keyCode] with META_SHIFT_ON for the pair, which both
    // transports turn into the right glyph: RFB reads the event's meta state for the unicode
    // character and wraps a Shift press around it, Moonlight reads it for the modifier byte
    // while the scancode stays the base key.
    //
    // They are appended here rather than folded into the rows above because the key editor
    // slices blocks out of this enum by ordinal (see VirtualKeysEditor.block).
    Tilde(keyCode = KeyEvent.KEYCODE_GRAVE, label = "~", needsShift = true),
    Exclam(keyCode = KeyEvent.KEYCODE_1, label = "!", needsShift = true),
    Hash(keyCode = KeyEvent.KEYCODE_3, label = "#", needsShift = true),
    Dollar(keyCode = KeyEvent.KEYCODE_4, label = "$", needsShift = true),
    Percent(keyCode = KeyEvent.KEYCODE_5, label = "%", needsShift = true),
    Caret(keyCode = KeyEvent.KEYCODE_6, label = "^", needsShift = true),
    Ampersand(keyCode = KeyEvent.KEYCODE_7, label = "&", needsShift = true),
    OpenParen(keyCode = KeyEvent.KEYCODE_9, label = "(", needsShift = true),
    CloseParen(keyCode = KeyEvent.KEYCODE_0, label = ")", needsShift = true),
    Underscore(keyCode = KeyEvent.KEYCODE_MINUS, label = "_", needsShift = true),
    OpenBrace(keyCode = KeyEvent.KEYCODE_LEFT_BRACKET, label = "{", needsShift = true),
    CloseBrace(keyCode = KeyEvent.KEYCODE_RIGHT_BRACKET, label = "}", needsShift = true),
    Pipe(keyCode = KeyEvent.KEYCODE_BACKSLASH, label = "|", needsShift = true),
    Colon(keyCode = KeyEvent.KEYCODE_SEMICOLON, label = ":", needsShift = true),
    DQuote(keyCode = KeyEvent.KEYCODE_APOSTROPHE, label = "\"", needsShift = true),
    LessThan(keyCode = KeyEvent.KEYCODE_COMMA, label = "<", needsShift = true),
    GreaterThan(keyCode = KeyEvent.KEYCODE_PERIOD, label = ">", needsShift = true),
    Question(keyCode = KeyEvent.KEYCODE_SLASH, label = "?", needsShift = true),

    // Numpad. Labels carry a Num prefix to tell this cluster apart from the number row above.
    Numpad0(keyCode = KeyEvent.KEYCODE_NUMPAD_0, label = "Num0"),
    Numpad1(keyCode = KeyEvent.KEYCODE_NUMPAD_1, label = "Num1"),
    Numpad2(keyCode = KeyEvent.KEYCODE_NUMPAD_2, label = "Num2"),
    Numpad3(keyCode = KeyEvent.KEYCODE_NUMPAD_3, label = "Num3"),
    Numpad4(keyCode = KeyEvent.KEYCODE_NUMPAD_4, label = "Num4"),
    Numpad5(keyCode = KeyEvent.KEYCODE_NUMPAD_5, label = "Num5"),
    Numpad6(keyCode = KeyEvent.KEYCODE_NUMPAD_6, label = "Num6"),
    Numpad7(keyCode = KeyEvent.KEYCODE_NUMPAD_7, label = "Num7"),
    Numpad8(keyCode = KeyEvent.KEYCODE_NUMPAD_8, label = "Num8"),
    Numpad9(keyCode = KeyEvent.KEYCODE_NUMPAD_9, label = "Num9"),
    NumpadAdd(keyCode = KeyEvent.KEYCODE_NUMPAD_ADD, label = "+"),
    NumpadSubtract(keyCode = KeyEvent.KEYCODE_NUMPAD_SUBTRACT, label = "-"),
    NumpadMultiply(keyCode = KeyEvent.KEYCODE_NUMPAD_MULTIPLY, label = "×"),
    NumpadDivide(keyCode = KeyEvent.KEYCODE_NUMPAD_DIVIDE, label = "÷"),
    NumpadDot(keyCode = KeyEvent.KEYCODE_NUMPAD_DOT, label = "."),
    NumpadEquals(keyCode = KeyEvent.KEYCODE_NUMPAD_EQUALS, label = "="),
    NumpadEnter(keyCode = KeyEvent.KEYCODE_NUMPAD_ENTER, label = "NEnt"),

    // Consumer keys. These reach the host as the keys above its function row.
    MediaPlayPause(keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, label = "Play"),
    MediaStop(keyCode = KeyEvent.KEYCODE_MEDIA_STOP, label = "Stop"),
    MediaNext(keyCode = KeyEvent.KEYCODE_MEDIA_NEXT, label = "Next"),
    MediaPrevious(keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS, label = "Prev"),
    VolumeUp(keyCode = KeyEvent.KEYCODE_VOLUME_UP, label = "Vol+"),
    VolumeDown(keyCode = KeyEvent.KEYCODE_VOLUME_DOWN, label = "Vol-"),
    VolumeMute(keyCode = KeyEvent.KEYCODE_VOLUME_MUTE, label = "Mute"),
}

/**
 * Users can change the layout of keys in app settings.
 * Layout configuration is stored as a simple list of key-names.
 */
object VirtualKeyLayoutConfig {

    // ToggleKeyboard is deliberately absent: raising the phone keyboard is now a fixed control
    // on the bar's left column (see virtual_keys.xml), not a grid key the editor can arrange.
    private val DEFAULT_LAYOUT = listOf(VirtualKey.Esc, VirtualKey.LeftSuper,
                                        VirtualKey.Tab, VirtualKey.LeftCtrl, VirtualKey.LeftShift, VirtualKey.LeftAlt,
                                        VirtualKey.Delete, VirtualKey.Left, VirtualKey.Up, VirtualKey.Down, VirtualKey.End,
                                        VirtualKey.Right, VirtualKey.PgUp, VirtualKey.PgDn)

    /**
     * In older versions, before users could customize key layout, there was a pref to
     * 'Show all' keys. This layout is used for compatibility with that pref. Delete is
     * missing from the additions because the default row already carries it.
     */
    private val DEFAULT_LAYOUT_ALL = DEFAULT_LAYOUT +
                                     listOf(VirtualKey.Insert, VirtualKey.Backspace, VirtualKey.Enter,
                                            VirtualKey.Home, VirtualKey.F1, VirtualKey.F2, VirtualKey.F3,
                                            VirtualKey.F4, VirtualKey.F5, VirtualKey.F6, VirtualKey.F7, VirtualKey.F8,
                                            VirtualKey.F9, VirtualKey.F10, VirtualKey.F11, VirtualKey.F12)


    fun getDefaultLayout(pref: AppPreferences): List<VirtualKey> {
        return if (pref.input.vkShowAll) DEFAULT_LAYOUT_ALL else DEFAULT_LAYOUT
    }

    /**
     * Names are looked up rather than run through [VirtualKey.valueOf], and unknown ones are
     * dropped: this string can come from a backup made by an older (or newer) build, and a
     * key that no longer exists - `CloseKeys` was removed when the bar's own settings button
     * took over - used to throw the whole layout away back to the defaults, silently undoing
     * whatever the user had arranged.
     */
    fun getLayout(pref: AppPreferences): List<VirtualKey> {
        runCatching {
            pref.input.vkLayout?.let { vkLayout ->
                vkLayout.split(',').mapNotNull { name ->
                    VirtualKey.entries.firstOrNull { it.name == name.trim() }
                }.filter { it != VirtualKey.ToggleKeyboard }.distinct().let { keys ->
                    // The layout string is a plain preference, so an imported backup can
                    // put anything in it. One view is created per entry and added to a
                    // GridLayout on the UI thread, so an endless list is an endless inflate:
                    // more keys than the enum has is neither a layout nor achievable.
                    //
                    // Repeated names are folded away here rather than downstream: a layout
                    // holds each key at most once. The editor refuses a
                    // second copy (`check` in VirtualKeysEditor.addNewKey), so a stored
                    // `Esc,Esc` would crash the customization screen - and would silently
                    // draw two identical buttons in the viewer until someone got there.
                    check(keys.isNotEmpty() && keys.size <= VirtualKey.values().size)
                    return keys
                }
            }
        }.onFailure { Log.e(javaClass.simpleName, "Error parsing key layout [${pref.input.vkLayout}]: ", it) }

        return getDefaultLayout(pref)
    }

    fun setLayout(pref: AppPreferences, keys: List<VirtualKey>) {
        if (keys == getDefaultLayout(pref) && pref.input.vkLayout != null) {
            // Restoring the defaults, so simply remove the pref.
            // Pref is only used if user changes the default layout.
            pref.input.vkLayout = null
            return
        }

        if (keys == getLayout(pref))
            return   // Nothing changed

        pref.input.vkLayout = keys.joinToString(",") { it.name }
    }
}

/**
 * Factory for creating individual key [View]s.
 */
object VirtualKeyViewFactory {

    /**
     * There are three types of Views that are generated:
     *
     * [ToggleButton] - if [key] is a modifier/toggle ([VirtualKey.isToggle])
     * [ImageButton]  - if [key] has an icon (label will be ignored)
     * [Button]       - in all other cases
     *
     * Whether a tapped modifier latches or releases after the next key is a runtime behaviour
     * owned by [VirtualKeys], not something that changes the view type here.
     */
    fun create(context: Context, key: VirtualKey): View {
        val asToggle = key.isToggle
        val view = if (asToggle) createToggle(context, key) else createSimple(context, key)
        view.layoutParams = GridLayout.LayoutParams().apply {
            width = GridLayout.LayoutParams.WRAP_CONTENT
            height = GridLayout.LayoutParams.WRAP_CONTENT
            setGravity(Gravity.CENTER)
        }
        return view
    }

    private fun createSimple(context: Context, key: VirtualKey): View {
        return if (key.icon != null)
            ImageButton(context, null, 0, selectStyle(key))
                    .apply {
                        setImageDrawable(ContextCompat.getDrawable(context, key.icon))
                        ImageViewCompat.setImageTintList(this, iconTint(context))
                        contentDescription = getDescription(context, key)
                    }
        else
            Button(context, null, 0, selectStyle(key))
                    .apply { text = getLabel(key) }
    }

    private fun createToggle(context: Context, key: VirtualKey): View {
        val view = ToggleButton(context, null, 0, selectStyle(key))
        view.isClickable = true

        if (key.icon != null) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(key.icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(view, iconTint(context))
            view.contentDescription = getDescription(context, key)
        } else {
            val label = getLabel(key)
            view.text = label
            view.textOff = label
            view.textOn = label
        }

        return view
    }

    /**
     * Colour of every icon in the bar.
     *
     * The vectors carry `?colorOnSurface` as their own fill, but that spelling only works as
     * long as whoever loads them resolves theme attributes - a compound drawable pulled
     * through a cached constant state, or an icon handed straight to a ToggleButton, is not
     * guaranteed to. When it is not, the fill degrades to the default black and the key
     * disappears into the bar, which paints itself with colorSurface. Setting the tint here
     * pins the icon to the same pair the bar's own background promises.
     */
    private fun iconTint(context: Context): ColorStateList =
            ColorStateList.valueOf(MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurface, Color.WHITE))

    private fun selectStyle(key: VirtualKey): Int {
        if (key == VirtualKey.ToggleKeyboard)
            return R.style.VirtualKey_Special

        if (key.isToggle) {
            return if (key.icon != null) R.style.VirtualKey_Toggle_Image else R.style.VirtualKey_Toggle
        }

        return R.style.VirtualKey
    }

    private fun getLabel(virtualKey: VirtualKey) = virtualKey.label ?: virtualKey.name
    private fun getDescription(context: Context, virtualKey: VirtualKey) =
            virtualKey.descriptionRes?.let { context.getString(it) } ?: getLabel(virtualKey)
}

/**
 * Stock [HorizontalScrollView] intercepts all scroll events irrespective of whether
 * it can actually scroll or not. It makes it unsuitable for use as child/parent of
 * another horizontally scrollable View, e.g. ViewPager.
 *
 * [NestableHorizontalScrollView] fixes this by only intercepting events when it is scrollable.
 */
class NestableHorizontalScrollView(context: Context, attributeSet: AttributeSet? = null) :
        HorizontalScrollView(context, attributeSet) {
    /**
     * Direction of current horizontal scrolling.
     * See [canScrollHorizontally].
     */
    private var hScrollDirection = 0
    private val gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            hScrollDirection = 0
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            hScrollDirection = distanceX.sign.toInt()
            return true
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        if (hScrollDirection != 0 && !canScrollHorizontally(hScrollDirection))
            return false

        return super.onInterceptTouchEvent(ev)
    }
}