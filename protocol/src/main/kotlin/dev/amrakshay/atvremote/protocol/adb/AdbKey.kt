package dev.amrakshay.atvremote.protocol.adb

import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The RSA key adbd remembers us by, in the two shapes the AUTH exchange needs.
 *
 * adbd sends a 20-byte token; we sign it, and if it does not recognise the key
 * we send the public key instead, which is what makes the device show its
 * "Allow debugging?" dialog. Once the user accepts, adbd stores the key and
 * every later connection goes through on the signature alone.
 */
class AdbKey(private val keyPair: KeyPair) {

    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    /**
     * PKCS#1 v1.5 over the token, with the SHA-1 DigestInfo prefix but no
     * further hashing: adbd calls `RSA_sign(NID_sha1, token, …)`, which treats
     * the token as an already-computed digest. Hashing it again here would
     * produce a signature adbd silently rejects, leaving the device stuck
     * asking to be authorised.
     */
    fun sign(token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(keyPair.private)
            update(SHA1_DIGEST_INFO)
            update(token)
            sign()
        }

    /**
     * The key in adbd's own format, base64 then a space then a name.
     *
     * That format is a little C struct of Montgomery parameters rather than
     * anything standard — see `android_pubkey_encode` in libcrypto_utils.
     */
    fun publicKeyPayload(name: String = "atvremote@android-tv-remote"): ByteArray =
        (Base64.getEncoder().encodeToString(encodePublicKey()) + " " + name + "\u0000")
            .toByteArray(Charsets.US_ASCII)

    internal fun encodePublicKey(): ByteArray {
        val n = publicKey.modulus
        require(n.bitLength() == MODULUS_BITS) {
            "adbd only accepts $MODULUS_BITS-bit keys, this one is ${n.bitLength()}"
        }

        // n0inv = 2^32 - (n mod 2^32)^-1 mod 2^32, and rr = (2^2048)^2 mod n.
        // Both are Montgomery precomputations adbd expects to be handed rather
        // than derive.
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(n)

        val out = ByteArray(ENCODED_SIZE)
        var at = 0
        at = putLeInt(out, at, MODULUS_BITS / 32)
        at = putLeInt(out, at, n0inv.toInt())
        littleEndian(n, MODULUS_BYTES).copyInto(out, at); at += MODULUS_BYTES
        littleEndian(rr, MODULUS_BYTES).copyInto(out, at); at += MODULUS_BYTES
        putLeInt(out, at, publicKey.publicExponent.toInt())
        return out
    }

    fun privateKeyPem(): String = StringWriter().also { w ->
        JcaPEMWriter(w).use { it.writeObject(keyPair.private) }
    }.toString()

    companion object {
        private const val MODULUS_BITS = 2048
        private const val MODULUS_BYTES = MODULUS_BITS / 8
        private const val ENCODED_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

        /** ASN.1 DigestInfo for SHA-1, the prefix `RSA_sign(NID_sha1, …)` adds. */
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        fun generate(): AdbKey =
            AdbKey(KeyPairGenerator.getInstance("RSA").apply { initialize(MODULUS_BITS) }.generateKeyPair())

        fun fromPem(pem: String): AdbKey {
            val parsed = PEMParser(StringReader(pem)).readObject()
            val converter = JcaPEMKeyConverter()
            val pair = when (parsed) {
                is PEMKeyPair -> converter.getKeyPair(parsed)
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> {
                    val private = converter.getPrivateKey(parsed) as RSAPrivateCrtKey
                    val public = KeyFactory.getInstance("RSA").generatePublic(
                        RSAPublicKeySpec(private.modulus, private.publicExponent),
                    )
                    KeyPair(public, private)
                }
                else -> error("unsupported private key format: ${parsed?.javaClass}")
            }
            return AdbKey(pair)
        }

        /**
         * [value] as exactly [size] bytes, least significant first.
         *
         * [BigInteger.toByteArray] is big-endian and prepends a zero sign byte
         * whenever the high bit is set, which a 2048-bit modulus always does —
         * so it has to be trimmed before reversing, or every byte lands one
         * place out.
         */
        internal fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val big = value.toByteArray()
            val from = if (big.size > size) big.size - size else 0
            val length = big.size - from
            val out = ByteArray(size)
            for (i in 0 until length) out[i] = big[big.size - 1 - i]
            return out
        }

        private fun putLeInt(target: ByteArray, offset: Int, value: Int): Int {
            target[offset] = value.toByte()
            target[offset + 1] = (value ushr 8).toByte()
            target[offset + 2] = (value ushr 16).toByte()
            target[offset + 3] = (value ushr 24).toByte()
            return offset + 4
        }
    }
}
