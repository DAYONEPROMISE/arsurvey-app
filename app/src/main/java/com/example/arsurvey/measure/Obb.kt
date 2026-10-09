package com.example.arsurvey.measure

/**
 * An **oriented** bounding box: a center, three orthonormal axis directions (the object's own
 * axes, from PCA), and half-extents along each axis. Unlike an axis-aligned box, this hugs the
 * object no matter how it's rotated in the room.
 *
 * All values are in **world meters**. The three full extents (2·halfExtent) are the object's
 * width/height/depth.
 */
class Obb(
    val center: FloatArray,                 // [x,y,z]
    val axes: Array<FloatArray>,            // 3 unit vectors, each [x,y,z]
    val halfExtents: FloatArray,            // [hx,hy,hz]
) {
    /** Full extents (meters), i.e. the three side lengths. */
    val dimensions: FloatArray
        get() = floatArrayOf(halfExtents[0] * 2f, halfExtents[1] * 2f, halfExtents[2] * 2f)

    /** The 8 corners as a packed [x0,y0,z0, …] array (24 floats). */
    fun corners(): FloatArray {
        val out = FloatArray(24)
        var o = 0
        for (i in 0 until 8) {
            val sx = if (i and 1 != 0) 1f else -1f
            val sy = if (i and 2 != 0) 1f else -1f
            val sz = if (i and 4 != 0) 1f else -1f
            for (c in 0..2) {
                out[o++] = center[c] +
                    sx * halfExtents[0] * axes[0][c] +
                    sy * halfExtents[1] * axes[1][c] +
                    sz * halfExtents[2] * axes[2][c]
            }
        }
        return out
    }

    companion object {
        /** The 12 edges as corner-index pairs (matches [corners] bit layout). */
        val EDGES: Array<IntArray> = buildList {
            for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) {
                if (i and bit == 0) add(intArrayOf(i, i or bit))
            }
        }.toTypedArray()
    }
}
