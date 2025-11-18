package com.example.bantaybeshie.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import android.renderscript.*
import android.util.Log
import java.nio.ByteBuffer

/**
 * Robust converter: converts ImageProxy (YUV_420_888) -> ARGB Bitmap.
 * Handles arbitrary rowStride / pixelStride in the planes.
 * Uses RenderScript ScriptIntrinsicYuvToRGB (fast, avoids JPEG).
 */
class YuvToRgbConverter(context: Context) {

    companion object {
        private const val TAG = "YuvToRgbConverter"
    }

    private val rs = RenderScript.create(context.applicationContext)
    private val script = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    // Reused buffer to avoid allocations on each frame
    private var yuvBuffer: ByteArray? = null

    /**
     * Convert image to Bitmap and return it.
     * Caller must still close ImageProxy (we don't close it here).
     */
    fun toBitmap(image: ImageProxy): Bitmap {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        convert(image, bitmap)
        return bitmap
    }

    /**
     * Convert ImageProxy into provided output Bitmap (must be correct size).
     */
    fun convert(image: ImageProxy, output: Bitmap) {
        val yuv = imageToNv21(image)
        // create allocations
        val yuvType = Type.Builder(rs, Element.U8(rs)).setX(yuv.size)
        val `in` = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
        val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(output.width).setY(output.height)
        val out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT)

        `in`.copyFrom(yuv)
        script.setInput(`in`)
        script.forEach(out)
        out.copyTo(output)

        // small debug log; remove in production if noisy
        Log.v(TAG, "Converted frame => ${image.width}x${image.height} (yuv:${yuv.size})")
    }

    /**
     * Build a NV21 byte array from ImageProxy, correctly handling rowStride/pixelStride.
     * NV21 layout = [Y plane (W*H bytes)], then [VU interleaved (W*H/2 bytes)].
     */
    private fun imageToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val ySize = width * height
        val chromaSize = width * height / 2
        val totalSize = ySize + chromaSize

        if (yuvBuffer == null || yuvBuffer!!.size < totalSize) {
            yuvBuffer = ByteArray(totalSize)
        }
        val out = yuvBuffer!!

        // 1) Copy Y plane (handle possible rowStride != width and pixelStride != 1)
        val yBuffer = yPlane.buffer
        var pos = 0
        val row = ByteArray(yRowStride)
        yBuffer.rewind()
        for (r in 0 until height) {
            yBuffer.position(r * yRowStride)
            yBuffer.get(row, 0, yRowStride)
            if (yPixelStride == 1) {
                // contiguous row - copy only width bytes
                System.arraycopy(row, 0, out, pos, width)
                pos += width
            } else {
                // copy with pixel stride
                var col = 0
                var idx = 0
                while (col < width) {
                    out[pos++] = row[idx]
                    idx += yPixelStride
                    col++
                }
            }
        }

        // 2) Interleave V and U planes into VU VU ... (NV21 expects V then U)
        // We'll read each chroma row and write interleaved bytes.
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        // Read entire plane data into arrays to allow indexed access
        val vBytes = ByteArray(vPlane.buffer.remaining())
        val uBytes = ByteArray(uPlane.buffer.remaining())
        vBuffer.rewind()
        uBuffer.rewind()
        vBuffer.get(vBytes)
        uBuffer.get(uBytes)

        var vuPos = ySize // start of chroma in NV21
        var vRowStart = 0
        var uRowStart = 0

        for (r in 0 until chromaHeight) {
            vRowStart = r * vRowStride
            uRowStart = r * uRowStride

            // within a chroma row, step by pixelStride to get next sample
            var c = 0
            var vIndex = vRowStart
            var uIndex = uRowStart
            while (c < chromaWidth) {
                // V then U (NV21)
                val vVal = vBytes.getOrNull(vIndex) ?: 0
                val uVal = uBytes.getOrNull(uIndex) ?: 0
                out[vuPos++] = vVal
                out[vuPos++] = uVal

                vIndex += vPixelStride
                uIndex += uPixelStride
                c++
            }
        }

        return out
    }
}
