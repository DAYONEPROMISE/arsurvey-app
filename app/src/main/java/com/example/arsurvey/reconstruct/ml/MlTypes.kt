package com.example.arsurvey.reconstruct.ml

/**
 * A decoded image handed to the reconstruction inference engines, kept free of
 * `android.graphics.Bitmap` so the pre/post-processing is plain-Kotlin (and matches the prod
 * source it was ported from). [pixels] is row-major ARGB_8888 of size `width * height`.
 *
 * Ported from prod `features/processing/domain/engine/ProcessingImage.kt` (the `seed` field, only
 * used by prod's simulated engines, is dropped).
 */
class ProcessingImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * An axis-aligned box in **normalized** coordinates (0..1). Produced by [YoloeDetector] and used as
 * the EfficientSAM box prompt. Ported from prod `domain/model/BoundingBox.kt`.
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/** A single detected object: label, detector confidence, normalized [box]. Ported from prod. */
data class Detection(
    val label: String,
    val confidence: Float,
    val box: BoundingBox,
)
