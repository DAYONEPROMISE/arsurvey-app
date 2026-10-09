package com.example.arsurvey.reconstruct.ml

import kotlin.math.roundToInt

/**
 * Prepares EfficientSAM inputs. Pure Kotlin (ARGB pixel array in, float arrays out). Ported verbatim
 * from prod `data/ml/sam/SamPreprocessor.kt`.
 *
 * The encoder expects a fixed `batched_images[1,3,1024,1024]` planar-RGB tensor normalized to 0..1.
 * The image is plain-resized to the square input; because the box prompt is scaled by the same
 * factor and the output mask is mapped back in **normalized** coordinates, the (uniform) aspect
 * distortion cancels out — no letterboxing needed. A box prompt is encoded the SAM way: two points,
 * the top-left corner (label 2) and the bottom-right corner (label 3), in input-pixel space.
 */
class SamPreprocessor(private val inputSize: Int = INPUT_SIZE) {

    /** Floats required for the encoder input buffer: `3 * inputSize * inputSize`. */
    val inputElements: Int = 3 * inputSize * inputSize

    /** Fills [dst] (planar CHW RGB, normalized 0..1) by resizing [image] to the square input. */
    fun encodeImage(image: ProcessingImage, dst: FloatArray) {
        require(dst.size == inputElements) { "dst size ${dst.size} != expected $inputElements" }
        val plane = inputSize * inputSize
        val scaleX = image.width.toFloat() / inputSize
        val scaleY = image.height.toFloat() / inputSize
        for (dy in 0 until inputSize) {
            val srcY = ((dy + 0.5f) * scaleY - 0.5f).roundToInt().coerceIn(0, image.height - 1)
            val rowOut = dy * inputSize
            val rowSrc = srcY * image.width
            for (dx in 0 until inputSize) {
                val srcX = ((dx + 0.5f) * scaleX - 0.5f).roundToInt().coerceIn(0, image.width - 1)
                val argb = image.pixels[rowSrc + srcX]
                val idx = rowOut + dx
                dst[idx] = ((argb ushr 16) and 0xFF) / 255f
                dst[plane + idx] = ((argb ushr 8) and 0xFF) / 255f
                dst[2 * plane + idx] = (argb and 0xFF) / 255f
            }
        }
    }

    /** Two box-corner points in input-pixel space: `[[l,t],[r,b]] * inputSize`. Shape [1,1,2,2]. */
    fun boxPointCoords(box: BoundingBox): FloatArray = floatArrayOf(
        box.left * inputSize, box.top * inputSize,
        box.right * inputSize, box.bottom * inputSize,
    )

    /** Point labels for a box prompt: 2 = top-left corner, 3 = bottom-right corner. Shape [1,1,2]. */
    fun boxPointLabels(): FloatArray = floatArrayOf(TOP_LEFT_LABEL, BOTTOM_RIGHT_LABEL)

    companion object {
        const val INPUT_SIZE = 1024
        const val POINTS_PER_BOX = 2
        private const val TOP_LEFT_LABEL = 2f
        private const val BOTTOM_RIGHT_LABEL = 3f
    }
}
