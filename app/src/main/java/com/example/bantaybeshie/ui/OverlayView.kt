package com.example.bantaybeshie.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class DetectionResult(
        val box: RectF,
        val label: String,
        val confidence: Float,
        val severity: String,
        val distance: Float
    )

    private var results: List<DetectionResult> = emptyList()

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setResults(newResults: List<DetectionResult>) {
        results = newResults
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (det in results) {

            // Color based on severity
            val color = when (det.severity) {
                "HIGH" -> Color.RED
                "MEDIUM" -> Color.YELLOW
                else -> Color.GREEN
            }

            boxPaint.color = color
            boxPaint.strokeWidth = when (det.severity) {
                "HIGH" -> 8f
                "MEDIUM" -> 5f
                else -> 3f
            }

            // Draw bounding box
            canvas.drawRect(det.box, boxPaint)

            // Label text (label + conf + severity + distance)
            val text =
                "${det.label} ${(det.confidence * 100).toInt()}%  ${det.severity}  ${"%.1f".format(det.distance)}m"

            val padding = 8f
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize

            val textX = det.box.left
            val textY = (det.box.top - 10).coerceAtLeast(40f)

            val bgRect = RectF(
                textX - padding,
                textY - textHeight,
                textX + textWidth + padding,
                textY + padding
            )

            val bgPaint = Paint().apply {
                this.color = Color.argb(180, 0, 0, 0)
            }

            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(text, textX, textY, textPaint)
        }
    }
}
