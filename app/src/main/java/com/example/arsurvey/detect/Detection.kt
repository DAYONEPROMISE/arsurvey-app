package com.example.arsurvey.detect

/**
 * One detected object, in the **camera image's** pixel coordinate space (sensor
 * landscape) — NOT screen space. The UI maps these to the screen at draw time.
 */
data class Detection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    /** Class probability 0..1 (YOLO already applies sigmoid in the exported graph). */
    val score: Float,
    val classId: Int,
    val label: String,
    /** 32 YOLO-Seg mask coefficients for this detection (empty if not decoded). */
    val maskCoeffs: FloatArray = FloatArray(0),
) {
    val area: Float get() = (x2 - x1) * (y2 - y1)
    val cx: Float get() = (x1 + x2) * 0.5f
    val cy: Float get() = (y1 + y2) * 0.5f
}
