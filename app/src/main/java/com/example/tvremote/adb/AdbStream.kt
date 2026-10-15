package com.example.tvremote.adb

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class AdbStream(
    private val connection: AdbConnection,
    val localId: Int
) {
    @Volatile var remoteId: Int = 0
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val writeAcks = Semaphore(0)
    @Volatile var closed = false
        private set

    internal fun onWrite(payload: ByteArray) { incoming.put(payload) }
    internal fun onOkay(remote: Int) {
        remoteId = remote
        writeAcks.release()
    }
    internal fun onClose() {
        closed = true
        incoming.offer(ByteArray(0))
        writeAcks.release()
    }

    /** ADB permits one unacknowledged WRTE per stream. */
    internal fun awaitWritePermission(timeoutMs: Long = 10_000) {
        if (closed) throw IOException("ADB stream is closed")
        if (!writeAcks.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IOException("Timeout waiting for ADB stream acknowledgement")
        }
        if (closed) throw IOException("ADB stream is closed")
    }

    fun write(data: ByteArray) { connection.sendWrite(this, data) }

    /** Blocks until a WRTE frame arrives or the stream closes. */
    fun read(timeoutMs: Long = 10_000): ByteArray? {
        val data = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        return if (closed && data.isEmpty()) null else data
    }

    fun readAllText(timeoutMs: Long = 10_000): String {
        val sb = StringBuilder()
        while (true) {
            val chunk = read(timeoutMs) ?: break
            sb.append(String(chunk, Charsets.UTF_8))
        }
        return sb.toString()
    }

    fun close() { connection.sendClose(this) }
}
