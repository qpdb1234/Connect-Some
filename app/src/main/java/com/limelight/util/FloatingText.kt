/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.limelight.App

/**
 * The message that floats up over the bottom edge of a screen - the one Material calls a
 * Snackbar - shown through one door instead of `Snackbar.make` at every call site.
 *
 * Both things the app adds here are impossible from the outside. Keeping the bar in hand is what
 * lets the next touch anywhere put it away: a bar covering the bottom of a screen also swallows
 * the touches that land on it (its own root is deliberately clickable), so a button it happens to
 * sit on is unreachable until the timeout expires. And keeping it in hand is what lets the user's
 * chosen transparency be applied to the bar as it is built, which no theme resource can do because
 * the number is typed on a slider at runtime.
 *
 * The two are connected by the sheet described in [Watch]: it fills the window while a bar is up
 * and is offered every touch first, so one touch anywhere ends the message - spent on the ending
 * where it lands on the message, and handed on to whatever is underneath where it lands beside it.
 */
object FloatingText {

    // Re-exported so a call site that only ever shows messages needs nothing but this file.
    const val LENGTH_SHORT = Snackbar.LENGTH_SHORT
    const val LENGTH_LONG = Snackbar.LENGTH_LONG
    const val LENGTH_INDEFINITE = Snackbar.LENGTH_INDEFINITE

    /**
     * Shows [message] at the bottom of [anchor]'s window. Only one message is ever up: a second
     * one replaces the first rather than stacking under it, exactly as Material's own queue does.
     *
     * [actionLabel] with an [action] turns the message into a question with one answer - the
     * delete snackbar's Undo - and a touch on that answer is the one touch that does not dismiss.
     */
    @JvmStatic
    @JvmOverloads
    fun show(anchor: View, message: CharSequence, duration: Int = LENGTH_LONG,
             actionLabel: CharSequence? = null, action: (() -> Unit)? = null) {
        present(anchor, Snackbar.make(anchor, message, duration), actionLabel, action)
    }

    /** [show] with the message still in a resource, so a call site does not have to unwrap it. */
    @JvmStatic
    @JvmOverloads
    fun show(anchor: View, @StringRes messageRes: Int, duration: Int = LENGTH_LONG,
             actionLabel: CharSequence? = null, action: (() -> Unit)? = null) {
        present(anchor, Snackbar.make(anchor, messageRes, duration), actionLabel, action)
    }

    private fun present(anchor: View, bar: Snackbar, actionLabel: CharSequence?,
                        action: (() -> Unit)?) {
        val hasAction = actionLabel != null && action != null
        if (hasAction)
            bar.setAction(actionLabel) { action() }

        applyTransparency(bar)

        // Per bar rather than one global listener: each message owns the sheet it put up and
        // takes it down again, so a bar that gets replaced while its replacement is still in the
        // queue cannot leave a second sheet stranded in the window.
        val watch = Watch(bar, hasAction)
        bar.addCallback(watch)

        // The sheet goes up before the bar is shown rather than from the callback that reports the
        // bar as shown, because that one waits for the entry animation. A touch arriving while the
        // bar is still sliding in would then find nothing in front of it and be held by the bar -
        // the swallowing this file exists to prevent, for the longest stretch of a short message.
        // The bar has no parent yet, so the sheet covers the window's content view, which is where
        // a bar with no CoordinatorLayout around it is about to be added; [Watch.onShown] moves the
        // sheet should the bar have found somewhere else to live.
        watch.fitTo(anchor)
        bar.show()
    }

    /**
     * Fades the whole message to the transparency picked in the interface settings.
     *
     * The slide animation is chosen explicitly because the other mode the bar can be given - fade -
     * animates the root view's alpha from 0 to 1 as it comes in, which would silently undo whatever
     * is set here as soon as the entry animation finishes. Sliding only moves the bar.
     */
    private fun applyTransparency(bar: Snackbar) {
        val percent = (bar.view.context.applicationContext as? App)
                ?.prefs?.ui?.floatingTextTransparencyPercent ?: 0
        val transparency = percent.coerceIn(AppPreferences.FLOATING_TEXT_TRANSPARENCY_MIN,
                AppPreferences.FLOATING_TEXT_TRANSPARENCY_MAX)

        bar.setAnimationMode(BaseTransientBottomBar.ANIMATION_MODE_SLIDE)
        bar.view.alpha = 1f - transparency / 100f
    }

    /**
     * Watches one message: the sheet that turns the next touch into a dismissal goes up before it
     * and comes down with it.
     */
    private class Watch(private val bar: Snackbar, private val hasAction: Boolean)
        : Snackbar.Callback() {

        private var sheet: View? = null

        /** Whether the sheet is holding on to the gesture that began on it. */
        private var holding = false

        /** Spreads the sheet over the window [anchor] is in, ahead of the bar reaching it. */
        fun fitTo(anchor: View) {
            attachTo(anchor.rootView.findViewById(android.R.id.content))
        }

        override fun onShown(sb: Snackbar) {
            // Almost always the very group the sheet was put into a moment ago: no screen here
            // wraps a CoordinatorLayout, which is the only other place a bar can be added. The
            // sheet follows the bar instead of assuming, since a sheet in the wrong window would
            // catch touches the message never sees and let the message sit past its dismissal.
            attachTo(bar.view.parent as? ViewGroup)
        }

        override fun onDismissed(sb: Snackbar?, event: Int) {
            removeSheet()
        }

        /** Lays the sheet over [parent], unless it is already lying there. */
        private fun attachTo(parent: ViewGroup?) {
            if (parent == null || parent === sheet?.parent)
                return
            removeSheet()

            val sheet = View(parent.context)
            sheet.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT)
            // A group offers touches in height order, so the sheet - transparent, drawing nothing -
            // has to be the first to be offered one, ahead of the message as well. That is why the
            // message's own answer button is pressed from here rather than left to the touch: to
            // have the sheet reach the button by itself it would have to sit under the message and
            // over every other view in the window, and no height does both.
            sheet.translationZ = SHEET_Z
            sheet.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN)
                    holding = onTouch(event)
                holding
            }
            parent.addView(sheet)
            this.sheet = sheet
        }

        /**
         * Takes the first touch of a gesture and says whether the sheet keeps the rest of it. A
         * touch it keeps has nowhere further to go; one it lets go travels on to the views under
         * the sheet, which is the point of getting out of the way.
         */
        private fun onTouch(event: MotionEvent): Boolean {
            // The one answer a message can offer - the delete bar's Undo. Pressing it is not a way
            // to make a message vanish before the button has been heard, and the button puts the
            // message away itself as it works.
            if (hasAction) {
                clickableUnder(bar.view, event.rawX, event.rawY)?.let {
                    it.performClick()
                    return true
                }
            }

            bar.dismiss()
            // A touch on the message is spent on the making-it-go-away: the button hiding behind
            // the message is not pressed as well, since a press nobody meant is worse than having
            // to tap once more. A touch beside the message only dismisses and carries on.
            return onScreen(bar.view, event.rawX, event.rawY)
        }

        /**
         * The clickable view of the message at ([x], [y]), or none. The bar's own root is passed
         * over: it is clickable only to hold touches this sheet has now taken. Children are read
         * back to front so that whatever is drawn on top wins.
         */
        private fun clickableUnder(view: View, x: Float, y: Float): View? {
            if (view is ViewGroup)
                for (i in view.childCount - 1 downTo 0) {
                    val hit = clickableUnder(view.getChildAt(i), x, y)
                    if (hit != null)
                        return hit
                }
            return if (view !== bar.view && view.isClickable &&
                    onScreen(view, x, y)) view else null
        }

        /**
         * Whether the point - measured on the screen - is where [view] stands at this moment.
         *
         * Screen coordinates throughout: a touch's raw position is measured against the screen, so
         * the views have to be located on the screen too. Against the window instead, a screen
         * whose window does not start at its top left reads every touch as missing the message.
         */
        private fun onScreen(view: View, x: Float, y: Float): Boolean {
            if (!view.isShown)
                return false
            val where = IntArray(2)
            view.getLocationOnScreen(where)
            return x >= where[0] && x < where[0] + view.width &&
                    y >= where[1] && y < where[1] + view.height
        }

        private fun removeSheet() {
            sheet?.let { (it.parent as? ViewGroup)?.removeView(it) }
            sheet = null
            holding = false
        }

        private companion object {
            /** Above anything the bar can raise itself to, in raw pixels. */
            const val SHEET_Z = 10000f
        }
    }
}
