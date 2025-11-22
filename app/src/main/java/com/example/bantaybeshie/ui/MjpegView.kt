package com.example.bantaybeshie.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MjpegView(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var job: Job? = null
    private var streamUrl: String = ""
    private var frameProcessor: ((Bitmap) -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    fun setUrl(url: String) {
        streamUrl = url
    }

    fun setFrameProcessor(processor: (Bitmap) -> Unit) {
        frameProcessor = processor
    }

    fun startStream() {
        if (streamUrl.isEmpty()) return

        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {

            try {
                val conn = URL(streamUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doInput = true
                conn.connect()

                val input = BufferedInputStream(conn.inputStream)

                val delimiter = "--boundary".toByteArray()

                while (isActive) {

                    // find JPEG start
                    val jpeg = readJpegFrame(input, delimiter) ?: continue

                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bmp != null) {

                        // draw to SurfaceView
                        val canvas = holder.lockCanvas()
                        if (canvas != null) {
                            canvas.drawBitmap(
                                bmp,
                                null,
                                Rect(0, 0, width, height),
                                null
                            )
                            holder.unlockCanvasAndPost(canvas)
                        }

                        // send to detection
                        frameProcessor?.invoke(bmp)
                    }
                }

            } catch (e: Exception) {
                Log.e("MjpegView", "Stream error: ${e.message}")
            }
        }
    }

    fun stopStream() {
        job?.cancel()
        job = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // optional auto-start, but MainActivity handles starting
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopStream()
    }

    // Reads one JPEG frame from the MJPEG stream
    private fun readJpegFrame(
        input: BufferedInputStream,
        boundary: ByteArray
    ): ByteArray? {
        val buffer = ByteArrayOutputStream()

        var prev = 0
        var cur: Int

        // JPEG start detection (0xFFD8)
        while (true) {
            cur = input.read()
            if (cur == -1) return null
            if (prev == 0xFF && cur == 0xD8) { // Start of Image
                buffer.write(0xFF)
                buffer.write(0xD8)
                break
            }
            prev = cur
        }

        // JPEG end detection (0xFFD9)
        while (true) {
            cur = input.read()
            if (cur == -1) break
            buffer.write(cur)

            if (prev == 0xFF && cur == 0xD9) { // End of Image
                break
            }
            prev = cur
        }

        return buffer.toByteArray()
    }
}
