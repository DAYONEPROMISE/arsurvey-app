package com.example.arsurvey.track

import com.example.arsurvey.detect.Detection
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Target-locking / target-tracking stage (object-isolation milestone).
 *
 * The measurement pipeline accumulates masked world points from many viewpoints. If the
 * chosen object silently switches between frames — or a misdetection with a large box is
 * picked — depth from *other* furniture contaminates the accumulator and the bounding
 * geometry explodes (observed: a 37 cm box measured as 6 m). This class keeps the scan
 * focused on **one** physical object for the whole scan.
 *
 * Scope: this is purely the *identity* decision — "which detection, if any, is my locked
 * target this frame". It works in the detector's **image-pixel space** (the space
 * [Detection] lives in) using cheap scalar signals only. It deliberately does NOT touch the
 * measurement algorithm; the caller feeds only the selected detection's mask downstream, and
 * a separate world-space radius gate ([TargetIsolation]) is the second line of defence.
 *
 * Why not class alone: two boxes both report `class = box`, so `classId ==` is insufficient
 * (a scan of box A must not jump to box B). Matching therefore combines class, bounding-box
 * IoU and screen-center distance. Because the user intentionally walks around the object, the
 * bbox/center are *tracking signals*, not hard requirements — class dropout and large
 * screen-space motion are both tolerated.
 *
 * Threading: [update] is only ever called from the single-flight inference coroutine (never
 * concurrently), and [arm]/[reset] from the render loop. The render loop reads the debug
 * snapshot; those reads are of independent immutable-ish scalars and are benign.
 *
 * All logic here is Android-free so it can be unit-tested on the JVM.
 */
class TargetTracker {

    enum class LockState {
        /** Not scanning — no persistent identity; [update] returns a best-effort preview pick. */
        NO_TARGET,

        /** Scan armed, still waiting for a detection at screen center to lock onto. */
        ACQUIRING,

        /** Confidently tracking the original target; its mask feeds the pipeline. */
        LOCKED,

        /** Target not confidently matched for a few frames — accumulation is paused, not switched. */
        TEMPORARILY_LOST,
    }

    /** The persistent identity of the locked object, in image-pixel space. */
    private data class Identity(
        val classId: Int,
        val label: String,
        var x1: Float, var y1: Float, var x2: Float, var y2: Float,
    ) {
        val cx get() = (x1 + x2) * 0.5f
        val cy get() = (y1 + y2) * 0.5f
    }

    var state: LockState = LockState.NO_TARGET
        private set
    private var identity: Identity? = null
    private var missed: Int = 0

    // ---- Debug snapshot (last update), for the HUD / logs. Plain scalars. -----------------
    var lastScore: Float = 0f; private set
    var lastBboxIou: Float = 0f; private set
    /** Center distance normalized by the image diagonal (0 = same point, ~1 = opposite corner). */
    var lastCenterDist: Float = 0f; private set
    var lastClassMatch: Boolean = false; private set
    var candidateCount: Int = 0; private set
    var selectedIndex: Int = -1; private set
    /** Class label of the locked identity, or null when none. */
    val targetLabel: String? get() = identity?.label

    /** Begin a scan: arm the tracker so the next [update] locks onto the object at screen center. */
    fun arm() {
        state = LockState.ACQUIRING
        identity = null
        missed = 0
        selectedIndex = -1
    }

    /**
     * Explicit tap-to-select: lock directly onto [d] as the persistent identity. Used when the
     * user taps an object (the preferred selection path) — the identity is the object the user
     * pointed at, not "whatever is largest/centered". Its world geometry is anchored separately
     * by the caller; this only fixes the image-space identity used for per-frame matching.
     */
    fun lockTo(d: Detection) {
        identity = Identity(d.classId, d.label, d.x1, d.y1, d.x2, d.y2)
        state = LockState.LOCKED
        missed = 0
        lastScore = 1f; lastClassMatch = true; lastBboxIou = 1f; lastCenterDist = 0f
        logLock("TARGET_SELECTED", d, 1f)
    }

    /** End/abort a scan: drop the identity entirely (back to passive preview). */
    fun reset() {
        state = LockState.NO_TARGET
        identity = null
        missed = 0
        selectedIndex = -1
        lastScore = 0f; lastBboxIou = 0f; lastCenterDist = 0f; lastClassMatch = false
    }

    /**
     * Decide which detection is the target this frame.
     *
     * @return index into [dets] of the locked target's detection, or **-1** to select nothing
     *   (no mask → accumulation pauses). Returning -1 while armed is the safe choice: it is far
     *   better to add fewer points than to add points from the wrong object.
     */
    fun update(dets: List<Detection>, imageW: Int, imageH: Int): Int {
        candidateCount = dets.size
        val diag = max(1f, hypot(imageW.toFloat(), imageH.toFloat()))
        val cxImg = imageW * 0.5f
        val cyImg = imageH * 0.5f

        return when (state) {
            // Passive preview while not scanning: point at whatever is nearest screen center so
            // the user can see the mask of the object they're about to lock. No identity stored.
            LockState.NO_TARGET -> {
                val idx = nearestCenter(dets, cxImg, cyImg)
                selectedIndex = idx
                idx
            }

            // First lock: grab the detection nearest screen center (confidence breaks ties). This
            // matches the UX — the user frames the object and taps Scan.
            LockState.ACQUIRING -> {
                val idx = nearestCenter(dets, cxImg, cyImg)
                if (idx >= 0) {
                    lockOnto(dets[idx])
                    state = LockState.LOCKED
                    missed = 0
                    lastScore = 1f; lastClassMatch = true; lastBboxIou = 1f; lastCenterDist = 0f
                    logLock("TARGET_ACQUIRED", dets[idx], 1f)
                }
                selectedIndex = idx
                idx
            }

            LockState.LOCKED, LockState.TEMPORARILY_LOST -> matchAgainstIdentity(dets, diag)
        }
    }

    /** Score every candidate against the locked identity and keep it, pause, or reacquire. */
    private fun matchAgainstIdentity(dets: List<Detection>, diag: Float): Int {
        val id = identity ?: run { selectedIndex = -1; return -1 }

        var bestIdx = -1
        var bestScore = -1f
        var bestIou = 0f
        var bestCenter = 1f
        var bestClass = false
        dets.forEachIndexed { i, d ->
            val iou = iou(d, id)
            val centerDist = (hypot(d.cx - id.cx, d.cy - id.cy) / diag).coerceIn(0f, 1f)
            val classMatch = d.classId == id.classId
            // Normalized 0..1 terms. Class match strongly discourages jumping to a *different*
            // object, while still tolerating occasional misclassification of the real target
            // (the bbox/center terms can carry a same-position candidate over the threshold).
            val classScore = if (classMatch) 1f else 0f
            val centerScore = 1f - centerDist
            val score = CLASS_W * classScore + BBOX_W * iou + CENTER_W * centerScore +
                // Tiny confidence nudge so a crisp detection edges out a marginal one.
                CONF_W * d.score
            if (score > bestScore) {
                bestScore = score; bestIdx = i
                bestIou = iou; bestCenter = centerDist; bestClass = classMatch
            }
        }

        val threshold = if (state == LockState.LOCKED) ACCEPT_THRESHOLD else REACQUIRE_THRESHOLD
        return if (bestIdx >= 0 && bestScore >= threshold) {
            val wasLost = state == LockState.TEMPORARILY_LOST
            updateIdentity(dets[bestIdx])
            state = LockState.LOCKED
            missed = 0
            lastScore = bestScore; lastBboxIou = bestIou; lastCenterDist = bestCenter
            lastClassMatch = bestClass
            selectedIndex = bestIdx
            if (wasLost) logLock("TARGET_REACQUIRED", dets[bestIdx], bestScore)
            else logMatch(bestScore, bestIou, bestCenter)
            bestIdx
        } else {
            // Not confident enough. Pause (return nothing) rather than risk the wrong object.
            missed++
            if (missed >= MAX_MISSED && state == LockState.LOCKED) {
                state = LockState.TEMPORARILY_LOST
                logSimple("TARGET_LOST")
            }
            lastScore = max(0f, bestScore)
            lastBboxIou = bestIou; lastCenterDist = bestCenter; lastClassMatch = bestClass
            selectedIndex = -1
            if (bestIdx >= 0) logSimple("TARGET_REJECTED score=${"%.2f".format(bestScore)}")
            -1
        }
    }

    /** Detection nearest the image center with at least a minimal confidence, or -1. */
    private fun nearestCenter(dets: List<Detection>, cx: Float, cy: Float): Int {
        var bestIdx = -1
        var bestD = Float.MAX_VALUE
        var bestScore = 0f
        dets.forEachIndexed { i, d ->
            if (d.score < MIN_ACQUIRE_CONF) return@forEachIndexed
            val dist = hypot(d.cx - cx, d.cy - cy)
            // Nearer wins; on a near-tie the higher-confidence detection wins.
            if (dist < bestD - CENTER_TIE_PX || (dist < bestD + CENTER_TIE_PX && d.score > bestScore)) {
                bestD = dist; bestIdx = i; bestScore = d.score
            }
        }
        return bestIdx
    }

    private fun lockOnto(d: Detection) {
        identity = Identity(d.classId, d.label, d.x1, d.y1, d.x2, d.y2)
    }

    /** EMA the stored box toward the matched detection so it follows the object as it moves. */
    private fun updateIdentity(d: Detection) {
        val id = identity ?: return
        id.x1 = lerp(id.x1, d.x1); id.y1 = lerp(id.y1, d.y1)
        id.x2 = lerp(id.x2, d.x2); id.y2 = lerp(id.y2, d.y2)
        // Class label is intentionally NOT overwritten — the original identity is authoritative,
        // so a one-frame misclassification can't quietly redefine what we're tracking.
    }

    private fun lerp(a: Float, b: Float) = a + BOX_EMA * (b - a)

    private fun iou(d: Detection, id: Identity): Float {
        val ix1 = max(d.x1, id.x1); val iy1 = max(d.y1, id.y1)
        val ix2 = min(d.x2, id.x2); val iy2 = min(d.y2, id.y2)
        val iw = ix2 - ix1; val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = d.area + (id.x2 - id.x1) * (id.y2 - id.y1) - inter
        return if (union <= 0f) 0f else (inter / union).coerceIn(0f, 1f)
    }

    // ---- Logging (identity-level only; never per-pixel/point). ----------------------------
    private fun logLock(tag: String, d: Detection, score: Float) =
        log("$tag class=${d.label} score=${"%.2f".format(score)}")

    private fun logMatch(score: Float, iou: Float, center: Float) =
        log("TARGET_MATCH score=${"%.2f".format(score)} bboxIoU=${"%.2f".format(iou)} " +
            "centerDist=${"%.2f".format(center)}")

    private fun logSimple(msg: String) = log(msg)

    private fun log(msg: String) {
        // android.util.Log is unavailable under plain-JVM unit tests; guard so tests stay pure.
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "TargetTracker"

        // Scoring weights (sum ≈ 1 before the small confidence nudge). Bbox overlap is the
        // strongest tracking cue; class prevents cross-object jumps; center anchors it in view.
        private const val CLASS_W = 0.30f
        private const val BBOX_W = 0.40f
        private const val CENTER_W = 0.30f
        private const val CONF_W = 0.05f

        /** Keep the lock while the best match clears this. Lenient — the phone moves a lot. */
        private const val ACCEPT_THRESHOLD = 0.35f
        /** Stricter bar to resume after a loss, so we don't grab a nearby impostor. */
        private const val REACQUIRE_THRESHOLD = 0.45f
        /** Consecutive low-confidence frames before we flip to TEMPORARILY_LOST (accumulation
         *  already pauses on the very first low-confidence frame regardless). */
        private const val MAX_MISSED = 3

        /** Minimum YOLO confidence for a detection to be eligible as the initial lock. */
        private const val MIN_ACQUIRE_CONF = 0.25f
        /** Center-distance dead-band (px) within which confidence, not distance, breaks the tie. */
        private const val CENTER_TIE_PX = 24f
        /** Bounding-box tracking inertia: 0 = frozen identity, 1 = snap to latest detection. */
        private const val BOX_EMA = 0.5f
    }
}
