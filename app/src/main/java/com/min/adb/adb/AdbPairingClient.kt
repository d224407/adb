package com.min.adb.adb

import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

class AdbWrongPairingCodeException : IOException("Wrong pairing code")

/**
 * ADB wireless-debugging pairing client (Android 11+): TLS 1.3 to the pairing port, SPAKE2 over
 * (pairing code + TLS exported key material), then an encrypted exchange of peer info that hands
 * our ADB public key to the device so it trusts us afterwards. Mirrors AOSP's pairing_connection.
 */
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val crypto: AdbCrypto
) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var ctx: PairingContext? = null

    /** Returns normally on success; throws [AdbWrongPairingCodeException] or [IOException] otherwise. */
    fun pair() {
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), 8000)
        plain.tcpNoDelay = true
        plain.soTimeout = 15000
        socket = plain

        val ssl = crypto.sslContext().socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.startHandshake()
        input = DataInputStream(ssl.inputStream)
        output = DataOutputStream(ssl.outputStream)

        val codeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(ssl, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE)
        val password = codeBytes + keyMaterial
        ctx = PairingContext.create(password) ?: throw IOException("Unable to create the SPAKE2 context")

        exchangeMessages()
        exchangePeerInfo()
    }

    private fun exchangeMessages() {
        val c = ctx!!
        writePacket(TYPE_SPAKE2_MSG, c.msg)
        val (type, payload) = readPacket()
        if (type != TYPE_SPAKE2_MSG) throw IOException("Unexpected pairing packet type $type")
        if (!c.initCipher(payload)) throw AdbWrongPairingCodeException()
    }

    private fun exchangePeerInfo() {
        val c = ctx!!
        val buf = ByteBuffer.allocate(MAX_PEER_INFO_SIZE)
        buf.put(PEER_INFO_ADB_RSA_PUB_KEY)
        val key = crypto.publicKeyAdbFormat("adb@min")
        buf.put(key, 0, minOf(key.size, MAX_PEER_INFO_SIZE - 1))
        val encrypted = c.encrypt(buf.array()) ?: throw IOException("Could not encrypt peer info")
        writePacket(TYPE_PEER_INFO, encrypted)

        val (type, payload) = readPacket()
        if (type != TYPE_PEER_INFO) throw IOException("Unexpected pairing packet type $type")
        // A wrong code only shows up here: the device's reply can't be decrypted with our key.
        val decrypted = c.decrypt(payload) ?: throw AdbWrongPairingCodeException()
        if (decrypted.size != MAX_PEER_INFO_SIZE) throw IOException("Bad peer info size ${decrypted.size}")
    }

    private fun writePacket(type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION).put(type).putInt(payload.size).array()
        output!!.write(header)
        output!!.write(payload)
        output!!.flush()
    }

    private fun readPacket(): Pair<Byte, ByteArray> {
        val h = ByteArray(HEADER_SIZE)
        input!!.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        val version = b.get()
        val type = b.get()
        val size = b.int
        if (version != VERSION) throw IOException("Pairing protocol version mismatch ($version)")
        if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) throw IOException("Unknown pairing packet type $type")
        if (size <= 0 || size > MAX_PEER_INFO_SIZE * 2) throw IOException("Unsafe pairing payload size $size")
        val payload = ByteArray(size)
        input!!.readFully(payload)
        return type to payload
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        runCatching { ctx?.destroy() }
        ctx = null
    }

    private companion object {
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2_MSG: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_INFO_ADB_RSA_PUB_KEY: Byte = 0
        const val HEADER_SIZE = 6
        const val MAX_PEER_INFO_SIZE = 8192
        const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val EXPORTED_KEY_SIZE = 64
    }
}
