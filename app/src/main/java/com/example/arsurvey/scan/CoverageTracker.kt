package com.example.arsurvey.scan

import kotlin.math.atan2

/**
 * Tracks how much of the object has been seen from different **horizontal viewpoints**.
 *
 * We divide the circle around the object into [sectorCount] angular sectors (default 12 → 30°
 * each). Each frame, we take the camera's world position, subtract the object's horizontal
 * centroid, and mark the sector that the camera currently sits in. Coverage is the fraction of
 * sectors ever observed.
 *
 * This is deliberately simple — it answers "has the user walked around enough of the object?"
 * not "which faces are reconstructed." The mentor's "at least three angles" becomes a
 * configurable [minSectorsForScan] threshold rather than a hard-coded rule.
 */
class CoverageTracker(
    val sectorCount: Int = 12,
    /** Initial experimental requirement: ≥3 distinct viewpoints before a scan can converge. */
    val minSectorsForScan: Int = 3,
) {
    private val observed = BooleanArray(sectorCount)

    /** Count of distinct sectors observed so far. */
    var observedCount: Int = 0
        private set

    /**
     * Record the current viewpoint.
     * @param camX,camZ  camera world position (X,Z).
     * @param objX,objZ  object horizontal centroid (X,Z).
     * @return the sector index just marked, or -1 if the camera is essentially on top of the
     *         object (no well-defined bearing).
     */
    fun observe(camX: Float, camZ: Float, objX: Float, objZ: Float): Int {
        val dx = camX - objX
        val dz = camZ - objZ
        if (dx * dx + dz * dz < MIN_RADIUS_SQ) return -1
        // atan2 → [-π,π]; shift to [0,2π) then bucket.
        var a = atan2(dz.toDouble(), dx.toDouble())
        if (a < 0) a += TWO_PI
        val sector = ((a / TWO_PI) * sectorCount).toInt().coerceIn(0, sectorCount - 1)
        if (!observed[sector]) {
            observed[sector] = true
            observedCount++
        }
        return sector
    }

    /** 0..1 fraction of sectors observed. */
    fun fraction(): Float = observedCount.toFloat() / sectorCount

    /** Whether the minimum multi-view requirement is met (≥ [minSectorsForScan] sectors). */
    fun hasMinimumViews(): Boolean = observedCount >= minSectorsForScan

    /** Snapshot of which sectors are observed (for the coverage-ring debug overlay). */
    fun sectors(): BooleanArray = observed.copyOf()

    fun clear() {
        observed.fill(false)
        observedCount = 0
    }

    private companion object {
        const val TWO_PI = 2.0 * Math.PI
        const val MIN_RADIUS_SQ = 0.10f * 0.10f // <10 cm from centroid → bearing undefined
    }
}
