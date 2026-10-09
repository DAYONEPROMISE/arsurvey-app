package com.example.arsurvey.measure

/**
 * Gravity-aligned floor + height estimation (Increment 2, M-Floor/Height).
 *
 * ARCore's world Y axis is gravity-aligned (up = +Y), so vertical measurement is just a matter
 * of finding the object's bottom and top **world Y**. The object sits on the floor, so the
 * accumulated *object* points (the mask excludes the floor itself) reach down to the
 * floor-contact line — the robust low percentile of their Y is our floor estimate, and the
 * robust high percentile is the object top.
 *
 * We use **percentiles, never raw min/max** (rule 13): depth noise sprays a few points above
 * and below the object, and raw extremes would inflate the height by centimeters.
 *
 * Limitation (documented in PROGRESS): this derives the floor from the object's own base rather
 * than a validated ARCore floor *plane*. If the object's bottom is occluded from every observed
 * viewpoint the low percentile sits above the true floor and height is underestimated.
 * ARCore-plane cross-validation is a planned refinement.
 */
object FloorEstimator {

    /**
     * @param floorY    world Y of the estimated floor (meters).
     * @param topY      world Y of the estimated object top (meters).
     * @param height    topY − floorY (meters).
     * @param valid     enough points and a positive height.
     * @param fromPlane the floor came from a validated ARCore plane (true) rather than the
     *                  cloud-base fallback (false). The docs require plane validation before a
     *                  final measurement, so callers gate COMPLETE on this.
     */
    data class Floor(
        val floorY: Float,
        val topY: Float,
        val height: Float,
        val valid: Boolean,
        val fromPlane: Boolean,
    )

    private val INVALID = Floor(0f, 0f, 0f, valid = false, fromPlane = false)

    /** Max |plane − cloud base| for the ARCore plane to be accepted as this object's floor. */
    private const val FLOOR_AGREE_TOL = 0.20f

    /**
     * @param points       packed world points [x0,y0,z0,…] (the accumulated cloud).
     * @param floorPlaneY  world Y of the detected ARCore floor plane, or null if none.
     * @param lowPct       percentile for the cloud-base floor (default 3rd).
     * @param highPct      percentile for the top (default 97th).
     * @param minPoints    below this we don't trust the estimate.
     */
    fun estimate(
        points: FloatArray,
        floorPlaneY: Float? = null,
        lowPct: Float = 0.03f,
        highPct: Float = 0.97f,
        minPoints: Int = 40,
    ): Floor {
        val n = points.size / 3
        if (n < minPoints) return INVALID

        val ys = FloatArray(n)
        var i = 0
        var j = 0
        while (i < points.size) { ys[j++] = points[i + 1]; i += 3 }
        ys.sort()

        val baseFloorY = percentile(ys, lowPct)
        val topY = percentile(ys, highPct)

        // Validate the ARCore floor plane against the object's own base: the plane is accepted
        // only if it sits within tolerance of the cloud base (i.e. it really is THIS object's
        // floor, not some other low surface). When accepted it also *refines* the floor, since a
        // plane fit is less noisy than a percentile of masked depth.
        val agree = floorPlaneY != null && kotlin.math.abs(floorPlaneY - baseFloorY) <= FLOOR_AGREE_TOL
        val floorY = if (agree) floorPlaneY!! else baseFloorY

        val height = topY - floorY
        return Floor(floorY, topY, height, valid = height > 0f, fromPlane = agree)
    }

    /** Linear-interpolated percentile of a pre-sorted array. */
    private fun percentile(sorted: FloatArray, p: Float): Float {
        if (sorted.size == 1) return sorted[0]
        val idx = (p.coerceIn(0f, 1f) * (sorted.size - 1))
        val lo = idx.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = idx - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }
}
