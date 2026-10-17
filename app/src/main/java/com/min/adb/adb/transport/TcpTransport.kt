package com.min.adb.adb.transport

import java.net.InetSocketAddress
import java.net.Socket

class TcpTransport(private val host: String, private val port: Int = 5555) : Transport {
    private lateinit var socket: Socket
    private lateinit var input: java.io.InputStream
    private lateinit var output: java.io.OutputStream

    override fun connect() {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.tcpNoDelay = true
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun startTls(context: javax.net.ssl.SSLContext) {
        val ssl = context.socketFactory.createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
        ssl.startHandshake()
        socket = ssl
        input = ssl.inputStream
        output = ssl.outputStream
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun readFully(len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
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
