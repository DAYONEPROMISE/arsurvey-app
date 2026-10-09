package com.example.arsurvey.track

/**
 * World-space object isolation: a horizontal (X/Z) radius gate around the locked target's
 * established center, applied to a frame's masked world points **before** they enter the
 * multi-view accumulator.
 *
 * This is the second line of defence behind [TargetTracker]. Even with the correct target
 * mask selected, a slightly-too-large or bleeding mask can back-project a few stray depth
 * pixels that belong to the floor beyond the object or a neighbouring item. Once we have an
 * anchor (the accumulator's running centroid of already-accepted target points), any new
 * point whose horizontal distance from that anchor exceeds [radius] cannot belong to this
 * object, so it is dropped.
 *
 * Horizontal only: vertical spread (floor/ceiling bleed) is already handled by the existing
 * outlier filter and the percentile floor/height estimate. The failure this addresses is a
 * *neighbouring object beside the target* (chair next to box), which is a horizontal offset.
 *
 * Pure / Android-free for unit testing. It filters points; it does not alter how the
 * accumulator fuses them.
 */
object TargetIsolation {

    /**
     * Keep only points within [radius] meters of ([anchorX], [anchorZ]) on the X/Z plane.
     *
     * @param packed world points [x0,y0,z0, x1,y1,z1, …].
     * @return a new packed array of the surviving points (may be empty); the input is unchanged.
     */
    fun filterHorizontalRadius(
        packed: FloatArray,
        anchorX: Float,
        anchorZ: Float,
        radius: Float,
    ): FloatArray {
        if (packed.isEmpty()) return packed
        val r2 = radius * radius
        val out = FloatArray(packed.size)
        var n = 0
        var i = 0
        while (i + 2 < packed.size) {
            val dx = packed[i] - anchorX
            val dz = packed[i + 2] - anchorZ
            if (dx * dx + dz * dz <= r2) {
                out[n++] = packed[i]
                out[n++] = packed[i + 1]
                out[n++] = packed[i + 2]
            }
            i += 3
        }
        return if (n == out.size) out else out.copyOf(n)
    }
}
