package com.myokooo.ceir

import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myokooo.ceir.data.AppDatabase
import com.myokooo.ceir.data.ScanEntity
import com.myokooo.ceir.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var historyList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var exportBtn: Button
    private lateinit var importBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var beeBtn: Button
    private lateinit var adapter: HistoryAdapter

    // CSV Import — SAF file picker (no storage permission needed)
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCsv(uri)
        }

    private val db by lazy { AppDatabase.get(this) }
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyList = findViewById(R.id.historyList)
        emptyText = findViewById(R.id.emptyText)
        exportBtn = findViewById(R.id.exportBtn)
        importBtn = findViewById(R.id.importBtn)
        clearBtn = findViewById(R.id.clearBtn)
        beeBtn = findViewById(R.id.beeBtn)

        adapter = HistoryAdapter { scan -> deleteScan(scan) }
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = adapter

        // Item 8 — 9 cards fit on any screen: card height = list height ÷ 9
        historyList.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    historyList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val h = historyList.height
                    if (h > 0) adapter.setCardHeight(h / 9)
                }
            }
        )

        lifecycleScope.launch {
            db.scanDao().getAll().collectLatest { list ->
                adapter.submitList(list)
                emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                historyList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        exportBtn.setOnClickListener { exportCsv() }

        // CSV Import — same columns as Export; dedup by IMEI pair, never wipes checked rows
        importBtn.setOnClickListener {
            importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
        }

        // Item 13 — Clear: local history only, cloud untouched
        clearBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("History ရှင်းမလား?")
                .setMessage("Local history ပဲ ပျက်မယ်။\nCloud history မထိဘူး။")
                .setPositiveButton("ရှင်းမယ်") { _, _ ->
                    lifecycleScope.launch {
                        db.scanDao().deleteAll()
                        Toast.makeText(this@HistoryActivity, "Local history ရှင်းပြီး", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("မလုပ်ဘူး", null)
                .show()
        }

        // Item 13 — 🐝 Bee: cloud history (key prompt + decrypt), wired in CloudHistory chunk
        beeBtn.setOnClickListener {
            CloudHistoryActivity.promptAndOpen(this)
        }
    }

    // Item 2 — delete confirmation: device name + date, ဖျက်မယ်/မလုပ်ဘူး
    private fun deleteScan(scan: ScanEntity) {
        val brand = scan.brand?.trim().orEmpty()
        val model = scan.model?.trim().orEmpty()
        val name = when {
            brand.isNotEmpty() && model.isNotEmpty() ->
                if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
            model.isNotEmpty() -> model
            brand.isNotEmpty() -> brand
            else -> "Unknown Device"
        }
        AlertDialog.Builder(this)
            .setTitle("ဖျက်မလား?")
            .setMessage("$name\n${dateFmt.format(Date(scan.scannedAt))}")
            .setPositiveButton("ဖျက်မယ်") { _, _ ->
                lifecycleScope.launch {
                    db.scanDao().delete(scan)
                    Toast.makeText(this@HistoryActivity, "ဖျက်ပြီး", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("မလုပ်ဘူး", null)
            .show()
    }

    private fun importCsv(uri: android.net.Uri) {
        lifecycleScope.launch {
            val (message, inserted) = withContext(Dispatchers.IO) { readAndImport(uri) }
            if (inserted > 0) {
                // Auto-sync: upload the fresh rows without Bee unlock.
                SyncWorker.scheduleNow(this@HistoryActivity)
                SyncWorker.schedulePeriodic(this@HistoryActivity)
            }
            Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    // Returns (one-line Burmese summary for the toast, rows inserted).
    private suspend fun readAndImport(uri: android.net.Uri): Pair<String, Int> {
        return try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return "ဖိုင်ဖတ်မရဘူး" to 0
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return "CSV အလွတ်ဖြစ်နေတယ်" to 0
            val header = parseCsvLine(lines[0])
            if (header.none { it.trim().equals("IMEI 1", ignoreCase = true) }) {
                return "CSV format မမှန်ဘူး (IMEI 1 column မတွေ့ဘူး)" to 0
            }
            fun col(row: List<String>, name: String): String {
                val i = header.indexOfFirst { it.trim().equals(name, ignoreCase = true) }
                return if (i >= 0 && i < row.size) row[i].trim() else ""
            }
            val deviceId = Settings.Secure.getString(
                contentResolver, Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            val dao = db.scanDao()
            var inserted = 0
            var skipped = 0
            for (line in lines.drop(1)) {
                val row = parseCsvLine(line)
                val imei1 = col(row, "IMEI 1").replace(" ", "")
                if (imei1.isBlank()) continue
                val imei2 = col(row, "IMEI 2").replace(" ", "").takeIf { it.isNotBlank() }
                // Dedup: existing pair (either order) is skipped — checked results never wiped.
                if (dao.findByImeiPair(imei1, imei2) != null) { skipped++; continue }
                val scannedAt = parseCsvDate(col(row, "Scanned At"))
                    ?: System.currentTimeMillis()
                dao.insert(
                    ScanEntity(
                        deviceId = deviceId,
                        imei1 = imei1,
                        imei2 = imei2,
                        imei1Status = col(row, "IMEI1 Status").takeIf { it.isNotBlank() },
                        imei1TaxPayment = col(row, "IMEI1 Tax").takeIf { it.isNotBlank() },
                        imei1BlockingStatus = col(row, "IMEI1 Blocking").takeIf { it.isNotBlank() },
                        imei2Status = col(row, "IMEI2 Status").takeIf { it.isNotBlank() },
                        imei2TaxPayment = col(row, "IMEI2 Tax").takeIf { it.isNotBlank() },
                        imei2BlockingStatus = col(row, "IMEI2 Blocking").takeIf { it.isNotBlank() },
                        brand = col(row, "Brand").takeIf { it.isNotBlank() },
                        model = col(row, "Model").takeIf { it.isNotBlank() },
                        scannedAt = scannedAt,
                        syncStatus = 0 // pending — Bee unlock flushes it to cloud
                    )
                )
                inserted++
            }
            "Import ပြီးပြီ: $inserted ထည့်, $skipped ထပ်လို့ ကျော်" to inserted
        } catch (e: Exception) {
            e.printStackTrace()
            "Import Fail: ${e.message}" to 0
        }
    }

    // Minimal CSV parser: handles quoted fields ("a, b" and doubled "").
    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    private fun parseCsvDate(s: String): Long? {
        if (s.isBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(s)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            val list = adapter.currentList
            if (list.isEmpty()) {
                Toast.makeText(this@HistoryActivity, "Data မရှိပါ", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val csv = buildCsv(list)
            val ok = writeCsv(csv)
            Toast.makeText(
                this@HistoryActivity,
                if (ok) "Downloads/ceir-scanner/ မှာ သိမ်းပြီး" else "Export Fail",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildCsv(list: List<ScanEntity>): String {
        val sb = StringBuilder()
        sb.append("IMEI 1,IMEI 2,IMEI1 Status,IMEI1 Tax,IMEI1 Blocking,IMEI2 Status,IMEI2 Tax,IMEI2 Blocking,Brand,Model,Scanned At\n")
        for (s in list) {
            sb.append(escape(s.imei1)).append(',')
                .append(escape(s.imei2 ?: "")).append(',')
                .append(escape(s.imei1Status ?: "")).append(',')
                .append(escape(s.imei1TaxPayment ?: "")).append(',')
                .append(escape(s.imei1BlockingStatus ?: "")).append(',')
                .append(escape(s.imei2Status ?: "")).append(',')
                .append(escape(s.imei2TaxPayment ?: "")).append(',')
                .append(escape(s.imei2BlockingStatus ?: "")).append(',')
                .append(escape(s.brand ?: "")).append(',')
                .append(escape(s.model ?: "")).append(',')
                .append(escape(dateFmt.format(Date(s.scannedAt))))
                .append('\n')
        }
        return sb.toString()
    }

    private fun escape(s: String): String {
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    private suspend fun writeCsv(content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ceir-scanner"
            )
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "scan_$ts.csv")
            file.writeText(content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
