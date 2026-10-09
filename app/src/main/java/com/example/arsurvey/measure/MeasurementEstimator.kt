package com.example.arsurvey.measure

import kotlin.math.cos
import kotlin.math.sin

/**
 * Assembles the **authoritative** object dimensions (Increment 2, M-Measurement).
 *
 * This is the non-PCA measurement the docs mandate:
 *  - **height** comes from the gravity-aligned [FloorEstimator] (world Y span, floor→top);
 *  - **width / depth** come from the [FootprintEstimator]'s minimum-area rectangle on X/Z.
 *
 * The two are orthogonal by construction (vertical vs. horizontal), so we can stitch them into a
 * single gravity-vertical, footprint-yawed oriented box — reusing [Obb] purely for rendering /
 * corner geometry. PCA's [ObbFitter] is kept elsewhere only as a debug comparison, never as the
 * reported value.
 */
object MeasurementEstimator {

    /**
     * @param widthM  longer horizontal side (meters).
     * @param heightM floor→top vertical span (meters).
     * @param depthM  shorter horizontal side (meters).
     * @param box     gravity-vertical, footprint-oriented box for overlay/corner rendering.
     */
    data class Measurement(
        val widthM: Float,
        val heightM: Float,
        val depthM: Float,
        val box: Obb,
    )

    /**
     * Stitch an already-computed [floor] and [footprint] into a measurement. Returns null when
     * either input is missing/invalid so callers don't report a half-formed box.
     */
    fun assemble(floor: FloorEstimator.Floor, footprint: FootprintEstimator.Footprint?): Measurement? {
        if (!floor.valid || footprint == null) return null
        if (floor.height <= 0f || footprint.width <= 0f || footprint.depth <= 0f) return null

        val w = footprint.width
        val h = floor.height
        val d = footprint.depth

        // Horizontal axes from the rectangle yaw; vertical axis is world up.
        val ca = cos(footprint.angleRad)
        val sa = sin(footprint.angleRad)
        val widthDir = floatArrayOf(ca, 0f, sa)          // longer side
        val upDir = floatArrayOf(0f, 1f, 0f)
        val depthDir = floatArrayOf(-sa, 0f, ca)         // perpendicular, shorter side

        val center = floatArrayOf(
            footprint.centerX,
            floor.floorY + h * 0.5f,
            footprint.centerZ,
        )
        val box = Obb(
            center = center,
            axes = arrayOf(widthDir, upDir, depthDir),
            halfExtents = floatArrayOf(w * 0.5f, h * 0.5f, d * 0.5f),
        )
        return Measurement(w, h, d, box)
    }
}
