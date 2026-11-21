package com.myokooo.ceir

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Public-key crypto for 🐝 cloud history (user decision 2026-11-21):
 * ONE shared pass, usable on ANY phone; the app goes to users and their
 * scans auto-sync with NO unlock.
 *
 * - Upload needs NO secret: rows are encrypted with the RSA PUBLIC key
 *   (hybrid: random AES-256 data key per row, RSA-OAEP wrapped). The
 *   public key is public by design — reverse engineering the APK reveals
 *   only it, and it cannot decrypt anything or reveal the pass.
 * - Viewing needs the pass: the RSA PRIVATE key is stored on the server
 *   encrypted with the pass (see [BeeKeyDirectory]); entering the pass
 *   decrypts it into memory only. No pass → no view.
 * - The pass itself is never stored anywhere, never shipped in the app,
 *   and never sent to the server.
 *
 * Row format: "v2:" + base64(wrappedKey[256] || iv[12] || ciphertext).
 * Legacy rows (symmetric AES-GCM, no prefix) are still decrypted with
 * the pass via [CryptoManager] for backward compatibility.
 */
object BeeCrypto {

    const val ROW_PREFIX = "v2:"
    private const val RSA_BITS = 2048
    private const val RSA_TRANS = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_TRANS = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    fun generateKeyPair(): java.security.KeyPair {
        val kg = KeyPairGenerator.getInstance("RSA")
        kg.initialize(RSA_BITS)
        return kg.generateKeyPair()
    }

    fun publicToB64(key: PublicKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun privateToB64(key: PrivateKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun publicFromB64(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun privateFromB64(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    /** Hybrid row encrypt with the public key. No pass needed. */
    fun encryptRow(publicKey: PublicKey, plaintext: ByteArray): String {
        val dataKey = ByteArray(32).also { random.nextBytes(it) }
        return try {
            val wrap = Cipher.getInstance(RSA_TRANS)
            wrap.init(Cipher.ENCRYPT_MODE, publicKey)
            val wrapped = wrap.doFinal(dataKey)
            val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
            val aes = Cipher.getInstance(AES_TRANS)
            aes.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val ct = aes.doFinal(plaintext)
            val out = ByteArray(wrapped.size + iv.size + ct.size)
            System.arraycopy(wrapped, 0, out, 0, wrapped.size)
            System.arraycopy(iv, 0, out, wrapped.size, iv.size)
            System.arraycopy(ct, 0, out, wrapped.size + iv.size, ct.size)
            ROW_PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } finally {
            dataKey.fill(0)
        }
    }

    /** Hybrid row decrypt with the private key (in memory only). */
    fun decryptRow(privateKey: PrivateKey, payload: String): ByteArray {
        require(payload.startsWith(ROW_PREFIX)) { "not a v2 payload" }
        val all = Base64.decode(payload.removePrefix(ROW_PREFIX), Base64.NO_WRAP)
        val wrappedLen = RSA_BITS / 8 // RSA-2048 wrap is always 256 bytes
        require(all.size > wrappedLen + IV_LEN) { "bad payload" }
        val wrapped = all.copyOfRange(0, wrappedLen)
        val iv = all.copyOfRange(wrappedLen, wrappedLen + IV_LEN)
        val ct = all.copyOfRange(wrappedLen + IV_LEN, all.size)
        val unwrap = Cipher.getInstance(RSA_TRANS)
        unwrap.init(Cipher.DECRYPT_MODE, privateKey)
        val dataKey = unwrap.doFinal(wrapped)
        try {
            val aes = Cipher.getInstance(AES_TRANS)
            aes.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dataKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            return aes.doFinal(ct)
        } finally {
            dataKey.fill(0)
        }
    }
}
