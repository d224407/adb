package com.min.adb.adb.transport

/** A raw byte pipe to adbd — either a TCP socket (network ADB) or a USB bulk endpoint pair. */
interface Transport {
    fun connect()
    fun write(data: ByteArray)
    fun readFully(len: Int): ByteArray
    fun close()
    /** Upgrades the live connection to TLS (ADB A_STLS). Only TCP supports it. */
    fun startTls(context: javax.net.ssl.SSLContext) { throw java.io.IOException("TLS is not supported on this transport") }
}
