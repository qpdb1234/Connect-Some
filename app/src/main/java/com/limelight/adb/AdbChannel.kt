/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import java.io.IOException
import java.nio.ByteBuffer

/**
 * The raw byte transport an [Adb] session runs on: a TCP socket to `adbd`, or an
 * OTG cable's USB bulk endpoints. Both speak identical ADB messages, so everything
 * above this interface is transport-agnostic.
 */
interface AdbChannel {
    @Throws(IOException::class, InterruptedException::class)
    fun write(data: ByteBuffer)

    @Throws(IOException::class)
    fun flush()

    /** Blocks until exactly [size] bytes have been received. */
    @Throws(IOException::class, InterruptedException::class)
    fun read(size: Int): ByteBuffer

    /**
     * Bounds how long [read] may wait with nothing arriving; `0` waits forever.
     *
     * A live session wants the infinite wait, but a caller that is merely asking whether
     * the peer is there wants an answer rather than a parked thread. Transports that cannot
     * do this keep the no-op default and simply behave as they always did.
     */
    fun setReadTimeout(timeoutMs: Int) {}

    /**
     * The bound this channel was built with, which [setReadTimeout] temporarily overrides
     * during the handshake and then hands back. `0` for a session that must wait for a
     * human to accept the authorisation prompt; the probe's few seconds for a caller that
     * only wants to know whether something answers at all.
     */
    val baselineReadTimeoutMs: Int
        get() = 0

    fun close()
}
