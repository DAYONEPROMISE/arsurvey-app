package com.example.arsurvey.reconstruct.ml

import java.nio.FloatBuffer

/**
 * Decodes the YOLOE `output0` tensor into normalized [Detection]s. Pure and framework-free
 * (operates on a [FloatBuffer] + its shape). Ported verbatim from prod
 * `data/ml/yolo/YoloPostprocessor.kt`.
 *
 * Tensor layout (Ultralytics segment export, `nms=False`): channels-first `[channels, anchors]`,
 * each anchor's channels are `[cx, cy, w, h, class_0 … class_{nc-1}, maskCoeff_0 … maskCoeff_31]`
 * with box geometry in **model-input pixels** (letterboxed space). The 32 mask coefficients are
 * ignored — masks come from EfficientSAM. NMS is class-**agnostic**: the prompt-free model often
 * tags one physical object with several near-synonym labels, so suppressing across classes yields
 * one clean box per object.
 */
class YoloPostprocessor(
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE,
    private val iouThreshold: Float = DEFAULT_IOU,
    private val maxDetections: Int = DEFAULT_MAX_DETECTIONS,
    private val maskCoefficients: Int = MASK_COEFFICIENTS,
) {

    fun parse(
        output: FloatBuffer,
        channels: Int,
        anchors: Int,
        labels: List<String>,
        transform: LetterboxTransform,
    ): List<Detection> {
        val classCount = minOf(labels.size, channels - BOX_CHANNELS - maskCoefficients)
        if (classCount <= 0) return emptyList()

        val boxes = ArrayList<BoundingBox>()
        val scores = ArrayList<Float>()
        val classes = ArrayList<Int>()

        // Channels-first indexing: value(channel, anchor) = output[channel * anchors + anchor].
        val cxRow = 0
        val cyRow = anchors
        val wRow = 2 * anchors
        val hRow = 3 * anchors
        val classBase = BOX_CHANNELS * anchors

        for (a in 0 until anchors) {
            var bestScore = confidenceThreshold
            var bestClass = -1
            var offset = classBase + a
            for (c in 0 until classCount) {
                val score = output.get(offset)
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
                offset += anchors
            }
            if (bestClass < 0) continue

            val cx = output.get(cxRow + a)
            val cy = output.get(cyRow + a)
            val w = output.get(wRow + a)
            val h = output.get(hRow + a)
            val left = transform.normalizeX(cx - w / 2f)
            val right = transform.normalizeX(cx + w / 2f)
            val top = transform.normalizeY(cy - h / 2f)
            val bottom = transform.normalizeY(cy + h / 2f)
            if (right <= left || bottom <= top) continue

            boxes += BoundingBox(left, top, right, bottom)
            scores += bestScore
            classes += bestClass
        }

        if (boxes.isEmpty()) return emptyList()

        val kept = Nms.keep(boxes, scores.toFloatArray(), iouThreshold, maxDetections)
        return kept.map { i ->
            Detection(
                label = labels[classes[i]],
                confidence = scores[i].coerceIn(0f, 1f),
                box = boxes[i],
            )
        }
    }

    private companion object {
        const val BOX_CHANNELS = 4
        const val MASK_COEFFICIENTS = 32
        const val DEFAULT_CONFIDENCE = 0.25f
        const val DEFAULT_IOU = 0.45f
        const val DEFAULT_MAX_DETECTIONS = 100
    }
}
