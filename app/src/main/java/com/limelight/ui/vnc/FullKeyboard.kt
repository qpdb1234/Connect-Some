/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * Based in part on AVNC (GPL-3.0-or-later), ui/vnc/VirtualKeys.kt: the toggle-key
 * bookkeeping, the repeat-on-hold touch listener, the post-event release sweep and the
 * key send path are its. The QWERTY grid itself, its weighted row layout and the opacity,
 * scale and drag handling are this app's.
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.annotation.SuppressLint
import android.graphics.Paint
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.limelight.R
import com.limelight.databinding.FullKeyboardBinding
import com.limelight.ui.vnc.input.InputHandler
import com.limelight.util.AppPreferences
import kotlin.math.hypot
import kotlin.math.max

/**
 * The full keyboard: a QWERTY overlay over the stream for typing ordinary text on the
 * remote machine - the letters, digits and punctuation that the virtual-key bar, whose
 * whole purpose is the keys a phone keyboard does not have, deliberately leaves out.
 *
 * Structurally it borrows from both of its neighbours: like [VirtualKeys] it inflates a
 * [ViewStub] lazily and pushes every tap through [InputHandler.onVkKeyEvent] (the key
 * handler keeps the meta state, so holding a Shift here and tapping a letter types the
 * letter shifted on the host), and like [AssistBall] it floats inside the frame container
 * and moves by raw x/y, persisting its spot as a pair of biases so the keyboard survives
 * a rotation at the same relative place.
 *
 * Its settings - on/off, opacity, the drag lock and the scale - are edited from the toolbar
 * drawer and live in [com.limelight.viewmodel.VncViewModel]; this class only watches them
 * and redraws, keeping the drawer the single place the keyboard is configured. Dragging
 * starts on the grip bar and nowhere else: a key that could also drag the board would
 * move it every time someone held a tap a moment too long.
 */
class FullKeyboard(private val activity: VncActivity, private val inputHandler: InputHandler) {

    private val viewModel = activity.viewModel
    private val stub = activity.binding.fullKeyboardStub
    private val parent = activity.binding.frameContainer

    /** Set once the stub has inflated; until then the keyboard does not exist on screen. */
    private var root: View? = null
    private var grip: View? = null
    private var keysContainer: View? = null

    private val toggleKeys = mutableSetOf<ToggleButton>()
    private val lockedToggleKeys = mutableSetOf<ToggleButton>()
    private var closedByPiPMode = false

    private val touchSlop by lazy { ViewConfiguration.get(activity).scaledTouchSlop }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragged = false

    /** Whether the current gesture started on the grip. See the same flag in [AssistBall]. */
    private var tracking = false

    private val isOpen: Boolean get() = root?.isVisible == true

    /**
     * Called once from VncActivity.onCreate. Registering the observers is all it does -
     * the stub stays uninflated until the keyboard is first raised, so a session that
     * never touches this feature pays nothing for it.
     */
    fun initialize() {
        viewModel.fullKeyboardEnabled.observe(activity) { if (it == true) show() else hide() }
        viewModel.fullKeyboardOpacity.observe(activity) { opacity -> opacity?.let { applyOpacity(it) } }
        viewModel.fullKeyboardDragLocked.observe(activity) { applyDragLock(it == true) }
        viewModel.fullKeyboardScale.observe(activity) { if (it != null) rebuildKeys() }
        viewModel.inPiPMode.observe(activity) { onPiPModeChanged(it) }

        // Rotations and resizes arrive here. The stored biases are fractions, so the
        // keyboard re-clamps into whatever space the new frame leaves it.
        parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) positionFromBias()
        }
    }

    fun show() {
        init()
        root?.apply {
            visibility = View.VISIBLE
            applyOpacity(viewModel.fullKeyboardOpacity.value ?: 1f)
            // Appearing from 'gone' starts a layout pass; only after it does the board
            // have real bounds to place itself by.
            doOnNextLayout { positionFromBias() }
        }
    }

    /**
     * Fade the keycaps and the plate they sit on, not just the caps. Alpha on a child does
     * nothing to its parent's background, so fading only the key container left the board's
     * own slab at full opacity: the keys went ghostly and the panel still read as solid.
     * Both backgrounds are dimmed here, through mutate() because the frame and the grip draw
     * the same color resource and share its constant state.
     *
     * The grip keeps a floor. The opacity slider runs all the way to fully transparent, and a
     * grip bar that faded along with the keys would take the only way to move the board - and
     * the only way back into the drawer that hides it - with it: an invisible strip still
     * swallows the touch that would have grabbed it.
     */
    private fun applyOpacity(opacity: Float) {
        val gripAlpha = max(opacity, GRIP_MIN_ALPHA)
        keysContainer?.alpha = opacity
        grip?.alpha = gripAlpha
        setBackgroundAlpha(root, opacity)
        setBackgroundAlpha(grip, gripAlpha)
    }

    private fun setBackgroundAlpha(view: View?, alpha: Float) {
        view?.background?.mutate()?.alpha = (alpha * 255).toInt()
    }

    fun hide() {
        releaseToggles()
        root?.visibility = View.GONE
    }

    /**
     * PiP shrinks the stream to a corner card, and a keyboard as wide as the phone has
     * no meaning there. Hidden on entry, restored on exit - but only if it was this
     * passage that hid it, never a board the user had switched off.
     */
    private fun onPiPModeChanged(inPiPMode: Boolean) {
        if (inPiPMode && isOpen) {
            hide()
            closedByPiPMode = true
        } else if (!inPiPMode && closedByPiPMode) {
            show()
            closedByPiPMode = false
        }
    }

    /** Un-press every meta key left latched, so nothing keeps pressing once we are gone. */
    fun releaseToggles() {
        toggleKeys.forEach { if (it.isChecked) it.isChecked = false }
    }

    private fun init() {
        if (stub.isInflated) return

        stub.viewStub?.inflate()
        val binding = stub.binding as FullKeyboardBinding
        buildKeys(binding.fkKeysContainer)
        root = binding.fkRoot
        grip = binding.fkDragHandle
        keysContainer = binding.fkKeysContainer
        applyOpacity(viewModel.fullKeyboardOpacity.value ?: 1f)
        setupDrag()
        applyDragLock(viewModel.fullKeyboardDragLocked.value == true)
        inputHandler.onAfterKeyEventListeners += ::onAfterKeyEvent
    }

    /**
     * Rebuild the key grid at the current scale. The cell size is baked into each view when
     * the board is first laid out, so a scale change means tearing the rows down and building
     * them again, then re-placing the board for its new footprint. A no-op before the stub has
     * inflated - an unraised keyboard simply picks the scale up whenever it is next shown.
     */
    private fun rebuildKeys() {
        if (!stub.isInflated) return
        val container = (stub.binding as FullKeyboardBinding).fkKeysContainer
        container.removeAllViews()
        buildKeys(container)
        root?.requestLayout()
        root?.doOnNextLayout { positionFromBias() }
    }

    /**********************************************************************************************
     * Keys
     *********************************************************************************************/

    /**
     * One key's edge, in px. The board is a fixed-size overlay - the same physical size in
     * portrait and landscape - so the unit no longer folds itself to the frame width. It is
     * [KEY_UNIT_DP] multiplied by the user's [VncViewModel.fullKeyboardScale], and on a screen
     * too narrow for the whole grid the overflow is reached by dragging the board or scrolling
     * it sideways, exactly like the reference keyboard rather than by shrinking every key.
     */
    private fun keyUnit(): Float {
        val density = activity.resources.displayMetrics.density
        val scale = viewModel.fullKeyboardScale.value ?: 1f
        return density * KEY_UNIT_DP * scale
    }

    private fun buildKeys(container: LinearLayout) {
        val unit = keyUnit()
        // Every row spans the board's inner width and shares it out among its keys in
        // proportion to their width units - exactly like the reference board, whose rows are
        // full-width LinearLayouts of weighted keys. Sizing each key to a fixed unit instead
        // made the rows come out a different length (the function row is 15 units, the shift
        // row 13.2) and left a ragged, misaligned right edge; weighting keeps that edge flush.
        val rowWidth = maxRowUnits() * unit

        container.layoutParams = container.layoutParams.apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
        }

        ROWS.forEach { row ->
            val rowTotal = row.sumOf { it.widthUnits.toDouble() }.toFloat()
            val rowView = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                // Align children by their top edge, not their text baseline (which is the
                // default for a horizontal LinearLayout). The shifted-punctuation keys carry a
                // two-line label - the shifted glyph stacked over the bare one - so their
                // baseline sits lower; leaving baseline alignment on dropped those keys below
                // the single-line keys around them, mislining the right-hand symbols.
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, unit.toInt())
            }
            row.forEach { spec ->
                val cellW = (rowWidth * spec.widthUnits / rowTotal).toInt()
                rowView.addView(createKey(spec, spec.widthUnits, cellW, unit.toInt()))
            }
            container.addView(rowView)
        }

        // Give the board an explicit width instead of leaving it wrap_content. Measured
        // wrap_content under its frame it would be clamped to the screen width, pinning spanX
        // to 0 and making a sideways drag a no-op. The natural footprint is the full row width
        // (plus the frame's own horizontal padding); the board is then free to exceed the
        // screen and is panned sideways by dragging, exactly like dragging it up and down.
        val pad = activity.resources.getDimensionPixelSize(R.dimen.padding_small) * 2
        val width = rowWidth.toInt() + pad
        (container.parent as? View)?.let { board ->
            board.layoutParams = board.layoutParams.apply { this.width = width }
        }
    }

    /** Width of the widest row in [ROWS], in key units; the function row, at 15. */
    private fun maxRowUnits(): Float =
            ROWS.maxOf { row -> row.sumOf { it.widthUnits.toDouble() }.toFloat() }

    private fun createKey(spec: KeySpec, weight: Float, cellW: Int, cellH: Int): View {
        val view: Button = if (spec.toggle)
            ToggleButton(activity, null, 0, R.style.FullKeyboardKey)
        else
            Button(activity, null, 0, R.style.FullKeyboardKey)

        view.text = spec.label
        view.minWidth = 0
        view.minimumWidth = 0
        view.setPadding(0, 0, 0, 0)
        // Keys butt together with no gap and split the row by weight (width 0), exactly like
        // the reference board: each carries its own 2dp border, so two neighbours show a double
        // hairline between them and the grid reads as one continuous sheet of keycaps. [cellW]
        // is only the expected width used to fit the label; the real width comes from the weight.
        view.layoutParams = LinearLayout.LayoutParams(0, cellH, weight)

        if (view is ToggleButton) {
            // Text survives the state change only because both states say the same word -
            // a ToggleButton that cleared its label when latched would look like a dead key.
            view.textOn = spec.label
            view.textOff = spec.label
            initToggleKey(view, spec.keyCode)
        } else {
            initNormalKey(view, spec.keyCode)
        }

        // A key is a fixed cell but its label is not: "PgUp" and the stacked "~ / `" have to
        // stay whole where a bare "Q" has room to spare. The framework's autosize proved
        // unreliable on these programmatically built buttons, so measure the label against the
        // cell and pick the largest size that fits.
        view.maxLines = 2
        fitLabel(view, spec.label, cellW, cellH)
        return view
    }

    /**
     * Shrink [view]'s text from [KEY_TEXT_MAX_SP] down to [KEY_TEXT_MIN_SP] until every line
     * of [label] fits inside the [availW] x [availH] cell, then apply it. Measuring with a
     * [Paint] copy keeps this independent of when the view is next laid out.
     */
    private fun fitLabel(view: TextView, label: String, availW: Int, availH: Int) {
        if (availW <= 0 || availH <= 0) return
        val density = activity.resources.displayMetrics.density
        // The keycap's 2dp border is drawn inside the background, so it eats into the room the
        // label has; measure against the cell minus that border (both sides) plus a hair, or a
        // "Ctrl" sized to the full cell gets its last stroke hidden under the frame.
        val usableW = (availW - 2 * KEY_BORDER_DP * density).toInt()
        val usableH = (availH - 2 * KEY_BORDER_DP * density).toInt()
        if (usableW <= 0 || usableH <= 0) return
        val paint = Paint(view.paint)
        val lines = label.split('\n')
        var sp = KEY_TEXT_MAX_SP.toFloat()
        while (sp > KEY_TEXT_MIN_SP) {
            val px = sp * density
            paint.textSize = px
            val widest = lines.maxOf { paint.measureText(it) }
            val tallest = px * 1.2f * lines.size
            if (widest <= usableW && tallest <= usableH) break
            sp -= 0.5f
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun initToggleKey(key: ToggleButton, keyCode: Int) {
        key.setOnCheckedChangeListener { _, isChecked ->
            sendKey(keyCode, isChecked)
            if (!isChecked) lockedToggleKeys.remove(key)
        }
        key.setOnLongClickListener {
            // Hold a modifier to pin it down past the next typed character; a plain tap
            // only holds it until that character lands. Same bargain as the key bar.
            key.toggle()
            if (key.isChecked) lockedToggleKeys.add(key)
            true
        }
        toggleKeys.add(key)
    }

    private fun initNormalKey(key: View, keyCode: Int) {
        key.setOnClickListener { sendKey(keyCode) }
        makeKeyRepeatable(key)
    }

    /**
     * When a View is touched, we schedule a callback to simulate a click. As long as the
     * finger stays on the view, we keep repeating this callback - which is what makes
     * holding Backspace or an arrow behave like a real keyboard.
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

    private fun onAfterKeyEvent(event: KeyEvent) {
        // A typed character lands with the modifiers still logically down; once it is
        // through, lift every modifier the user did not deliberately pin.
        if (event.action == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(event.keyCode))
            releaseUnlockedToggles()
    }

    private fun releaseUnlockedToggles() {
        toggleKeys.forEach {
            if (it.isChecked && !lockedToggleKeys.contains(it))
                it.isChecked = false
        }
    }

    private fun sendKey(keyCode: Int) {
        sendKey(keyCode, true)
        sendKey(keyCode, false)
    }

    private fun sendKey(keyCode: Int, isDown: Boolean) {
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        inputHandler.onVkKeyEvent(KeyEvent(action, keyCode))
    }

    /**********************************************************************************************
     * Dragging
     *********************************************************************************************/

    /**
     * Touch is on the grip bar only, and handled instead of using a click listener for
     * the same reason the assist ball does: the gesture has to resolve into either a
     * drag or a plain press before anything decides to move.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        grip?.setOnTouchListener { view, event ->
            val board = root ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Claim the whole gesture. The grip is a full-width bar the user drags
                    // sideways, and it lives inside the toolbar's DrawerLayout, whose content
                    // pane reads a horizontal drag as a drawer swipe and steals it - stranding
                    // the grip mid-gesture without ever delivering its ACTION_UP.
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    beginGesture(board, event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragLocked()) return@setOnTouchListener true
                    // If the ACTION_DOWN never reached us - the drawer took the previous
                    // gesture, so we never saw it end - re-anchor to THIS touch instead of
                    // dragging from the stale start. A stale startX still pointing at the home
                    // position would snap the board to the far edge on the very first move.
                    if (!tracking) beginGesture(board, event.rawX, event.rawY)
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    if (!dragged && hypot(dx, dy) > touchSlop) dragged = true
                    if (dragged) place(startX + dx, startY + dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (tracking && dragged) saveBias()
                    tracking = false
                    dragged = false
                }
            }

            // Consumed either way: a drag (or a stray tap) on the grip must never reach
            // the remote desktop underneath.
            true
        }
    }

    /** Mark the start of a drag: remember where the finger landed and where the board was. */
    private fun beginGesture(board: View, rawX: Float, rawY: Float) {
        downRawX = rawX
        downRawY = rawY
        startX = board.x
        startY = board.y
        dragged = false
        tracking = true
    }

    private fun isDragLocked(): Boolean = viewModel.fullKeyboardDragLocked.value == true

    /** Dim the grip when locking, so the board shows that it will not move. */
    private fun applyDragLock(locked: Boolean) {
        grip?.alpha = if (locked) .4f else 1f
    }

    private fun positionFromBias() {
        val board = root ?: return
        val runInfo = viewModel.pref.runInfo
        val (loX, hiX) = travelX(board)
        val (loY, hiY) = travelY(board)
        val biasX = runInfo.fullKeyboardXBias
        val biasY = runInfo.fullKeyboardYBias
        // A negative share on either axis is the sentinel for "never dragged"; the two together
        // are the corner a fresh install and the restore button mean - the board's own
        // bottom-left on the display's bottom-left.
        val x = if (biasX < 0f) 0f else biasX.fractionOf(loX, hiX)
        val y = if (biasY < 0f) bottomAlignedY(board) else biasY.fractionOf(loY, hiY)
        place(x, y)
    }

    /**
     * The lowest spot a board can take: its bottom edge on the bottom edge of the frame. This is
     * what a fresh install starts at and what the drawer's restore button writes back (a negative
     * bias; see [AppPreferences.FULL_KEYBOARD_Y_BIAS_DEFAULT]), and it is the bottom half of the
     * corner - the top half being the board's left edge on the frame's left edge.
     *
     * The frame's own bottom is the target even though something is usually parked there: the
     * system bar's strip, and below the board the virtual-key bar. Both used to be stepped
     * around, which left the board floating above them rather than in the corner; it is the
     * corner that is asked for now, and a board dropped over the key bar can be dragged up or
     * faded out, the bar having its own row in the drawer.
     *
     * A board taller than the frame - a large scale in landscape - has no such spot, so it is
     * pinned to the top instead and its last rows hang below the edge: the grip, the only way
     * to move it, stays reachable.
     */
    private fun bottomAlignedY(board: View): Float {
        val (loY, hiY) = travelY(board)
        return (parent.height - board.height).toFloat().coerceIn(loY, hiY)
    }

    /**
     * Back to the spot a fresh install shows the board in, from the drawer's restore button.
     *
     * Writing the stored fractions alone would not be enough: the drawer opens over a
     * running session, so the board is usually on screen while the button is pressed, and a
     * keyboard that only moved on the next rotation would look like the button did nothing.
     * A board that was never raised has no position to correct - it reads the restored pair
     * whenever it is next shown.
     */
    fun restoreDefaultPosition() {
        val runInfo = viewModel.pref.runInfo
        runInfo.fullKeyboardXBias = AppPreferences.FULL_KEYBOARD_X_BIAS_DEFAULT
        runInfo.fullKeyboardYBias = AppPreferences.FULL_KEYBOARD_Y_BIAS_DEFAULT

        val board = root
        if (board != null && isOpen) {
            if (board.isLaidOut) positionFromBias()
            else board.doOnNextLayout { positionFromBias() }
        }
    }

    private fun saveBias() {
        val board = root ?: return
        val runInfo = viewModel.pref.runInfo
        val (loX, hiX) = travelX(board)
        val (loY, hiY) = travelY(board)
        runInfo.fullKeyboardXBias = bias(board.x, loX, hiX)
        runInfo.fullKeyboardYBias = bias(board.y, loY, hiY)
    }

    /**
     * Horizontal travel, widened to the Windows convention: the board may be dragged partly
     * off either side of the frame, but never so far that the full-width grip loses its reach.
     * At least a [OFFSCREEN_MIN_WIDTH_FRACTION] slice of the board - and therefore a slice of
     * the grip that spans it - stays inside, so the strip can always be grabbed back. A board
     * wider than the frame already panned off-screen edges; this subsumes that case.
     */
    private fun travelX(board: View): Pair<Float, Float> {
        val keepVisible = board.width * OFFSCREEN_MIN_WIDTH_FRACTION
        val lo = keepVisible - board.width
        val hi = parent.width - keepVisible
        return if (lo <= hi) Pair(lo, hi)
        else Pair((parent.width - board.width) / 2f, (parent.width - board.width) / 2f)
    }

    /**
     * Vertical travel. The grip is the top row of the board, so the board's top is never allowed
     * above the frame (lo = 0): the grip stays fully visible and reachable. The bottom may sink
     * off the frame, but only until a grip-tall strip is left at the edge, so the board can never
     * be dragged somewhere it cannot be pulled back from.
     */
    private fun travelY(board: View): Pair<Float, Float> {
        val density = activity.resources.displayMetrics.density
        val gripKeep = (grip?.height ?: (GRIP_MIN_VISIBLE_DP * density).toInt()).toFloat()
        val hi = max(0f, parent.height - gripKeep)
        return Pair(0f, hi)
    }

    private fun bias(value: Float, lo: Float, hi: Float): Float =
            if (hi <= lo || !value.isFinite()) 0f else ((value - lo) / (hi - lo)).coerceIn(0f, 1f)

    private fun Float.fractionOf(lo: Float, hi: Float): Float =
            if (!isFinite()) lo else lo + coerceIn(0f, 1f) * (hi - lo)

    private fun place(x: Float, y: Float) {
        val board = root ?: return
        if (!x.isFinite() || !y.isFinite()) return
        val (loX, hiX) = travelX(board)
        val (loY, hiY) = travelY(board)
        board.x = x.coerceIn(loX, hiX)
        board.y = y.coerceIn(loY, hiY)
    }

    /**********************************************************************************************
     * Layout data
     *********************************************************************************************/

    private class KeySpec(val label: String, val keyCode: Int, val widthUnits: Float = 1f, val toggle: Boolean = false)

    companion object {
        /** One ordinary key's edge, in dp, at scale 1.0. The board keeps this size in both
         *  orientations; the drawer's scale multiplies it up or down proportionally. */
        private const val KEY_UNIT_DP = 40f

        /** Keycap border drawn inside the background, in dp; it steals width from the label. */
        private const val KEY_BORDER_DP = 2f

        /** Bounds for a key label, in sp: a bare letter sits at the top, long tags at the bottom. */
        private const val KEY_TEXT_MIN_SP = 7
        private const val KEY_TEXT_MAX_SP = 14

        /** Lowest alpha of the grip bar, however transparent the keys above it are set. */
        private const val GRIP_MIN_ALPHA = 0.4f

        /**
         * How much of the board is kept inside the display when it is dragged off an edge: a
         * quarter of its width, so some of the full-width grip always remains grabbable. The
         * grip is the board's top row, so vertically the top is pinned to the frame instead.
         */
        private const val OFFSCREEN_MIN_WIDTH_FRACTION = 0.25f

        /** Fallback height of the grip strip, in dp, before it has measured itself. */
        private const val GRIP_MIN_VISIBLE_DP = 40f

        private fun letter(c: Char) =
                KeySpec(c.uppercaseChar().toString(), KeyEvent.KEYCODE_A + (c - 'a'))

        private fun digit(c: Char) =
                KeySpec(c.toString(), if (c == '0') KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + (c - '1'))

        /** A key that carries two glyphs, the shifted one over the bare one, like `~` on `` ` ``. */
        private fun dual(shift: String, base: String, keyCode: Int, widthUnits: Float = 1f) =
                KeySpec("$shift\n$base", keyCode, widthUnits)

        /**
         * The full board, laid out to match moonlight-android-dev's floating keyboard:
         * a function row (ESC, F1-F12, Ins, Del), a number row that opens on the backtick
         * and closes on Back, the three staggered letter rows carrying their shifted
         * punctuation as a second glyph, and a bottom row of modifiers (Ctrl/Win/Alt on
         * both sides of Space) followed by the arrows and the Home/End/PageUp/PageDown
         * cluster. Labels and keycodes follow that reference one for one.
         */
        private val ROWS = listOf(
                listOf(KeySpec("ESC", KeyEvent.KEYCODE_ESCAPE)) +
                        (1..12).map { n -> KeySpec("F$n", KeyEvent.KEYCODE_F1 + (n - 1)) } +
                        listOf(KeySpec("Ins", KeyEvent.KEYCODE_INSERT),
                               KeySpec("Del", KeyEvent.KEYCODE_FORWARD_DEL)),

                listOf(dual("~", "`", KeyEvent.KEYCODE_GRAVE, 0.9f)) +
                        "1234567890".map { digit(it) } +
                        listOf(dual("_", "-", KeyEvent.KEYCODE_MINUS),
                               dual("+", "=", KeyEvent.KEYCODE_EQUALS),
                               KeySpec("Back", KeyEvent.KEYCODE_DEL, 1.4f)),

                listOf(KeySpec("Tab", KeyEvent.KEYCODE_TAB, 1.4f)) +
                        "qwertyuiop".map { letter(it) } +
                        listOf(dual("{", "[", KeyEvent.KEYCODE_LEFT_BRACKET),
                               dual("}", "]", KeyEvent.KEYCODE_RIGHT_BRACKET),
                               dual("|", "\\", KeyEvent.KEYCODE_BACKSLASH)),

                listOf(KeySpec("Cap", KeyEvent.KEYCODE_CAPS_LOCK, 1.5f, toggle = true)) +
                        "asdfghjkl".map { letter(it) } +
                        listOf(dual(":", ";", KeyEvent.KEYCODE_SEMICOLON),
                               dual("\"", "'", KeyEvent.KEYCODE_APOSTROPHE),
                               KeySpec("Enter", KeyEvent.KEYCODE_ENTER, 1.6f)),

                listOf(KeySpec("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 1.6f, toggle = true)) +
                        "zxcvbnm".map { letter(it) } +
                        listOf(dual("<", ",", KeyEvent.KEYCODE_COMMA),
                               dual(">", ".", KeyEvent.KEYCODE_PERIOD),
                               dual("?", "/", KeyEvent.KEYCODE_SLASH),
                               KeySpec("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, 1.6f, toggle = true)),

                listOf(KeySpec("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, toggle = true),
                       KeySpec("Win", KeyEvent.KEYCODE_META_LEFT, toggle = true),
                       KeySpec("Alt", KeyEvent.KEYCODE_ALT_LEFT, toggle = true),
                       KeySpec("Space", KeyEvent.KEYCODE_SPACE, 0.95f),
                       KeySpec("Alt", KeyEvent.KEYCODE_ALT_RIGHT, toggle = true),
                       KeySpec("Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, toggle = true),
                       KeySpec("\u2191", KeyEvent.KEYCODE_DPAD_UP),
                       KeySpec("\u2193", KeyEvent.KEYCODE_DPAD_DOWN),
                       KeySpec("\u2190", KeyEvent.KEYCODE_DPAD_LEFT),
                       KeySpec("\u2192", KeyEvent.KEYCODE_DPAD_RIGHT),
                       KeySpec("Home", KeyEvent.KEYCODE_MOVE_HOME),
                       KeySpec("End", KeyEvent.KEYCODE_MOVE_END),
                       KeySpec("PgUp", KeyEvent.KEYCODE_PAGE_UP),
                       KeySpec("PgDn", KeyEvent.KEYCODE_PAGE_DOWN)),
        )
    }
}
