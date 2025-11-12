package com.example.bantaybeshie

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class DetectionResult(val box: RectF, val label: String, val confidence: Float)

    private val paintBox = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }

    private var results: List<DetectionResult> = emptyList()

    fun setResults(newResults: List<DetectionResult>) {
        results = newResults
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (result in results) {
            val conf = result.confidence
            val boxColor = when {
                conf > 0.8f -> Color.RED      // High confidence
                conf > 0.5f -> Color.YELLOW   // Medium confidence
                else -> Color.GREEN           // Low confidence
            }

            val paint = Paint().apply {
                color = boxColor
                style = Paint.Style.STROKE
                strokeWidth = when {
                    conf > 0.8f -> 8f
                    conf > 0.5f -> 5f
                    else -> 3f
                }
                isAntiAlias = true
            }

            val textPaint = Paint().apply {
                color = boxColor
                textSize = 40f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            // Draw box
            canvas.drawRect(result.box, paint)

            // Draw label and confidence
            canvas.drawText(
                "${result.label} ${(result.confidence * 100).toInt()}%",
                result.box.left,
                result.box.top - 10,
                textPaint
            )
        }
    }

}
