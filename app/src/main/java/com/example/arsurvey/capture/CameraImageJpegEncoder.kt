package com.example.arsurvey.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import com.example.arsurvey.ar.YuvImage
import java.io.ByteArrayOutputStream

/**
 * Encodes an ARCore camera frame (already copied off the GL thread as a stride-aware
 * [YuvImage]) into a JPEG byte array.
 *
 * We reuse the existing [YuvImage] plane copy rather than introducing a new image path: the
 * frame is copied cheaply on the render thread, and this heavier YUV→NV21→JPEG step then runs
 * on a background thread. No ML, no resizing — this is a faithful full-resolution encode of the
 * camera image ARCore exposed.
 *
 * The JPEG is written in the camera's native **sensor-landscape** orientation so it stays
 * consistent with the persisted intrinsics; the review UI rotates it for display using the
 * `displayRotationDegrees` recorded in [com.example.arsurvey.capture.model.ARCoreFrameMetadata].
 */
object CameraImageJpegEncoder {

    /** @param quality JPEG quality 0..100. */
    fun encode(image: YuvImage, quality: Int = 95): ByteArray {
        val nv21 = toNv21(image)
        val out = ByteArrayOutputStream(image.width * image.height / 2)
        android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    /**
     * Repacks stride-aware YUV_420_888 planes into a tightly packed NV21 buffer
     * (full-res Y plane followed by interleaved V/U at quarter resolution), honoring each
     * plane's row/pixel strides so padded rows and semi-planar chroma are handled correctly.
     */
    private fun toNv21(img: YuvImage): ByteArray {
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2))

        // Luma: copy w bytes per row, skipping any row padding.
        var pos = 0
        for (row in 0 until h) {
            val base = row * img.yRowStride
            System.arraycopy(img.y, base, out, pos, w)
            pos += w
        }

        // Chroma: NV21 wants V then U interleaved, sampled at half resolution. Indices are
        // clamped because with semi-planar chroma (pixelStride 2) the last row's final sample
        // can point one byte past the plane copy's `remaining()` — a well-known YUV_420_888 trap.
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val vMax = img.v.size - 1
        val uMax = img.u.size - 1
        for (cy in 0 until ch) {
            val uRow = cy * img.uRowStride
            val vRow = cy * img.vRowStride
            for (cx in 0 until cw) {
                out[pos++] = img.v[(vRow + cx * img.vPixelStride).coerceAtMost(vMax)]
                out[pos++] = img.u[(uRow + cx * img.uPixelStride).coerceAtMost(uMax)]
            }
        }
        return out
    }
}
