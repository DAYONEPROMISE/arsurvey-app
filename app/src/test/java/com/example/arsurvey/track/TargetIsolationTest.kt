package com.example.arsurvey.track

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the world-space object-isolation radius gate. */
class TargetIsolationTest {

    @Test
    fun keeps_points_within_radius_and_drops_the_rest() {
        // Anchor at origin; radius 0.5 m. One point on the object, one on a neighbour 1 m away.
        val packed = floatArrayOf(
            0.1f, 0.0f, 0.1f,   // ~0.14 m from anchor → keep
            1.0f, 0.0f, 0.0f,   // 1.0 m from anchor  → drop
        )
        val out = TargetIsolation.filterHorizontalRadius(packed, 0f, 0f, 0.5f)
        assertEquals(3, out.size)
        assertEquals(0.1f, out[0])
        assertEquals(0.1f, out[2])
    }

    @Test
    fun ignores_vertical_distance() {
        // A point directly above the anchor (large Y) is still on the object horizontally → keep.
        val packed = floatArrayOf(0.0f, 5.0f, 0.0f)
        val out = TargetIsolation.filterHorizontalRadius(packed, 0f, 0f, 0.5f)
        assertEquals(3, out.size)
    }

    @Test
    fun empty_input_returns_empty() {
        val out = TargetIsolation.filterHorizontalRadius(FloatArray(0), 0f, 0f, 0.5f)
        assertEquals(0, out.size)
    }
}
