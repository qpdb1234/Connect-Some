/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.limelight.viewmodel.VncViewModel
import com.limelight.util.AccentTheme
import com.limelight.util.AppPreferences
import java.lang.ref.WeakReference

/**
 * This is a simple, transparent view to handle input events.
 * It acts as an edit box to handle key events.
 *
 * It also draws the marker that says where the remote pointer is (see [onDraw]) - it sits
 * over the picture with the same origin as the view underneath, which makes it the natural
 * place for something that has to land on a point of the stream.
 */
class InputView(context: Context?, attrs: AttributeSet? = null) : View(context, attrs) {

    private var inputHandler: InputHandler? = null
    private var viewModel: VncViewModel? = null

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerPath = Path()

    /**
     * The size fraction and the shape [markerPath] were measured out for, kept so that moving
     * the size slider or picking the other shape rebuilds the mark instead of redrawing the one
     * already in hand. The shape starts unset, which is also what makes the first frame build it.
     */
    private var markerScale = 0f
    private var markerShape: String? = null

    /**
     * The radius the dot is drawn at, in the same scaled units as [markerPath].
     */
    private var markerDotRadius = 0f

    /**
     * Input connection used for intercepting key events. The viewer is not a text editor of
     * its own - it mirrors the field on the host - so the connection tracks that mirror and
     * forwards only what changed (see [RemoteInputConnection]). The live one is kept so
     * [resetInputModel] can clear the mirror when a session ends.
     */
    private var connection: RemoteInputConnection? = null

    /**
     * Forget everything the input connection remembered of the last session's field, so the
     * next one starts from an empty mirror. Called when a session connects or disconnects.
     */
    fun resetInputModel() {
        connection?.reset()
        connection = null
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
    }

    /**
     * Should be called from [com.limelight.ui.vnc.VncActivity.onCreate].
     */
    fun initialize(viewModel: VncViewModel, inputHandler: InputHandler) {
        this.viewModel = viewModel
        this.inputHandler = inputHandler

        // Whatever repaints the frame now repaints the marker with it.
        viewModel.pointerOverlayRef = WeakReference(this)

        // Hide local cursor if requested and supported
        if (Build.VERSION.SDK_INT >= 24 && viewModel.pref.input.hideLocalCursor)
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    }

    /**
     * The remote pointer, marked at its own position. The system arrow of an attached mouse
     * is a different thing entirely and stays under Settings -> Input.
     *
     * Nothing is drawn until the session has actually reported a position: (0, 0) is the top
     * left of every desktop, so a marker there would be a statement about the computer rather
     * than the absence of one.
     *
     * What [client] holds is a position in the host's own pixels, and [toVP] is the same mapping
     * the input path uses in reverse: a round trip through the two of them is what makes this
     * mark land where we told the host to point rather than where we guess it pointed. The two
     * protocols still differ about the number itself, and no amount of drawing can fix that:
     * RFB is told the real position by the server (unless a local gesture has the pointer,
     * which sets [VncClient.ignorePointerMovesByServer]), while GameStream never reports one -
     * there the coordinates are the absolute position we last sent. When the host's desktop and
     * the streamed picture disagree (Sunshine adding its own bars, say), the leftover offset is
     * not something the client can compute; the dot below is then the honest choice, because it
     * claims nothing about the host's arrow beyond the point we asked for.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vm = viewModel ?: return
        if (!vm.pref.viewer.showLocalCursorMarker) return

        val client = vm.client ?: return
        if (!client.pointerPositionKnown) return

        val tip = vm.frameState.toVP(PointF(client.pointerX.toFloat(), client.pointerY.toFloat()))
        if (!tip.x.isFinite() || !tip.y.isFinite()) return

        val scale = vm.pref.viewer.cursorMarkerSizePercent
                .coerceIn(AppPreferences.CURSOR_MARKER_SIZE_MIN, AppPreferences.CURSOR_MARKER_SIZE_MAX) / 100f
        val shape = vm.pref.viewer.cursorMarkerShape
        if (shape != markerShape || scale != markerScale) setupMarker(scale, shape)

        // Read every frame so a color picked in the toolbar shows on the very next draw rather
        // than only once the shape or size happens to change.
        markerPaint.color = AccentTheme.argbOf(vm.pref.viewer.cursorMarkerColor)

        if (shape == AppPreferences.CURSOR_MARKER_SHAPE_DOT) {
            // Centered on the coordinate, with no compensation of any kind: a plain filled
            // circle, deliberately bare - a ring around it reads as a second pointer.
            canvas.drawCircle(tip.x, tip.y, markerDotRadius, markerPaint)
            return
        }

        canvas.save()
        canvas.translate(tip.x, tip.y)
        // The arrow as one flat fill, with no outline of any kind. Its tip is exactly (0,0), which
        // is the coordinate itself, so the canvas goes straight to the pointer with no correction.
        canvas.drawPath(markerPath, markerPaint)
        canvas.restore()
    }

    /**
     * Measure out the mark at its own [scale]: either the arrow below or the dot, which is only
     * a radius. Both are a single flat fill in the user's chosen marker color (see [onDraw],
     * which sets it every frame) and nothing else - no outline and no ring, because a border
     * around the mark reads as a second pointer sitting on top of the first.
     *
     * The arrow is the standard Windows pointer silhouette, taken from the system cursor itself.
     * Every vertex below is a fraction of [height] and of nothing else: the figure is measured out
     * in one isotropic unit, so its edges come out at the angles the real arrow has them at. Giving
     * the width its own factor instead - the 16x20dp box this replaces - sheared every edge that is
     * not vertical: the tail came out as a narrow sliver folded back on itself, and the long
     * diagonal left the tip at 54 degrees rather than the 45.6 the cursor actually has, which is
     * what made the mark read as a broken arrow at any size.
     *
     * Walked once: down the left edge to 92.5% of the height, up-right at exactly 45 degrees to the
     * notch where the tail starts, down the tail's left side to its corner at the full height,
     * across the cap square to the tail, up its right side - the same slope, so the band keeps one
     * width all the way down - out along the wing to 67.4% of the height, and home to the tip on
     * the diagonal. Any stored shape that is not the dot draws this arrow, so an unset or
     * hand-edited value lands on the shipped default.
     */
    private fun setupMarker(scale: Float, shape: String?) {
        markerScale = scale
        markerShape = shape

        val metrics = resources.displayMetrics
        val height = 20f * metrics.density * scale

        if (shape != AppPreferences.CURSOR_MARKER_SHAPE_DOT) {
            // The silhouette of the Windows pointer arrow in the order the KDoc gives it: tip,
            // left edge, notch, the tail's corner and cap, under the wing, out to the wing.
            markerPath.reset()
            markerPath.moveTo(0f, 0f)
            markerPath.lineTo(0f, .9252f * height)
            markerPath.lineTo(.2095f * height, .7157f * height)
            markerPath.lineTo(.3339f * height, height)
            markerPath.lineTo(.4858f * height, .9335f * height)
            markerPath.lineTo(.3661f * height, .6599f * height)
            markerPath.lineTo(.6735f * height, .6599f * height)
            markerPath.close()
            return
        }

        // The dot: half the arrow's height, so the slider means roughly the same thing for
        // both shapes - a mark at 100% covers the same patch of the picture either way.
        markerDotRadius = 0.5f * height
        markerPath.reset()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = outAttrs.imeOptions or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        // A plain text field with the usual soft-keyboard behavior. The connection answers the
        // IME as a real editor, so the input method keeps its own composition/candidate state
        // and revises it in place instead of replaying every keystroke to the host.
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        val handler = inputHandler ?: return BaseInputConnection(this, false)
        return RemoteInputConnection(this, handler) { handler.imeTextMode }.also { connection = it }
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return inputHandler?.onTouchEvent(event) == true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return inputHandler?.onGenericMotionEvent(event) == true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        return inputHandler?.onHoverEvent(event) == true
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        return inputHandler?.onCapturedPointerEvent(event) == true
    }
}