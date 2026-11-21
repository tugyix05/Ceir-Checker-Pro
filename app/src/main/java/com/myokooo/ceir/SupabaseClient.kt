package com.myokooo.ceir

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SupabaseManager {
    // Build-time secrets (env SUPABASE_URL / SUPABASE_KEY in CI, or gradle.properties locally)
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
    val SUPABASE_KEY: String = BuildConfig.SUPABASE_KEY

    val isConfigured: Boolean
        get() = SUPABASE_URL.isNotBlank() && SUPABASE_KEY.isNotBlank()

    private val client = OkHttpClient.Builder().build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ============================================================
    // AUTH — Sign Up
    // ============================================================
    fun signUp(email: String, password: String): Result<JSONObject> {
        return try {
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/signup")
                .addHeader("apikey", SUPABASE_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            val json = JSONObject(responseBody)

            if (response.isSuccessful) {
                Result.success(json)
            } else {
                Result.failure(Exception(json.optString("msg", "Sign up failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    // AUTH — Sign In
    // ============================================================
    fun signIn(email: String, password: String): Result<JSONObject> {
        return try {
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=password")
                .addHeader("apikey", SUPABASE_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            val json = JSONObject(responseBody)

            if (response.isSuccessful) {
                Result.success(json)
            } else {
                Result.failure(Exception(json.optString("error_description", "Sign in failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
