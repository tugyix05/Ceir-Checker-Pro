package com.myokooo.ceir

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * ImageProxy (YUV_420_888) -> Bitmap converter.
 * CameraX 1.3.1 ImageProxy has no toBitmap(), so we convert manually.
 * Handles arbitrary rowStride/pixelStride (device-safe).
 */
object YuvUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val nv21 = yuv420ToNv21(image) ?: return null
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y plane — row by row (rowStride may exceed width)
        var pos = 0
        val yRow = ByteArray(yRowStride)
        yBuffer.rewind()
        for (row in 0 until height) {
            yBuffer.get(yRow, 0, yRowStride.coerceAtMost(yBuffer.remaining()))
            System.arraycopy(yRow, 0, nv21, pos, width)
            pos += width
        }

        // UV planes -> interleaved VU (NV21)
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uRow = ByteArray(uvRowStride)
        val vRow = ByteArray(vPlane.rowStride)
        uBuffer.rewind()
        vBuffer.rewind()
        for (row in 0 until uvHeight) {
            val uLen = uvRowStride.coerceAtMost(uBuffer.remaining())
            val vLen = vPlane.rowStride.coerceAtMost(vBuffer.remaining())
            uBuffer.get(uRow, 0, uLen)
            vBuffer.get(vRow, 0, vLen)
            for (col in 0 until uvWidth) {
                val vuIndex = pos + col * 2
                if (vuIndex + 1 < nv21.size) {
                    nv21[vuIndex] = vRow[col * uvPixelStride]
                    nv21[vuIndex + 1] = uRow[col * uvPixelStride]
                }
            }
            pos += width
        }

        return nv21
    }
}
