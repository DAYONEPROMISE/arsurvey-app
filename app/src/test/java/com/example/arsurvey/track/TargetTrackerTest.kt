package com.example.arsurvey.track

import com.example.arsurvey.detect.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure target-tracking logic. These run on the JVM (no device) and pin the
 * behaviours the object-isolation milestone depends on: lock persistence, no cross-object
 * switching, nearest-of-same-class selection, tolerance of brief loss, and reacquisition.
 */
class TargetTrackerTest {

    private val imgW = 640
    private val imgH = 480

    /** Box centered on the image (what the user frames before tapping Scan). */
    private fun centerBox(cls: Int = BOX, score: Float = 0.9f) =
        det(280f, 200f, 360f, 280f, cls, score, "box")

    private fun det(
        x1: Float, y1: Float, x2: Float, y2: Float,
        cls: Int, score: Float, label: String,
    ) = Detection(x1, y1, x2, y2, score, cls, label)

    @Test
    fun acquires_object_at_screen_center() {
        val t = TargetTracker()
        t.arm()
        // Two candidates: one at center, one off to the side. The centered one is the target.
        val offside = det(500f, 340f, 600f, 440f, BOX, 0.95f, "box")
        val idx = t.update(listOf(offside, centerBox()), imgW, imgH)
        assertEquals(1, idx)
        assertEquals(TargetTracker.LockState.LOCKED, t.state)
    }

    @Test
    fun keeps_lock_on_same_target_across_movement() {
        val t = TargetTracker()
        t.arm()
        t.update(listOf(centerBox()), imgW, imgH)
        // Same box, shifted as the phone pans — should stay locked to index 0.
        val moved = det(300f, 210f, 380f, 290f, BOX, 0.85f, "box")
        val idx = t.update(listOf(moved), imgW, imgH)
        assertEquals(0, idx)
        assertEquals(TargetTracker.LockState.LOCKED, t.state)
    }

    @Test
    fun does_not_switch_to_different_class_object() {
        val t = TargetTracker()
        t.arm()
        t.update(listOf(centerBox()), imgW, imgH)
        // A chair appears (different class), bigger and higher confidence, but off to the side.
        val chair = det(440f, 260f, 620f, 460f, CHAIR, 0.98f, "chair")
        val boxMoved = det(285f, 205f, 365f, 285f, BOX, 0.6f, "box")
        val idx = t.update(listOf(chair, boxMoved), imgW, imgH)
        assertEquals("must stay on the box, not jump to the chair", 1, idx)
        assertEquals(BOX_LABEL, t.targetLabel)
    }

    @Test
    fun picks_nearest_of_two_same_class_objects() {
        val t = TargetTracker()
        t.arm()
        t.update(listOf(centerBox()), imgW, imgH)
        // Two boxes now: B is bigger + higher confidence but far; A is where the target was.
        val boxAnear = det(290f, 205f, 370f, 285f, BOX, 0.6f, "box")
        val boxBfar = det(470f, 300f, 640f, 470f, BOX, 0.97f, "box")
        val idx = t.update(listOf(boxBfar, boxAnear), imgW, imgH)
        assertEquals("nearest same-class box wins over the bigger, higher-conf far one", 1, idx)
    }

    @Test
    fun brief_loss_pauses_but_does_not_switch() {
        val t = TargetTracker()
        t.arm()
        t.update(listOf(centerBox()), imgW, imgH)
        // Target undetected for several frames: each returns -1 (accumulation paused).
        repeat(3) {
            assertEquals(-1, t.update(emptyList(), imgW, imgH))
        }
        assertEquals(TargetTracker.LockState.TEMPORARILY_LOST, t.state)
    }

    @Test
    fun reacquires_original_target_after_loss() {
        val t = TargetTracker()
        t.arm()
        t.update(listOf(centerBox()), imgW, imgH)
        repeat(3) { t.update(emptyList(), imgW, imgH) }
        assertEquals(TargetTracker.LockState.TEMPORARILY_LOST, t.state)
        // Original target reappears near where it was → reacquired, not replaced.
        val idx = t.update(listOf(centerBox(score = 0.8f)), imgW, imgH)
        assertNotEquals(-1, idx)
        assertEquals(TargetTracker.LockState.LOCKED, t.state)
    }

    @Test
    fun resists_higher_confidence_impostor_of_same_class() {
        val t = TargetTracker()
        t.arm()
        t.update(listOf(centerBox(score = 0.65f)), imgW, imgH)
        // Impostor box B: much higher confidence, but far from the tracked world/screen position.
        val impostor = det(480f, 320f, 620f, 460f, BOX, 0.95f, "box")
        val realTarget = det(285f, 205f, 365f, 285f, BOX, 0.65f, "box")
        val idx = t.update(listOf(impostor, realTarget), imgW, imgH)
        assertEquals(1, idx)
    }

    @Test
    fun tap_lock_holds_that_object_and_resists_a_bigger_neighbour() {
        val t = TargetTracker()
        // User tapped box B (small, off-center) — lockTo forces it as the identity directly.
        val tapped = det(440f, 300f, 520f, 380f, BOX, 0.7f, "box")
        t.lockTo(tapped)
        assertEquals(TargetTracker.LockState.LOCKED, t.state)
        assertEquals(BOX_LABEL, t.targetLabel)
        // Next frame: the tapped box moved slightly, plus a big centered box appears. Stay on the
        // tapped one, not the bigger/more-central newcomer.
        val tappedMoved = det(445f, 305f, 525f, 385f, BOX, 0.7f, "box")
        val bigCentered = det(240f, 160f, 420f, 340f, BOX, 0.95f, "box")
        val idx = t.update(listOf(bigCentered, tappedMoved), imgW, imgH)
        assertEquals(1, idx)
    }

    @Test
    fun preview_before_arming_does_not_hold_identity() {
        val t = TargetTracker()
        // Not armed: passive preview picks the centered object but stores no identity.
        val idx = t.update(listOf(centerBox()), imgW, imgH)
        assertEquals(0, idx)
        assertEquals(TargetTracker.LockState.NO_TARGET, t.state)
        assertEquals(null, t.targetLabel)
    }

    private companion object {
        const val BOX = 0
        const val CHAIR = 56
        const val BOX_LABEL = "box"
    }
}
