package com.min.adb.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level framing for the ADB wire protocol (the same protocol `adb` itself speaks to
 * adbd on a device). A message is a 24-byte header followed by an optional payload.
 */
object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534C5453
    const val A_STLS_VERSION = 0x01000000

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val A_VERSION = 0x01000001
    const val MAX_PAYLOAD = 1024 * 1024

    class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray = ByteArray(0)
    ) {
        fun encode(): ByteArray {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            var checksum = 0
            for (b in payload) checksum += (b.toInt() and 0xFF)
            header.putInt(command)
            header.putInt(arg0)
            header.putInt(arg1)
            header.putInt(payload.size)
            header.putInt(checksum)
            header.putInt(command.inv())
            val out = ByteArray(24 + payload.size)
            System.arraycopy(header.array(), 0, out, 0, 24)
            System.arraycopy(payload, 0, out, 24, payload.size)
            return out
        }

        companion object {
            fun parseHeader(bytes: ByteArray): Message {
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val command = buf.int
                val arg0 = buf.int
                val arg1 = buf.int
                buf.int // data length, read separately by caller
                buf.int // checksum, verified by caller if needed
                return Message(command, arg0, arg1)
            }
        }
    }

    fun dataLength(header: ByteArray): Int =
        ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int

    fun generateMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) =
        Message(command, arg0, arg1, payload).encode()

    fun cnxnPayload(): ByteArray = "host::adb\u0000".toByteArray(Charsets.UTF_8)
}
