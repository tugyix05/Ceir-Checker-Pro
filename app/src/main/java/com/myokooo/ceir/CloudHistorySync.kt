package com.myokooo.ceir

import android.content.Context
import com.myokooo.ceir.data.AppDatabase
import com.myokooo.ceir.data.ScanEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Item 13 — 🐝 encrypted cloud history transport (public-key design, 2026-11-21).
 *
 * Uploads carry ONLY id / device_id / scanned_at / enc_payload.
 * The row's real content is a JSON blob encrypted with the RSA PUBLIC key
 * (hybrid AES-256-GCM, "v2:" payload) — the server never sees plaintext
 * IMEIs, statuses, or device info. Plaintext columns stay NULL for new rows.
 * Upload needs NO pass and NO unlock: any phone encrypts with the public
 * key fetched from the `bee_keys` directory, so users' scans auto-sync.
 * Only the owner, with the pass, can decrypt (see [BeeCrypto]).
 */
object CloudHistorySync {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Full row → JSON, then encrypted. Mirrors the old plaintext payload 1:1. */
    fun rowToJson(scan: ScanEntity): JSONObject = JSONObject().apply {
        put("id", scan.id)
        put("device_id", scan.deviceId)
        put("imei1", scan.imei1)
        put("imei2", scan.imei2 ?: JSONObject.NULL)
        put("imei1_status", scan.imei1Status ?: JSONObject.NULL)
        put("imei1_tax_payment", scan.imei1TaxPayment ?: JSONObject.NULL)
        put("imei1_blocking_status", scan.imei1BlockingStatus ?: JSONObject.NULL)
        put("imei2_status", scan.imei2Status ?: JSONObject.NULL)
        put("imei2_tax_payment", scan.imei2TaxPayment ?: JSONObject.NULL)
        put("imei2_blocking_status", scan.imei2BlockingStatus ?: JSONObject.NULL)
        put("brand", scan.brand ?: JSONObject.NULL)
        put("model", scan.model ?: JSONObject.NULL)
        put("manufacturer", scan.manufacturer ?: JSONObject.NULL)
        put("operating_system", scan.operatingSystem ?: JSONObject.NULL)
        put("device_type", scan.deviceType ?: JSONObject.NULL)
        put("allocation_date", scan.allocationDate ?: JSONObject.NULL)
        put("scanned_at", scan.scannedAt)
    }

    /** Decrypted JSON → ScanEntity (in-memory; never auto-inserted). */
    fun jsonToEntity(o: JSONObject): ScanEntity = ScanEntity(
        id = o.optString("id"),
        deviceId = o.optString("device_id"),
        imei1 = o.optString("imei1"),
        imei2 = o.optString("imei2", null)?.takeIf { it != "null" && it.isNotEmpty() },
        imei1Status = o.optString("imei1_status", null)?.takeIf { it != "null" },
        imei1TaxPayment = o.optString("imei1_tax_payment", null)?.takeIf { it != "null" },
        imei1BlockingStatus = o.optString("imei1_blocking_status", null)?.takeIf { it != "null" },
        imei2Status = o.optString("imei2_status", null)?.takeIf { it != "null" },
        imei2TaxPayment = o.optString("imei2_tax_payment", null)?.takeIf { it != "null" },
        imei2BlockingStatus = o.optString("imei2_blocking_status", null)?.takeIf { it != "null" },
        brand = o.optString("brand", null)?.takeIf { it != "null" },
        model = o.optString("model", null)?.takeIf { it != "null" },
        manufacturer = o.optString("manufacturer", null)?.takeIf { it != "null" },
        operatingSystem = o.optString("operating_system", null)?.takeIf { it != "null" },
        deviceType = o.optString("device_type", null)?.takeIf { it != "null" },
        allocationDate = o.optString("allocation_date", null)?.takeIf { it != "null" },
        scannedAt = o.optLong("scanned_at", System.currentTimeMillis()),
        syncStatus = 1
    )

    /**
     * Order-insensitive IMEI-pair key for dedup.
     * Same physical device regardless of scan order; single-IMEI rows
     * key on the one IMEI alone.
     */
    fun pairKey(imei1: String, imei2: String?): String =
        listOf(imei1.trim(), (imei2 ?: "").trim())
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString("|")

    /**
     * Encrypt every pending local row with the PUBLIC key and upsert.
     *
     * No pass, no unlock, no cloud decrypt: each local row keeps its own
     * id, and `scans.id` is the primary key, so re-uploading an updated
     * row (same id) merges in place via `resolution=merge-duplicates`.
     * A pair scanned on TWO phones keeps two cloud rows (different ids);
     * the Bee screen collapses them at view time (newest wins).
     *
     * Returns the number of pending rows handled. 0 when setup hasn't
     * happened yet (no public key) or nothing is pending. Throws on
     * network failure (nothing is marked synced, so the next run retries).
     */
    suspend fun uploadPending(context: Context): Int =
        withContext(Dispatchers.IO) {
            if (!SupabaseManager.isConfigured) return@withContext 0
            val publicB64 = BeeKeyDirectory.getPublicKeyB64(context)
                ?: return@withContext 0
            val publicKey = try {
                BeeCrypto.publicFromB64(publicB64)
            } catch (_: Exception) {
                return@withContext 0
            }
            val dao = AppDatabase.get(context).scanDao()
            val pending = dao.getPending()
            if (pending.isEmpty()) return@withContext 0

            val payload = JSONArray()
            for (scan in pending) {
                val enc = BeeCrypto.encryptRow(
                    publicKey,
                    rowToJson(scan).toString().toByteArray(Charsets.UTF_8)
                )
                payload.put(JSONObject().apply {
                    put("id", scan.id)
                    put("device_id", scan.deviceId)
                    put("scanned_at", scan.scannedAt)
                    put("enc_payload", enc)
                })
            }

            val request = Request.Builder()
                .url("${SupabaseManager.SUPABASE_URL}/rest/v1/scans")
                .addHeader("apikey", SupabaseManager.SUPABASE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseManager.SUPABASE_KEY}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("upload HTTP ${response.code}")
            }
            for (scan in pending) dao.markSynced(scan.id)
            pending.size
        }

    /** UPDATE one cloud row in place (fresh timestamp + latest payload). */
    suspend fun updateCloudRow(id: String, encPayload: String, scannedAt: Long): Unit =
        withContext(Dispatchers.IO) {
            if (!SupabaseManager.isConfigured) {
                throw IllegalStateException("Supabase not configured")
            }
            val body = JSONObject().apply {
                put("scanned_at", scannedAt)
                put("enc_payload", encPayload)
            }.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url(
                    "${SupabaseManager.SUPABASE_URL}/rest/v1/scans" +
                        "?id=eq.${java.net.URLEncoder.encode(id, "UTF-8")}"
                )
                .addHeader("apikey", SupabaseManager.SUPABASE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseManager.SUPABASE_KEY}")
                .addHeader("Content-Type", "application/json")
                .patch(body)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("update HTTP ${response.code}")
            }
        }

    /**
     * DELETE one cloud row. This is the ONLY path that removes a cloud
     * row — local history Clear/delete never touches Supabase.
     */
    suspend fun deleteCloudRow(id: String): Unit =
        withContext(Dispatchers.IO) {
            if (!SupabaseManager.isConfigured) {
                throw IllegalStateException("Supabase not configured")
            }
            val request = Request.Builder()
                .url(
                    "${SupabaseManager.SUPABASE_URL}/rest/v1/scans" +
                        "?id=eq.${java.net.URLEncoder.encode(id, "UTF-8")}"
                )
                .addHeader("apikey", SupabaseManager.SUPABASE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseManager.SUPABASE_KEY}")
                .delete()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("delete HTTP ${response.code}")
            }
        }

    /**
     * Encrypted cloud rows from ALL devices, newest first.
     * The owner views every user's uploads; rows are decrypted by the
     * Bee screen (pass → private key), never here.
     */
    suspend fun fetchCloudRows(context: Context): List<CloudRow> =
        withContext(Dispatchers.IO) {
            if (!SupabaseManager.isConfigured) return@withContext emptyList()
            val url = "${SupabaseManager.SUPABASE_URL}/rest/v1/scans" +
                "?select=id,scanned_at,enc_payload" +
                "&order=scanned_at.desc"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseManager.SUPABASE_KEY)
                .addHeader("Authorization", "Bearer ${SupabaseManager.SUPABASE_KEY}")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("fetch HTTP ${response.code}")
            }
            val arr = JSONArray(response.body?.string() ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CloudRow(
                    id = o.optString("id"),
                    scannedAt = o.optLong("scanned_at"),
                    encPayload = o.optString("enc_payload", null)
                )
            }.filter { !it.encPayload.isNullOrEmpty() }
        }

    data class CloudRow(
        val id: String,
        val scannedAt: Long,
        val encPayload: String?
    )
}
