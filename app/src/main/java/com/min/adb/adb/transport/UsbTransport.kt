package com.min.adb.adb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Talks to adbd over the device's USB ADB interface (class 0xFF/0x42/0x01), the same
 * endpoints the desktop `adb` binary uses. Requires the user to grant USB permission via
 * UsbManager.requestPermission before connect() is called (handled by the Activity).
 */
class UsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : Transport {

    private lateinit var connection: UsbDeviceConnection
    private lateinit var usbInterface: UsbInterface
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint

    override fun connect() {
        val iface = findAdbInterface(device) ?: throw IOException("No ADB interface on this USB device")
        usbInterface = iface
        connection = usbManager.openDevice(device) ?: throw IOException("Could not open USB device (permission?)")
        if (!connection.claimInterface(iface, true)) throw IOException("Could not claim USB interface")

        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                else epOut = ep
            }
        }
        if (!::epIn.isInitialized || !::epOut.isInitialized) throw IOException("ADB bulk endpoints not found")
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = data.copyOfRange(off, minOf(off + epOut.maxPacketSize * 16, data.size))
            val n = connection.bulkTransfer(epOut, chunk, chunk.size, 5000)
            if (n < 0) throw IOException("USB bulk write failed")
            off += n
        }
    }

    override fun readFully(len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val chunk = ByteArray(minOf(epIn.maxPacketSize * 16, len - off))
            val n = connection.bulkTransfer(epIn, chunk, chunk.size, 10000)
            if (n < 0) throw IOException("USB bulk read failed")
            System.arraycopy(chunk, 0, out, off, n)
            off += n
        }
        return out
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        /** adb's USB interface class/subclass/protocol, per the AOSP adb transport source. */
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == ADB_CLASS &&
                    iface.interfaceSubclass == ADB_SUBCLASS &&
                    iface.interfaceProtocol == ADB_PROTOCOL
                ) return iface
            }
            return null
        }
    }
}
