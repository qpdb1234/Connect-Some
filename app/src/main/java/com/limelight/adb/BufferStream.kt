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
 * A duplex stream on top of one ADB logical connection ([Buffer] in each direction).
 *
 * Two flags carry real protocol constraints and are kept exactly as in the
 * reference implementation:
 *
 *  - `canWrite`: ADB only allows a local-id's payload once the peer has confirmed
 *    the channel (CMD_OKAY). Streams therefore start with it false and the
 *    ADB reader flips it when the confirmation arrives.
 *  - `canMultipleSend`: when true, several upper-layer writes may be coalesced
 *    into a single transport write; when false, one upper-layer write is exactly
 *    one transport write. Shell streams need the coalescing behaviour, the
 *    `sync:` push channel does not.
 */
class BufferStream(
        canWriteInitially: Boolean,
        private val canMultipleSend: Boolean,
        private val underlySocketFunction: UnderlySocketFunction,
) {

    /** Callbacks into [Adb], which owns the actual ADB wire. */
    interface UnderlySocketFunction {
        fun connect(bufferStream: BufferStream)

        fun write(bufferStream: BufferStream, buffer: ByteBuffer)

        fun flush(bufferStream: BufferStream)

        fun close(bufferStream: BufferStream)
    }

    private val source = Buffer()
    private val sink = Buffer()

    private var canWrite = canWriteInitially

    @Volatile
    var isClosed = false
        private set

    val isEmpty: Boolean
        get() = source.isEmpty

    val size: Int
        get() = source.size

    init {
        underlySocketFunction.connect(this)
    }

    constructor(canWrite: Boolean, underlySocketFunction: UnderlySocketFunction) :
            this(canWrite, true, underlySocketFunction)

    constructor(underlySocketFunction: UnderlySocketFunction) : this(true, true, underlySocketFunction)

    /** Called by the ADB reader thread when a CMD_WRTE payload arrives for this stream. */
    fun pushSource(byteBuffer: ByteBuffer?) {
        if (byteBuffer != null) source.write(byteBuffer)
    }

    fun readByte(): Byte = readByteArray(1).get()

    fun readShort(): Short = readByteArray(2).short

    fun readInt(): Int = readByteArray(4).int

    fun readLong(): Long = readByteArray(8).long

    fun readAllBytes(): ByteBuffer = readByteArray(size)

    fun readByteArray(size: Int): ByteBuffer {
        if (isClosed) throw IOException("connection is closed")
        return source.read(size)
    }

    fun readByteArrayBeforeClose(): ByteBuffer = source.readByteArrayBeforeClose()

    fun write(byteBuffer: ByteBuffer) {
        if (isClosed) throw IOException("connection is closed")
        sink.write(byteBuffer)
        pollSink()
    }

    fun setCanWrite(canWrite: Boolean) {
        if (isClosed) return
        this.canWrite = canWrite
        if (canWrite) pollSink()
    }

    @Synchronized
    private fun pollSink() {
        if (canWrite && !sink.isEmpty) {
            underlySocketFunction.write(this, if (canMultipleSend) sink.read(sink.size) else sink.readNext())
        }
    }

    fun flush() {
        underlySocketFunction.flush(this)
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        source.close()
        sink.close()
        try {
            underlySocketFunction.close(this)
        } catch (_: Exception) {
        }
    }
}
