package com.example.arsurvey.reconstruct.ml

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import com.example.arsurvey.segment.Mask

/**
 * Ties YOLOE detection + single-target selection + EfficientSAM segmentation into the one call the
 * reconstruction needs: image in → the target object's full-frame [Mask] out. Owns the shared
 * [OrtEnvironment] and both engines; construct once per reconstruction run and [close] when done.
 *
 * This replaces the reconstruction path's old `YoloSegDetector` (80 COCO classes, couldn't name a
 * cardboard box). Only *which mask* feeds the point-cloud build changes — everything downstream
 * (back-projection, viewer, stats) is untouched.
 */
class SegmentationPipeline(context: Context) {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detector = YoloeDetector(environment, context)
    private val segmenter = EfficientSamSegmenter(environment, context)

    /** Outcome for one image: all detections (for logging), the chosen target, and its mask. */
    data class Result(
        val detections: List<Detection>,
        val target: Detection?,
        val mask: Mask?,
    )

    /** Detect → pick the single target object → segment it into a full-frame mask. */
    fun run(image: ProcessingImage): Result {
        val detections = detector.detect(image)
        if (detections.isEmpty()) return Result(detections, null, null)
        val target = pickTarget(detections)
        val mask = segmenter.segment(image, target.box)
        return Result(detections, target, mask)
    }

    /**
     * One object: prefer detections whose normalized box **contains the image center** (0.5,0.5) —
     * the user framed the object centrally — taking the highest-confidence such box; otherwise the
     * box whose center is nearest the image center.
     */
    private fun pickTarget(detections: List<Detection>): Detection {
        val cx = 0.5f
        val cy = 0.5f
        val containing = detections.filter {
            it.box.left <= cx && it.box.right >= cx && it.box.top <= cy && it.box.bottom >= cy
        }
        if (containing.isNotEmpty()) return containing.maxByOrNull { it.confidence }!!
        return detections.minByOrNull {
            val bx = (it.box.left + it.box.right) / 2f
            val by = (it.box.top + it.box.bottom) / 2f
            val dx = bx - cx
            val dy = by - cy
            dx * dx + dy * dy
        }!!
    }

    fun close() {
        detector.close()
        segmenter.close()
    }
}
