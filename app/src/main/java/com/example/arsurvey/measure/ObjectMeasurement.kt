package com.example.arsurvey.measure

import java.time.Instant

/**
 * The final on-device measurement result (Increment 2, M-Result).
 *
 * This is the **only** thing the backend ever receives — the phone does all reconstruction and
 * geometry; the server just stores W/H/D + metadata (see docs/architecture.md, server boundary).
 * Deliberately a plain immutable value with no ARCore / Android types so it can be serialized and
 * shipped as-is.
 */
data class ObjectMeasurement(
    val widthCm: Float,
    val heightCm: Float,
    val depthCm: Float,
    /** 0..1 overall confidence (coverage + temporal stability + point density). */
    val confidence: Float,
    /** 0..1 fraction of angular viewpoints observed. */
    val coverage: Float,
    /** ISO-8601 capture time. */
    val timestamp: String = Instant.now().toString(),
) {
    /** Minimal JSON for the eventual backend hand-off. */
    fun toJson(): String = buildString {
        append('{')
        append("\"widthCm\":").append(round1(widthCm)).append(',')
        append("\"heightCm\":").append(round1(heightCm)).append(',')
        append("\"depthCm\":").append(round1(depthCm)).append(',')
        append("\"confidence\":").append(round2(confidence)).append(',')
        append("\"coverage\":").append(round2(coverage)).append(',')
        append("\"timestamp\":\"").append(timestamp).append('"')
        append('}')
    }

    private fun round1(v: Float): String = "%.1f".format(v)
    private fun round2(v: Float): String = "%.2f".format(v)

    companion object {
        /**
         * Build a result from meter-space dimensions + scan metadata, converting to centimeters.
         */
        fun from(whdMeters: FloatArray, confidence: Float, coverage: Float): ObjectMeasurement =
            ObjectMeasurement(
                widthCm = whdMeters[0] * 100f,
                heightCm = whdMeters[1] * 100f,
                depthCm = whdMeters[2] * 100f,
                confidence = confidence.coerceIn(0f, 1f),
                coverage = coverage.coerceIn(0f, 1f),
            )
    }
}
