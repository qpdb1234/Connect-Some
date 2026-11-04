package com.limelight.stream

/**
 * Pointer (mouse) buttons, kept bit-compatible with the original AVNC
 * [com.gaurav.avnc.vnc.PointerButton] so the [com.limelight.session.Messenger]
 * pointer-mask bookkeeping works unchanged on top of the new [StreamClient].
 *
 * The bit values match the RFB pointer-event button mask. Moonlight does not use
 * a combined mask (it exposes per-button + absolute/relative calls), so the
 * [StreamClient] implementation interprets the mask accordingly.
 */
enum class PointerButton(val bitMask: Int) {
    None(0),
    Left(1),
    Middle(2),
    Right(4),
    WheelUp(8),
    WheelDown(16),
    WheelLeft(32),
    WheelRight(64)
}
