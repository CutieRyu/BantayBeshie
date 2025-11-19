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
import androidx.camera.core.*
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private var isPopupVisible = false

        private const val MODEL_SIZE = 640
        private const val NUM_CHANNELS = 9
        private const val NUM_ANCHORS = 8400

        private const val SMS_COOLDOWN = 30_000L
        private const val EMAIL_COOLDOWN = 8_000L

        private var lastSmsAt = 0L
        private var lastEmailAt = 0L

        private val LABELS = listOf("knife", "person", "person-like", "pistol", "undefined")
    }

    private var homePreview: PreviewView? = null
    private var homeOverlay: OverlayView? = null
    private var homeResult: TextView? = null

    private lateinit var nav: NavController
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var yuv: YuvToRgbConverter

    private var tflite: Interpreter? = null
    private var sensitivity = 0.3f

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (allPermissionsGranted()) startCamera()
        }

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
    }

    private fun requestLocation(callback: (Location?) -> Unit) {
        try {
            fused.lastLocation
                .addOnSuccessListener { lastLoc ->
                    if (lastLoc != null) {
                        callback(lastLoc)
                    } else {
                        // Force an active location request
                        fused.requestLocationUpdates(
                            locReq,
                            object : LocationCallback() {
                                override fun onLocationResult(result: LocationResult) {
                                    fused.removeLocationUpdates(this)
                                    callback(result.lastLocation)
                                }
                            },
                            mainLooper
                        )
                    }
                }
                .addOnFailureListener {
                    callback(null)
                }
        } catch (e: SecurityException) {
            callback(null)
        }
    }

    fun setSensitivity(value: Float) {
        sensitivity = value
    }

    // ---------------------------------------------------------------------------------------------
    // YOLO Detection
    // ---------------------------------------------------------------------------------------------
    private fun runDetection(bitmap: Bitmap) {
        val interpreter = tflite ?: return

        try {
            val resized = Bitmap.createScaledBitmap(bitmap, MODEL_SIZE, MODEL_SIZE, true)

            val buf = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 3 * 4)
            buf.order(ByteOrder.nativeOrder())

            for (y in 0 until MODEL_SIZE) {
                for (x in 0 until MODEL_SIZE) {
                    val px = resized.getPixel(x, y)
                    buf.putFloat(((px shr 16) and 0xFF) / 255f)
                    buf.putFloat(((px shr 8) and 0xFF) / 255f)
                    buf.putFloat((px and 0xFF) / 255f)
                }
            }

            val out = Array(1) { Array(NUM_CHANNELS) { FloatArray(NUM_ANCHORS) } }
            interpreter.run(buf, out)

            val preds = out[0]
            val dets = mutableListOf<OverlayView.DetectionResult>()

            fun sig(x: Float) = (1f / (1f + kotlin.math.exp(-x)))

            val pw = homePreview?.width?.toFloat() ?: MODEL_SIZE.toFloat()
            val ph = homePreview?.height?.toFloat() ?: MODEL_SIZE.toFloat()

            for (i in 0 until NUM_ANCHORS) {
                val x = preds[0][i]
                val y = preds[1][i]
                val w = preds[2][i]
                val h = preds[3][i]

                val conf = sig(preds[4][i])
                val clsScores = FloatArray(NUM_CHANNELS - 5) { j -> sig(preds[5 + j][i]) }
                val (clsIdx, clsConf) = clsScores.withIndex().maxByOrNull { it.value } ?: continue

                val finalConf = conf * clsConf
                if (finalConf < sensitivity) continue

                val left = (x - w / 2f) * pw
                val top = (y - h / 2f) * ph
                val right = (x + w / 2f) * pw
                val bottom = (y + h / 2f) * ph

                val mirroredLeft = pw - right
                val mirroredRight = pw - left

                val rect = RectF(mirroredLeft, top, mirroredRight, bottom)
                val label = LABELS.getOrNull(clsIdx) ?: "Unknown"
                val sev = computeSeverity(finalConf)
                val dist = estimateDistance(rect)

                // Add detection to overlay
                dets.add(
                    OverlayView.DetectionResult(
                        rect, label, finalConf, sev, dist
                    )
                )

                // 🔥 SEND SMS + EMAIL FOR EVERY DETECTION
                askUserConfirmation(label, finalConf, sev, dist)

            }

            runOnUiThread {
                homeOverlay?.setResults(dets)
                homeResult?.text = "Detected: ${dets.size}"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
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
        return 5f * (1f - relative)
    }

    // ---------------------------------------------------------------------------------------------
    // SOS + Logging + SMS + EMAIL
    // ---------------------------------------------------------------------------------------------
    fun triggerManualSOS() {
        logEvent("Manual SOS", "Triggered by user", LogType.SOS)

        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                // If location is null, still send SOS without location
                val safeLoc = loc ?: null

                attemptSendAlert(
                    label = "Manual SOS",
                    conf = 1f,
                    sev = "HIGH",
                    dist = 0f,
                    loc = safeLoc
                )

                attemptSendEmail(
                    label = "Manual SOS",
                    conf = 1f,
                    sev = "HIGH",
                    dist = 0f,
                    loc = safeLoc
                )
            }
        } catch (e: SecurityException) {
            // If permission error, still send SOS without location
            attemptSendAlert("Manual SOS", 1f, "HIGH", 0f, null)
            attemptSendEmail("Manual SOS", 1f, "HIGH", 0f, null)
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
            ActivityLogDatabase.getDb(this@MainActivity).logDao().insert(entry)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SMS
    // ---------------------------------------------------------------------------------------------
    private fun attemptSendAlert(label: String, conf: Float, sev: String, dist: Float, loc: Location?) {
        val now = System.currentTimeMillis()
        if (now - lastSmsAt < SMS_COOLDOWN) {
            Log.d("SMS", "Cooldown")
            return
        }

        lastSmsAt = now
        val message = buildMessage(label, conf, sev, dist, loc)


        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val contacts = ContactsDatabase.getDatabase(this@MainActivity)
                    .contactsDao().getAllContactsList()

                val sms = SmsManager.getDefault()
                for (c in contacts) {
                    sms.sendTextMessage(c.number, null, message, null, null)
                }
            } catch (e: Exception) {
                Log.e("SMS", "Failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // EMAIL
    // ---------------------------------------------------------------------------------------------
    private fun attemptSendEmail(label: String, conf: Float, sev: String, dist: Float, loc: Location?) {
        val now = System.currentTimeMillis()
        if (now - lastEmailAt < EMAIL_COOLDOWN) {
            Log.d("EMAIL", "Cooldown")
            return
        }

        lastEmailAt = now

        val contacts = ContactsDatabase.getDatabase(this)
            .contactsDao().getAllContactsList()
            .mapNotNull { it.email }
            .filter { it.isNotBlank() }

        if (contacts.isEmpty()) {
            Log.w("EMAIL", "No email contacts available")
            return
        }

        val subject = "BantayBeshie Alert: $label"
        val body = buildMessage(label, conf, sev, dist, loc)

        sendEmailViaGmail(contacts, subject, body)
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

    private fun askUserConfirmation(label: String, conf: Float, sev: String, dist: Float) {
        if (isPopupVisible) return
        isPopupVisible = true

        runOnUiThread {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            builder.setTitle("Threat Detected")

            val percent = (conf * 100).toInt()

            builder.setMessage(
                "Type: $label\n" +
                        "Confidence: $percent%\n" +
                        "Severity: $sev\n\n" +
                        "Share your live location with emergency contacts?"
            )

            builder.setPositiveButton("SEND") { dialog, _ ->
                requestLocation { loc ->
                    attemptSendAlert(label, conf, sev, dist, loc)
                    attemptSendEmail(label, conf, sev, dist, loc)
                }
                isPopupVisible = false
                dialog.dismiss()
            }

            builder.setNegativeButton("CANCEL") { dialog, _ ->
                Toast.makeText(this, "Alert canceled.", Toast.LENGTH_SHORT).show()
                isPopupVisible = false
                dialog.dismiss()
            }

            builder.setOnDismissListener { isPopupVisible = false }
            builder.setCancelable(false)

            builder.show()
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


    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    ).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
