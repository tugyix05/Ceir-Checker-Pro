package com.myokooo.ceir

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.myokooo.ceir.data.AppDatabase
import com.myokooo.ceir.data.ScanEntity
import com.myokooo.ceir.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var loadingBarContainer: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorBackBtn: Button

    private val CEIR_URL = "https://ceir.gov.mm/pay-tax"

    private var pendingImei1: String? = null
    private var pendingImei2: String? = null
    private var imeiSent = false
    private var booted = false

    /** Chrome-like UA with the WebView "; wv" marker stripped (Cloudflare 403 test). */
    private var chromeUA: String = ""

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val SEGMENT_COUNT = 16
    private val loadingSegments = mutableListOf<View>()
    private var loadingAnimHandler: Handler? = null
    private var loadingAnimRunnable: Runnable? = null
    private var loadingFillIndex = 0

    companion object {
        /** Set by ScannerActivity.resetAll(): destroy any in-progress CEIR WebView page. */
        @Volatile var resetSignal: Boolean = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            pendingImei1 = intent.getStringExtra("imei1")
            pendingImei2 = intent.getStringExtra("imei2")
            Log.d("CEIR", "Received IMEIs: $pendingImei1, $pendingImei2")

            webView = findViewById(R.id.webView)
            loadingPanel = findViewById(R.id.loadingPanel)
            loadingText = findViewById(R.id.loadingText)
            loadingBarContainer = findViewById(R.id.loadingBarContainer)
            errorPanel = findViewById(R.id.errorPanel)
            errorText = findViewById(R.id.errorText)
            errorBackBtn = findViewById(R.id.errorBackBtn)

            loadingPanel.visibility = View.VISIBLE
            errorPanel.visibility = View.GONE

            setupLoadingBar()
            startLoadingBarAnimation()

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Strip the "; wv" WebView marker → Chrome-like UA (Cloudflare 403 test).
                // Keeps the real Chrome version; only the marker is removed.
                val defaultUA = userAgentString
                chromeUA = defaultUA.replace("; wv", "")
                userAgentString = chromeUA
                Log.d("CEIR", "UA wv stripped: ${!chromeUA.contains("; wv")}")
            }

            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.addJavascriptInterface(WebAppInterface(), "Android")

            // Forward JS console.log to logcat so content.js diagnostics
            // (sitekey source, token length) are visible via adb logcat -s CEIR.
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("CEIR", "JS: ${msg.message()}")
                    return true
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Boot once: inject immediately after page load.
                    // (2026-09-30) The 25s cf_clearance gate was REMOVED: phone logs
                    // proved the native OkHttp bridge succeeds without the cookie,
                    // so waiting up to 25s for it was pure latency.
                    if (!booted) {
                        booted = true
                        logClearanceOnce()
                        injectContentJs()
                        sendImeisToJs()
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        showNativeError("Page load မရ — internet စစ်ပါ (${error?.description})")
                    }
                }
            }

            webView.loadUrl(CEIR_URL)

            // Fix E: preload tac.json natively in the background (parallel with page load)
            preloadTac()

            errorBackBtn.setOnClickListener { goBackToScanner() }

            scheduleSync()

            // 120s ကြာမှ result မရောက်သေးရင် native error
            Handler(Looper.getMainLooper()).postDelayed({
                if (loadingPanel.visibility == View.VISIBLE) {
                    showNativeError("Timeout — ထပ်စမ်းပါ")
                }
            }, 120_000)

        } catch (e: Exception) {
            Log.e("CEIR", "onCreate error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Scanner Reset requested a completely fresh session → destroy this WebView page
        if (resetSignal) {
            resetSignal = false
            finish()
        }
    }

    /**
     * One-shot diagnostic: log whether Cloudflare issued cf_clearance.
     * Never blocks — the native bridge works without it (verified 2026-09-30).
     */
    private fun logClearanceOnce() {
        try {
            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://ceir.gov.mm")
            Log.d("CEIR", "cf_clearance present: ${cookies != null && cookies.contains("cf_clearance")}")
        } catch (e: Exception) {
            Log.d("CEIR", "cf_clearance check failed")
        }
    }

    private fun sendImeisToJs() {
        if (pendingImei1 != null && !imeiSent) {
            imeiSent = true
            val imei1ToSend = pendingImei1
            val imei2ToSend = pendingImei2 ?: ""
            webView.postDelayed({
                try {
                    val js = "window.receiveImeis(" +
                            "${JSONObject.quote(imei1ToSend)}, " +
                            "${JSONObject.quote(imei2ToSend)});"
                    webView.evaluateJavascript(js, null)
                    Log.d("CEIR", "Sent IMEIs to content.js: $imei1ToSend, $imei2ToSend")
                } catch (e: Exception) {
                    Log.e("CEIR", "evaluateJavascript error", e)
                }
            }, 800)
        }
    }

    private fun setupLoadingBar() {
        loadingBarContainer.removeAllViews()
        loadingSegments.clear()
        for (i in 0 until SEGMENT_COUNT) {
            val seg = View(this)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (i != 0) params.marginStart = 2
            seg.layoutParams = params
            seg.background = makeSegmentDrawable(false)
            loadingBarContainer.addView(seg)
            loadingSegments.add(seg)
        }
    }

    private fun makeSegmentDrawable(filled: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 4f
            setColor(if (filled) 0xFFFFC400.toInt() else 0xFF1E293B.toInt())
        }
    }

    private fun startLoadingBarAnimation() {
        stopLoadingBarAnimation()
        loadingFillIndex = 0
        loadingAnimHandler = Handler(Looper.getMainLooper())
        loadingAnimRunnable = object : Runnable {
            override fun run() {
                for (i in loadingSegments.indices) {
                    loadingSegments[i].background = makeSegmentDrawable(i < loadingFillIndex)
                }
                loadingFillIndex = (loadingFillIndex + 1) % (SEGMENT_COUNT + 4)
                loadingAnimHandler?.postDelayed(this, 90)
            }
        }
        loadingAnimHandler?.post(loadingAnimRunnable!!)
    }

    private fun stopLoadingBarAnimation() {
        loadingAnimRunnable?.let { loadingAnimHandler?.removeCallbacks(it) }
        loadingAnimHandler = null
        loadingAnimRunnable = null
    }

    private data class TacInfo(val brand: String, val model: String)
    private val tacLock = Object()
    @Volatile private var tacMap: Map<String, TacInfo>? = null
    @Volatile private var tacPreloading = false

    /**
     * (2026-09-30, fix E) Parse tac.json (12.9MB, 255k entries) ONCE in a
     * background thread at startup and keep it in a native HashMap.
     * content.js calls Android.tacLookup(imei) instead of receiving the whole
     * 13MB file through evaluateJavascript on every check.
     */
    private fun preloadTac() {
        if (tacPreloading) return
        tacPreloading = true
        Thread {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val json = assets.open("tac.json").bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val map = HashMap<String, TacInfo>(obj.length())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val e = obj.optJSONObject(k) ?: continue
                    val nk = k.replace("\\s+".toRegex(), "").lowercase(Locale.US)
                    map[nk] = TacInfo(e.optString("brand"), e.optString("model"))
                }
                synchronized(tacLock) { tacMap = map }
                Log.d("CEIR", "tac native ready: ${map.size} entries, ${SystemClock.elapsedRealtime() - t0}ms")
            } catch (e: Exception) {
                Log.e("CEIR", "tac preload failed", e)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun injectContentJs() {
        try {
            val js = assets.open("content.js").bufferedReader().use { it.readText() }
            webView.evaluateJavascript(js, null)
            Log.d("CEIR", "content.js injected")
        } catch (e: Exception) {
            Log.e("CEIR", "content.js inject failed", e)
        }
    }

    private fun scheduleSync() {
        try {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                SyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            // Auto-sync: keep the 15-min background schedule enrolled.
            SyncWorker.schedulePeriodic(this)
        } catch (e: Exception) {
            Log.e("CEIR", "scheduleSync error", e)
        }
    }

    private fun onResultReceived(json: String) {
        stopLoadingBarAnimation()
        loadingPanel.visibility = View.GONE
        try {
            saveToDatabase(json)
        } catch (e: Exception) {
            Log.e("CEIR", "saveToDatabase error", e)
        }
    }

    private fun showNativeError(message: String) {
        stopLoadingBarAnimation()
        loadingPanel.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        errorText.text = message
    }

    private fun goBackToScanner() {
        // ScannerActivity stays alive below in the back stack — finishing here
        // returns to it with the scanned IMEIs preserved, and destroys this WebView.
        finish()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("IMEI", text))
            Toast.makeText(this, "Copy ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("CEIR", "copyToClipboard error", e)
        }
    }

    private fun exportWebViewToJpg() {
        try {
            if (webView.width <= 0 || webView.height <= 0) return
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "ceir jpg"
                        )
                        if (!dir.exists()) dir.mkdirs()
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val file = File(dir, "ceir_$ts.jpg")
                        FileOutputStream(file).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        }
                        true
                    } catch (e: Exception) {
                        Log.e("CEIR", "exportJpg error", e)
                        false
                    }
                }
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "JPG သိမ်းပြီး — Downloads/ceir jpg/" else "Export Fail",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("CEIR", "exportWebViewToJpg error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToDatabase(json: String) {
        val obj = JSONObject(json)
        val device = obj.getJSONObject("deviceInfo")
        val imeiResults = obj.getJSONArray("imeiResults")
        lifecycleScope.launch {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                val imei1 = if (imeiResults.length() >= 1) imeiResults.getJSONObject(0).optString("imei") else ""
                val imei2 = if (imeiResults.length() >= 2) imeiResults.getJSONObject(1).optString("imei") else null
                val r1 = if (imeiResults.length() >= 1) imeiResults.getJSONObject(0) else null
                val r2 = if (imeiResults.length() >= 2) imeiResults.getJSONObject(1) else null

                val entity = ScanEntity(
                    deviceId = deviceId,
                    imei1 = imei1,
                    imei2 = imei2,
                    imei1Status = r1?.optString("imeiStatus"),
                    imei1TaxPayment = r1?.optString("taxPayment"),
                    imei1BlockingStatus = r1?.optString("blockingStatus"),
                    imei2Status = r2?.optString("imeiStatus"),
                    imei2TaxPayment = r2?.optString("taxPayment"),
                    imei2BlockingStatus = r2?.optString("blockingStatus"),
                    brand = device.optString("brand"),
                    model = device.optString("model"),
                    manufacturer = device.optString("manufacturer"),
                    operatingSystem = device.optString("operatingSystem"),
                    deviceType = device.optString("deviceType"),
                    allocationDate = device.optString("allocationDate")
                )
                val dao = AppDatabase.get(applicationContext).scanDao()
                // Item 12 — same-device dedup: order-insensitive IMEI pair match.
                // Found → update in place (timestamp + latest status), no new row.
                val existing = dao.findByImeiPair(imei1, imei2)
                if (existing != null) {
                    // CRITICAL FIX: the stored row keeps its own IMEI order,
                    // which may be REVERSED vs this scan. Map each result to the
                    // retained IMEI *value*, never to the new scan order —
                    // otherwise a status lands on the wrong IMEI.
                    fun resultFor(storedImei: String): JSONObject? = when (storedImei) {
                        imei1 -> r1
                        imei2 -> r2
                        else -> null
                    }
                    val s1 = resultFor(existing.imei1)
                    val s2 = existing.imei2?.let { resultFor(it) }
                    val updated = existing.copy(
                        imei1Status = s1?.optString("imeiStatus"),
                        imei1TaxPayment = s1?.optString("taxPayment"),
                        imei1BlockingStatus = s1?.optString("blockingStatus"),
                        imei2Status = s2?.optString("imeiStatus"),
                        imei2TaxPayment = s2?.optString("taxPayment"),
                        imei2BlockingStatus = s2?.optString("blockingStatus"),
                        brand = device.optString("brand"),
                        model = device.optString("model"),
                        manufacturer = device.optString("manufacturer"),
                        operatingSystem = device.optString("operatingSystem"),
                        deviceType = device.optString("deviceType"),
                        allocationDate = device.optString("allocationDate"),
                        scannedAt = System.currentTimeMillis(),
                        syncStatus = 0,
                        syncedAt = null
                    )
                    dao.insert(updated)
                } else {
                    dao.insert(entity)
                }
                scheduleSync()
            } catch (e: Exception) {
                Log.e("CEIR", "DB insert error", e)
            }
        }
    }

    inner class WebAppInterface {

        /**
         * TAC (first 8 IMEI digits) → brand/model from the preloaded native map.
         * Returns {"brand":"..","model":".."} or {}. content.js falls back to the
         * API's gsmaBrandName/gsmaModelName when empty.
         * Called on a WebView background thread; waits max ~2s for the preload.
         */
        @JavascriptInterface
        fun tacLookup(imei: String): String {
            return try {
                val tac = imei.take(8).replace("\\s+".toRegex(), "").lowercase(Locale.US)
                var map = synchronized(tacLock) { tacMap }
                if (map == null) {
                    val t0 = SystemClock.elapsedRealtime()
                    while (map == null && SystemClock.elapsedRealtime() - t0 < 2000) {
                        Thread.sleep(100)
                        map = synchronized(tacLock) { tacMap }
                    }
                }
                val e = map?.get(tac)
                if (e != null) JSONObject().put("brand", e.brand).put("model", e.model).toString()
                else "{}"
            } catch (ex: Exception) {
                "{}"
            }
        }

        /**
         * Native CEIR API call over OkHttp (separate TLS stack from WebView,
         * full browser-like headers, Chrome-like UA without "; wv").
         * content.js still solves the fresh single-use Turnstile token per call
         * in the WebView and passes it here; the token is never logged.
         *
         * Called on a WebView background thread → synchronous OkHttp is fine.
         * Returns JSON string: {"status":200,"ok":true,"text":"..."}.
         */
        @JavascriptInterface
        fun nativeApi(method: String, path: String, token: String, bodyJson: String?): String {
            return try {
                val sep = if (path.contains("?")) "&" else "?"
                val url = "https://ceir.gov.mm$path${sep}altchaData=" +
                        URLEncoder.encode(token, "UTF-8")
                val cookie = try {
                    android.webkit.CookieManager.getInstance().getCookie("https://ceir.gov.mm")
                } catch (e: Exception) {
                    null
                }
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", chromeUA.ifEmpty { "Mozilla/5.0" })
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Origin", "https://ceir.gov.mm")
                    .header("Referer", "https://ceir.gov.mm/pay-tax")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                if (cookie != null) reqBuilder.header("Cookie", cookie)
                if (method == "POST") {
                    val body = (bodyJson ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
                    reqBuilder.post(body)
                } else {
                    reqBuilder.get()
                }
                okHttp.newCall(reqBuilder.build()).execute().use { resp ->
                    val text = try {
                        resp.body?.string() ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    Log.d("CEIR", "nativeApi $method $path → ${resp.code}")
                    JSONObject()
                        .put("status", resp.code)
                        .put("ok", resp.isSuccessful)
                        .put("text", text)
                        .toString()
                }
            } catch (e: Exception) {
                Log.e("CEIR", "nativeApi error", e)
                JSONObject()
                    .put("status", 0)
                    .put("ok", false)
                    .put("text", "native error: ${e.message}")
                    .toString()
            }
        }

        @JavascriptInterface
        fun onResult(json: String) {
            Log.d("CEIR", "Result received")
            runOnUiThread { onResultReceived(json) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e("CEIR", "onError: $message")
            // content.js က overlay မှာ error card ပြပြီးသား — native loading ပဲ ဖျောက်
            runOnUiThread {
                stopLoadingBarAnimation()
                loadingPanel.visibility = View.GONE
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun onProgress(message: String) {
            Log.d("CEIR", "Progress: $message")
            runOnUiThread {
                if (loadingPanel.visibility == View.VISIBLE) {
                    loadingText.text = message
                }
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread { copyToClipboard(text) }
        }

        @JavascriptInterface
        fun goBack() {
            runOnUiThread { goBackToScanner() }
        }

        @JavascriptInterface
        fun exportJpg() {
            runOnUiThread { exportWebViewToJpg() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLoadingBarAnimation()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goBackToScanner()
    }
}
