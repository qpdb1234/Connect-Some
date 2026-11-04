/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.wrappers;

import android.os.IInterface;

import java.lang.reflect.Method;

import com.limelight.androidserver.utils.L;

public class UiModeManager {
    /**
     * The night modes the framework defines: {@code UI_NIGHT_AUTO} through
     * {@code UI_NIGHT_YES}, plus the device-specific modes up to {@code DEVICE_SPECIFIC}.
     * A number outside this set is not a mode the service can enter, so callers bound
     * requests to it before handing one over.
     */
    public static final int MIN_NIGHT_MODE = 0;
    public static final int MAX_NIGHT_MODE = 7;

    private static IInterface manager;
    private static Class<?> CLASS;
    private static Method getGetNightModeMethod = null;
    private static Method getSetNightModeMethod = null;

    public static void init(IInterface m) {
        manager = m;
        if (manager == null) {
            L.e("Error in IPackageManager.init: manager is null");
            return;
        }
        CLASS = manager.getClass();
    }

    private static Method getGetNightModeMethod() throws ReflectiveOperationException {
        if (getGetNightModeMethod == null) {
            if (CLASS == null) {
                L.e("Error in getGetNightModeMethod: CLASS is null");
                return null;
            }
            getGetNightModeMethod = CLASS.getDeclaredMethod("getNightMode");
        }
        return getGetNightModeMethod;
    }

    private static Method getSetNightModeMethod() throws ReflectiveOperationException {
        if (getSetNightModeMethod == null) {
            if (CLASS == null) {
                L.e("Error in getGetNightModeMethod: CLASS is null");
                return null;
            }
            getSetNightModeMethod = CLASS.getDeclaredMethod("setNightMode", int.class);
        }
        return getSetNightModeMethod;
    }

    private static int currentNightMode = -1;

    public static int getNightMode() {
        try {
            Method method = getGetNightModeMethod();
            if (method == null || manager == null) return -1;
            currentNightMode = (int) method.invoke(manager);
            return currentNightMode;
        } catch (ReflectiveOperationException e) {
            L.e("Error in getNightMode: " + e.getMessage());
            return -1;
        }
    }

    public static void setNightMode(int mode) {
        try {
            if (mode < MIN_NIGHT_MODE || mode > MAX_NIGHT_MODE) {
                L.e("Ignoring out of range night mode: " + mode);
                return;
            }
            if (currentNightMode == mode) return;
            // The manager is missing on a ROM that does not publish the UI service. Handled
            // as "this device cannot change the night mode" rather than as an uncaught
            // NullPointerException on the control thread, which would end the session.
            Method method = getSetNightModeMethod();
            if (method == null || manager == null) {
                L.e("setNightMode is unavailable on this device");
                return;
            }
            method.invoke(manager, mode);
            currentNightMode = mode;
        } catch (ReflectiveOperationException e) {
            L.e("Error in setNightMode: " + e.getMessage());
        }
    }
}