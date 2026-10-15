package com.example.tvremote.adb.transport

import java.net.InetSocketAddress
import java.net.Socket

class TcpTransport(private val host: String, private val port: Int = 5555) : Transport {
    private lateinit var socket: Socket

    override fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.tcpNoDelay = true
    }

    override fun write(data: ByteArray) {
        socket.getOutputStream().write(data)
        socket.getOutputStream().flush()
    }

    override fun readFully(len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        val input = socket.getInputStream()
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw java.io.IOException("ADB connection closed by remote")
            off += n
        }
        return buf
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
