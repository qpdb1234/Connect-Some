/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.prefs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.KeyCharacterMap
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limelight.App
import com.limelight.R
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.databinding.FragmentKeyTestBinding
import com.limelight.databinding.FragmentLogsBinding
import com.limelight.databinding.FragmentTouchTestBinding
import com.limelight.util.Debugging
import com.limelight.util.FloatingText
import com.limelight.util.PersistentLog
import com.limelight.util.setClipboardTextWithNotification
import com.limelight.util.showKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class DebugFragment : Fragment() {
    abstract fun title(): String

    override fun onResume() {
        super.onResume()
        activity?.title = title()
    }

    fun copyLogs(logs: String) {
        val wrapped = Debugging.wrapLogs(title(), logs)
        requireActivity().setClipboardTextWithNotification(wrapped)
    }

    fun snackbar(text: String) = FloatingText.show(requireView(), text, FloatingText.LENGTH_SHORT)
}

/**
 * Ceiling for the live event logs of the touch/key testers.
 *
 * Both record one line per input event, and an event stream has no natural end: a few
 * minutes of dragging already means tens of thousands of lines, which the `TextView` has
 * to lay out again on every append. Past this many characters the log restarts with a
 * note, because the recent events are the ones being looked at anyway.
 */
private const val MAX_DEBUG_LOG_CHARS = 200_000

/** What the two testers write where the discarded events used to be. */
private const val DROPPED_NOTE = "===== Older events dropped ====="


/**
 * The recent, partial view of the device's logcat buffer, reached from the Tools screen.
 * What it shows is whatever the ring buffer still holds: exporting and clearing live on
 * the Tools screen next to the file-capture dropdown, not here. With log recording off
 * the buffer is deliberately not read: it is full of lines this app can switch off
 * nowhere - other apps' output and framework noise inside our own process (an
 * OpenGLRenderer warning is not one of our log calls). Only the frozen record file
 * remains, which after a clear means this screen honestly says "no logs".
 */
@Keep
class LogsFragment : DebugFragment() {

    override fun title() = getString(R.string.pref_logs)

    private var logs = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentLogsBinding.inflate(inflater, container, false)
        binding.logsRv.layoutManager = LinearLayoutManager(requireContext())
        binding.logsRv.adapter = LogsAdapter(listOf(getString(R.string.msg_debug_loading)))

        val app = requireContext().applicationContext as App
        lifecycleScope.launch(Dispatchers.IO) {
            val content = if (app.prefs.viewer.loggingMode == "off")
                PersistentLog.existing(app)?.readText() ?: ""
            else
                Debugging.logcat()
            logs = content.ifBlank { getString(R.string.msg_debug_no_logs) }
            val lines = logs.lines()
            withContext(Dispatchers.Main) {
                binding.logsRv.adapter = LogsAdapter(lines)
                if (lines.isNotEmpty())
                    binding.logsRv.scrollToPosition(lines.size - 1)
            }
        }

        binding.copyBtn.setOnClickListener { copyLogs(logs) }
        return binding.root
    }

    class LogsAdapter(private val logList: List<String>) : RecyclerView.Adapter<LogsViewHolder>() {
        private var normalTextColor = Color.GRAY
        private var errorTextColor = Color.RED

        override fun getItemCount() = logList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogsViewHolder {
            val tv = TextView(parent.context)
            tv.typeface = Typeface.MONOSPACE
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tv.setSingleLine()
            normalTextColor = tv.currentTextColor
            return LogsViewHolder(tv)
        }

        override fun onBindViewHolder(holder: LogsViewHolder, position: Int) {
            val line = logList[position]
            val lineColor = if (line.contains(" E ")) errorTextColor else normalTextColor

            holder.tv.text = line
            holder.tv.setTextColor(lineColor)
        }
    }

    class LogsViewHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)
}


/**
 * This fragment is used to record [MotionEvent]s generated for a gesture.
 * It can help in understanding why a gesture is not working correctly on
 * a particular device. It also helps in understanding how [MotionEvent]s
 * are generated for other devices like Stylus, Touchpad etc.
 *
 * The gesture area shows a live readout (action/position/pointer count) while
 * the event log below scrolls every recorded event in real time so the user
 * gets immediate feedback without having to copy the logs first.
 */
@Keep
class TouchTestFragment : DebugFragment() {

    override fun title() = getString(R.string.pref_touch_test)

    private lateinit var binding: FragmentTouchTestBinding
    private val eventLog = StringBuilder()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentTouchTestBinding.inflate(inflater, container, false)

        @Suppress("ClickableViewAccessibility")
        binding.gestureArea.setOnTouchListener { _, e -> record("Touch", e) }
        binding.gestureArea.setOnGenericMotionListener { _, e -> record("Generic", e) }
        binding.gestureArea.setOnHoverListener { _, e -> record("Hover", e) }

        binding.gestures.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<TextView>(checkedIds[0])
                append("\n\n===== GestureStart: " + chip.text + " =====")
            }
        }

        binding.resetBtn.setOnClickListener {
            binding.gestures.clearCheck()
            eventLog.setLength(0)
            binding.eventLog.text = ""
            snackbar(getString(R.string.action_reset))
        }

        binding.copyBtn.setOnClickListener { copyLogs(eventLog.toString()) }
        return binding.root
    }

    private fun record(prefix: String, e: MotionEvent): Boolean {
        append(prefix + ": " + format(e))
        binding.gestureArea.text = liveStatus(e)
        return true
    }

    private fun append(line: String) {
        if (eventLog.length > MAX_DEBUG_LOG_CHARS) {
            eventLog.setLength(0)
            binding.eventLog.text = ""
            eventLog.appendLine(DROPPED_NOTE)
            binding.eventLog.append(DROPPED_NOTE + "\n")
        }
        eventLog.appendLine(line)
        binding.eventLog.append(line + "\n")
        // NB: scrollTo (not fullScroll) - fullScroll would move focus onto the scroll
        // view, after which the input area stops receiving key events.
        binding.logScroll.post { binding.logScroll.scrollTo(0, binding.eventLog.bottom) }
    }

    private fun liveStatus(e: MotionEvent): String {
        val action = MotionEvent.actionToString(e.actionMasked)
        val ptrs = getString(R.string.label_pointer_count, e.pointerCount)
        val pos = "(" + coord(e.getX(0)) + ", " + coord(e.getY(0)) + ")"
        return getString(R.string.label_gesture_now, action, ptrs, pos)
    }

    private fun format(e: MotionEvent): String {
        val sb = StringBuilder()
        sb.append(MotionEvent.actionToString(e.actionMasked))
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            sb.append("(idx=").append(e.actionIndex).append(')')
        }
        sb.append(" ptrs=").append(e.pointerCount)
        for (i in 0 until e.pointerCount.coerceAtMost(10)) {
            sb.append(" p").append(i)
                    .append("(id=").append(e.getPointerId(i))
                    .append(", ").append(coord(e.getX(i)))
                    .append(", ").append(coord(e.getY(i))).append(')')
        }
        sb.append(" tool=").append(toolName(e.getToolType(0)))
        if (e.buttonState != 0) sb.append(" btn=").append(buttonsName(e.buttonState))
        if (e.metaState != 0) sb.append(" meta=").append(metaName(e.metaState))
        sb.append(" dev=").append(e.device?.name ?: "-")
        return sb.toString()
    }

    private fun toolName(toolType: Int) = when (toolType) {
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        else -> "type$toolType"
    }

    private fun buttonsName(state: Int) = buildString {
        val names = listOf(
            MotionEvent.BUTTON_PRIMARY to "PRIMARY", MotionEvent.BUTTON_SECONDARY to "SECONDARY",
            MotionEvent.BUTTON_TERTIARY to "TERTIARY", MotionEvent.BUTTON_BACK to "BACK",
            MotionEvent.BUTTON_FORWARD to "FORWARD", MotionEvent.BUTTON_STYLUS_PRIMARY to "STYLUS_P",
            MotionEvent.BUTTON_STYLUS_SECONDARY to "STYLUS_S",
        )
        append(names.filter { state and it.first != 0 }.joinToString("|") { it.second })
    }

    private fun metaName(state: Int) = buildString {
        val names = listOf(
            KeyEvent.META_SHIFT_ON to "SHIFT", KeyEvent.META_ALT_ON to "ALT",
            KeyEvent.META_CTRL_ON to "CTRL", KeyEvent.META_META_ON to "META",
            KeyEvent.META_SYM_ON to "SYM", KeyEvent.META_FUNCTION_ON to "FN",
        )
        append(names.filter { state and it.first != 0 }.joinToString("|") { it.second })
    }

    private fun coord(v: Float) = String.format("%.1f", v)
}

/**
 * This fragment is used to record [KeyEvent]s generated for different key presses.
 * It can help in understanding key combination generates which character.
 *
 * Besides the raw event info, each line shows what the Moonlight keyboard bridge
 * ([com.limelight.ui.vnc.input.MoonlightKeyHandler]) would send to the host: the
 * translated VK scancode, or the UTF-8 text fallback path. This allows verifying
 * keyboard input without connecting to a stream host.
 */
@Keep
class KeyTestFragment : DebugFragment() {
    override fun title() = getString(R.string.pref_key_test)

    lateinit var binding: FragmentKeyTestBinding
    private val eventLog = StringBuilder()
    private val translator = KeyboardTranslator()

    // The preview EditText can take focus once it starts handling text, and injected/
    // soft keys may then arrive elsewhere. lastLogged dedupes so an event seen by more
    // than one listener is still recorded only once.
    private var lastLogged: KeyEvent? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentKeyTestBinding.inflate(inflater, container, false)

        binding.inputArea.setOnKeyListener { _, _, event ->
            logKey(event)
            event.dispatch(binding.preview, binding.preview.keyDispatcherState, binding.preview)
        }

        binding.inputArea.setOnClickListener { showKeyboard(binding.inputArea) }

        binding.resetBtn.setOnClickListener {
            eventLog.setLength(0)
            lastLogged = null
            binding.eventLog.text = ""
            binding.preview.setText("")
            snackbar(getString(R.string.action_reset))
        }

        binding.copyBtn.setOnClickListener {
            eventLog.appendLine("\n\nPreview text: " + binding.preview.text)
            copyLogs(eventLog.toString())
        }

        binding.inputArea.requestFocus()
        return binding.root
    }

    private fun logKey(event: KeyEvent) {
        if (event === lastLogged) return
        lastLogged = event
        append(formatKey(event))
    }

    private fun append(line: String) {
        if (eventLog.length > MAX_DEBUG_LOG_CHARS) {
            eventLog.setLength(0)
            binding.eventLog.text = ""
            eventLog.appendLine(DROPPED_NOTE)
            binding.eventLog.append(DROPPED_NOTE + "\n")
        }
        eventLog.appendLine(line)
        binding.eventLog.append(line + "\n")
        binding.logScroll.post { binding.logScroll.scrollTo(0, binding.eventLog.bottom) }
    }

    private fun metaName(state: Int) = buildString {
        val names = listOf(
            KeyEvent.META_SHIFT_ON to "SHIFT", KeyEvent.META_ALT_ON to "ALT",
            KeyEvent.META_CTRL_ON to "CTRL", KeyEvent.META_META_ON to "META",
            KeyEvent.META_SYM_ON to "SYM", KeyEvent.META_FUNCTION_ON to "FN",
        )
        append(names.filter { state and it.first != 0 }.joinToString("|") { it.second })
    }

    private fun formatKey(e: KeyEvent): String {
        val action = when (e.action) {
            KeyEvent.ACTION_DOWN -> "DOWN "
            KeyEvent.ACTION_UP -> "UP   "
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "OTHER"
        }

        val sb = StringBuilder(action).append(' ')
                .append(KeyEvent.keyCodeToString(e.keyCode))
                .append(" scan=").append(e.scanCode)

        if (e.action == KeyEvent.ACTION_MULTIPLE) {
            e.characters?.let { sb.append(" chars=").append(it) }
        } else {
            val u = e.getUnicodeChar()
            if (u != 0 && u and KeyCharacterMap.COMBINING_ACCENT == 0) {
                sb.append(" utf='").append(u.toChar()).append('\'')
            }
            if (e.repeatCount > 0) sb.append(" x").append(e.repeatCount + 1)
        }
        if (e.metaState != 0) sb.append(" meta=").append(metaName(e.metaState))

        // What the Moonlight keyboard bridge would receive for this event.
        sb.append("  ->  ")
        when (e.action) {
            KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP -> {
                val vk = translator.translate(e.keyCode, e.deviceId)
                if (vk.toInt() != 0) {
                    sb.append("vk=0x").append(Integer.toHexString(vk.toInt() and 0xFFFF))
                } else {
                    val u = e.getUnicodeChar()
                    if (u != 0 && u and KeyCharacterMap.COMBINING_ACCENT == 0)
                        sb.append("UTF-8 text '").append(u.toChar()).append('\'')
                    else
                        sb.append("ignored")
                }
            }
            else -> {
                val chars = e.characters
                if (!chars.isNullOrEmpty()) sb.append("UTF-8 text ").append(chars)
                else sb.append("ignored")
            }
        }
        return sb.toString()
    }

    override fun onStop() {
        super.onStop()
        if (binding.autoCopy.isChecked)
            binding.copyBtn.callOnClick()
    }
}

class KeyTestView(context: Context, attributeSet: AttributeSet?) : View(context, attributeSet) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): BaseInputConnection {
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return BaseInputConnection(this, false)
    }

    override fun onCheckIsTextEditor() = true
}