package com.myokooo.ceir

import java.security.PrivateKey

/**
 * In-memory view session for 🐝 cloud history (public-key design).
 *
 * Holds the pass (legacy-row decrypt) AND the decrypted private key
 * (v2-row decrypt), ONLY while the cloud screen is open. Leaving the
 * screen wipes both immediately (auto-lock). Nothing here is ever
 * written to disk. Uploads never touch this holder — they use the
 * public key only, so auto-sync works with no unlock at all.
 */
object BeeSession {

    private var password: CharArray? = null
    private var privateKey: PrivateKey? = null

    @Synchronized
    fun unlock(pw: CharArray, key: PrivateKey) {
        clear()
        password = pw.copyOf()
        privateKey = key
    }

    @Synchronized
    fun isUnlocked(): Boolean = password != null && privateKey != null

    @Synchronized
    fun <T> withPassword(block: (CharArray) -> T): T {
        val pw = password ?: throw IllegalStateException("Bee locked")
        return block(pw)
    }

    @Synchronized
    fun <T> withPrivateKey(block: (PrivateKey) -> T): T {
        val key = privateKey ?: throw IllegalStateException("Bee locked")
        return block(key)
    }

    @Synchronized
    fun clear() {
        password?.fill('\u0000')
        password = null
        privateKey = null
    }
}
