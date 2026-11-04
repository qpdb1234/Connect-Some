/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import android.util.Base64

/**
 * The base64 alphabet ADB expects.
 *
 * The reference project injected this as an interface so its pure-Java ADB code
 * could stay platform-agnostic; on Android there is exactly one implementation, so
 * it is collapsed into an object. `NO_WRAP` matters: a line break inside the public
 * key payload makes adbd reject the authentication.
 */
object AdbBase64 {
    fun encodeToString(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun decode(data: ByteArray): ByteArray = Base64.decode(String(data), Base64.NO_WRAP)
}
