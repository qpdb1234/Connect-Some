/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.vnc

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root of the floating mini window.
 *
 * It is a plain FrameLayout with one addition: [onTouchHandle] sees every motion event the
 * window receives, including the ones a child consumes - which is only possible above
 * [dispatchTouchEvent], where the event still belongs to nobody.
 *
 * The window needs that vantage point because its chrome is supposed to disappear while the
 * user is looking at the picture and come back the moment they touch anything. A normal
 * [setOnTouchListener] would only report touches that no button, no drag handle and no
 * stream input claimed, which is precisely the set that says nothing about the user being
 * there. Upstream's mini window does the same trick with its own root subclass.
 */
class MiniWindowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                              defStyleAttr: Int = 0) : FrameLayout(context, attrs, defStyleAttr) {

    /** Called with every event, before any child gets a chance at it. */
    var onTouchHandle: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onTouchHandle?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
