package com.example.bantaybeshie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.bantaybeshie.camera.YuvToRgbConverter
import com.example.bantaybeshie.data.ActivityLogDatabase
import com.example.bantaybeshie.data.ContactsDatabase
import com.example.bantaybeshie.model.ActivityLogEntry
import com.example.bantaybeshie.model.LogType
import com.example.bantaybeshie.ui.OverlayView
import com.example.bantaybeshie.utils.NavBarHelper
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val MODEL_SIZE = 640
        private const val NUM_CHANNELS = 9
        private const val NUM_ANCHORS = 8400

        private const val SMS_COOLDOWN = 30_000L
        private const val EMAIL_COOLDOWN = 8_000L

        private var lastSmsAt = 0L
        private var lastEmailAt = 0L

        private var detectionEnabled = true

        // ===== Scenario thresholds (from your matrix) =====
        private const val POPUP_COOLDOWN_MS = 10_000L   // don't show popup more often than this
        private var lastPopupAt = 0L
        private var popupActive = false

        // Per-scenario anomaly threshold & follower persistence
        data class ScenarioConfig(
            val anomalyThreshold: Float,
            val followerPersistence: Int,
            val name: String
        )

        private val SCENARIO_MAP = mapOf(
            "NORMAL" to ScenarioConfig(anomalyThreshold = 0.65f, followerPersistence = 2, name = "Normal"),
            "CROWDED" to ScenarioConfig(anomalyThreshold = 0.80f, followerPersistence = 4, name = "Crowded"),
            "NIGHT" to ScenarioConfig(anomalyThreshold = 0.60f, followerPersistence = 2, name = "Nighttime"),
            "HIGHRISK" to ScenarioConfig(anomalyThreshold = 0.55f, followerPersistence = 1, name = "High-Risk")
        )

        // ===== Balanced Mode B thresholds (applied) =====
        // These are tuned to reduce false positives while keeping weapon detection responsive.
        private val LABEL_MIN_CONF = mapOf(
            "pistol" to 0.27f,
            "person" to 0.37f,
            "knife" to 0.35f
        )

        private val LABELS = listOf("knife", "person", "person-like", "pistol", "undefined")
    }

    // UI
    private var homePreview: PreviewView? = null
    private var homeOverlay: OverlayView? = null
    private var homeResult: TextView? = null

    private lateinit var nav: NavController
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var yuv: YuvToRgbConverter

    // Model
    private var tflite: Interpreter? = null
    private var sensitivity = 0.25f

    // Location + logging
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Tracking
    private var trackIdCounter = 0
    private val tracks = mutableListOf<Track>()

    private data class Track(
        val id: Int,
        var box: RectF,
        var label: String,
        var confidence: Float,
        var consecutive: Int,
        var lastSeenMs: Long,
        var alerted: Boolean = false
    )

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (allPermissionsGranted()) startCamera()
        }

    // -------------------------
    // Scenario state (set these from your UI buttons)
    // -------------------------
    private var currentScenarioKey: String = "NORMAL" // NORMAL / CROWDED / NIGHT / HIGHRISK

    fun setScenarioNormal() { currentScenarioKey = "NORMAL" }
    fun setScenarioCrowded() { currentScenarioKey = "CROWDED" }
    fun setScenarioNight() { currentScenarioKey = "NIGHT" }
    fun setScenarioHighRisk() { currentScenarioKey = "HIGHRISK" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Navigation
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        nav = navHost.navController
        NavBarHelper.setup(findViewById(R.id.bottomNavBar), nav, "home")

        // Utils
        yuv = YuvToRgbConverter(this)
        fused = LocationServices.getFusedLocationProviderClient(this)

        locReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500)
            .setMinUpdateIntervalMillis(700)
            .build()

        // Load TFLite model
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val buf = FileUtil.loadMappedFile(this@MainActivity, "bantaybeshie_model.tflite")
                tflite = Interpreter(buf)
                Log.d(TAG, "Model loaded successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        tflite?.close()
        cameraExecutor.shutdown()
    }

    // ---------------------------------------------------------------------------------------------
    // HomeFragment Attach/Detach
    // ---------------------------------------------------------------------------------------------
    fun attachHomeViews(preview: PreviewView, overlay: OverlayView, resultText: TextView) {
        homePreview = preview
        homeOverlay = overlay
        homeResult = resultText

        if (allPermissionsGranted()) startCamera()
        else permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS
            )
        )
    }

    fun detachHomeViews() {
        homePreview = null
        homeOverlay = null
        homeResult = null
        stopCamera()
    }

    // ---------------------------------------------------------------------------------------------
    // Camera
    // ---------------------------------------------------------------------------------------------
    private fun startCamera() {
        val previewView = homePreview ?: return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            cameraProvider?.unbindAll()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(MODEL_SIZE, MODEL_SIZE))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { img ->
                val bmp = yuv.toBitmap(img)
                runDetection(bmp)
                img.close()
            }

            cameraProvider!!.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        tracks.clear()
    }

    fun enableDetection() {
        detectionEnabled = true
        Toast.makeText(this, "Detection ON", Toast.LENGTH_SHORT).show()
    }

    fun disableDetection() {
        detectionEnabled = false
        Toast.makeText(this, "Detection OFF", Toast.LENGTH_SHORT).show()
    }


    fun setSensitivity(value: Float) {
        sensitivity = value.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------------------------------------
    // Detection pipeline (no NMS — per your preference). We'll apply label-min thresholds and tracking.
    // ---------------------------------------------------------------------------------------------
    private fun runDetection(bitmap: Bitmap) {
        val interpreter = tflite ?: return
        if (!detectionEnabled) return
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, MODEL_SIZE, MODEL_SIZE, true)

            val inputBuf = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 3 * 4)
            inputBuf.order(ByteOrder.nativeOrder())

            for (y in 0 until MODEL_SIZE) {
                for (x in 0 until MODEL_SIZE) {
                    val px = resized.getPixel(x, y)
                    inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)
                    inputBuf.putFloat(((px shr 8) and 0xFF) / 255f)
                    inputBuf.putFloat((px and 0xFF) / 255f)
                }
            }
            inputBuf.rewind()

            val output = Array(1) { Array(NUM_CHANNELS) { FloatArray(NUM_ANCHORS) } }
            interpreter.run(inputBuf, output)

            val preds = output[0]
            val rawDets = mutableListOf<SimpleDet>()

            fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))

            val pw = homePreview?.width?.toFloat() ?: MODEL_SIZE.toFloat()
            val ph = homePreview?.height?.toFloat() ?: MODEL_SIZE.toFloat()

            val numClasses = (NUM_CHANNELS - 5)

            for (i in 0 until NUM_ANCHORS) {
                val rx = preds[0][i]
                val ry = preds[1][i]
                val rw = preds[2][i]
                val rh = preds[3][i]

                val obj = sigmoid(preds[4][i])
                if (!obj.isFinite()) continue

                val classScores = FloatArray(numClasses) { c -> sigmoid(preds[5 + c][i]) }
                val bestIdx = classScores.indices.maxByOrNull { classScores[it] } ?: continue
                val clsConf = classScores[bestIdx]

                var finalConf = obj * clsConf
                if (!finalConf.isFinite()) continue
                if (finalConf < sensitivity) continue


                val left = (rx - rw / 2f) * pw
                val top = (ry - rh / 2f) * ph
                val right = (rx + rw / 2f) * pw
                val bottom = (ry + rh / 2f) * ph

                val mLeft = pw - right
                val mRight = pw - left

                val rect = RectF(mLeft, top, mRight, bottom)

                // Map 'person-like' -> 'person' to reduce label-flapping & false positives
                val rawLabel = LABELS.getOrNull(bestIdx) ?: "Unknown"
                val finalLabel = if (rawLabel == "person-like") "person" else rawLabel

                // small heuristic: ignore tiny boxes (likely noise)
                val boxW = rect.width()
                val boxH = rect.height()
                val minBoxSizePx = 3f
                if (boxW < minBoxSizePx || boxH < minBoxSizePx) continue

                // label-specific confidence threshold
                val labelMin = LABEL_MIN_CONF[finalLabel] ?: 0.25f
                if (finalConf < labelMin) continue

                // YOLO DEBUG LOG — uses finalLabel to avoid conflicts
                Log.d(
                    "YOLO_DEBUG",
                    "i=$i | label=$finalLabel | conf=${"%.3f".format(finalConf)} | obj=${"%.3f".format(obj)} | cls=${"%.3f".format(clsConf)}"
                )

                // Add detection using normalized label
                rawDets.add(SimpleDet(rect, finalLabel, finalConf))

            }

            // update tracking with detections
            val scenario = SCENARIO_MAP[currentScenarioKey] ?: SCENARIO_MAP["NORMAL"]!!
            updateTracksWithDetections(rawDets, scenario)

            // Prepare overlay results (show raw detections for now)
            val visible = rawDets.map { d ->
                OverlayView.DetectionResult(d.rect, d.label, d.conf, computeSeverity(d.conf), estimateDistance(d.rect))
            }

            runOnUiThread {
                homeOverlay?.setResults(visible)
                homeResult?.text = "Detected: ${visible.size}"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
        }
    }

    private data class SimpleDet(val rect: RectF, val label: String, val conf: Float)

    // -------------------------
    // Tracking & alert logic (IoU matching)
    // -------------------------
    private fun updateTracksWithDetections(dets: List<SimpleDet>, scenario: ScenarioConfig) {
        val now = System.currentTimeMillis()
        val matched = BooleanArray(dets.size)

        // match existing tracks
        for (track in tracks) {
            var bestIdx = -1
            var bestIoU = 0f
            for ((i, d) in dets.withIndex()) {
                if (matched[i]) continue
                val iou = computeIou(track.box, d.rect)
                if (iou > bestIoU) {
                    bestIoU = iou
                    bestIdx = i
                }
            }

            if (bestIdx >= 0 && bestIoU > 0.25f) { // loose IoU to accommodate model jitter
                val d = dets[bestIdx]
                track.box = d.rect
                track.confidence = d.conf
                track.label = d.label
                track.consecutive += 1
                track.lastSeenMs = now
                matched[bestIdx] = true

                processTrackForAlert(track, scenario)
            } else {
                // not matched -> if older than small gap, decay consecutive
                if (now - track.lastSeenMs > 1000L) {
                    track.consecutive = max(0, track.consecutive - 1)
                }
            }
        }

        // add unmatched as new tracks
        for ((i, d) in dets.withIndex()) {
            if (matched[i]) continue
            val id = ++trackIdCounter
            val tr = Track(id = id, box = d.rect, label = d.label, confidence = d.conf, consecutive = 1, lastSeenMs = now)
            tracks.add(tr)
            processTrackForAlert(tr, scenario) // immediate check for weapons
        }

        // cleanup stale tracks older than 3s
        val cutoff = System.currentTimeMillis() - 3000L
        tracks.removeAll { it.lastSeenMs < cutoff }
    }

    private fun processTrackForAlert(track: Track, scenario: ScenarioConfig) {
        // weapons -> immediate candidates (raised threshold per Option B)
        if (track.label == "knife" || track.label == "pistol") {
            val required = LABEL_MIN_CONF[track.label] ?: 0.60f
            if (track.confidence >= required && !track.alerted) {
                track.alerted = true
                showConfirmationForTrack(track, scenario)
            }
            return
        }

        // people-like/person -> require persistence + scenario threshold + label min conf
        if (track.label == "person") {
            val minConf = LABEL_MIN_CONF["person"] ?: 0.50f
            if (track.consecutive >= scenario.followerPersistence &&
                track.confidence >= max(minConf, scenario.anomalyThreshold) &&
                !track.alerted
            ) {
                track.alerted = true
                showConfirmationForTrack(track, scenario)
            }
        }
    }

    private fun showConfirmationForTrack(track: Track, scenario: ScenarioConfig) {
        val now = System.currentTimeMillis()
        if (popupActive || (now - lastPopupAt) < POPUP_COOLDOWN_MS) {
            Log.d(TAG, "Popup suppressed (active=$popupActive, since=${now - lastPopupAt}ms)")
            return
        }
        popupActive = true
        lastPopupAt = now

        // acquire location, then show popup with location included
        requestLocation { loc ->
            val summary = "${track.label} detected (${(track.confidence * 100).toInt()}%)"
            askUserConfirmation(summary, loc) {
                // on confirm
                lifecycleScope.launch(Dispatchers.IO) {
                    attemptSendAlert(track.label, track.confidence, computeSeverity(track.confidence), estimateDistance(track.box), loc)
                    attemptSendEmail(track.label, track.confidence, computeSeverity(track.confidence), estimateDistance(track.box), loc)
                }
                // small UI reset to prevent immediate successive popups
                runOnUiThread {
                    popupActive = false
                }
            }

            // if user cancels/dismisses, askUserConfirmation will reset popupActive
        }
    }

    private fun computeIou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = max(0f, (a.right - a.left)) * max(0f, (a.bottom - a.top))
        val areaB = max(0f, (b.right - b.left)) * max(0f, (b.bottom - b.top))
        return if (areaA + areaB - interArea <= 0f) 0f else interArea / (areaA + areaB - interArea)
    }

    // Popup that includes label + location; calls onConfirm() on positive
    private fun askUserConfirmation(summary: String, loc: Location?, onConfirm: () -> Unit) {
        runOnUiThread {
            val msg = StringBuilder()
                .append(summary)
                .append("\n\nSend alert to your contacts?")
                .append("\n\nLocation: ")
                .append(if (loc != null) "${loc.latitude}, ${loc.longitude}" else "Unknown")
                .toString()

            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("BantayBeshie Alert")
                .setMessage(msg)
                .setPositiveButton("Yes") { _, _ ->
                    onConfirm()
                    popupActive = false
                }
                .setNegativeButton("No") { _, _ ->
                    popupActive = false
                }
                .setOnDismissListener {
                    popupActive = false
                }

            builder.show()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SOS + Logging + SMS + EMAIL
    // ---------------------------------------------------------------------------------------------
    fun triggerManualSOS() {
        logEvent("Manual SOS", "Triggered by user", LogType.SOS)
        // request location and then send alert & compose email
        requestLocation { loc ->
            lifecycleScope.launch(Dispatchers.IO) {
                attemptSendAlert("Manual SOS", 1f, "HIGH", 0f, loc)
            }
            lifecycleScope.launch(Dispatchers.Main) {
                attemptSendEmail("Manual SOS", 1f, "HIGH", 0f, loc)
            }
        }
    }

    fun logEvent(title: String, message: String, type: LogType) {
        val entry = ActivityLogEntry(
            timestamp = sdf.format(Date()),
            title = title,
            message = message,
            type = type
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ActivityLogDatabase.getDb(this@MainActivity).logDao().insert(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save event log: ${e.message}")
            }
        }
    }

    private fun attemptSendAlert(label: String, conf: Float, sev: String, dist: Float, loc: Location?) {
        val now = System.currentTimeMillis()
        if (now - lastSmsAt < SMS_COOLDOWN) {
            Log.d("SMS", "Cooldown")
            return
        }
        lastSmsAt = now

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contacts = ContactsDatabase.getDatabase(this@MainActivity)
                    .contactsDao().getAllContactsList()
                    .mapNotNull { it.number }
                    .filter { it.isNotBlank() }

                if (contacts.isEmpty()) {
                    Log.w("SMS", "No contacts found")
                    return@launch
                }

                val message = buildMessage(label, conf, sev, dist, loc)
                val sms = SmsManager.getDefault()
                for (n in contacts) {
                    try {
                        sms.sendTextMessage(n, null, message, null, null)
                    } catch (e: Exception) {
                        Log.e("SMS", "Failed to send to $n: ${e.message}")
                    }
                }
                Log.d("SMS", "Sent to ${contacts.size} contacts")
            } catch (e: Exception) {
                Log.e("SMS", "Failed: ${e.message}")
            }
        }
    }

    private fun attemptSendEmail(label: String, conf: Float, sev: String, dist: Float, loc: Location?) {
        val now = System.currentTimeMillis()
        if (now - lastEmailAt < EMAIL_COOLDOWN) {
            Log.d("EMAIL", "Cooldown")
            return
        }
        lastEmailAt = now

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contacts = ContactsDatabase.getDatabase(this@MainActivity)
                    .contactsDao().getAllContactsList()
                    .mapNotNull { it.email }
                    .filter { it.isNotBlank() }

                if (contacts.isEmpty()) {
                    Log.w("EMAIL", "No email contacts")
                    return@launch
                }

                val subject = "BantayBeshie Alert: $label"
                val body = buildMessage(label, conf, sev, dist, loc)
                // switch to main to start email intent
                launch(Dispatchers.Main) {
                    sendEmailViaGmail(contacts, subject, body)
                }

            } catch (e: Exception) {
                Log.e("EMAIL", "Failed: ${e.message}")
            }
        }
    }

    private fun sendEmailViaGmail(recipients: List<String>, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            setPackage("com.google.android.gm")
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Gmail app not found", Toast.LENGTH_LONG).show()
        }
    }

    // Request a fresh location (first try lastLocation, then fallback to getCurrentLocation)
    private fun requestLocation(onLocation: (Location?) -> Unit) {
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    onLocation(loc)
                } else {
                    fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { fallback ->
                            onLocation(fallback)
                        }
                        .addOnFailureListener {
                            onLocation(null)
                        }
                }
            }.addOnFailureListener {
                onLocation(null)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
            onLocation(null)
        } catch (e: Exception) {
            Log.e(TAG, "Location error: ${e.message}")
            onLocation(null)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Build message
    // ---------------------------------------------------------------------------------------------
    private fun buildMessage(
        label: String,
        conf: Float,
        sev: String,
        dist: Float,
        loc: Location?
    ): String {

        val lat = loc?.latitude
        val lon = loc?.longitude

        return buildString {
            append("🚨 BantayBeshie Alert 🚨\n\n")
            append("A potential threat has been detected.\n\n")
            append("• Type: $label\n")
            append("• Confidence: ${(conf * 100).toInt()}%\n")
            append("• Severity: $sev\n")
            append("• Distance: ${"%.1f".format(dist)}m\n")
            append("• Time: ${sdf.format(Date())}\n\n")

            if (lat != null && lon != null) {
                append("📍 Location Detected:\n")
                append("$lat,$lon\n\n")
                append("Google Maps:\n")
                append("https://maps.google.com/?q=$lat,$lon\n")
            } else {
                append("📍 Location: Not Available\n")
                append("(GPS still acquiring signal)\n")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------
    private fun computeSeverity(conf: Float) = when {
        conf > 0.75f -> "HIGH"
        conf > 0.45f -> "MEDIUM"
        else -> "LOW"
    }

    private fun estimateDistance(rect: RectF): Float {
        val h = rect.height().coerceAtLeast(1f)
        val relative = h / 1000f
        return (5f * (1f - relative)).coerceAtLeast(0.5f)
    }

    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    ).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
