package com.myokooo.ceir

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Server-side key directory for the public-key design (2026-11-21).
 *
 * One row (id='main') in the `bee_keys` table holds:
 *  - public_key: the RSA public key (public by design — safe to cache
 *    on any phone, safe for any user to fetch).
 *  - private_blob: the RSA private key encrypted with the owner's pass
 *    (PBKDF2 + AES-GCM via [CryptoManager]). Downloadable by anyone,
 *    useless without the pass.
 *
 * The app never ships either value. Setup happens once, on the owner's
 * phone, from the Bee screen. First-write-wins is enforced server-side
 * by the Lovable proxy (a second setup POST must be rejected); the app
 * also refuses to overwrite and verifies the stored row after writing.
 */
object BeeKeyDirectory {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val PREFS = "ceir_cloud_crypto"
    private const val KEY_PUBLIC = "bee_public_key_v2"
    private const val KEY_BLOB = "bee_private_blob_v2"
    private const val ROW_ID = "main"

    data class BeeKeys(val publicKeyB64: String, val privateBlob: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cachedPublicKey(context: Context): String? =
        prefs(context).getString(KEY_PUBLIC, null)

    fun cachedPrivateBlob(context: Context): String? =
        prefs(context).getString(KEY_BLOB, null)

    private fun cacheKeys(context: Context, keys: BeeKeys) {
        prefs(context).edit()
            .putString(KEY_PUBLIC, keys.publicKeyB64)
            .putString(KEY_BLOB, keys.privateBlob)
            .apply()
    }

    /** Fetch the key row from the server (null when setup hasn't happened yet). */
    suspend fun fetchKeys(context: Context): BeeKeys? = withContext(Dispatchers.IO) {
        if (!SupabaseManager.isConfigured) return@withContext null
        val url = "${SupabaseManager.SUPABASE_URL}/rest/v1/bee_keys" +
            "?select=public_key,private_blob&id=eq.$ROW_ID&limit=1"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseManager.SUPABASE_KEY)
            .addHeader("Authorization", "Bearer ${SupabaseManager.SUPABASE_KEY}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null
        val arr = JSONArray(response.body?.string() ?: "[]")
        if (arr.length() == 0) return@withContext null
        val o = arr.getJSONObject(0)
        BeeKeys(o.optString("public_key"), o.optString("private_blob"))
            .takeIf { it.publicKeyB64.isNotBlank() && it.privateBlob.isNotBlank() }
    }

    /**
     * First-write-wins setup: false if keys already exist or the upload
     * fails. A second insert for the same id is rejected (no merge).
     */
    suspend fun setupKeys(context: Context, keys: BeeKeys): Boolean =
        withContext(Dispatchers.IO) {
            if (!SupabaseManager.isConfigured) return@withContext false
            if (fetchKeys(context) != null) return@withContext false
            val body = JSONObject().apply {
                put("id", ROW_ID)
                put("public_key", keys.publicKeyB64)
                put("private_blob", keys.privateBlob)
            }.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("${SupabaseManager.SUPABASE_URL}/rest/v1/bee_keys")
                .addHeader("apikey", SupabaseManager.SUPABASE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseManager.SUPABASE_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false
            // Re-read to confirm the stored row is the one we just wrote
            // (a lost race returns someone else's row).
            val stored = fetchKeys(context) ?: return@withContext false
            if (stored.publicKeyB64 != keys.publicKeyB64) return@withContext false
            cacheKeys(context, stored)
            true
        }

    /** Public key for uploads: local cache first, else server. Null = setup not done yet. */
    suspend fun getPublicKeyB64(context: Context): String? {
        cachedPublicKey(context)?.let { return it }
        val keys = fetchKeys(context) ?: return null
        cacheKeys(context, keys)
        return keys.publicKeyB64
    }

    /** Encrypted private blob for viewing: local cache first, else server. */
    suspend fun getPrivateBlob(context: Context): String? {
        cachedPrivateBlob(context)?.let { return it }
        val keys = fetchKeys(context) ?: return null
        cacheKeys(context, keys)
        return keys.privateBlob
    }
}
