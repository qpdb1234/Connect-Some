/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */
package com.limelight.androidserver.entity;

public final class Pointer {

    public int id;

    public float x;

    public float y;

    public long downTime;

    public Pointer(int id, long downTime) {
        this.id = id;
        this.downTime = downTime;
    }

}
