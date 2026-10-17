package com.min.adb.adb

/**
 * Thin JNI wrapper over BoringSSL's SPAKE2 + AES-128-GCM, as used by the ADB wireless-debugging
 * pairing protocol. Native side: app/src/main/jni/adb_pairing.cpp (adapted from Shizuku, Apache-2.0).
 */
internal class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)
    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)
    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)
    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init { System.loadLibrary("adbpairing") }

        fun create(password: ByteArray): PairingContext? {
            val ptr = nativeConstructor(true, password)
            return if (ptr != 0L) PairingContext(ptr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}
