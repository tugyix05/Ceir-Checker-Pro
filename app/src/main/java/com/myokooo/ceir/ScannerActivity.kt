package com.myokooo.ceir

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.myokooo.ceir.data.AppDatabase
import com.myokooo.ceir.data.ScanEntity
import com.myokooo.ceir.sync.SyncWorker
import kotlinx.coroutines.launch
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var scanBand: ScanBandView
    private lateinit var cameraFrame: FrameLayout
    private lateinit var imeiResultArea: LinearLayout
    private lateinit var imei1Text: TextView
    private lateinit var imei2Text: TextView
    private lateinit var copyBothBtn: Button
    private lateinit var copyImei1Btn: Button
    private lateinit var copyImei2Btn: Button
    private lateinit var statusText: TextView
    private lateinit var modeBtn: TextView
    private lateinit var barcodeBtn: TextView
    private lateinit var startBtn: Button
    private lateinit var historyBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var checkCeirBtn: Button
    private lateinit var liveIndicator: LinearLayout
    private lateinit var cameraOffPlaceholder: FrameLayout

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null // Item 11: focus auto-detect needs it
    private var running = false
    private var cameraStarted = false
    private var mode = "ocr"

    // v5 state machine: idle (camera off) → scanning → captured
    private var scanState = "idle"

    private val detectedImeis = mutableListOf<DetectedImei>()
    private val stabilityMap = mutableMapOf<String, Int>()
    // threshold 1 ဆို stability check အဓိပ္ပာယ် မရှိ
    private val STABILITY_THRESHOLD = 3

    private val barcodeScanner = BarcodeScanning.getClient()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val TAG = "CEIR_Scanner"
        private const val REQUEST_CAMERA = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_scanner)

            previewView = findViewById(R.id.previewView)
            scanBand = findViewById(R.id.scanBand)
            cameraFrame = findViewById(R.id.cameraFrame)
            imeiResultArea = findViewById(R.id.imeiResultArea)
            imei1Text = findViewById(R.id.imei1Text)
            imei2Text = findViewById(R.id.imei2Text)
            copyBothBtn = findViewById(R.id.copyBothBtn)
            copyImei1Btn = findViewById(R.id.copyImei1Btn)
            copyImei2Btn = findViewById(R.id.copyImei2Btn)
            statusText = findViewById(R.id.statusText)
            modeBtn = findViewById(R.id.modeBtn)
            barcodeBtn = findViewById(R.id.barcodeBtn)
            startBtn = findViewById(R.id.startBtn)
            historyBtn = findViewById(R.id.historyBtn)
            resetBtn = findViewById(R.id.resetBtn)
            checkCeirBtn = findViewById(R.id.checkCeirBtn)
            liveIndicator = findViewById(R.id.liveIndicator)
            cameraOffPlaceholder = findViewById(R.id.cameraOffPlaceholder)

            cameraExecutor = Executors.newSingleThreadExecutor()

            // Item 8 — match History density: scale scanner rows with screen size
            applyDensity()

            // v5: camera does NOT auto-start — user taps Start Scanning
            showCameraOffState()

            // Mode switch: seamless — processImage reads `mode` per frame,
            // so switching mid-scan auto-stops one analyzer and starts the other.
            // No re-press of Start Scanning needed.
            modeBtn.setOnClickListener {
                if (mode == "ocr") return@setOnClickListener
                mode = "ocr"
                updateTabUI()
                Toast.makeText(this, "Mode: OCR", Toast.LENGTH_SHORT).show()
            }

            barcodeBtn.setOnClickListener {
                if (mode == "barcode") return@setOnClickListener
                mode = "barcode"
                updateTabUI()
                Toast.makeText(this, "Mode: BARCODE", Toast.LENGTH_SHORT).show()
            }

            // Start / Stop / Scan Again toggle
            startBtn.setOnClickListener {
                when (scanState) {
                    "idle" -> {
                        if (cameraStarted) return@setOnClickListener
                        if (allPermissionsGranted()) {
                            startCamera()
                        } else {
                            ActivityCompat.requestPermissions(
                                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
                            )
                        }
                    }
                    "scanning" -> stopScan()
                    "captured" -> {
                        // Scan Again — camera still bound, just re-arm analysis
                        resetDetected()
                        startScanning()
                    }
                }
            }

            historyBtn.setOnClickListener {
                startActivity(Intent(this, HistoryActivity::class.java))
            }

            resetBtn.setOnClickListener { resetAll() }

            // Copy both — Item 10: MUST follow slot order (assignSlots), never
            // detection order. IMEI 2 detected first is still copied as IMEI 2.
            copyBothBtn.setOnClickListener {
                val (s1, s2) = assignSlots(detectedImeis)
                val both = listOfNotNull(s1?.value, s2?.value).joinToString("\n")
                if (both.isNotEmpty()) copyWithFeedback(both, copyBothBtn, "Copy both")
            }

            copyImei1Btn.setOnClickListener {
                val (s1, _) = assignSlots(detectedImeis)
                if (s1 != null) copyWithFeedback(s1.value, copyImei1Btn, "📋 Copy")
            }

            copyImei2Btn.setOnClickListener {
                val (_, s2) = assignSlots(detectedImeis)
                if (s2 != null) copyWithFeedback(s2.value, copyImei2Btn, "📋 Copy")
            }

            checkCeirBtn.setOnClickListener {
                try {
                    if (detectedImeis.isNotEmpty()) {
                        // Fresh check session must not inherit a stale reset signal
                        // (otherwise the new MainActivity would finish itself on resume).
                        MainActivity.resetSignal = false
                        // Slot-assigned order: index 0 is always the true IMEI 1
                        val (slot1, slot2) = assignSlots(detectedImeis)
                        val imei1 = slot1?.value ?: ""
                        val imei2 = slot2?.value ?: ""
                        val intent = Intent(this, MainActivity::class.java)
                        intent.putExtra("imei1", imei1)
                        intent.putExtra("imei2", imei2)
                        startActivity(intent)
                        // NOTE: no finish() — Back from result returns here with IMEIs preserved
                    } else {
                        Toast.makeText(this, "IMEI မရှိသေးပါ", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "checkCeirBtn error", e)
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Item 8 — Scanner matches History density: scale IMEI rows, buttons and
     * status text with the same screen-size factor.
     */
    private fun applyDensity() {
        try {
            val f = DensityUtils.screenFactor(this)
            if (f < 0.99f || f > 1.01f) {
                val auto = android.util.TypedValue.COMPLEX_UNIT_SP
                imei1Text.setAutoSizeTextTypeUniformWithConfiguration(
                    (10 * f).toInt(), (14 * f).toInt(), 1, auto)
                imei2Text.setAutoSizeTextTypeUniformWithConfiguration(
                    (10 * f).toInt(), (14 * f).toInt(), 1, auto)
                DensityUtils.scaledSp(copyBothBtn, 12f, f)
                DensityUtils.scaledSp(copyImei1Btn, 12f, f)
                DensityUtils.scaledSp(copyImei2Btn, 12f, f)
                DensityUtils.scaledSp(statusText, 13f, f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyDensity error", e)
        }
    }

    private fun copyWithFeedback(text: String, btn: Button, original: String) {        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("IMEI", text))
            btn.text = "✓ Copied"
            btn.postDelayed({ if (scanState == "captured") btn.text = original }, 1500)
            Toast.makeText(this, "Copy ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "copyWithFeedback error", e)
        }
    }

    private fun showCameraOffState() {
        scanState = "idle"
        setCameraSize(true)
        cameraFrame.visibility = View.VISIBLE
        imeiResultArea.visibility = View.GONE
        // Hide the preview surface: after unbindAll() PreviewView keeps its last
        // frame frozen on screen — GONE guarantees plain black, no stale image.
        previewView.visibility = View.GONE
        cameraOffPlaceholder.visibility = View.VISIBLE
        liveIndicator.visibility = View.GONE
        checkCeirBtn.visibility = View.GONE
        scanBand.scanning = false
        scanBand.dimmed = false
        startBtn.visibility = View.VISIBLE
        startBtn.text = "▶ Start Scanning"
        startBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_accent)
        startBtn.setTextColor(0xFF0B0F19.toInt())
    }

    private fun showScanningState() {
        scanState = "scanning"
        setCameraSize(true)
        cameraFrame.visibility = View.VISIBLE
        imeiResultArea.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        cameraOffPlaceholder.visibility = View.GONE
        liveIndicator.visibility = View.VISIBLE
        checkCeirBtn.visibility = View.GONE
        scanBand.scanning = true
        scanBand.dimmed = true
        startBtn.visibility = View.VISIBLE
        startBtn.text = "⏹ Stop Scanning"
        startBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_danger_ghost)
        startBtn.setTextColor(0xFFFFC400.toInt())
    }

    private fun showCapturedState() {
        scanState = "captured"
        // Scan-save (2026-11-21): scan alone saves to History now.
        saveScannedToHistory()
        // camera preview ဆက်ပြ (LIVE badge ပါ), detected card အောက်မှာပြ
        setCameraSize(false)
        cameraFrame.visibility = View.VISIBLE
        imeiResultArea.visibility = View.VISIBLE
        previewView.visibility = View.VISIBLE
        cameraOffPlaceholder.visibility = View.GONE
        liveIndicator.visibility = View.VISIBLE
        checkCeirBtn.visibility = View.VISIBLE
        scanBand.scanning = false
        scanBand.dimmed = true
        startBtn.visibility = View.VISIBLE
        startBtn.text = "↻ Scan Again"
        startBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_btn_ghost)
        startBtn.setTextColor(0xFFFFC400.toInt())
    }

    /**
     * No-scroll layout: the camera card is a fixed 300dp when idle/scanning,
     * and compacts to 150dp once results are shown so everything (IMEI rows,
     * Check CEIR, bottom buttons) stays visible without scrolling.
     * The ScanBandView overlay keeps its fractional band, so the ML Kit crop
     * rect stays aligned at any preview size.
     */
    private fun setCameraSize(expanded: Boolean) {
        val lp = cameraFrame.layoutParams as LinearLayout.LayoutParams
        val dp = resources.displayMetrics.density
        lp.height = ((if (expanded) 300 else 150) * dp).toInt()
        lp.weight = 0f
        cameraFrame.layoutParams = lp
    }

    private fun updateTabUI() {
        if (mode == "ocr") {
            modeBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_toggle_selected)
            modeBtn.setTextColor(0xFFFFC400.toInt())
            barcodeBtn.background = ContextCompat.getDrawable(this, android.R.color.transparent)
            barcodeBtn.setTextColor(0xFF64748B.toInt())
        } else {
            barcodeBtn.background = ContextCompat.getDrawable(this, R.drawable.bg_toggle_selected)
            barcodeBtn.setTextColor(0xFFFFC400.toInt())
            modeBtn.background = ContextCompat.getDrawable(this, android.R.color.transparent)
            modeBtn.setTextColor(0xFF64748B.toInt())
        }
    }

    private fun startScanning() {
        running = true
        showScanningState()
    }

    private fun stopScan() {
        running = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "stopScan unbind error", e)
        }
        cameraStarted = false
        showCameraOffState()
    }

    /**
     * Scan-save (2026-11-21): a completed scan saves to History immediately,
     * with status/brand/model blank (unchecked row — History shows the
     * "မစစ်ရသေးဘူး" badge). No duplicate rows, ever:
     * - pair already in History → only scannedAt is refreshed; a checked
     *   row's results are NEVER wiped by a re-scan.
     * - new pair → one new unchecked row (syncStatus=0, syncs immediately
     *   via SyncWorker.scheduleNow — auto-sync needs no unlock, no check).
     * The later CEIR check updates this same row in place via
     * MainActivity.saveToDatabase (findByImeiPair), which fills the statuses
     * and clears the badge.
     */
    private fun saveScannedToHistory() {
        val (slot1, slot2) = assignSlots(detectedImeis)
        val imei1 = slot1?.value ?: return
        val imei2 = slot2?.value
        lifecycleScope.launch {
            try {
                val dao = AppDatabase.get(applicationContext).scanDao()
                val existing = dao.findByImeiPair(imei1, imei2)
                if (existing != null) {
                    dao.insert(existing.copy(scannedAt = System.currentTimeMillis()))
                } else {
                    val deviceId = android.provider.Settings.Secure.getString(
                        contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""
                    dao.insert(ScanEntity(deviceId = deviceId, imei1 = imei1, imei2 = imei2))
                }
                Log.d(TAG, "scan-save: $imei1 / ${imei2 ?: "—"}")
                // Auto-sync: a scan alone must upload right away, not wait
                // for the next 15-min run or a CEIR check.
                SyncWorker.scheduleNow(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "scan-save error", e)
            }
        }
    }

    private fun resetDetected() {
        detectedImeis.clear()
        stabilityMap.clear()
        imei1Text.text = "—"
        imei2Text.text = "—"
        copyBothBtn.text = "Copy both"
        copyImei1Btn.text = "📋 Copy"
        copyImei2Btn.text = "📋 Copy"
        statusText.text = "0/2 ✓"
    }

    private fun resetAll() {
        running = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "resetAll unbind error", e)
        }
        cameraStarted = false
        resetDetected()
        showCameraOffState()
        updateTabUI()
        // Fresh session: destroy any in-progress CEIR WebView page.
        // (Consumed by MainActivity.onResume; cleared again on next Check CEIR.)
        MainActivity.resetSignal = true
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            if (allPermissionsGranted()) startCamera()
            else Toast.makeText(this, "Camera permission လိုတယ်", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    if (scanState != "idle") return@addListener
                    cameraProvider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // Item 11: lower analysis resolution first — cuts per-frame
                        // YUV→Bitmap/ML Kit cost; adaptive upscale covers small text.
                        .setTargetResolution(android.util.Size(1280, 720))
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { processImage(it) }

                    cameraProvider?.unbindAll()
                    camera = cameraProvider?.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )

                    try {
                        val focusAction = FocusMeteringAction.Builder(
                            previewView.meteringPointFactory.createPoint(
                                previewView.width / 2f,
                                previewView.height / 2f
                            ),
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        camera?.cameraControl?.startFocusAndMetering(focusAction)
                    } catch (e: Exception) {
                        Log.e(TAG, "Autofocus error", e)
                    }

                    cameraStarted = true
                    resetDetected()
                    startScanning()

                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed", e)
                    Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "startCamera error", e)
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (!running) { imageProxy.close(); return }

        var fullBitmap: android.graphics.Bitmap? = null
        try {
            fullBitmap = YuvUtils.imageProxyToBitmap(imageProxy)
            if (fullBitmap == null) return
            val cropped = CropUtils.cropBitmap(fullBitmap) ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees
            // ML Kit callbacks are async; recycle the crop when fully done.
            val onDone = {
                try { cropped.recycle() } catch (_: Exception) {}
                imageProxy.close()
            }
            if (mode == "barcode") processBarcodeFrame(cropped, rotation, onDone)
            else processOcrFrame(cropped, rotation, onDone)
        } catch (e: Exception) {
            Log.e(TAG, "processImage error", e)
            imageProxy.close()
        } finally {
            try { fullBitmap?.recycle() } catch (_: Exception) {}
        }
    }

    // ---------------- OCR (Item 11: scale + focus auto-detect) ----------------

    private fun processOcrFrame(
        cropped: android.graphics.Bitmap,
        rotation: Int,
        onDone: () -> Unit
    ) {
        val image = InputImage.fromBitmap(cropped, rotation)
        textRecognizer.process(image)
            .addOnSuccessListener { text ->
                // Line text + line top → "no labels ⇒ topmost is IMEI 1"
                val lines = text.textBlocks
                    .flatMap { block -> block.lines }
                    .map { line -> line.text to line.boundingBox?.top }
                val found = ImeiUtils.extractImeis(lines)
                if (found.isNotEmpty()) {
                    handleOcrFound(found)
                } else {
                    // Scale auto-detect: same frame, one retry at a second scale
                    // derived from detected line heights.
                    val retry = retryBitmapForScale(cropped, text)
                    if (retry != null) {
                        val yScale = cropped.height.toFloat() / retry.height
                        val rImage = InputImage.fromBitmap(retry, rotation)
                        textRecognizer.process(rImage)
                            .addOnSuccessListener { text2 ->
                                val lines2 = text2.textBlocks
                                    .flatMap { block -> block.lines }
                                    .map { line ->
                                        line.text to line.boundingBox?.top?.let {
                                            (it * yScale).toInt()
                                        }
                                    }
                                handleOcrFound(ImeiUtils.extractImeis(lines2))
                            }
                            .addOnCompleteListener {
                                try { retry.recycle() } catch (_: Exception) {}
                            }
                    } else {
                        // Focus auto-detect: blurry frame → re-trigger AF (throttled)
                        maybeRefocus(cropped)
                    }
                }
            }
            .addOnCompleteListener { onDone() }
    }

    private fun handleOcrFound(found: List<DetectedImei>) {
        for (imei in found) {
            val count = (stabilityMap[imei.value] ?: 0) + 1
            stabilityMap[imei.value] = count
            if (count >= STABILITY_THRESHOLD) {
                handleDetected(imei)
                stabilityMap[imei.value] = 0
            }
        }
    }

    /**
     * Item 11 — scale auto-detect: if text exists but no IMEI parsed, the
     * scale is likely wrong. Too-small lines → upscale; huge lines → downscale.
     * Returns a retry bitmap, or null when scale looks fine (→ try refocus).
     */
    private fun retryBitmapForScale(
        cropped: android.graphics.Bitmap,
        text: com.google.mlkit.vision.text.Text
    ): android.graphics.Bitmap? {
        return try {
            val heights = text.textBlocks
                .flatMap { it.lines }
                .mapNotNull { it.boundingBox?.height() }
                .filter { it > 0 }
            if (heights.isEmpty()) return null
            val avgH = heights.average()
            val w = cropped.width
            val h = cropped.height
            when {
                avgH < 22 -> {
                    // Text too small for ML Kit → upscale (cap 1600px long edge)
                    val s = minOf(2.2f, 1600f / maxOf(w, h))
                    if (s < 1.15f) return null
                    android.graphics.Bitmap.createScaledBitmap(
                        cropped, (w * s).toInt(), (h * s).toInt(), true
                    )
                }
                avgH > h / 3.5 -> {
                    // Text oversized (too close) → downscale
                    val s = 0.55f
                    android.graphics.Bitmap.createScaledBitmap(
                        cropped, (w * s).toInt(), (h * s).toInt(), true
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "retryBitmapForScale error", e)
            null
        }
    }

    private var lastRefocusAt = 0L

    /** Item 11 — focus auto-detect: blurry frame → re-trigger AF, max once per 3s. */
    private fun maybeRefocus(cropped: android.graphics.Bitmap) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRefocusAt < 3000) return
        try {
            if (!isBlurry(cropped)) return
        } catch (e: Exception) {
            return
        }
        lastRefocusAt = now
        try {
            val cam = camera ?: return
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f, previewView.height / 2f
            )
            val action = FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.e(TAG, "refocus error", e)
        }
    }

    /** Laplacian variance on a 64px-wide thumb; low variance = blurry. */
    private fun isBlurry(bmp: android.graphics.Bitmap): Boolean {
        val w = 64
        val h = (64f * bmp.height / bmp.width).toInt().coerceAtLeast(16)
        val thumb = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        try {
            val px = IntArray(w * h)
            thumb.getPixels(px, 0, w, 0, 0, w, h)
            val gray = IntArray(w * h) { i ->
                val p = px[i]
                (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 +
                        (p and 0xFF) * 114) / 1000
            }
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val c = gray[y * w + x]
                    val l = gray[y * w + x - 1] + gray[y * w + x + 1] +
                            gray[(y - 1) * w + x] + gray[(y + 1) * w + x] - 4 * c
                    sum += l; sumSq += l * l; n++
                }
            }
            if (n == 0) return false
            val mean = sum / n
            return (sumSq / n - mean * mean) < 90
        } finally {
            try { thumb.recycle() } catch (_: Exception) {}
        }
    }

    // ---------------- Barcode (Item 10: hybrid label matching) ----------------

    private fun processBarcodeFrame(
        cropped: android.graphics.Bitmap,
        rotation: Int,
        onDone: () -> Unit
    ) {
        val image = InputImage.fromBitmap(cropped, rotation)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                // 15-digit + Luhn filters out the SN barcode automatically
                val imeiBoxes = barcodes.mapNotNull { b ->
                    val raw = b.rawValue ?: return@mapNotNull null
                    val digits = raw.filter { it.isDigit() }
                    if (digits.length == 15 && ImeiUtils.isValidImei(digits))
                        digits to b.boundingBox
                    else null
                }
                if (imeiBoxes.isEmpty()) return@addOnSuccessListener
                // Item 10 — hybrid: OCR the SAME frame for printed IMEI1/IMEI2
                // labels; match each barcode to its nearest label ABOVE it.
                textRecognizer.process(image)
                    .addOnSuccessListener { text ->
                        val labels = text.textBlocks
                            .flatMap { it.lines }
                            .mapNotNull { line ->
                                val slot = ImeiUtils.findSlotLabel(line.text)
                                    ?: return@mapNotNull null
                                slot to line.boundingBox
                            }
                        for ((digits, box) in imeiBoxes) {
                            handleDetected(
                                DetectedImei(digits, nearestLabelAbove(box, labels), box?.top)
                            )
                        }
                    }
                    .addOnFailureListener {
                        for ((digits, box) in imeiBoxes) {
                            handleDetected(DetectedImei(digits, null, box?.top))
                        }
                    }
            }
            .addOnCompleteListener { onDone() }
    }

    /**
     * Item 10 — printed "IMEI1:"/"IMEI2:" labels sit ABOVE their barcodes.
     * Nearest label above (vertical gap, horizontal overlap) wins.
     */
    private fun nearestLabelAbove(
        box: android.graphics.Rect?,
        labels: List<Pair<Int, android.graphics.Rect?>>
    ): Int? {
        if (box == null) return null
        var bestSlot: Int? = null
        var bestDy = Int.MAX_VALUE
        for ((slot, lbox) in labels) {
            if (lbox == null) continue
            if (lbox.bottom > box.top) continue
            val overlap = minOf(lbox.right, box.right) - maxOf(lbox.left, box.left)
            if (overlap <= 0) continue
            val dy = box.top - lbox.bottom
            if (dy < bestDy) {
                bestDy = dy
                bestSlot = slot
            }
        }
        return bestSlot
    }

    private fun handleDetected(imei: DetectedImei) {
        // ✅ IMEI 15 လုံး ပြည့်မပြည့် စစ်
        if (imei.value.length != 15) return
        if (!ImeiUtils.isValidImei(imei.value)) return

        runOnUiThread {
            try {
                val existing = detectedImeis.find { it.value == imei.value }
                if (existing != null) {
                    // Re-detected with a better hint/top → upgrade the stored one
                    if (existing.slotHint == null && imei.slotHint != null) {
                        detectedImeis[detectedImeis.indexOf(existing)] =
                            existing.copy(
                                slotHint = imei.slotHint,
                                top = imei.top ?: existing.top
                            )
                    }
                } else {
                    detectedImeis.add(imei)
                }

                refreshSlotDisplay()

                if (detectedImeis.size >= 2) {
                    running = false
                    showCapturedState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleDetected error", e)
            }
        }
    }

    /**
     * Slot assignment (absolute, not detection order):
     * 1. "IMEI 1" label → IMEI 1 အသေ, "IMEI 2" label → IMEI 2 အသေ
     * 2. hint တစ်ခုပဲ ပါရင် ကျန်တဲ့ကောင်က အားနေတဲ့ slot ယူ
     * 3. hint မပါရင် အပေါ်ဆုံး (top အငယ်ဆုံး) က IMEI 1
     */
    private fun assignSlots(list: List<DetectedImei>): Pair<DetectedImei?, DetectedImei?> {
        val slot1 = list.find { it.slotHint == 1 }
        val slot2 = list.find { it.slotHint == 2 && it != slot1 }
        val remaining = list.filter { it != slot1 && it != slot2 }.toMutableList()
        fun pickTopmost(): DetectedImei? {
            if (remaining.isEmpty()) return null
            val withTop = remaining.filter { it.top != null }
            // top အငယ်ဆုံး = အပေါ်ဆုံး; top မသိရင် တွေ့တဲ့အစဉ်
            val pick = if (withTop.isNotEmpty()) withTop.minByOrNull { it.top!! }
                       else remaining.first()
            remaining.remove(pick)
            return pick
        }
        return (slot1 ?: pickTopmost()) to (slot2 ?: pickTopmost())
    }

    private fun refreshSlotDisplay() {
        val (slot1, slot2) = assignSlots(detectedImeis)
        imei1Text.text = slot1?.value ?: "—"
        imei2Text.text = slot2?.value ?: "—"
        statusText.text = "${detectedImeis.size}/2 ✓"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            barcodeScanner.close()
            textRecognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
    }
}
