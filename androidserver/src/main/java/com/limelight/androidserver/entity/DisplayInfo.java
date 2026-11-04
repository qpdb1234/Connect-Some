/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.entity;

import android.util.Pair;

public final class DisplayInfo {
    public final int displayId;
    public final Pair<Integer, Integer> size;
    public int density;
    public final int rotation;
    public final int layerStack;
    public final int flags;

    public DisplayInfo(int displayId, Pair<Integer, Integer> size, int density, int rotation, int layerStack, int flags) {
        this.displayId = displayId;
        this.size = size;
        this.density = density;
        this.rotation = rotation;
        this.layerStack = layerStack;
        this.flags = flags;
    }
}
