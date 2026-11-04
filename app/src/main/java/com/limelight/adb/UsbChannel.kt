/*
 * Adapted from Easycontrol_For_Car (https://github.com/Eiyooooo/Easycontrol_For_Car),
 * Copyright (c) Eiyooooo.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 *
 * The USB bulk-transfer choreography was itself credited by the reference project
 * to https://github.com/wuxudong/flashbot (adblib/UsbChannel.java).
 */

package com.limelight.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import com.limelight.App
import com.limelight.R
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList

/**
 * ADB over USB-OTG: the controlling phone talks straight to the target's `adbd`
 * through its bulk endpoints, which is the only transport that needs no pairing and
 * no previously authorised key.
 *
 * [usbManager] is injected rather than looked up, so the caller owns the permission
 * grant ([android.app.Activity.requestPermission] is asynchronous and cannot be
 * awaited from here).
 */
class UsbChannel(usbManager: UsbManager, usbDevice: UsbDevice) : AdbChannel {

    private val usbConnection: UsbDeviceConnection =
            usbManager.openDevice(usbDevice)
                    ?: throw IOException(App.appContext.getString(R.string.error_usb_open_denied))

    private var usbInterface: UsbInterface? = null

    private lateinit var endpointIn: UsbEndpoint
    private lateinit var endpointOut: UsbEndpoint

    private val sourceBuffer = Buffer()
    private val readBackgroundThread = Thread(this::readBackground, "connectsome-adb-usb-read")

    /** Reusable queue-side requests; allocating one per read leaks kernel buffers. */
    private val inRequestPool = LinkedList<UsbRequest>()

    init {
        try {
            // The ADB function is advertised as vendor-specific class 0xff with
            // subclass 0x42 (66) and protocol 1.
            val adbInterface = (0 until usbDevice.interfaceCount)
                    .map(usbDevice::getInterface)
                    .firstOrNull {
                        it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                                it.interfaceSubclass == USB_SUBCLASS_ADB &&
                                it.interfaceProtocol == USB_PROTOCOL_ADB
                    }
                    ?: throw IOException(App.appContext.getString(R.string.error_usb_no_adb))
            usbInterface = adbInterface

            if (!usbConnection.claimInterface(adbInterface, true))
                throw IOException(App.appContext.getString(R.string.error_usb_busy))

            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (i in 0 until adbInterface.endpointCount) {
                val endpoint = adbInterface.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_OUT -> outEndpoint = endpoint
                    UsbConstants.USB_DIR_IN -> inEndpoint = endpoint
                }
            }
            if (inEndpoint == null || outEndpoint == null)
                throw IOException(App.appContext.getString(R.string.error_usb_endpoints))

            endpointIn = inEndpoint
            endpointOut = outEndpoint
            readBackgroundThread.start()
        } catch (e: Exception) {
            closeQuietly()
            throw IOException(App.appContext.getString(
                    R.string.error_usb_wired, e.message ?: e.javaClass.simpleName), e)
        }
    }

    /**
     * ADB over USB must send a message's 24-byte header and its payload as *two*
     * separate bulk transfers. Sending them in one transfer resets the connection on
     * the device side - surprising, but consistently reported by the reference
     * project and by upstream adb implementations.
     *
     * A failed transfer is fatal to the session rather than something to step over:
     * [bulkTransfer] reports -1 (or a short count) without throwing, and a dropped ADB
     * message is not a lost packet - the peer keeps reading the next bytes as the
     * message it expected, so the whole multiplexed stream silently falls out of step.
     */
    @Throws(IOException::class)
    override fun write(data: ByteBuffer) {
        while (data.remaining() > 0) {
            if (data.remaining() < AdbProtocol.ADB_HEADER_LENGTH)
                throw IOException("partial ADB header in the outbound buffer")
            val header = ByteArray(AdbProtocol.ADB_HEADER_LENGTH)
            data.get(header)

            val payloadLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            // The length is read back out of a header we wrote ourselves, so a nonsense
            // value means this buffer was never a message sequence - and passing it on
            // would either allocate on trust or read the payload as the next header.
            if (payloadLength < 0 || payloadLength > data.remaining())
                throw IOException("ADB header announces $payloadLength payload bytes with " +
                        "${data.remaining()} left in the buffer")

            bulkOut(header)
            if (payloadLength > 0) {
                val payload = ByteArray(payloadLength)
                data.get(payload)
                bulkOut(payload)
            }
        }
    }

    private fun bulkOut(buffer: ByteArray) {
        val sent = usbConnection.bulkTransfer(endpointOut, buffer, buffer.size, BULK_TIMEOUT_MS)
        if (sent != buffer.size)
            throw IOException("USB bulk write transferred $sent of ${buffer.size} bytes")
    }

    override fun read(size: Int): ByteBuffer = sourceBuffer.read(size)

    override fun flush() = Unit

    private fun readBackground() {
        try {
            while (!Thread.interrupted()) {
                val header = readRequest(AdbProtocol.ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
                if (header.remaining() < AdbProtocol.ADB_HEADER_LENGTH) throw IOException("read error")
                sourceBuffer.write(header)

                val payloadLength = header.getInt(12)
                // This length is the peer's word, not ours: the device on the other end of
                // the cable announces it, and a single message claiming two gigabytes would
                // be allocated before a byte of it arrived - an OutOfMemoryError that this
                // thread does not catch, so it would take the process with it. Anything
                // outside what adbd can legitimately send ends the session instead.
                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH)
                    throw IOException("device announced a $payloadLength byte payload")
                if (payloadLength > 0) sourceBuffer.write(readRequest(payloadLength))
            }
        } catch (_: IOException) {
            sourceBuffer.close()
        }
    }

    /** Queues an async [UsbRequest] and waits for *this* request's completion. */
    private fun readRequest(len: Int): ByteBuffer {
        val request = if (inRequestPool.isEmpty()) {
            UsbRequest().also { it.initialize(usbConnection, endpointIn) }
        } else {
            inRequestPool.removeFirst()
        }
        val data = ByteBuffer.allocate(len)
        request.setClientData(data)
        if (!request.queue(data, len)) throw IOException("fail to queue read UsbRequest")

        while (true) {
            val wait = usbConnection.requestWait() ?: throw IOException("Connection.requestWait return null")
            if (wait.endpoint == endpointIn) {
                val clientData = wait.clientData
                inRequestPool.add(request)
                if (clientData === data) {
                    data.flip()
                    return data
                }
            }
        }
    }

    override fun close() {
        readBackgroundThread.interrupt()
        sourceBuffer.close()
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            usbInterface?.let { usbConnection.releaseInterface(it) }
            // Provoke a transfer error so adbd drops the USB session and the device
            // can be re-enumerated right away; without this the next open hangs.
            if (this::endpointOut.isInitialized)
                usbConnection.bulkTransfer(endpointOut, ByteArray(40), 40, CLOSE_TIMEOUT_MS)
            usbConnection.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val USB_SUBCLASS_ADB = 66
        private const val USB_PROTOCOL_ADB = 1

        /**
         * Budget for one bulk transfer. Outbound messages are up to the negotiated
         * maxdata (16 KB for a jar push), and the stack splits that into packets over a
         * hub that may be busy - a second is a plausible-looking number that a stalled
         * device beats, and a timeout here now ends the session instead of losing the
         * message unnoticed.
         */
        private const val BULK_TIMEOUT_MS = 5_000
        private const val CLOSE_TIMEOUT_MS = 2000

        /**
         * Ceiling for a payload announced by the device. adbd's own limit is the negotiated
         * maxdata - 256 KB to 1 MB on any real target, and replies carry at most that much -
         * so this is already generous, while still being far below what would exhaust the
         * heap of an app that also holds a video decoder.
         */
        private const val MAX_PAYLOAD_LENGTH = 4 * 1024 * 1024
    }
}
