package com.min.adb.adb

import com.min.adb.adb.transport.Transport
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One authenticated ADB connection to a single device (over TCP or USB) and the
 * read-loop that demultiplexes OPEN/OKAY/WRTE/CLSE frames into AdbStreams.
 *
 * This mirrors the handshake `adb` itself performs: CNXN, then on an AUTH(TOKEN)
 * challenge, reply AUTH(SIGNATURE) signed with the stored key; if the device hasn't
 * seen this key before it will prompt the user to accept an "Allow USB/network
 * debugging?" dialog on first connect, same as plugging in a laptop.
 */
class AdbConnection(private val transport: Transport, private val crypto: AdbCrypto) {

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)
    @Volatile private var connected = false
    private var readThread: Thread? = null

    fun connect() {
        transport.connect()
        transport.write(AdbProtocol.generateMessage(
            AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, AdbProtocol.cnxnPayload()
        ))

        var signedAlready = false
        while (!connected) {
            val header = transport.readFully(24)
            val msg = AdbProtocol.Message.parseHeader(header)
            val len = AdbProtocol.dataLength(header)
            val payload = if (len > 0) transport.readFully(len) else ByteArray(0)

            when (msg.command) {
                AdbProtocol.A_CNXN -> connected = true
                AdbProtocol.A_STLS -> {
                    // Wireless-debugging port: device wants TLS. Reply, upgrade, then it sends CNXN over TLS.
                    transport.write(AdbProtocol.generateMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0))
                    transport.startTls(crypto.sslContext())
                }
                AdbProtocol.A_AUTH -> {
                    if (msg.arg0 == AdbProtocol.AUTH_TOKEN) {
                        if (!signedAlready) {
                            val sig = crypto.sign(payload)
                            transport.write(AdbProtocol.generateMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, sig))
                            signedAlready = true
                        } else {
                            // Device didn't accept the signature; fall back to sending our
                            // public key so the user can tap "Allow" on the TV/device.
                            val pubkey = crypto.publicKeyAdbFormat("adb")
                            transport.write(AdbProtocol.generateMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, pubkey))
                        }
                    }
                }
                else -> { /* ignore until CNXN completes */ }
            }
        }

        readThread = thread(name = "adb-read-loop", isDaemon = true) { readLoop() }
    }

    private fun readLoop() {
        try {
            while (connected) {
                val header = transport.readFully(24)
                val msg = AdbProtocol.Message.parseHeader(header)
                val len = AdbProtocol.dataLength(header)
                val payload = if (len > 0) transport.readFully(len) else ByteArray(0)
                dispatch(msg, payload)
            }
        } catch (e: IOException) {
            connected = false
        } finally {
            // Link is gone: unblock anyone waiting on a stream instead of making them time out.
            connected = false
            streams.values.forEach { it.onClose() }
            streams.clear()
        }
    }

    private fun dispatch(msg: AdbProtocol.Message, payload: ByteArray) {
        when (msg.command) {
            AdbProtocol.A_OKAY -> streams[msg.arg1]?.onOkay(msg.arg0)
            AdbProtocol.A_WRTE -> {
                val stream = streams[msg.arg1] ?: return
                stream.onWrite(payload)
                transport.write(AdbProtocol.generateMessage(AdbProtocol.A_OKAY, msg.arg1, stream.remoteId))
            }
            AdbProtocol.A_CLSE -> {
                streams[msg.arg1]?.onClose()
                streams.remove(msg.arg1)
            }
        }
    }

    /** Opens a new ADB "service" stream, e.g. "shell,v2,raw:cmd" or "shell:cmd". */
    fun open(destination: String): AdbStream {
        if (!connected) throw IOException("Not connected")
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(this, localId)
        streams[localId] = stream
        val payload = (destination + "\u0000").toByteArray(Charsets.UTF_8)
        transport.write(AdbProtocol.generateMessage(AdbProtocol.A_OPEN, localId, 0, payload))
        return stream
    }

    internal fun sendWrite(stream: AdbStream, data: ByteArray) {
        stream.awaitWritePermission()
        transport.write(AdbProtocol.generateMessage(AdbProtocol.A_WRTE, stream.localId, stream.remoteId, data))
    }

    internal fun sendClose(stream: AdbStream) {
        transport.write(AdbProtocol.generateMessage(AdbProtocol.A_CLSE, stream.localId, stream.remoteId))
        streams.remove(stream.localId)
    }

    /** Convenience: run a one-shot shell command and collect its full output. */
    fun shell(command: String): String {
        val stream = open("shell:$command")
        val out = stream.readAllText()
        stream.close()
        return out
    }

    fun close() {
        connected = false
        readThread?.interrupt()
        transport.close()
    }
}
