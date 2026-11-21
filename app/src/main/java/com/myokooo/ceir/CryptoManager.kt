package com.myokooo.ceir

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Item 13 — 🐝 crypto primitives.
 *
 * - AES-256-GCM; key derived via PBKDF2-HMAC-SHA256 from the user-supplied
 *   key (100,000 iterations, 256-bit).
 * - Each encrypted value carries its own random salt + IV:
 *   enc_payload = base64(salt[16] || iv[12] || ciphertext).
 * - Used for: the pass-encrypted RSA private blob (public-key design) and
 *   decrypting legacy symmetric rows. Wrong key → GCM auth tag fails →
 *   [decrypt] throws, nothing is revealed.
 */
object CryptoManager {

    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    private fun deriveKeyBytes(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Encrypt → base64(salt || iv || ciphertext). Fresh random salt + IV
     * every call, so identical rows encrypt differently.
     */
    fun encrypt(password: CharArray, plaintext: ByteArray): String {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val keyBytes = deriveKeyBytes(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val ct = cipher.doFinal(plaintext)
            val out = ByteArray(SALT_LEN + IV_LEN + ct.size)
            System.arraycopy(salt, 0, out, 0, SALT_LEN)
            System.arraycopy(iv, 0, out, SALT_LEN, IV_LEN)
            System.arraycopy(ct, 0, out, SALT_LEN + IV_LEN, ct.size)
            return Base64.encodeToString(out, Base64.NO_WRAP)
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * Decrypt base64(salt || iv || ciphertext).
     * @throws javax.crypto.AEADBadTagException on wrong key / tampered data.
     */
    fun decrypt(password: CharArray, payloadB64: String): ByteArray {
        val all = Base64.decode(payloadB64, Base64.NO_WRAP)
        require(all.size > SALT_LEN + IV_LEN) { "bad payload" }
        val salt = all.copyOfRange(0, SALT_LEN)
        val iv = all.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val ct = all.copyOfRange(SALT_LEN + IV_LEN, all.size)
        val keyBytes = deriveKeyBytes(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            return cipher.doFinal(ct)
        } finally {
            keyBytes.fill(0)
        }
    }
}
