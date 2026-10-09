package com.example.arsurvey.ar

import android.media.Image

/**
 * SDK-free snapshot of one ARCore camera frame in YUV_420_888.
 *
 * Why copy the raw planes instead of converting to RGB here: [FrameExtractor] runs on
 * the AR **render thread**, where every millisecond costs frame rate. Copying the three
 * plane byte arrays is a cheap memcpy; the expensive YUV→RGB conversion + inference then
 * happens on a background thread (see the detector). The native [Image] is closed
 * immediately after this copy — holding it would starve ARCore's image pool and freeze.
 */
class YuvImage(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val uRowStride: Int,
    val vRowStride: Int,
    val uPixelStride: Int,
    val vPixelStride: Int,
) {
    companion object {
        /** Copies planes out of an ARCore YUV_420_888 [Image]. Caller still owns/closes the Image. */
        fun from(image: Image): YuvImage {
            val p = image.planes
            val yb = p[0].buffer
            val ub = p[1].buffer
            val vb = p[2].buffer
            val y = ByteArray(yb.remaining()).also { yb.get(it) }
            val u = ByteArray(ub.remaining()).also { ub.get(it) }
            val v = ByteArray(vb.remaining()).also { vb.get(it) }
            return YuvImage(
                width = image.width,
                height = image.height,
                y = y, u = u, v = v,
                yRowStride = p[0].rowStride,
                uRowStride = p[1].rowStride,
                vRowStride = p[2].rowStride,
                uPixelStride = p[1].pixelStride,
                vPixelStride = p[2].pixelStride,
            )
        }
    }
}
