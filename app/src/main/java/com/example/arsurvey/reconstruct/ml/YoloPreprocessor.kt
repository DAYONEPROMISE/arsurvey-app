package com.example.arsurvey.reconstruct.ml

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a decoded [ProcessingImage] into the YOLOE input tensor: a letterboxed (aspect-preserving),
 * `inputSize`×`inputSize`, planar **RGB** float array normalized to 0..1 — exactly what the
 * Ultralytics export expects (`images[1,3,640,640]`). Padding is the standard gray (114/255).
 * Ported verbatim from prod `data/ml/yolo/YoloPreprocessor.kt`.
 */
class YoloPreprocessor(private val inputSize: Int) {

    /** Number of floats the [dst] buffer must hold: `3 * inputSize * inputSize`. */
    val inputElements: Int = 3 * inputSize * inputSize

    /** Fills [dst] (planar CHW, R plane then G then B) and returns the letterbox mapping. */
    fun preprocess(image: ProcessingImage, dst: FloatArray): LetterboxTransform {
        require(dst.size == inputElements) {
            "dst size ${dst.size} != expected $inputElements"
        }

        val gain = min(
            inputSize.toFloat() / image.width,
            inputSize.toFloat() / image.height,
        )
        val scaledWidth = (image.width * gain).roundToInt().coerceIn(1, inputSize)
        val scaledHeight = (image.height * gain).roundToInt().coerceIn(1, inputSize)
        val padX = (inputSize - scaledWidth) / 2f
        val padY = (inputSize - scaledHeight) / 2f

        val plane = inputSize * inputSize
        dst.fill(PAD_VALUE)

        val padXi = padX.toInt()
        val padYi = padY.toInt()
        for (dy in 0 until scaledHeight) {
            val srcY = (dy + 0.5f) / gain - 0.5f
            val outRow = (dy + padYi) * inputSize + padXi
            for (dx in 0 until scaledWidth) {
                val srcX = (dx + 0.5f) / gain - 0.5f
                val argb = sampleBilinear(image, srcX, srcY)
                val idx = outRow + dx
                dst[idx] = ((argb ushr 16) and 0xFF) / 255f          // R plane
                dst[plane + idx] = ((argb ushr 8) and 0xFF) / 255f   // G plane
                dst[2 * plane + idx] = (argb and 0xFF) / 255f        // B plane
            }
        }

        return LetterboxTransform(inputSize, scaledWidth, scaledHeight, padX, padY)
    }

    /** Bilinear ARGB sample with clamped edges. Returns a packed ARGB int. */
    private fun sampleBilinear(image: ProcessingImage, x: Float, y: Float): Int {
        val w = image.width
        val h = image.height
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)

        val p00 = image.pixels[y0 * w + x0]
        val p01 = image.pixels[y0 * w + x1]
        val p10 = image.pixels[y1 * w + x0]
        val p11 = image.pixels[y1 * w + x1]

        val r = lerp2(channel(p00, 16), channel(p01, 16), channel(p10, 16), channel(p11, 16), fx, fy)
        val g = lerp2(channel(p00, 8), channel(p01, 8), channel(p10, 8), channel(p11, 8), fx, fy)
        val b = lerp2(channel(p00, 0), channel(p01, 0), channel(p10, 0), channel(p11, 0), fx, fy)
        return (r shl 16) or (g shl 8) or b
    }

    private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF

    private fun lerp2(c00: Int, c01: Int, c10: Int, c11: Int, fx: Float, fy: Float): Int {
        val top = c00 + (c01 - c00) * fx
        val bottom = c10 + (c11 - c10) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    }

    private companion object {
        /** Ultralytics letterbox fill (gray 114), normalized. */
        const val PAD_VALUE = 114f / 255f
    }
}
