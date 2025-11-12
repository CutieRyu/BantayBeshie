package com.example.bantaybeshie.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.bantaybeshie.OverlayView
import com.example.bantaybeshie.R
import com.example.bantaybeshie.utils.FabMenuHelper
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class HomeFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var resultText: TextView
    private lateinit var btnSOS: Button
    private lateinit var tflite: Interpreter
    private lateinit var alertPlayer: MediaPlayer


    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var sensitivityThreshold = 0.3f

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        previewView = view.findViewById(R.id.previewView)
        overlayView = view.findViewById(R.id.overlayView)
        resultText = view.findViewById(R.id.resultText)
        btnSOS = view.findViewById(R.id.btnSOS)

        FabMenuHelper.setupFABMenu(view, findNavController())
        setupSensitivityButtons(view)
        setupSOSButton()

        try {
            val modelBuffer = FileUtil.loadMappedFile(requireContext(), "bantaybeshie_model.tflite")
            tflite = Interpreter(modelBuffer)
            resultText.text = "✅ Model loaded"
        } catch (e: Exception) {
            Log.e("TFLite", "Load error: ${e.message}")
            resultText.text = "❌ Model load failed"
        }

        alertPlayer = MediaPlayer.create(requireContext(), R.raw.alert_sound)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        return view
    }

    private fun setupSensitivityButtons(view: View) {
        view.findViewById<Button>(R.id.btnWalking).setOnClickListener {
            sensitivityThreshold = 0.6f
            Toast.makeText(requireContext(), "Mode: Walking (High)", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.btnTranspo).setOnClickListener {
            sensitivityThreshold = 0.75f
            Toast.makeText(requireContext(), "Mode: In Transport (Medium)", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.btnRoom).setOnClickListener {
            sensitivityThreshold = 0.9f
            Toast.makeText(requireContext(), "Mode: In Room (Low)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSOSButton() {
        btnSOS.setOnClickListener {
            // This is just manual SOS
            triggerAlert("Manual SOS", "HIGH")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor, { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)
                runInference(bitmap)
                imageProxy.close()
            })

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
                Log.d("Camera", "Started camera")
            } catch (e: Exception) {
                Log.e("Camera", "Bind error: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun runInference(bitmap: Bitmap) {
        try {
            val inputSize = 640
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())

            for (y in 0 until inputSize) {
                for (x in 0 until inputSize) {
                    val pixel = resized.getPixel(x, y)
                    inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                    inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                    inputBuffer.putFloat((pixel and 0xFF) / 255f)
                }
            }

            // ✅ Correct shape for your model: [1, 9, 8400]
            val outputArray = Array(1) { Array(9) { FloatArray(8400) } }
            tflite.run(inputBuffer, outputArray)

            val outputs = outputArray[0]
            val detections = mutableListOf<OverlayView.DetectionResult>()

            // ✅ For each of the 8400 detections
            for (i in 0 until 8400) {
                val x = outputs[0][i]
                val y = outputs[1][i]
                val w = outputs[2][i]
                val h = outputs[3][i]
                fun sigmoid(x: Float) = (1f / (1f + kotlin.math.exp(-x)))

                val conf = sigmoid(outputs[4][i])
                if (i < 5) Log.d("YOLO", "Conf after sigmoid=$conf")
                val classScores = FloatArray(outputs.size - 5) { j -> sigmoid(outputs[5 + j][i]) }

                val (clsIdx, maxScore) = classScores.withIndex().maxByOrNull { it.value } ?: continue
                val finalConf = conf * maxScore

                // ✅ Lower this if you want more detections
                if (finalConf > sensitivityThreshold) {
                    val previewWidth = previewView.width.toFloat()
                    val previewHeight = previewView.height.toFloat()

                    val scaledLeft = (x - w / 2) * previewWidth
                    val scaledTop = (y - h / 2) * previewHeight
                    val scaledRight = (x + w / 2) * previewWidth
                    val scaledBottom = (y + h / 2) * previewHeight

                    val rect = RectF(scaledLeft, scaledTop, scaledRight, scaledBottom)
                    rect.left = previewWidth - rect.right
                    rect.right = previewWidth - rect.left
                    val label = LABELS.getOrNull(clsIdx) ?: "Unknown"
                    detections.add(OverlayView.DetectionResult(rect, label, finalConf))
                }
            }
            // Debug: Log top confidence values
            val allConfs = FloatArray(8400) { i -> outputs[4][i] }
            val topConfs = allConfs.sortedDescending().take(10)
            Log.d("YOLO", "Top 10 confidences: ${topConfs.joinToString()}")

            requireActivity().runOnUiThread {
                overlayView.setResults(detections)
                resultText.text = "Detected: ${detections.size}"

                if (detections.isNotEmpty()) {
                    detections.forEach {
                        Log.d("YOLO", "Detected ${it.label} conf=${it.confidence}")
                    }
                } else {
                    Log.d("YOLO", "No detections above threshold $sensitivityThreshold")
                }
            }

        } catch (e: Exception) {
            Log.e("YOLO", "Detection failed: ${e.message}")
        }
    }


    private fun getThreatSeverity(box: RectF): String {
        val area = box.width() * box.height()
        return when {
            area > 200_000 -> "HIGH"
            area > 80_000 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun triggerAlert(label: String, severity: String) {
        Toast.makeText(requireContext(), "⚠️ $label Detected ($severity)", Toast.LENGTH_LONG).show()
        alertPlayer.start()
        // You can insert SOS logic here
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        tflite.close()
        alertPlayer.release()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )
        private val LABELS = listOf("knife", "person", "person-like", "pistol", "undefined")
    }
}
