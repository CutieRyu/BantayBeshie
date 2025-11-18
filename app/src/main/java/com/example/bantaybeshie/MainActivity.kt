package com.example.bantaybeshie

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.widget.TextView
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

        private const val MODEL_SIZE = 640
        private const val NUM_CHANNELS = 9
        private const val NUM_ANCHORS = 8400

        private val LABELS = listOf("knife", "person", "person-like", "pistol", "undefined")
    }

    // Views attached by HomeFragment
    private var homePreview: PreviewView? = null
    private var homeOverlay: OverlayView? = null
    private var homeResult: TextView? = null

    // Navigation
    private lateinit var nav: NavController

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var yuv: YuvToRgbConverter

    // TFLite
    private var tflite: Interpreter? = null

    // Settings
    private var sensitivity = 0.3f

    // Location + SMS
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private var lastSmsAt = 0L
    private val SMS_COOLDOWN = 30_000L
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Permissions
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

        // Converters + GPS
        yuv = YuvToRgbConverter(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        locReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500)
            .setMinUpdateIntervalMillis(700).build()

        // Load model
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val buf = FileUtil.loadMappedFile(this@MainActivity, "bantaybeshie_model.tflite")
                tflite = Interpreter(buf)
                Log.d(TAG, "Model loaded: output = [1,9,8400]")
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

    // --------------------------------------------------------------------
    // Attach / Detach HomeFragment views
    // --------------------------------------------------------------------
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

    // --------------------------------------------------------------------
    // Camera Pipeline
    // --------------------------------------------------------------------
    private fun startCamera() {
        val prev = homePreview ?: return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            cameraProvider?.unbindAll()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(prev.surfaceProvider)
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

    // --------------------------------------------------------------------
    // RAW XYWH Detection (Your working logic)
    // --------------------------------------------------------------------
    private fun runDetection(bitmap: Bitmap) {
        val tfl = tflite ?: return

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
            tfl.run(buf, out)

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

                dets.add(
                    OverlayView.DetectionResult(
                        rect,
                        label,
                        finalConf,
                        computeSeverity(finalConf),
                        estimateDistance(rect)
                    )
                )
                Log.d("CONF_LOG", "Detected $label — conf=${"%.4f".format(finalConf)}")
            }

            runOnUiThread {
                homeOverlay?.setResults(dets)
                homeResult?.text = "Detected: ${dets.size}"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
        }
    }

    // --------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------
    private fun computeSeverity(conf: Float): String = when {
        conf > 0.75f -> "HIGH"
        conf > 0.45f -> "MEDIUM"
        else -> "LOW"
    }

    private fun estimateDistance(rect: RectF): Float {
        val h = rect.height().coerceAtLeast(1f)
        val relative = h / 1000f
        return 5f * (1f - relative)
    }

    // Sensitivity from UI
    fun setSensitivity(value: Float) {
        sensitivity = value
    }

    // --------------------------------------------------------------------
    // SOS + Logging + SMS
    // --------------------------------------------------------------------
    fun triggerManualSOS() {
        logEvent("Manual SOS", "Triggered by user", LogType.SOS)
        attemptSendAlert("Manual SOS", 1f, "HIGH", 0f)
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

    private fun attemptSendAlert(label: String, conf: Float, sev: String, dist: Float) {
        val now = System.currentTimeMillis()
        if (now - lastSmsAt < SMS_COOLDOWN) return
        lastSmsAt = now

        // no GPS permission → send without location
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendSms(buildMessage(label, conf, sev, dist, null))
            return
        }

        fused.requestLocationUpdates(locReq, object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                fused.removeLocationUpdates(this)
                sendSms(buildMessage(label, conf, sev, dist, res.lastLocation))
            }
        }, mainLooper)
    }

    private fun buildMessage(label: String, conf: Float, sev: String, dist: Float, loc: Location?): String {
        val ts = sdf.format(Date())
        return buildString {
            append("BantayBeshie Alert\n")
            append("Type: $label\n")
            append("Confidence: ${(conf * 100).toInt()}%\n")
            append("Severity: $sev\n")
            append("Distance: ${"%.1f".format(dist)}m\n")
            append("Time: $ts\n")
            if (loc != null) {
                append("Location: ${loc.latitude},${loc.longitude}\n")
                append("Map: https://maps.google.com/?q=${loc.latitude},${loc.longitude}\n")
            } else append("Location: Unknown\n")
        }
    }

    private fun sendSms(message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val contacts = ContactsDatabase.getDatabase(this@MainActivity)
                .contactsDao().getAllContactsList()

            val sms = SmsManager.getDefault()
            for (c in contacts) {
                sms.sendTextMessage(c.number, null, message, null, null)
            }
        }
    }

    private fun allPermissionsGranted(): Boolean =
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS
        ).all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }
}
