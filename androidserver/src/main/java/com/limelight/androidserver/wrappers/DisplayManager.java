/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.wrappers;

import android.hardware.display.VirtualDisplay;
import android.util.Pair;
import android.view.Display;
import android.view.Surface;
import com.limelight.androidserver.Channel;
import com.limelight.androidserver.entity.DisplayInfo;
import com.limelight.androidserver.utils.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisplayManager {
    private static Object manager;
    private static Method getDisplayIdsMethod = null;
    private static Method createVirtualDisplayMethod = null;

    public static void init(Object m) {
        manager = m;
    }

    public static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) return null;
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(Objects.requireNonNull(m.group(2)));
        int height = Integer.parseInt(Objects.requireNonNull(m.group(3)));
        int rotation = Integer.parseInt(Objects.requireNonNull(m.group(4)));
        int density = Integer.parseInt(Objects.requireNonNull(m.group(5)));
        int layerStack = Integer.parseInt(Objects.requireNonNull(m.group(6)));

        return new DisplayInfo(displayId, new Pair<>(width, height), density, rotation, layerStack, flags);
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Channel.execReadOutput("dumpsys display");
            return parseDisplayInfo(dumpsysDisplayOutput, displayId);
        } catch (Exception e) {
            L.e("getDisplayInfoFromDumpsysDisplay error", e);
            return null;
        }
    }

    private static int parseDisplayFlags(String text) {
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        if (text == null) return 0;

        int flags = 0;
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field filed = Display.class.getDeclaredField(flagString);
                flags |= filed.getInt(null);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Silently ignore, some flags reported by "dumpsys display" are @TestApi
                L.d("Unknown display flag: " + flagString);
            }
        }
        return flags;
    }

    public static DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            // fallback when displayInfo is null
            if (displayInfo == null) return getDisplayInfoFromDumpsysDisplay(displayId);
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int density = (int) cls.getDeclaredField("logicalDensityDpi").getFloat(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            return new DisplayInfo(displayId, new Pair<>(width, height), density, rotation, layerStack, flags);
        } catch (Exception e) {
            L.e("getDisplayInfo error", e);
            return getDisplayInfoFromDumpsysDisplay(displayId);
        }
    }

    private static Method getGetDisplayIdsMethod() throws ReflectiveOperationException {
        if (getDisplayIdsMethod == null) getDisplayIdsMethod = manager.getClass().getMethod("getDisplayIds");
        return getDisplayIdsMethod;
    }

    public static int[] getDisplayIds() {
        try {
            int[] ids = (int[]) getGetDisplayIdsMethod().invoke(manager);
            return ids == null ? new int[0] : ids;
        } catch (Exception e) {
            L.e("getDisplayIds error", e);
            return new int[0];
        }
    }

    private static Method getCreateVirtualDisplayMethod() {
        if (createVirtualDisplayMethod == null) {
            try {
                createVirtualDisplayMethod = android.hardware.display.DisplayManager.class
                        .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
            } catch (NoSuchMethodException e) {
                L.w("no hidden createVirtualDisplay overload on this device");
                return null;
            }
        }
        return createVirtualDisplayMethod;
    }

    public static VirtualDisplay createVirtualDisplay(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        // The looked up overload is the @hide instance method (name, width, height, density,
        // surface). It used to be invoked on a null receiver - which is a
        // NullPointerException for an instance method - and with the display id placed in the
        // density slot. A DisplayManager bound to the shell context is the receiver it needs,
        // and passing a non-null receiver is also harmless should the method turn out to be
        // static on a given release.
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                com.limelight.androidserver.helper.FakeContext.get()
                        .getSystemService(android.content.Context.DISPLAY_SERVICE);
        if (dm == null) throw new Exception("no display service");

        DisplayInfo info = getDisplayInfo(displayIdToMirror);
        int density = (info != null && info.density > 0) ? info.density : 160;

        Method method = getCreateVirtualDisplayMethod();
        if (method != null) {
            try {
                return (VirtualDisplay) method.invoke(dm, name, width, height, density, surface);
            } catch (Exception e) {
                // Hidden API, blacklisted on some releases: reflection failure is expected
                // here, not a reason to end the session.
                L.w("5 argument createVirtualDisplay unavailable, trying the public overload", e);
            }
        }
        // Public since API 21: (name, width, height, densityDpi, surface, flags). Flag 0 is
        // the plain private virtual display, which for the default display is what mirroring
        // asks for.
        return dm.createVirtualDisplay(name, width, height, density, surface, 0);
    }
}
