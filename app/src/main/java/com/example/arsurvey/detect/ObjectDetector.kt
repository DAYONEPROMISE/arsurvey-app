package com.example.arsurvey.detect

import com.example.arsurvey.ar.YuvImage

/**
 * A swappable object detector. One implementation for now ([YoloSegDetector]); the
 * interface keeps the ViewModel decoupled from ONNX Runtime and lets later milestones
 * (or a different model) drop in without touching the orchestration layer.
 */
interface ObjectDetector {
    /** Human-readable backend note for the HUD, e.g. "NNAPI" or "CPU". */
    val backend: String

    /** Runs detection on one frame. Call off the main thread — it's heavy. */
    fun detect(image: YuvImage): DetectionResult

    /** Releases the native ONNX session. */
    fun close()
}
