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
import androidx.camera.lifecycle.ProcessCameraProvider
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*
import android.content.Context
import android.view.ViewGroup
import com.example.bantaybeshie.ui.MjpegView

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

        // ===== Popup cooldowns
        private const val POPUP_COOLDOWN_MS = 10_000L
        private var lastPopupAt = 0L
        private var popupActive = false

        private var homeMjpeg: MjpegView? = null
        private val piStreamUrl = "http://192.168.1.24:8080/?action=stream"


        // Scenario config extended for sensitivity multiplier and alert threshold
        data class ScenarioConfig(
            val anomalyThreshold: Float,         // legacy use (not forcing)
            val followerPersistence: Int,        // how many consecutive frames to require for people
            val sensitivityMultiplier: Float,    // multiplies base sensitivity (lower -> easier detection)
            val alertScoreThreshold: Float,      // threat score required to prompt SOS
            val name: String
        )

        private val SCENARIO_MAP = mapOf(
            // walking: least sensitive (so user isn't spammed), requires higher persistence to alert
            "NORMAL" to ScenarioConfig(anomalyThreshold = 0.65f, followerPersistence = 4, sensitivityMultiplier = 0.85f, alertScoreThreshold = 0.65f, name = "Walking"),
            // crowded: harder to alert (higher persistence), more noise so multiplier reduces sensitivity
            "CROWDED" to ScenarioConfig(anomalyThreshold = 0.80f, followerPersistence = 6, sensitivityMultiplier = 0.75f, alertScoreThreshold = 0.75f, name = "Crowded"),
            // room: highest sensitivity (small area)
            "NIGHT" to ScenarioConfig(anomalyThreshold = 0.60f, followerPersistence = 2, sensitivityMultiplier = 1.10f, alertScoreThreshold = 0.50f, name = "Night"),
            // high risk: most sensitive and short persistence
            "HIGHRISK" to ScenarioConfig(anomalyThreshold = 0.55f, followerPersistence = 1, sensitivityMultiplier = 1.15f, alertScoreThreshold = 0.45f, name = "High-Risk")
        )

        // Balanced thresholds tuned for low-confidence outputs — you can tweak these
        private val LABEL_MIN_CONF = mapOf(
            "pistol" to 0.27f,
            "person" to 0.30f,
            "knife" to 0.30f
        )

        private val WEAPON_MIN_CONF = mapOf(
            "pistol" to 0.27f,
            "knife" to 0.30f
        )

        private val LABELS = listOf("knife", "person", "person-like", "pistol", "undefined")
    }

    // UI
    private var homePreview: MjpegView? = null
    private var homeOverlay: OverlayView? = null
    private var homeResult: TextView? = null

    private lateinit var nav: NavController
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var yuv: YuvToRgbConverter

    // Model
    private var tflite: Interpreter? = null
    private var baseSensitivity = 0.25f // user slider base; scenario multiplies this

    private var lastFrame: Bitmap? = null

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
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var alerted: Boolean = false,
        // keep the last center to compute approach speed / tailing
        var lastCenterX: Float = 0f,
        var lastCenterY: Float = 0f,
        var speedPxPerSec: Float = 0f,
        var threatLevel: String = "PASSIVE", // PASSIVE / ACTIVE / CRITICAL
        var threatScore: Float = 0f
    )

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (allPermissionsGranted()) startPiStream()
        }

    // Scenario state
    private var currentScenarioKey: String = "NORMAL" // NORMAL / CROWDED / NIGHT / HIGHRISK

    fun setScenarioNormal() { currentScenarioKey = "NORMAL" }
    fun setScenarioCrowded() { currentScenarioKey = "CROWDED" }
    fun setScenarioNight() { currentScenarioKey = "NIGHT" }
    fun setScenarioHighRisk() { currentScenarioKey = "HIGHRISK" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        nav = navHost.navController
        NavBarHelper.setup(findViewById(R.id.bottomNavBar), nav, "home")

        yuv = YuvToRgbConverter(this)
        fused = LocationServices.getFusedLocationProviderClient(this)

        locReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500)
            .setMinUpdateIntervalMillis(700)
            .build()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val buf = FileUtil.loadMappedFile(this@MainActivity, "bantaybeshie_model.tflite")
                tflite = Interpreter(buf)
                Log.d(TAG, "Model loaded successfully. (Also note local uploaded model: /mnt/data/best.pt)")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tflite?.close()
        cameraExecutor.shutdown()
    }

    // HomeFragment attach/detach
    fun attachHomeViews(overlay: OverlayView, resultText: TextView) {
        homeOverlay = overlay
        homeResult = resultText

        // find mjpegView from the layout
        val parent = overlay.parent as ViewGroup
        homeMjpeg = parent.findViewById(R.id.mjpegView)

        homeMjpeg?.setUrl(piStreamUrl)
        homeMjpeg?.setFrameProcessor { bmp ->
            lastFrame = bmp
            runDetection(bmp)
        }

        homeMjpeg?.startStream()
    }


    fun detachHomeViews() {
        homeMjpeg?.stopStream()
        homeMjpeg = null
        homeOverlay = null
        homeResult = null
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
        baseSensitivity = value.coerceIn(0f, 1f)
    }

    private fun startPiStream() {
        val overlay = homeOverlay ?: return
        val parent = overlay.parent as? ViewGroup ?: return

        if (homeMjpeg == null) {
            homeMjpeg = parent.findViewById(R.id.mjpegView)
        }

        homeMjpeg?.apply {
            setUrl(piStreamUrl)
            setFrameProcessor { bmp ->
                lastFrame = bmp
                runDetection(bmp)
            }
            startStream()
        }
    }
    // Core detection pipeline
    private fun runDetection(bitmap: Bitmap) {
        val interpreter = tflite ?: return
        if (!detectionEnabled) {
            // Clear overlay but keep tracks so resuming is smooth
            runOnUiThread {
                homeOverlay?.setResults(emptyList())
                homeResult?.text = "Detection: OFF"
            }
            return
        }

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

            val pw = homeOverlay?.width?.toFloat() ?: MODEL_SIZE.toFloat()
            val ph = homeOverlay?.height?.toFloat() ?: MODEL_SIZE.toFloat()

            val numClasses = (NUM_CHANNELS - 5)

            // effective sensitivity = base * scenario multiplier
            val scenario = SCENARIO_MAP[currentScenarioKey] ?: SCENARIO_MAP["NORMAL"]!!
            val effectiveSensitivity = (baseSensitivity * scenario.sensitivityMultiplier).coerceIn(0.01f, 0.99f)

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
                if (finalConf < effectiveSensitivity) continue // use effective sensitivity

                val left = (rx - rw / 2f) * pw
                val top = (ry - rh / 2f) * ph
                val right = (rx + rw / 2f) * pw
                val bottom = (ry + rh / 2f) * ph

                val mLeft = pw - right
                val mRight = pw - left

                val rect = RectF(mLeft, top, mRight, bottom)

                val rawLabel = LABELS.getOrNull(bestIdx) ?: "Unknown"
                val finalLabel = if (rawLabel == "person-like") "person" else rawLabel

                // tiny boxes filter
                val boxW = rect.width()
                val boxH = rect.height()
                val minBoxSizePx = 6f // raised to reduce noise
                if (boxW < minBoxSizePx || boxH < minBoxSizePx) continue

                // label-specific min threshold (accounting for low model conf)
                val labelMin = LABEL_MIN_CONF[finalLabel] ?: 0.25f
                if (finalConf < labelMin) continue

                // Debug logs
                Log.d(
                    "YOLO_DEBUG",
                    "i=$i | label=$finalLabel | conf=${"%.3f".format(finalConf)} | obj=${"%.3f".format(obj)} | cls=${"%.3f".format(clsConf)}"
                )

                rawDets.add(SimpleDet(rect, finalLabel, finalConf))
            }

            // update tracks using detections
            updateTracksWithDetections(rawDets, scenario)

            // show overlay results from active tracks (more stable than rawDets)
            val visible = tracks.map { t ->
                // now severity shown is PASSIVE/ACTIVE/CRITICAL stored in t.threatLevel
                OverlayView.DetectionResult(t.box, t.label, t.confidence, t.threatLevel, estimateDistance(t.box))
            }

            runOnUiThread {
                homeOverlay?.setResults(visible)
                homeResult?.text = "Tracked: ${visible.size}"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
        }
    }

    private data class SimpleDet(val rect: RectF, val label: String, val conf: Float)

    // Tracking with IoU matching (now stores firstSeen + speed estimation)
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

            if (bestIdx >= 0 && bestIoU > 0.2f) { // slightly looser IoU to allow multiple boxes per person if model jittery
                val d = dets[bestIdx]
                // compute centers and speed
                val centerX = (d.rect.left + d.rect.right) / 2f
                val centerY = (d.rect.top + d.rect.bottom) / 2f
                val dtSec = max(0.001f, (now - track.lastSeenMs) / 1000f)
                val dx = centerX - track.lastCenterX
                val dy = centerY - track.lastCenterY
                val speed = sqrt(dx * dx + dy * dy) / dtSec

                track.box = d.rect
                track.confidence = d.conf
                track.label = d.label
                track.consecutive += 1
                track.lastSeenMs = now
                track.lastCenterX = centerX
                track.lastCenterY = centerY
                track.speedPxPerSec = speed
                matched[bestIdx] = true

                evaluateThreatForTrack(track, scenario)
            } else {
                // not matched -> decay consecutive after small delay
                if (now - track.lastSeenMs > 1000L) {
                    track.consecutive = max(0, track.consecutive - 1)
                }
            }
        }

        // add unmatched as new tracks
        for ((i, d) in dets.withIndex()) {
            if (matched[i]) continue
            val id = ++trackIdCounter
            val nowMs = System.currentTimeMillis()
            val centerX = (d.rect.left + d.rect.right) / 2f
            val centerY = (d.rect.top + d.rect.bottom) / 2f
            val tr = Track(
                id = id,
                box = d.rect,
                label = d.label,
                confidence = d.conf,
                consecutive = 1,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                alerted = false,
                lastCenterX = centerX,
                lastCenterY = centerY,
                speedPxPerSec = 0f,
                threatLevel = "PASSIVE",
                threatScore = 0f
            )
            tracks.add(tr)
            // immediate evaluate for weapons
            evaluateThreatForTrack(tr, scenario)
        }

        // cleanup stale tracks older than 5s
        val cutoff = System.currentTimeMillis() - 5000L
        tracks.removeAll { it.lastSeenMs < cutoff }
    }

    private fun evaluateThreatForTrack(track: Track, scenario: ScenarioConfig) {
        // Weapons are high priority
        if (track.label == "pistol" || track.label == "knife") {
            val minWeapon = WEAPON_MIN_CONF[track.label] ?: 0.3f
            if (track.confidence >= minWeapon) {
                track.threatLevel = "CRITICAL"
                track.threatScore = 1f
                if (!track.alerted) {
                    track.alerted = true
                    // CRITICAL → auto SOS
                    Log.d(TAG, "Weapon detected → CRITICAL: id=${track.id} label=${track.label} conf=${track.confidence}")
                    triggerAutoSosForTrack(track)
                }
            }
            return
        }

        // People: compute threat score
        if (track.label == "person") {
            val now = System.currentTimeMillis()
            val persistence = track.consecutive.toFloat()
            val persistenceScore = (persistence / max(1f, scenario.followerPersistence.toFloat())).coerceAtMost(1f)

            val timeSeenMs = (now - track.firstSeenMs).coerceAtLeast(0L)
            val timeSeenScore = (timeSeenMs / 3000f).toFloat().coerceAtMost(1f) // normalized over 3s

            // proximity normalized: estimateDistance returns meters approximate; 3m is threshold (closer => higher)
            val distMeters = estimateDistance(track.box)
            val proximityScore = ((3.0f - distMeters) / 3.0f).coerceIn(0f, 1f) // 3m -> 0..1

            // approach speed: higher speed => more threat. Normalize by a heuristic pixel/sec max (e.g., 2000 px/s)
            val speedNorm = (track.speedPxPerSec / 2000f).coerceAtMost(1f)

            // tailing: simple heuristic: if speed low and consecutive high and center roughly same horizontally near user's frame center,
            // we treat it as tailing. For simplicity assume user's safe region center ~ center of preview.
            var tailingScore = 0f
            try {
                val previewW = homePreview?.width?.toFloat() ?: MODEL_SIZE.toFloat()
                val previewH = homePreview?.height?.toFloat() ?: MODEL_SIZE.toFloat()
                val centerX = (track.box.left + track.box.right) / 2f
                val centerY = (track.box.top + track.box.bottom) / 2f

                val distFromCenter = hypot(centerX - previewW / 2f, centerY - previewH / 2f)
                val centerDistNorm = (1f - (distFromCenter / hypot(previewW / 2f, previewH / 2f))).coerceIn(0f, 1f)
                if (track.consecutive >= max(2, scenario.followerPersistence) && track.speedPxPerSec < 50f) {
                    tailingScore = centerDistNorm * 0.6f // moderate weight
                }
            } catch (e: Exception) {
                tailingScore = 0f
            }

            // weights (tuneable)
            val wPersistence = 0.30f
            val wProximity = 0.30f
            val wTime = 0.15f
            val wSpeed = 0.15f
            val wTailing = 0.10f

            val threatScore = (persistenceScore * wPersistence) +
                    (proximityScore * wProximity) +
                    (timeSeenScore * wTime) +
                    (speedNorm * wSpeed) +
                    (tailingScore * wTailing)

            track.threatScore = threatScore

            Log.d(TAG, "ThreatScore: id=${track.id} label=${track.label} score=${"%.3f".format(threatScore)} " +
                    "p=${"%.2f".format(persistenceScore)} prox=${"%.2f".format(proximityScore)} time=${"%.2f".format(timeSeenScore)} speed=${"%.2f".format(speedNorm)} tail=${"%.2f".format(tailingScore)} conf=${"%.3f".format(track.confidence)} dist=${"%.2f".format(distMeters)}")

            // Determine level:
            // CRITICAL: weapon OR very close (<1m) OR very high threatScore (e.g., >=0.9)
            // ACTIVE: threatScore >= scenario.alertScoreThreshold OR medium proximity or high persistence
            // PASSIVE: otherwise
            val isVeryClose = distMeters <= 1.0f
            if (isVeryClose || threatScore >= 0.9f) {
                track.threatLevel = "CRITICAL"
            } else if (threatScore >= scenario.alertScoreThreshold || (persistence >= max(2f, scenario.followerPersistence.toFloat()) && proximityScore >= 0.3f)) {
                track.threatLevel = "ACTIVE"
            } else {
                track.threatLevel = "PASSIVE"
            }

            // gating: require label min confidence
            val minConf = LABEL_MIN_CONF["person"] ?: 0.30f
            val meetsConf = track.confidence >= minConf

            // Behavior:
            // - PASSIVE: do nothing (maybe log)
            // - ACTIVE: show popup (ask user) if not already alerted
            // - CRITICAL: auto SOS if not already alerted
            if (meetsConf) {
                if (track.threatLevel == "CRITICAL" && !track.alerted) {
                    track.alerted = true
                    Log.d(TAG, "Person → CRITICAL: id=${track.id} score=${"%.3f".format(threatScore)}")
                    triggerAutoSosForTrack(track)
                } else if (track.threatLevel == "ACTIVE" && !track.alerted) {
                    // require at least scenario.followerPersistence frames to avoid spam
                    if (track.consecutive >= max(1, scenario.followerPersistence / 2)) {
                        track.alerted = true
                        Log.d(TAG, "Person → ACTIVE: id=${track.id} score=${"%.3f".format(threatScore)}")
                        // show confirmation popup to user
                        showConfirmationForTrack(track, scenario)
                    }
                } else {
                    // PASSIVE - just log for analytics
                    if (track.threatLevel == "PASSIVE") {
                        Log.d(TAG, "Person PASSIVE: id=${track.id} score=${"%.3f".format(threatScore)}")
                    }
                }
            }
        }
    }

    private fun triggerAutoSosForTrack(track: Track) {
        // Acquire location then auto send (SMS + email) and show a brief popup letting the user know
        requestLocation { loc ->
            lifecycleScope.launch(Dispatchers.IO) {
                attemptSendAlert(track.label, track.confidence, track.threatLevel, estimateDistance(track.box), loc)
            }
            lifecycleScope.launch(Dispatchers.Main) {
                attemptSendEmail(track.label, track.confidence, track.threatLevel, estimateDistance(track.box), loc)
                // Brief toast to communicate auto-action
                Toast.makeText(this@MainActivity, "Auto SOS triggered (CRITICAL)", Toast.LENGTH_LONG).show()
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

        // acquire location then show popup (includes location & calculated distance)
        requestLocation { loc ->
            val summary = "${track.label} detected (${(track.confidence * 100).toInt()}%) — distance ${"%.1f".format(estimateDistance(track.box))}m — ${track.threatLevel}"
            askUserConfirmation(summary, loc) {
                lifecycleScope.launch(Dispatchers.IO) {
                    attemptSendAlert(track.label, track.confidence, track.threatLevel, estimateDistance(track.box), loc)
                    attemptSendEmail(track.label, track.confidence, track.threatLevel, estimateDistance(track.box), loc)
                }
                runOnUiThread {
                    popupActive = false
                }
            }
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

    // Popup with confirmation
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

    // SOS + Logging + SMS + EMAIL
    fun triggerManualSOS() {
        logEvent("Manual SOS", "Triggered by user", LogType.SOS)
        requestLocation { loc ->
            lifecycleScope.launch(Dispatchers.IO) {
                attemptSendAlert("Manual SOS", 1f, "CRITICAL", 0f, loc)
            }
            lifecycleScope.launch(Dispatchers.Main) {
                attemptSendEmail("Manual SOS", 1f, "CRITICAL", 0f, loc)
            }
        }
    }

    fun takeSnapshot(): String? {
        val bmp = lastFrame ?: return null

        val dir = File(filesDir, "snapshots")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "snap_${System.currentTimeMillis()}.jpg")

        FileOutputStream(file).use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }

        return file.absolutePath
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
            // fallback chooser if Gmail is not installed
            try {
                startActivity(Intent.createChooser(intent, "Send email"))
            } catch (ex: Exception) {
                Toast.makeText(this, "No email client found", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Location acquisition
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

    // Build message with Google Maps link
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
            append("• Threat: $sev\n")
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

    // crude estimate of distance in meters based on box height in pixels relative to preview height
    // put near the other helpers in MainActivity
    private val DEFAULT_PERSON_HEIGHT_M = 1.7f

    // SharedPreferences keys (tweak names as needed)
    private val PREFS_CAL = "bantay_cal"
    private val PREF_FOCAL_PX = "focal_px"          // focal length in pixels (if calibrated)
    private val PREF_REAL_H_M = "real_h_m"          // reference real object height used when calibrating

    // call this to clear calibration during testing
    fun clearCalibration() {
        val prefs = getSharedPreferences(PREFS_CAL, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    // Save calibration after user provides knownDistanceMeters and observedBoxHeightPx
    fun saveCalibration(knownDistanceMeters: Float, realHeightMeters: Float, observedBoxHeightPx: Float) {
        if (observedBoxHeightPx <= 1f || knownDistanceMeters <= 0f || realHeightMeters <= 0f) return
        // focal_px = (observed_pixel_height * known_distance) / real_height
        val focalPx = (observedBoxHeightPx * knownDistanceMeters) / realHeightMeters
        val prefs = getSharedPreferences(PREFS_CAL, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(PREF_FOCAL_PX, focalPx)
            .putFloat(PREF_REAL_H_M, realHeightMeters)
            .apply()
        Log.d(TAG, "Calibration saved focalPx=${"%.1f".format(focalPx)} realH=${"%.2f".format(realHeightMeters)}")
    }

    // Read saved focal (returns null if no calibration)
    private fun loadCalibratedFocalPx(): Pair<Float, Float>? {
        val prefs = getSharedPreferences(PREFS_CAL, Context.MODE_PRIVATE)
        val f = prefs.getFloat(PREF_FOCAL_PX, -1f)
        val h = prefs.getFloat(PREF_REAL_H_M, -1f)
        return if (f > 0f && h > 0f) Pair(f, h) else null
    }

    /**
     * Improved estimateDistance:
     * - If calibrated focal is available -> distance = (real_height_m * focal_px) / pixel_height
     * - Otherwise fallback to a robust inverse-proportional heuristic using preview height,
     *   with clamping and smoothing to avoid the '0.8m when far' behavior.
     */
    private fun estimateDistance(rect: RectF): Float {
        try {
            val ph = homePreview?.height?.toFloat() ?: MODEL_SIZE.toFloat()
            val boxHpx = rect.height().coerceAtLeast(1f)

            // Try calibrated focal method first
            val cal = loadCalibratedFocalPx()
            if (cal != null) {
                val (focalPx, realHeightM) = cal
                // distance (m) = (real_height_in_m * focal_px) / pixel_height
                val dist = (realHeightM * focalPx) / boxHpx
                // clamp realistic range
                return dist.coerceIn(0.3f, 100f)
            }

            // --- Fallback heuristic (safer than previous buckets) ---
            // Compute relative height in preview (0..1)
            val relative = (boxHpx / ph).coerceIn(0.0001f, 1f)

            // Inverse proportional mapping: distance ≈ k / relative
            // Choose k so that when relative = 0.5 -> ~1.0–1.5 m
            // We'll compute k from assumed person height and preview size heuristics:
            // k = assumed_person_height_m * preview_pixel_equiv
            // preview_pixel_equiv: scale factor to convert relative to meters. Use ph * factor.
            val previewFactor = ph * 0.6f // tuning constant; 0.6 gives reasonable numbers
            val k = DEFAULT_PERSON_HEIGHT_M * previewFactor

            var dist = k / (boxHpx) // dist in "meters" approximate
            // Because we used previewFactor and ph in k, above returns meterish numbers.
            // Clamp & smooth to avoid extreme low values
            if (dist.isNaN() || !dist.isFinite()) dist = 50f
            // clamp reasonable range
            dist = dist.coerceIn(0.5f, 100f)

            // small smoothing (optional)
            // return lastDistanceSmoothed = last * 0.7f + dist * 0.3f if you keep state

            return dist
        } catch (e: Exception) {
            Log.e(TAG, "estimateDistance error: ${e.message}")
            return 50f
        }
    }


    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    ).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}