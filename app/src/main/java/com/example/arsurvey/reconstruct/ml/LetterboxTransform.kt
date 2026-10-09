package com.example.arsurvey.reconstruct.ml

/**
 * Records how a source image was letterboxed into the square YOLOE input, so box coordinates
 * predicted in model space can be mapped back to **normalized** (0..1) image coordinates.
 * Ported verbatim from prod `data/ml/yolo/LetterboxTransform.kt`.
 */
data class LetterboxTransform(
    val inputSize: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Float,
    val padY: Float,
) {
    /** Model-space X → normalized image X (0..1). */
    fun normalizeX(modelX: Float): Float =
        ((modelX - padX) / scaledWidth).coerceIn(0f, 1f)

    /** Model-space Y → normalized image Y (0..1). */
    fun normalizeY(modelY: Float): Float =
        ((modelY - padY) / scaledHeight).coerceIn(0f, 1f)
}
