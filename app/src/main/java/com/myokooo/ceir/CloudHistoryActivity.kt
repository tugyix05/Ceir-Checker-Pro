package com.myokooo.ceir

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Item 13 — 🐝 encrypted cloud history (public-key design, 2026-11-21).
 *
 * - Upload needs no pass and no unlock (public key) — users' scans
 *   auto-sync. The Bee screen is VIEWING only.
 * - First open on the owner's phone = SETUP: enter a new pass twice →
 *   an RSA keypair is generated, the public key + pass-encrypted private
 *   blob are uploaded once to the `bee_keys` directory.
 * - Later opens = UNLOCK: enter the pass → the private blob is decrypted
 *   (GCM tag = pass check) and rows decrypt in memory only.
 * - Wrong pass → GCM auth fails → nothing decrypts, error shown.
 * - Leaving the screen wipes the session immediately (auto-lock).
 * - The pass itself is never stored, never shipped, never sent anywhere.
 */
class CloudHistoryActivity : AppCompatActivity() {

    companion object {
        fun promptAndOpen(context: Context) {
            context.startActivity(
                Intent(context, CloudHistoryActivity::class.java)
            )
        }
    }

    private lateinit var lockView: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var keyConfirmInput: EditText
    private lateinit var unlockBtn: Button
    private lateinit var lockErrorText: TextView
    private lateinit var lockHintText: TextView
    private lateinit var cloudList: RecyclerView
    private lateinit var cloudEmptyText: TextView
    private lateinit var cloudStatusText: TextView
    private lateinit var adapter: HistoryAdapter

    private var setupMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud_history)

        lockView = findViewById(R.id.lockView)
        keyInput = findViewById(R.id.keyInput)
        keyConfirmInput = findViewById(R.id.keyConfirmInput)
        unlockBtn = findViewById(R.id.unlockBtn)
        lockErrorText = findViewById(R.id.lockErrorText)
        lockHintText = findViewById(R.id.lockHintText)
        cloudList = findViewById(R.id.cloudList)
        cloudEmptyText = findViewById(R.id.cloudEmptyText)
        cloudStatusText = findViewById(R.id.cloudStatusText)

        adapter = HistoryAdapter { entity -> confirmDeleteCloudRow(entity) }
        cloudList.layoutManager = LinearLayoutManager(this)
        cloudList.adapter = adapter

        // Item 8 — same 9-card adaptive density as the local history list.
        cloudList.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    cloudList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val h = cloudList.height
                    if (h > 0) adapter.setCardHeight(h / 9)
                }
            }
        )

        unlockBtn.setOnClickListener { tryUnlock() }
        unlockBtn.isEnabled = false // enabled once setup/unlock mode is known
        lifecycleScope.launch { refreshKeysMode() }
        // Note: initial showLock()/showList() happens in onResume (which always
        // runs after onCreate) — doing it in both would fetch twice.
    }

    override fun onResume() {
        super.onResume()
        // Auto-lock may have wiped the session while we were away.
        if (BeeSession.isUnlocked()) showList() else showLock()
    }

    override fun onPause() {
        super.onPause()
        // Item 13 — auto-lock: leaving this screen wipes the in-memory
        // session immediately (rotation doesn't count as leaving).
        if (!isChangingConfigurations) {
            BeeSession.clear()
        }
    }

    /** Setup mode when no keys exist on the server (first open, owner's phone). */
    private suspend fun refreshKeysMode() {
        val keys = try {
            BeeKeyDirectory.fetchKeys(this)
        } catch (_: Exception) {
            null
        }
        val hasKeys = keys != null ||
            BeeKeyDirectory.cachedPublicKey(this) != null ||
            BeeKeyDirectory.cachedPrivateBlob(this) != null
        setupMode = !hasKeys
        keyConfirmInput.visibility = if (setupMode) View.VISIBLE else View.GONE
        lockHintText.text = if (setupMode) {
            "ပထမအကြိမ် setup — pass ကို ၂ ခါထည့်ပါ။\n" +
                "ဒီ pass နဲ့ပဲ cloud ကို ဖွင့်လို့ရမယ်။\n" +
                "မေ့သွားရင် ဘယ်လိုမှ ပြန်ဖော်လို့မရဘူး။"
        } else {
            "Owner pass ထည့်ပါ"
        }
        unlockBtn.isEnabled = true
    }

    private fun showLock() {
        lockView.visibility = View.VISIBLE
        cloudList.visibility = View.GONE
        cloudEmptyText.visibility = View.GONE
        cloudStatusText.visibility = View.GONE
        lockErrorText.visibility = View.GONE
        keyInput.text?.clear()
        keyConfirmInput.text?.clear()
    }

    private fun showList() {
        lockView.visibility = View.GONE
        cloudList.visibility = View.VISIBLE
        loadCloudRows()
    }

    private fun lockError(msg: String) {
        lockErrorText.text = msg
        lockErrorText.visibility = View.VISIBLE
    }

    /**
     * Cloud row delete — the ONLY way a Supabase row is removed.
     * Local history Clear/delete never touches cloud rows.
     */
    private fun confirmDeleteCloudRow(entity: com.myokooo.ceir.data.ScanEntity) {
        val brand = entity.brand?.trim().orEmpty()
        val model = entity.model?.trim().orEmpty()
        val name = when {
            brand.isNotEmpty() && model.isNotEmpty() ->
                if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
            model.isNotEmpty() -> model
            brand.isNotEmpty() -> brand
            else -> "Unknown Device"
        }
        val imeis = entity.imei1 +
            (if (!entity.imei2.isNullOrBlank()) "\n${entity.imei2}" else "")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Cloud က ဖျက်မလား?")
            .setMessage("$name\n$imeis")
            .setPositiveButton("ဖျက်မယ်") { _, _ ->
                lifecycleScope.launch {
                    try {
                        CloudHistorySync.deleteCloudRow(entity.id)
                        Toast.makeText(
                            this@CloudHistoryActivity,
                            "Cloud က ဖျက်ပြီး",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadCloudRows()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@CloudHistoryActivity,
                            "Delete fail: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("မလုပ်ဘူး", null)
            .show()
    }

    private fun tryUnlock() {
        val pw = keyInput.text?.toString()?.toCharArray() ?: CharArray(0)
        lifecycleScope.launch {
            try {
                if (pw.isEmpty()) {
                    lockError("Key ထည့်ပါ")
                    return@launch
                }
                if (setupMode) {
                    val confirm = keyConfirmInput.text?.toString()?.toCharArray()
                        ?: CharArray(0)
                    try {
                        if (!pw.contentEquals(confirm)) {
                            lockError("Key ၂ ခု မတူဘူး")
                            return@launch
                        }
                        val ok = withContext(Dispatchers.IO) { setupNewKeys(pw) }
                        if (!ok) {
                            // Keys appeared meanwhile (or network failed) → re-check mode.
                            refreshKeysMode()
                            lockError("Setup မအောင်ဘူး — ပြန်စစ်ပါ")
                            return@launch
                        }
                    } finally {
                        confirm.fill('\u0000')
                    }
                } else {
                    val result = withContext(Dispatchers.IO) { unlockWithPass(pw) }
                    if (result != UnlockResult.OK) {
                        lockError(
                            if (result == UnlockResult.WRONG_PASS) "Key မှားနေတယ်"
                            else "Server မရဘူး — internet စစ်ပြီး ပြန်စမ်းပါ"
                        )
                        return@launch
                    }
                }
                showList()
            } finally {
                pw.fill('\u0000')
            }
        }
    }

    /**
     * First-time setup (owner's phone): generate the RSA keypair, upload
     * the public key + pass-encrypted private blob once, open the session.
     */
    private suspend fun setupNewKeys(pw: CharArray): Boolean {
        val pair = BeeCrypto.generateKeyPair()
        val publicB64 = BeeCrypto.publicToB64(pair.public)
        val blob = CryptoManager.encrypt(
            pw,
            BeeCrypto.privateToB64(pair.private).toByteArray(Charsets.UTF_8)
        )
        val keys = BeeKeyDirectory.BeeKeys(publicB64, blob)
        if (!BeeKeyDirectory.setupKeys(this, keys)) return false
        BeeSession.unlock(pw, pair.private)
        return true
    }

    private enum class UnlockResult { OK, WRONG_PASS, UNAVAILABLE }

    /** Unlock: decrypt the private blob with the pass (GCM tag = pass check). */
    private suspend fun unlockWithPass(pw: CharArray): UnlockResult {
        val blob = try {
            BeeKeyDirectory.getPrivateBlob(this)
        } catch (_: Exception) {
            null
        } ?: return UnlockResult.UNAVAILABLE
        return try {
            val plain = CryptoManager.decrypt(pw, blob).toString(Charsets.UTF_8)
            val key = BeeCrypto.privateFromB64(plain)
            BeeSession.unlock(pw, key)
            UnlockResult.OK
        } catch (_: javax.crypto.AEADBadTagException) {
            UnlockResult.WRONG_PASS // GCM tag failure = wrong pass
        } catch (_: Exception) {
            UnlockResult.UNAVAILABLE // corrupt blob / bad key data
        }
    }

    private fun loadCloudRows() {
        cloudStatusText.visibility = View.VISIBLE
        cloudStatusText.text = "Loading…"
        lifecycleScope.launch {
            // Working copies of the in-memory session; pass zeroed when done.
            val pw: CharArray = try {
                BeeSession.withPassword { it.copyOf() }
            } catch (e: IllegalStateException) {
                showLock()
                return@launch
            }
            val privateKey = try {
                BeeSession.withPrivateKey { it }
            } catch (e: IllegalStateException) {
                pw.fill('\u0000')
                showLock()
                return@launch
            }
            try {
                // Flush queued local rows first (public-key, no pass needed).
                val uploaded = try {
                    CloudHistorySync.uploadPending(this@CloudHistoryActivity)
                } catch (e: Exception) {
                    0
                }

                val rows = CloudHistorySync.fetchCloudRows(this@CloudHistoryActivity)
                // View-side dedup: same pair scanned on different phones → newest wins.
                val byPair = mutableMapOf<String, Pair<com.myokooo.ceir.data.ScanEntity, Long>>()
                var failed = 0
                for (row in rows) {
                    try {
                        val payload = row.encPayload!!
                        val plain = if (payload.startsWith(BeeCrypto.ROW_PREFIX)) {
                            BeeCrypto.decryptRow(privateKey, payload)
                        } else {
                            CryptoManager.decrypt(pw, payload)
                        }
                        val obj = org.json.JSONObject(plain.toString(Charsets.UTF_8))
                        val entity = CloudHistorySync.jsonToEntity(obj)
                        val key = CloudHistorySync.pairKey(entity.imei1, entity.imei2)
                        val prev = byPair[key]
                        if (prev == null || row.scannedAt > prev.second) {
                            byPair[key] = entity to row.scannedAt
                        }
                    } catch (e: Exception) {
                        failed++ // wrong-key/tampered row: never shown
                    }
                }
                val entities = byPair.values.map { it.first }
                    .sortedByDescending { it.scannedAt }
                adapter.submitList(entities)
                cloudEmptyText.visibility =
                    if (entities.isEmpty()) View.VISIBLE else View.GONE
                cloudStatusText.text = buildString {
                    if (uploaded > 0) append("⬆ $uploaded rows uploaded  •  ")
                    append("☁ ${entities.size} rows")
                    if (failed > 0) append("  •  🔒 $failed rows (key မကိုက်)")
                }
            } catch (e: Exception) {
                cloudStatusText.text = "Error: ${e.message}"
            } finally {
                pw.fill('\u0000')
            }
        }
    }
}
