/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.entity;

import android.os.Build;

import com.limelight.androidserver.utils.L;

public final class Options {

    /**
     * Clipboard ceiling used when the host sends no `clipMax` argument. This is also the
     * ceiling an older host cannot exceed, which is why the client tolerates inbound
     * payloads of this size even after the limit was lowered - do not change it lightly.
     */
    public static final int CLIPBOARD_MAX_DEFAULT = 5000;

    public static boolean isAudio = true;
    public static int maxSize = 1600;
    public static int maxVideoBit = 4000000;
    public static int maxFps = 60;
    public static int displayId = 0;
    public static boolean keepAwake = true;
    public static boolean TurnOnScreenIfStart = true;
    public static boolean TurnOffScreenIfStart = false;
    public static boolean TurnOffScreenIfStop = false;
    public static boolean TurnOnScreenIfStop = true;
    public static boolean useH265 = true;
    public static boolean useOpus = true;
    public static int mirrorMode = 0;

    /** Largest clipboard payload one packet may carry, in UTF-8 bytes; both directions. */
    public static int clipboardMax = CLIPBOARD_MAX_DEFAULT;

    public static void parse(String... args) {
        for (String arg : args) {
            // One argument is one argument: a value the helper cannot make sense of (an
            // older controller sending a format this build does not know, a hand typed
            // command line) used to throw out of parse() and end the process before it had
            // even opened a socket, which the controller could only report as a failure to
            // connect. The rest of the options are still worth honouring.
            try {
                parseOne(arg);
            } catch (RuntimeException e) {
                L.w("ignoring unparsable option: " + arg + " (" + e + ")");
            }
        }
    }

    private static void parseOne(String arg) {
        int equalIndex = arg.indexOf('=');
            if (equalIndex == -1) throw new IllegalArgumentException("参数格式错误");
            String key = arg.substring(0, equalIndex);
            String value = arg.substring(equalIndex + 1);
            switch (key) {
                case "isAudio":
                    isAudio = Integer.parseInt(value) == 1;
                    break;
                case "maxSize":
                    // Negative would make every reported dimension negative, and a huge one
                    // asks the codec for a surface no device can allocate. Both read as a
                    // broken connection, so they are clamped instead of trusted.
                    maxSize = Math.max(Math.min(Integer.parseInt(value), 8192), 0);
                    break;
                case "maxFps":
                    maxFps = Math.max(Math.min(Integer.parseInt(value), 240), 1);
                    break;
                case "maxVideoBit":
                    // Multiplied into bits per second: beyond ~2100 the int overflows into a
                    // negative bitrate, which the codec rejects outright.
                    maxVideoBit = Math.max(Math.min(Integer.parseInt(value), 100), 1) * 1000000;
                    break;
                case "displayId":
                    displayId = Integer.parseInt(value);
                    break;
                case "keepAwake":
                    keepAwake = Integer.parseInt(value) == 1;
                    break;
                case "ScreenMode":
                    int ScreenMode = Integer.parseInt(value);
                    TurnOnScreenIfStart = (ScreenMode / 1000) % 10 == 1;
                    TurnOffScreenIfStart = (ScreenMode / 100) % 10 == 1;
                    TurnOffScreenIfStop = (ScreenMode / 10) % 10 == 1;
                    TurnOnScreenIfStop = ScreenMode % 10 == 1;
                    break;
                case "useH265":
                    useH265 = Integer.parseInt(value) == 1;
                    break;
                case "useOpus":
                    useOpus = Integer.parseInt(value) == 1;
                    break;
                case "mirrorMode":
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mirrorMode = 0;
                        break;
                    }
                    mirrorMode = Integer.parseInt(value);
                    break;
                case "clipMax":
                    // Clamped rather than trusted: a negative or absurd ceiling would make
                    // every clipboard event vanish, which reads as a broken feature.
                    clipboardMax = Math.min(Math.max(Integer.parseInt(value), 1), 65535);
                    break;
            }
    }
}

