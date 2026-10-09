package com.example.arsurvey.segment

import com.example.arsurvey.detect.Detection
import com.example.arsurvey.detect.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests tap-to-select mask containment. Uses a tiny 2×2 prototype grid with 2 channels so two
 * overlapping detections can have deliberately different masks from the same prototype buffer.
 *
 * Grid cells (proto idx): 0=TL, 1=TR, 2=BL, 3=BR. Prototype stride = 640/2 = 320, so an image
 * point maps to cell (x/320, y/320). Channel 0 is true everywhere except BR; channel 1 is true
 * only in TL. Detection A (coeffs [1,0]) → channel 0; Detection B (coeffs [0,1]) → channel 1.
 */
class MaskProcessorTapTest {

    private val protos = floatArrayOf(
        // channel 0: TL, TR, BL, BR
        10f, 10f, 10f, -10f,
        // channel 1: TL only
        10f, -10f, -10f, -10f,
    )

    private fun result(): DetectionResult {
        val a = Detection(0f, 0f, 400f, 400f, 0.9f, 0, "box", floatArrayOf(1f, 0f))   // big
        val b = Detection(0f, 0f, 320f, 320f, 0.8f, 0, "box", floatArrayOf(0f, 1f))   // small
        return DetectionResult(
            detections = listOf(a, b),
            protos = protos,
            protoChannels = 2,
            protoHeight = 2,
            protoWidth = 2,
            letterboxScale = 1f,
            letterboxPadX = 0,
            letterboxPadY = 0,
            imageWidth = 640,
            imageHeight = 640,
        )
    }

    @Test
    fun tap_on_overlap_selects_the_smaller_detection() {
        // (100,100) → TL cell, inside both boxes; both masks true → smaller (B, index 1) wins.
        assertEquals(1, MaskProcessor.pickDetectionAtPoint(result(), 100f, 100f))
    }

    @Test
    fun tap_where_only_the_larger_covers_selects_it() {
        // (360,100) → TR cell; inside A only (x>320 is outside B's box). A's channel-0 TR is true.
        assertEquals(0, MaskProcessor.pickDetectionAtPoint(result(), 360f, 100f))
    }

    @Test
    fun tap_off_all_masks_selects_nothing() {
        // (380,380) → BR cell: inside A's box but A's mask is false there, and B doesn't reach it.
        assertEquals(-1, MaskProcessor.pickDetectionAtPoint(result(), 380f, 380f))
        // (500,500) → outside every detection box entirely.
        assertEquals(-1, MaskProcessor.pickDetectionAtPoint(result(), 500f, 500f))
    }

    @Test
    fun mask_contains_point_respects_the_actual_mask_not_just_the_box() {
        val r = result()
        assertTrue(MaskProcessor.maskContainsPoint(r, 0, 100f, 100f))   // A true at TL
        assertFalse(MaskProcessor.maskContainsPoint(r, 0, 380f, 380f))  // A false at BR (in box)
    }
}
