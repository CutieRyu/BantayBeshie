package com.example.bantaybeshie.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions

class ObjectDetectorHelper(
    context: Context,
    private val modelName: String = "bantaybeshie_model.tflite",
    private val scoreThreshold: Float = 0.5f,
    private val maxResults: Int = 5
) {
    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder()
            .useNnapi() // optional: accelerates detection if NNAPI is available
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(maxResults)
            .setScoreThreshold(scoreThreshold)
            .build()

        detector = ObjectDetector.createFromFileAndOptions(context, modelName, options)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val image = TensorImage.fromBitmap(bitmap)
        return detector.detect(image)
    }
}