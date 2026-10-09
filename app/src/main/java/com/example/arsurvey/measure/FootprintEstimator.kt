package com.example.arsurvey.measure

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal footprint via a **minimum-area oriented rectangle** (Increment 2, M-Footprint).
 *
 * Per the measurement-pipeline doc, PCA is *not* the authoritative horizontal method. Instead we
 * drop the vertical component, project the accumulated object points onto the world X/Z plane,
 * and fit the smallest-area rectangle that encloses them. The rectangle's two side lengths are
 * the object's width and depth, and — crucially — its orientation is found from the point
 * distribution, so it does **not** inflate when a rectangular object is rotated relative to the
 * world axes (the failure mode of an axis-aligned box).
 *
 * Algorithm: robust 2D trim → convex hull (Andrew's monotone chain) → rotating calipers
 * (test a rectangle aligned to each hull edge; the min-area one is optimal — a classic result).
 * All O(n log n); cheap enough to run every scan tick.
 */
object FootprintEstimator {

    /**
     * @param width     longer horizontal side (meters).
     * @param depth     shorter horizontal side (meters).
     * @param angleRad  yaw of the width axis in the X/Z plane (atan2(z,x)).
     * @param cornersXZ 4 rectangle corners, packed [x0,z0, x1,z1, x2,z2, x3,z3] (CCW).
     * @param centerX,centerZ rectangle center in world X/Z.
     */
    data class Footprint(
        val width: Float,
        val depth: Float,
        val angleRad: Float,
        val cornersXZ: FloatArray,
        val centerX: Float,
        val centerZ: Float,
    )

    /**
     * @param points   packed world points [x0,y0,z0,…] (the accumulated cloud).
     * @param minPoints below this we don't trust a footprint.
     * @param keepPct  radial percentile of X/Z points kept before hull (drops stragglers).
     * @return the rectangle, or null when degenerate / too few points.
     */
    fun estimate(points: FloatArray, minPoints: Int = 40, keepPct: Float = 0.975f): Footprint? {
        val n = points.size / 3
        if (n < minPoints) return null

        // 1) Project to X/Z.
        val xs = FloatArray(n)
        val zs = FloatArray(n)
        var i = 0
        var j = 0
        while (i < points.size) { xs[j] = points[i]; zs[j] = points[i + 2]; j++; i += 3 }

        // 2) Robust radial trim: drop the farthest (100−keepPct)% from the X/Z centroid so a few
        //    stray depth points can't dominate the enclosing rectangle.
        var mx = 0f; var mz = 0f
        for (k in 0 until n) { mx += xs[k]; mz += zs[k] }
        mx /= n; mz /= n
        val d2 = FloatArray(n) { val dx = xs[it] - mx; val dz = zs[it] - mz; dx * dx + dz * dz }
        val sortedD2 = d2.copyOf().also { it.sort() }
        val limit = sortedD2[(keepPct.coerceIn(0f, 1f) * (n - 1)).toInt()]
        // Deduplicate onto a coarse grid too — hull cost is on unique extreme points anyway.
        val hx = ArrayList<Float>(n)
        val hz = ArrayList<Float>(n)
        for (k in 0 until n) if (d2[k] <= limit) { hx.add(xs[k]); hz.add(zs[k]) }
        if (hx.size < 3) return null

        // 3) Convex hull.
        val hull = convexHull(hx, hz) ?: return null
        val hn = hull.size / 2
        if (hn < 3) return null

        // 4) Rotating calipers: min-area rectangle aligned to some hull edge.
        var bestArea = Float.MAX_VALUE
        var bU = 0f; var bV = 0f          // edge unit direction (u) — perp is (−v, u)... see below
        var bMinU = 0f; var bMaxU = 0f; var bMinV = 0f; var bMaxV = 0f
        for (e in 0 until hn) {
            val ax = hull[e * 2]; val az = hull[e * 2 + 1]
            val bx = hull[((e + 1) % hn) * 2]; val bz = hull[((e + 1) % hn) * 2 + 1]
            var dx = bx - ax; var dz = bz - az
            val len = hypot(dx, dz)
            if (len < 1e-6f) continue
            dx /= len; dz /= len                 // edge unit direction
            // perpendicular = (−dz, dx)
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in 0 until hn) {
                val px = hull[p * 2]; val pz = hull[p * 2 + 1]
                val u = px * dx + pz * dz
                val v = px * (-dz) + pz * dx
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (v < minV) minV = v; if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                bU = dx; bV = dz
                bMinU = minU; bMaxU = maxU; bMinV = minV; bMaxV = maxV
            }
        }
        if (bestArea == Float.MAX_VALUE) return null

        // 5) Reconstruct the 4 corners from the best (u,v) frame. u-axis=(bU,bV), v-axis=(−bV,bU).
        val ux = bU; val uz = bV
        val vx = -bV; val vz = bU
        fun corner(u: Float, v: Float) = floatArrayOf(u * ux + v * vx, u * uz + v * vz)
        val c0 = corner(bMinU, bMinV)
        val c1 = corner(bMaxU, bMinV)
        val c2 = corner(bMaxU, bMaxV)
        val c3 = corner(bMinU, bMaxV)
        val cornersXZ = floatArrayOf(c0[0], c0[1], c1[0], c1[1], c2[0], c2[1], c3[0], c3[1])

        val sideU = bMaxU - bMinU   // extent along u
        val sideV = bMaxV - bMinV   // extent along v
        val width = max(sideU, sideV)
        val depth = min(sideU, sideV)
        // Width axis direction (the longer side) → yaw.
        val angle = if (sideU >= sideV) atan2(uz, ux) else atan2(vz, vx)
        val cx = (c0[0] + c2[0]) * 0.5f
        val cz = (c0[1] + c2[1]) * 0.5f
        return Footprint(width, depth, angle, cornersXZ, cx, cz)
    }

    /**
     * Andrew's monotone-chain convex hull. @return packed CCW hull [x0,z0,…], or null if <3.
     */
    private fun convexHull(xs: List<Float>, zs: List<Float>): FloatArray? {
        val n = xs.size
        if (n < 3) return null
        val idx = (0 until n).sortedWith(compareBy({ xs[it] }, { zs[it] }))
        val hull = IntArray(2 * n)
        var k = 0
        // Lower hull.
        for (ii in 0 until n) {
            val p = idx[ii]
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p, xs, zs) <= 0f) k--
            hull[k++] = p
        }
        // Upper hull.
        val lower = k + 1
        for (ii in n - 2 downTo 0) {
            val p = idx[ii]
            while (k >= lower && cross(hull[k - 2], hull[k - 1], p, xs, zs) <= 0f) k--
            hull[k++] = p
        }
        // k−1 points (last == first). Pack.
        val m = k - 1
        if (m < 3) return null
        val out = FloatArray(m * 2)
        for (t in 0 until m) { out[t * 2] = xs[hull[t]]; out[t * 2 + 1] = zs[hull[t]] }
        return out
    }

    /** Cross product (o→a) × (o→b) sign; >0 means CCW turn. */
    private fun cross(o: Int, a: Int, b: Int, xs: List<Float>, zs: List<Float>): Float {
        val ox = xs[o]; val oz = zs[o]
        return (xs[a] - ox) * (zs[b] - oz) - (zs[a] - oz) * (xs[b] - ox)
    }
}
