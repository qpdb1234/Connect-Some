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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * A FIFO of [ByteBuffer] chunks with blocking reads.
 *
 * The ADB transport produces whole protocol messages (header, then payload) while
 * the consumers above need fixed-size values (`readInt`, `readByteArray(15)`...).
 * This buffer is the piece that lets a reader take *exactly* n bytes regardless of
 * how the writer happened to split them: leftovers go back to the front of the
 * queue instead of being copied out.
 *
 * [close] pushes one dummy byte so a reader parked inside [takeFirst] wakes up and
 * observes the closed state instead of blocking forever.
 */
class Buffer {
    private val dataQueue = LinkedBlockingDeque<ByteBuffer>()

    @Volatile
    private var closed = false

    /**
     * Bytes currently queued.
     *
     * Kept next to the queue instead of being summed over it: the inbound pump and the
     * wait loops below read [size] on every message and every poll slice, so walking the
     * whole queue each time would cost more the larger the backlog grows - which is
     * exactly when the number is needed.
     */
    private val queued = AtomicLong(0)

    val isEmpty: Boolean
        get() = dataQueue.isEmpty()

    /** Total bytes currently queued. */
    val size: Int
        get() = queued.get().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    fun write(data: ByteBuffer) {
        queued.addAndGet(data.remaining().toLong())
        dataQueue.offerLast(data)
    }

    /** Blocks until [len] bytes are available and returns them in a fresh buffer. */
    @Synchronized
    fun read(len: Int): ByteBuffer {
        if (len < 0 || closed) throw IOException("Buffer error")
        val data = ByteBuffer.allocate(len)
        var bytesToRead = len
        while (bytesToRead > 0) {
            val tmpData = dataQueue.takeFirst()
            if (closed) throw IOException("Buffer error")
            val remaining = tmpData.remaining()
            queued.addAndGet(-remaining.toLong())
            if (remaining <= bytesToRead) {
                data.put(tmpData)
                bytesToRead -= remaining
            } else {
                // Take only what is needed and hand the rest back to the queue.
                val oldLimit = tmpData.limit()
                tmpData.limit(tmpData.position() + bytesToRead)
                data.put(tmpData)
                tmpData.limit(oldLimit)
                dataQueue.offerFirst(tmpData)
                queued.addAndGet(tmpData.remaining().toLong())
                bytesToRead = 0
            }
        }
        data.flip()
        return data
    }

    /** Returns the next queued chunk as-is (no re-splitting). */
    @Synchronized
    fun readNext(): ByteBuffer {
        if (closed) throw IOException("Buffer error")
        val byteBuffer = dataQueue.takeFirst()
        if (closed) throw IOException("Buffer error")
        queued.addAndGet(-byteBuffer.remaining().toLong())
        return byteBuffer
    }

    /** Snapshot of whatever is queued. Used to harvest shell output right before teardown. */
    fun readByteArrayBeforeClose(): ByteBuffer {
        // Summed by walking the queue rather than from [size]: [close] pushes an uncounted
        // wake-up byte, and this runs exactly once per stream, so the linear pass is the
        // honest total without the cost the [size] readers are billed for.
        var total = 0
        for (tmpBuffer in dataQueue) total += tmpBuffer.remaining()
        val byteBuffer = ByteBuffer.allocate(maxOf(total, 1))
        for (tmpBuffer in dataQueue) byteBuffer.put(tmpBuffer)
        return byteBuffer
    }

    fun close() {
        if (closed) return
        closed = true
        dataQueue.offer(ByteBuffer.allocate(1))
    }
}
