package com.example.arsurvey.measure

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a per-frame [Obb] into stable, consistently-labeled **width / height / depth**
 * (Milestone 7).
 *
 * Two problems this solves:
 * - **Which extent is which?** PCA orders axes by spread, which can swap frame to frame. We
 *   instead label by geometry: the axis most aligned with **gravity (world +Y up)** is the
 *   **height**; of the remaining two, the longer is **width**, the shorter is **depth**. Stable
 *   and physically meaningful.
 * - **Jitter.** Raw per-frame numbers flicker. We low-pass each dimension with an exponential
 *   moving average so the displayed value is steady.
 *
 * All values are meters. Not thread-safe; call from one thread (the render loop).
 */
class DimensionSmoother(private val alpha: Float = 0.25f) {

    private var whd: FloatArray? = null

    /** Feeds a new fit; returns the smoothed [w,h,d] in meters. */
    fun update(obb: Obb): FloatArray {
        val raw = label(obb)
        val prev = whd
        val next = if (prev == null) raw
        else FloatArray(3) { prev[it] + alpha * (raw[it] - prev[it]) }
        whd = next
        return next.copyOf()
    }

    /** Last smoothed value (e.g. while frozen), or null if none yet. */
    fun current(): FloatArray? = whd?.copyOf()

    fun reset() { whd = null }

    /** Labels the three extents as [w,h,d] using gravity for height + size for width/depth. */
    private fun label(obb: Obb): FloatArray {
        val ext = obb.dimensions
        // Height = axis whose direction is most vertical (|axis·up|).
        var hi = 0
        var best = -1f
        for (i in 0..2) {
            val vertical = abs(obb.axes[i][1])   // world up is (0,1,0) → the y component
            if (vertical > best) { best = vertical; hi = i }
        }
        val h = ext[hi]
        val others = (0..2).filter { it != hi }
        val a = ext[others[0]]
        val b = ext[others[1]]
        return floatArrayOf(max(a, b), h, min(a, b))   // width, height, depth
    }
}
