package com.example.arsurvey.scan

/**
 * Multi-view point accumulation with a **bounded voxel grid** (the core new feature).
 *
 * Each frame's masked depth is back-projected to world space by
 * [com.example.arsurvey.cloud.PointCloudBuilder]. Instead of throwing that cloud away every
 * frame (the old single-view behaviour), we drop every world point into a fixed-size voxel
 * grid keyed by its quantized (x,y,z) cell. This:
 *
 *  - **fuses viewpoints**: points from different phone positions that land in the same cell
 *    reinforce one voxel rather than duplicating — the reconstruction fills in as the user
 *    walks around the object;
 *  - **bounds memory**: the representation grows with the object's *surface*, not with time.
 *    A voxel is stored once no matter how many frames observe it, and we hard-cap the map.
 *
 * We keep a running centroid per voxel (sum + count) so the reported point is the average of
 * all observations that fell in that cell — cheap denoising for free.
 *
 * Not thread-safe: all mutation happens on the render loop (see [com.example.arsurvey.ui.MeasureViewModel]).
 */
class PointCloudAccumulator(
    /** Cell edge length in meters. 1.5 cm keeps household-object detail without exploding count. */
    private val voxelSize: Float = 0.015f,
    /** Hard cap on occupied cells; past this we stop creating new voxels (existing ones still refine). */
    private val maxVoxels: Int = 80_000,
) {
    /** voxel key → [sumX, sumY, sumZ, count]. */
    private val cells = HashMap<Long, FloatArray>()

    /** Occupied voxel count == number of points a [snapshot] returns. */
    val size: Int get() = cells.size

    /** Total raw observations folded in (for a HUD "N pts / M views" style readout). */
    var observationCount: Long = 0L
        private set

    /**
     * Fold a frame's packed world points `[x0,y0,z0, x1,y1,z1, …]` into the grid.
     * Points are assumed already filtered (outlier rejection happens before this).
     */
    fun addWorldPoints(packed: FloatArray) {
        val inv = 1f / voxelSize
        var i = 0
        while (i + 2 < packed.size) {
            val x = packed[i]; val y = packed[i + 1]; val z = packed[i + 2]
            i += 3
            observationCount++
            val key = keyOf(
                Math.floor((x * inv).toDouble()).toInt(),
                Math.floor((y * inv).toDouble()).toInt(),
                Math.floor((z * inv).toDouble()).toInt(),
            )
            val cell = cells[key]
            if (cell != null) {
                cell[0] += x; cell[1] += y; cell[2] += z; cell[3] += 1f
            } else if (cells.size < maxVoxels) {
                cells[key] = floatArrayOf(x, y, z, 1f)
            }
            // else: grid full — ignore this (new) cell but keep refining existing ones above.
        }
    }

    /** Packed per-voxel centroids `[x0,y0,z0, …]` — the accumulated reconstruction. */
    fun snapshot(): FloatArray {
        val out = FloatArray(cells.size * 3)
        var o = 0
        for (cell in cells.values) {
            val c = cell[3]
            out[o++] = cell[0] / c
            out[o++] = cell[1] / c
            out[o++] = cell[2] / c
        }
        return out
    }

    /** Horizontal (X,Z) centroid of the reconstruction, or null when empty — the coverage pivot. */
    fun centroidXZ(): FloatArray? {
        if (cells.isEmpty()) return null
        var sx = 0.0; var sz = 0.0
        for (cell in cells.values) {
            val c = cell[3]
            sx += cell[0] / c; sz += cell[2] / c
        }
        val n = cells.size
        return floatArrayOf((sx / n).toFloat(), (sz / n).toFloat())
    }

    fun clear() {
        cells.clear()
        observationCount = 0L
    }

    /** Pack three signed 21-bit grid indices into one Long key. */
    private fun keyOf(gx: Int, gy: Int, gz: Int): Long {
        val bx = (gx.toLong() and MASK)
        val by = (gy.toLong() and MASK)
        val bz = (gz.toLong() and MASK)
        return (bx shl 42) or (by shl 21) or bz
    }

    private companion object {
        const val MASK = 0x1FFFFFL // 21 bits → ±1,048,575 cells ≈ ±15 km at 1.5 cm. Ample.
    }
}
