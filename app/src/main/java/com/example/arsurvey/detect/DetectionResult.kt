package com.example.arsurvey.detect

/**
 * Everything one inference produced: the [detections] (each with its 32 mask
 * coefficients) plus the frame's raw **mask prototypes** and the letterbox metadata
 * needed to map between spaces. Milestone 4's [com.example.arsurvey.segment.MaskProcessor]
 * combines a detection's coefficients with these prototypes to build its instance mask.
 *
 * `protos` is `[protoChannels * protoHeight * protoWidth]` (32×160×160), channel-major:
 * `proto(c,y,x) = protos[c*protoHeight*protoWidth + y*protoWidth + x]`.
 *
 * Note: `protos` may be a buffer the detector **reuses** next frame — consume it before
 * the next `detect()` (the ViewModel does, single-flight on one thread).
 */
class DetectionResult(
    val detections: List<Detection>,
    val protos: FloatArray,
    val protoChannels: Int,
    val protoHeight: Int,
    val protoWidth: Int,
    /** Letterbox: image→640 is `x*scale + pad`; invert with `(x−pad)/scale`. */
    val letterboxScale: Float,
    val letterboxPadX: Int,
    val letterboxPadY: Int,
    val imageWidth: Int,
    val imageHeight: Int,
)
