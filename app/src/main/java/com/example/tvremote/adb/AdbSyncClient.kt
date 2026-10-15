package com.example.tvremote.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal ADB SYNC client for normal files accessible to the authenticated ADB user. */
class AdbSyncClient(private val connection: AdbConnection) {
    fun push(input: InputStream, remotePath: String, modifiedSeconds: Int = (System.currentTimeMillis() / 1000L).toInt()) {
        val stream = connection.open("sync:")
        val channel = SyncChannel(stream)
        try {
            val spec = "$remotePath,33188".toByteArray(Charsets.UTF_8) // regular file, 0644
            channel.writePacket("SEND", spec)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                channel.writePacket("DATA", buffer.copyOf(n))
            }
            channel.writeIdAndInt("DONE", modifiedSeconds)
            val (id, value) = channel.readHeader()
            if (id == "FAIL") throw IOException("ADB push failed: ${channel.readText(value)}")
            if (id != "OKAY") throw IOException("Unexpected ADB SYNC response: $id")
        } finally { runCatching { stream.close() } }
    }

    fun pull(remotePath: String, output: OutputStream) {
        val stream = connection.open("sync:")
        val channel = SyncChannel(stream)
        try {
            channel.writePacket("RECV", remotePath.toByteArray(Charsets.UTF_8))
            while (true) {
                val (id, length) = channel.readHeader()
                when (id) {
                    "DATA" -> channel.copyTo(output, length)
                    "DONE" -> break
                    "FAIL" -> throw IOException("ADB pull failed: ${channel.readText(length)}")
                    else -> throw IOException("Unexpected ADB SYNC response: $id")
                }
            }
            output.flush()
        } finally { runCatching { stream.close() } }
    }

    private class SyncChannel(private val stream: AdbStream) {
        private var chunk = ByteArray(0)
        private var offset = 0

        fun writePacket(id: String, data: ByteArray) {
            val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray(Charsets.US_ASCII)).putInt(data.size).array()
            stream.write(head + data)
        }

        fun writeIdAndInt(id: String, value: Int) {
            stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array())
        }

        fun readHeader(): Pair<String, Int> {
            val bytes = readExact(8)
            val id = String(bytes, 0, 4, Charsets.US_ASCII)
            val n = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id != "DONE" && (n < 0 || n > 64 * 1024 * 1024)) throw IOException("Invalid ADB SYNC length: $n")
            return id to n
        }

        fun readText(length: Int): String = String(readExact(length), Charsets.UTF_8)

        fun copyTo(output: OutputStream, length: Int) {
            var remaining = length
            while (remaining > 0) {
                val n = minOf(remaining, 64 * 1024)
                output.write(readExact(n))
                remaining -= n
            }
        }

        private fun readExact(length: Int): ByteArray {
            val out = ByteArrayOutputStream(length)
            var remaining = length
            while (remaining > 0) {
                if (offset >= chunk.size) {
                    chunk = stream.read(30_000) ?: throw IOException("ADB SYNC stream closed unexpectedly")
                    offset = 0
                    if (chunk.isEmpty()) throw IOException("Empty ADB SYNC packet")
                }
                val n = minOf(remaining, chunk.size - offset)
                out.write(chunk, offset, n)
                offset += n
                remaining -= n
            }
            return out.toByteArray()
        }
    }
}
