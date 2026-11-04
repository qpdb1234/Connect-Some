/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.wrappers;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.IInterface;
import com.limelight.androidserver.utils.L;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection over the {@code IPackageManager} binder interface.
 *
 * This is the fallback path: {@link com.limelight.androidserver.Channel} prefers a real
 * {@code Context} and only ends up here before that context exists (or when the helper
 * cannot build one). It has to work anyway, because a silently empty app list is
 * indistinguishable from a device with no apps installed.
 *
 * Every method here is looked up from a list of signatures rather than by name alone.
 * {@code Method.invoke()} does not widen an {@code int} argument for a parameter
 * declared {@code long}, and {@code flags} became {@code filterFlags} on some releases
 * while other releases gained a trailing calling-feature id - so both the shape and the
 * argument array are derived from the method that was actually found.
 */
public class IPackageManager {
    private static IInterface manager;
    private static Class<?> CLASS;

    /** {@code getPackageInfo} as the binder declares it across releases. */
    private static final Class<?>[][] GET_PACKAGE_INFO_SHAPES = {
            {String.class, long.class, int.class, String.class},
            {String.class, int.class, int.class, String.class},
            {String.class, long.class, int.class},
            {String.class, int.class, int.class},
    };

    /** {@code queryIntentActivities} as the binder declares it across releases. */
    private static final Class<?>[][] QUERY_INTENT_ACTIVITIES_SHAPES = {
            {Intent.class, String.class, long.class, int.class, String.class},
            {Intent.class, String.class, int.class, int.class, String.class},
            {Intent.class, String.class, long.class, int.class},
            {Intent.class, String.class, int.class, int.class},
    };

    /** {@code getInstalledPackages} as the binder declares it across releases. */
    private static final Class<?>[][] GET_INSTALLED_PACKAGES_SHAPES = {
            {String[].class, long.class, int.class},
            {String[].class, int.class, int.class},
    };

    private static Method getPackageInfoMethod;
    private static Method queryIntentActivitiesMethod;
    private static Method getInstalledPackagesMethod;

    /** Set once a lookup fails, so a per-package loop does not redo reflection per entry. */
    private static boolean getPackageInfoFailed;
    private static boolean queryIntentActivitiesFailed;
    private static boolean getInstalledPackagesFailed;

    public static void init(IInterface m) {
        manager = m;
        if (m == null) {
            L.e("Error in IPackageManager.init: manager is null");
            return;
        }
        CLASS = m.getClass();
    }

    /**
     * First of [shapes] that this release actually declares, or null when none does.
     * Callers remember a null so that a per-package loop does not repeat a doomed search.
     */
    private static Method find(String name, Class<?>[][] shapes) {
        if (CLASS == null) {
            L.e("Error in find(" + name + "): manager is not initialised");
            return null;
        }
        for (Class<?>[] shape : shapes) {
            try {
                Method method = CLASS.getDeclaredMethod(name, shape);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the next release's shape.
            }
        }
        L.e("No supported signature for " + name + " on this device");
        return null;
    }

    /**
     * Arguments for a method found by {@link #find}: each position gets the value that
     * belongs there, numeric ones widened to whatever the declaration says. Positions
     * with no candidate value (a trailing calling feature id) stay null.
     */
    private static Object[] arguments(Method method, Object... values) {
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length && i < values.length; i++) {
            Object value = values[i];
            if (value instanceof Integer
                    && (params[i] == long.class || params[i] == Long.class)) {
                value = ((Integer) value).longValue();
            }
            args[i] = value;
        }
        return args;
    }

    /**
     * The list a binder method really returned. Most of these declare a
     * {@code ParceledListSlice} rather than a {@code List}, and the slice only hands the
     * contents over through {@code getList()}.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> unwrapList(Object result) throws Exception {
        if (result == null) return null;
        if (result instanceof List) return (List<Object>) result;
        Method getList = result.getClass().getMethod("getList");
        Object list = getList.invoke(result);
        return (list instanceof List) ? (List<Object>) list : null;
    }

    public static PackageInfo getPackageInfo(String packageName, int flag) {
        try {
            Method method = getPackageInfoMethod;
            if (method == null) {
                method = find("getPackageInfo", GET_PACKAGE_INFO_SHAPES);
                if (method == null) {
                    getPackageInfoFailed = true;
                    return null;
                }
                getPackageInfoMethod = method;
            }
            // userId 0: this helper runs as shell in the device owner's profile.
            return (PackageInfo) method.invoke(manager,
                    arguments(method, packageName, flag, 0));
        } catch (Exception e) {
            L.e("Error in getPackageInfo", e);
        }
        return null;
    }

    public static List<ResolveInfo> queryIntentActivities(Intent intent,
                                                          String resolvedType, int flags, int userId) {
        try {
            Method method = queryIntentActivitiesMethod;
            if (method == null) {
                method = find("queryIntentActivities", QUERY_INTENT_ACTIVITIES_SHAPES);
                if (method == null) {
                    queryIntentActivitiesFailed = true;
                    return null;
                }
                queryIntentActivitiesMethod = method;
            }
            List<Object> items = unwrapList(method.invoke(manager,
                    arguments(method, intent, resolvedType, flags, userId)));
            if (items == null) return null;
            List<ResolveInfo> resolved = new ArrayList<>(items.size());
            for (Object item : items) {
                if (item instanceof ResolveInfo) resolved.add((ResolveInfo) item);
            }
            return resolved;
        } catch (Exception e) {
            L.e("Error in queryIntentActivities", e);
        }
        return null;
    }

    /**
     * Names of every installed package, filtered by the given PackageManager flags.
     *
     * @return the package names, or null when this device offers no usable signature -
     *         callers treat null as "no list" and say so, rather than as an empty device.
     */
    public static List<String> getInstalledPackages(int flag) {
        try {
            Method method = getInstalledPackagesMethod;
            if (method == null) {
                method = find("getInstalledPackages", GET_INSTALLED_PACKAGES_SHAPES);
                if (method == null) {
                    getInstalledPackagesFailed = true;
                    return null;
                }
                getInstalledPackagesMethod = method;
            }
            // Slot 0 is the list of required permissions to filter by: unfiltered.
            List<Object> items = unwrapList(method.invoke(manager,
                    arguments(method, null, flag, 0)));
            if (items == null) return null;
            List<String> names = new ArrayList<>(items.size());
            for (Object item : items) {
                if (item instanceof PackageInfo) {
                    if (((PackageInfo) item).packageName != null) names.add(((PackageInfo) item).packageName);
                } else if (item instanceof String) {
                    names.add((String) item);
                }
            }
            return names;
        } catch (Exception e) {
            L.e("Error in getInstalledPackages", e);
        }
        return null;
    }
}
