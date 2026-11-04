/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.adb

import com.limelight.App
import com.limelight.R
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * ADB over TCP - either a device with `adb tcpip` / wireless debugging enabled, or
 * a local forward created by [Adb.localSocketForward].
 *
 * @param readTimeoutMs `0` (the streaming case) means reads block forever, which is
 *   what a session wants. A probing caller (pairing, reachability test) passes a
 *   timeout so that "connected but silent" is reported as a failure instead of a hang.
 */
class TcpChannel(
        host: String,
        port: Int,
        private val readTimeoutMs: Int = 0,
) : AdbChannel {

    private val socket = Socket()
    private val inputStream: InputStream
    private val outputStream: OutputStream

    init {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        if (readTimeoutMs > 0) socket.soTimeout = readTimeoutMs
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
    }

    override fun write(data: ByteBuffer) {
        // Written from the buffer's current position rather than `array()`: the ADB
        // writer hands out freshly flipped messages, but a re-sent/sliced buffer must
        // not drag its already-consumed prefix along.
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        outputStream.write(bytes)
    }

    override fun flush() {
        outputStream.flush()
    }

    override fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override val baselineReadTimeoutMs: Int
        get() = readTimeoutMs

    override fun read(size: Int): ByteBuffer {
        val buffer = ByteArray(size)
        var bytesRead = 0
        while (bytesRead < size) {
            val read = inputStream.read(buffer, bytesRead, size - bytesRead)
            if (read == -1) {
                // Say so instead of padding the rest with zeros. A fabricated message reads
                // as `command = 0`, which every dispatcher below ignores, so a peer that said
                // an orderly goodbye keeps being polled until it sends a RST and the failure
                // finally surfaces as "software caused connection abort" - blaming the wire
                // for what was really the device hanging up.
                throw EOFException(if (bytesRead == 0)
                    App.appContext.getString(R.string.error_device_closed)
                else App.appContext.getString(
                        R.string.error_device_closed_partial, bytesRead, size))
            }
            bytesRead += read
        }
        return ByteBuffer.wrap(buffer)
    }

    override fun close() {
        try {
            outputStream.close()
            inputStream.close()
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
    }
}
