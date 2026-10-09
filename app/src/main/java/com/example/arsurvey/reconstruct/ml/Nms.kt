package com.example.arsurvey.reconstruct.ml

import kotlin.math.max
import kotlin.math.min

/**
 * Greedy class-agnostic Non-Maximum Suppression over normalized boxes. Returns the indices to keep,
 * ordered by descending score, suppressing any lower-scoring box whose IoU with a kept box exceeds
 * [iouThreshold]. Ported verbatim from prod `data/ml/Nms.kt`.
 */
internal object Nms {

    fun keep(
        boxes: List<BoundingBox>,
        scores: FloatArray,
        iouThreshold: Float,
        maxKeep: Int = Int.MAX_VALUE,
    ): List<Int> {
        require(boxes.size == scores.size) { "boxes and scores must align" }
        val order = boxes.indices.sortedByDescending { scores[it] }
        val suppressed = BooleanArray(boxes.size)
        val kept = ArrayList<Int>(min(boxes.size, if (maxKeep == Int.MAX_VALUE) boxes.size else maxKeep))

        for (i in order) {
            if (suppressed[i]) continue
            kept += i
            if (kept.size >= maxKeep) break
            for (j in order) {
                if (j == i || suppressed[j]) continue
                if (iou(boxes[i], boxes[j]) > iouThreshold) suppressed[j] = true
            }
        }
        return kept
    }

    /** Intersection-over-union of two boxes (0 when they don't overlap). */
    fun iou(a: BoundingBox, b: BoundingBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = interW * interH
        if (intersection <= 0f) return 0f
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
