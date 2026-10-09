package com.example.arsurvey.measure

import com.example.arsurvey.util.SymmetricEigen

/**
 * Fits an oriented bounding box to a point cloud via **PCA** (Milestone 6).
 *
 * Why PCA / why OBB: an axis-aligned box is locked to world X/Y/Z, so a chair rotated 30°
 * gets a loose, oversized box and wrong dimensions. PCA instead finds the object's **own**
 * axes — the directions of greatest point spread:
 *   1. Center the points on their centroid.
 *   2. Build the 3×3 covariance matrix (how the points co-vary in x/y/z).
 *   3. Its **eigenvectors** are the principal axes; eigenvalues rank them by spread.
 *   4. Project the points onto each axis; the min/max along each axis give the box extents.
 * The three extents are the object's width/height/depth.
 */
object ObbFitter {

    private const val MIN_POINTS = 12

    /** @param points packed world points [x0,y0,z0,…]. @return the OBB, or null if degenerate. */
    fun fit(points: FloatArray): Obb? {
        val n = points.size / 3
        if (n < MIN_POINTS) return null

        // 1) Centroid.
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        var i = 0
        while (i < points.size) { cx += points[i]; cy += points[i + 1]; cz += points[i + 2]; i += 3 }
        cx /= n; cy /= n; cz /= n

        // 2) Covariance (symmetric 3×3), accumulated from centered points.
        var c00 = 0.0; var c01 = 0.0; var c02 = 0.0; var c11 = 0.0; var c12 = 0.0; var c22 = 0.0
        i = 0
        while (i < points.size) {
            val dx = points[i] - cx; val dy = points[i + 1] - cy; val dz = points[i + 2] - cz
            c00 += dx * dx; c01 += dx * dy; c02 += dx * dz
            c11 += dy * dy; c12 += dy * dz; c22 += dz * dz
            i += 3
        }
        val inv = 1.0 / n
        val cov = doubleArrayOf(
            c00 * inv, c01 * inv, c02 * inv,
            c01 * inv, c11 * inv, c12 * inv,
            c02 * inv, c12 * inv, c22 * inv,
        )

        // 3) Principal axes = eigenvectors of the covariance.
        val eig = SymmetricEigen.decompose(cov)
        val axes = Array(3) { a -> floatArrayOf(
            eig.vectors[a][0].toFloat(), eig.vectors[a][1].toFloat(), eig.vectors[a][2].toFloat(),
        ) }

        // 4) Project points onto each axis → min/max extent along it.
        val min = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val max = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        i = 0
        while (i < points.size) {
            val dx = points[i] - cx; val dy = points[i + 1] - cy; val dz = points[i + 2] - cz
            for (a in 0..2) {
                val t = dx * axes[a][0] + dy * axes[a][1] + dz * axes[a][2]
                if (t < min[a]) min[a] = t
                if (t > max[a]) max[a] = t
            }
            i += 3
        }

        // Box center = centroid shifted to the middle of the projected span on each axis.
        val center = floatArrayOf(cx.toFloat(), cy.toFloat(), cz.toFloat())
        val half = FloatArray(3)
        for (a in 0..2) {
            val mid = (min[a] + max[a]) * 0.5
            center[0] += (mid * axes[a][0]).toFloat()
            center[1] += (mid * axes[a][1]).toFloat()
            center[2] += (mid * axes[a][2]).toFloat()
            half[a] = ((max[a] - min[a]) * 0.5).toFloat()
        }
        return Obb(center, axes, half)
    }
}
