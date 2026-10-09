package com.example.arsurvey.reconstruct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the post-capture dimension glue. Feeds a synthetic point cloud sampled on the
 * surface of a box of KNOWN size (all Android-free) and checks the reported W×H×D — this exercises
 * the whole reused measure/ stack (outlier reject → floor/height → min-area footprint → assemble)
 * through [MeasurementCalculator].
 */
class MeasurementCalculatorTest {

    /** Tolerance: the floor/top percentiles + footprint trimming can shave a couple of cm. */
    private val tolCm = 6f

    @Test
    fun measures_a_known_axis_aligned_box() {
        // 0.60 (X) × 0.40 (Y, height) × 0.30 (Z) box sitting on the floor (Y in 0..0.40).
        val pts = boxSurface(width = 0.60f, height = 0.40f, depth = 0.30f, n = 14)
        // Split across 3 photos to mimic a 3-view capture.
        val result = resultOf(splitIntoViews(pts, 3))

        val prediction = MeasurementCalculator.compute(result)
        val m = prediction.measurement
        assertNotNull("expected a measurement for a dense box cloud", m)
        m!!

        assertEquals("width", 60f, m.widthCm, tolCm)
        assertEquals("height", 40f, m.heightCm, tolCm)
        assertEquals("depth", 30f, m.depthCm, tolCm)
        assertEquals(3, prediction.viewsUsed)
        assertEquals(3, prediction.totalViews)
        assertNotNull("box for overlay", prediction.box)
        assertTrue("confidence in (0,1]", m.confidence > 0f && m.confidence <= 1f)
        assertEquals("coverage = 3/3", 1f, m.coverage, 1e-4f)
    }

    @Test
    fun width_is_the_longer_horizontal_side_regardless_of_orientation() {
        // Same box rotated 35° about vertical: the min-area rectangle must still recover 60×30,
        // not an inflated axis-aligned footprint.
        val pts = boxSurface(0.60f, 0.40f, 0.30f, n = 16)
        rotateAboutY(pts, Math.toRadians(35.0).toFloat())
        val prediction = MeasurementCalculator.compute(resultOf(splitIntoViews(pts, 3)))
        val m = prediction.measurement!!
        assertEquals("width (longer side)", 60f, m.widthCm, tolCm)
        assertEquals("depth (shorter side)", 30f, m.depthCm, tolCm)
        assertEquals("height", 40f, m.heightCm, tolCm)
    }

    @Test
    fun too_few_points_yields_no_measurement() {
        val sparse = floatArrayOf(
            0f, 0f, 0f, 0.1f, 0.1f, 0.1f, 0.2f, 0.0f, 0.1f, 0.05f, 0.2f, 0.05f,
        ) // 4 points, well under the estimators' minimum
        val prediction = MeasurementCalculator.compute(resultOf(listOf(sparse)))
        assertNull("no box from a handful of points", prediction.measurement)
        assertNotNull("a reason is given", prediction.note)
    }

    @Test
    fun clustered_viewpoints_yield_no_measurement() {
        // Same dense box, but all 3 cameras within ~5° of each other: single-view in disguise.
        val pts = boxSurface(width = 0.60f, height = 0.40f, depth = 0.30f, n = 14)
        val views = splitIntoViews(pts, 3)
        val cams = listOf(
            floatArrayOf(0f, 0.3f, 1.5f),
            floatArrayOf(0.05f, 0.3f, 1.5f),
            floatArrayOf(-0.05f, 0.32f, 1.5f),
        )
        val prediction = MeasurementCalculator.compute(resultOf(views, cams))
        assertNull("clustered viewpoints must not produce a measurement", prediction.measurement)
        assertTrue(
            "reason names the spread problem, was: ${prediction.note}",
            (prediction.note ?: "").contains("spread", ignoreCase = true),
        )
    }

    @Test
    fun spread_viewpoints_yield_a_measurement() {
        // Same box with ~90°-separated cameras: the guard must let it through.
        val pts = boxSurface(width = 0.60f, height = 0.40f, depth = 0.30f, n = 14)
        val views = splitIntoViews(pts, 3)
        val cams = listOf(
            floatArrayOf(0f, 0.3f, 1.5f),
            floatArrayOf(1.5f, 0.3f, 0f),
            floatArrayOf(-1.5f, 0.3f, 0f),
        )
        val prediction = MeasurementCalculator.compute(resultOf(views, cams))
        assertNotNull("spread viewpoints should measure", prediction.measurement)
    }

    // --- helpers ---

    /** Packs a [ReconstructionResult] from per-view packed clouds (bounds computed by the model). */
    private fun resultOf(views: List<FloatArray>, cams: List<FloatArray?>? = null): ReconstructionResult {
        val perPhoto = views.mapIndexed { i, w ->
            PhotoCloud(
                index = i, worldPoints = w, maskPixelCount = w.size / 3,
                validDepthCount = w.size / 3, pointCount = w.size / 3,
                depthMinMm = 0, depthMedianMm = 0, depthMaxMm = 0, note = null,
                cameraPosition = cams?.getOrNull(i),
            )
        }
        return ReconstructionResult.from(perPhoto)
    }

    /** Round-robins a packed cloud into [k] separate packed clouds. */
    private fun splitIntoViews(pts: FloatArray, k: Int): List<FloatArray> {
        val buckets = Array(k) { ArrayList<Float>() }
        var i = 0
        var p = 0
        while (i + 2 < pts.size) {
            val b = buckets[p % k]
            b.add(pts[i]); b.add(pts[i + 1]); b.add(pts[i + 2])
            p++; i += 3
        }
        return buckets.map { it.toFloatArray() }
    }

    /** Rotates a packed cloud about the vertical (Y) axis in place. */
    private fun rotateAboutY(pts: FloatArray, rad: Float) {
        val c = Math.cos(rad.toDouble()).toFloat()
        val s = Math.sin(rad.toDouble()).toFloat()
        var i = 0
        while (i + 2 < pts.size) {
            val x = pts[i]; val z = pts[i + 2]
            pts[i] = c * x + s * z
            pts[i + 2] = -s * x + c * z
            i += 3
        }
    }

    /**
     * Samples the 6 faces of a box (centered on X/Z, resting on Y=0) at an n×n grid per face, so the
     * top and bottom faces pin the floor/top percentiles at exactly 0 and [height].
     */
    private fun boxSurface(width: Float, height: Float, depth: Float, n: Int): FloatArray {
        val hx = width / 2f
        val hz = depth / 2f
        val out = ArrayList<Float>(6 * n * n * 3)
        fun add(x: Float, y: Float, z: Float) { out.add(x); out.add(y); out.add(z) }
        for (a in 0 until n) for (b in 0 until n) {
            val u = a / (n - 1f)
            val v = b / (n - 1f)
            val x = -hx + u * width
            val y = v * height
            val z = -hz + u * depth
            // Side faces (vary X/Y, fixed Z=±hz).
            add(x, y, -hz); add(x, y, hz)
            // Front/back faces (vary Z/Y, fixed X=±hx).
            add(-hx, y, z); add(hx, y, z)
            // Top/bottom faces (vary X/Z, fixed Y=0 and Y=height).
            add(x, 0f, -hz + v * depth); add(x, height, -hz + v * depth)
        }
        return out.toFloatArray()
    }
}
