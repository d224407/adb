package com.min.adb.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * Generates and persists an RSA keypair for ADB auth, and signs auth tokens the way
 * adbd expects (raw PKCS#1 v1.5 padded SHA-1-less signature over the 20-byte token).
 * Equivalent to the ~/.android/adbkey pair that the real `adb` client keeps.
 */
class AdbCrypto private constructor(val keyPair: KeyPair) {

    /** Signs adbd's 20-byte "token": PKCS#1 v1.5 with a SHA-1 DigestInfo prefix (token = already-hashed digest). */
    fun sign(token: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val digestInfo = prefix + token
        val padded = ByteArray(256)
        padded[1] = 1
        val psLen = 256 - 3 - digestInfo.size
        for (i in 0 until psLen) padded[2 + i] = 0xFF.toByte()
        System.arraycopy(digestInfo, 0, padded, 3 + psLen, digestInfo.size)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(padded)
    }

    /** ADB's custom public-key wire format: base64(mincrypt RSAPublicKey struct) + " label\u0000". */
    fun publicKeyAdbFormat(deviceLabel: String): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val encoded = AdbRsaPublicKeyEncoder.encode(pub)
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return "$b64 $deviceLabel\u0000".toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val FILE_PRIV = "adb_key_priv"
        private const val FILE_PUB = "adb_key_pub"

        fun loadOrGenerate(context: Context): AdbCrypto {
            val dir = context.filesDir
            val privFile = File(dir, FILE_PRIV)
            val pubFile = File(dir, FILE_PUB)
            if (privFile.exists() && pubFile.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())) as RSAPrivateKey
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
                return AdbCrypto(KeyPair(pub, priv))
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val kp = gen.generateKeyPair()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
            return AdbCrypto(kp)
        }
    }
}

/**
 * Encodes an RSA-2048 public key into the little-endian "mincrypt" RSAPublicKey struct
 * adbd parses: { len=64 u32-words, n0inv, n[256], rr[256], exponent }, base64'd.
 */
object AdbRsaPublicKeyEncoder {
    fun encode(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64) // modulus size in 32-bit words (2048 / 32)
        buf.putInt(n0inv.toInt())
        buf.put(toLittleEndian(n, 256))
        buf.put(toLittleEndian(rr, 256))
        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }

    private fun toLittleEndian(x: BigInteger, size: Int): ByteArray {
        val be = x.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until size) {
            val src = be.size - 1 - i
            if (src >= 0) out[i] = be[src]
        }
        return out
    }
}
