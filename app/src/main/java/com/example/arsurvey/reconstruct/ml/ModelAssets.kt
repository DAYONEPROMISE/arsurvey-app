package com.example.arsurvey.reconstruct.ml

import android.content.Context

/**
 * Central asset inventory for on-device ML models.
 * Gives a designed, actionable error instead of a raw exception when
 * weights are missing (they are gitignored — see README assets/models/).
 */
object ModelAssets {
    const val YOLOE = "models/yoloe-11s-seg-pf.onnx"
    const val SAM_ENCODER = "models/efficient_sam_vits_encoder.onnx"
    const val SAM_DECODER = "models/efficient_sam_vits_decoder.onnx"
    const val YOLO_SEG = "yolo11n-seg.onnx"

    val REQUIRED = listOf(YOLOE, SAM_ENCODER, SAM_DECODER)

    /** Returns the subset of [REQUIRED] not present in APK assets. */
    fun missing(context: Context): List<String> {
        val available = try {
            (context.assets.list("models") ?: emptyArray()).toSet()
        } catch (t: Throwable) { emptySet<String>() }
        // YOLO_SEG lives at assets root for the legacy live path.
        val rootAvailable = try {
            (context.assets.list("") ?: emptyArray()).toSet()
        } catch (t: Throwable) { emptySet<String>() }
        val out = mutableListOf<String>()
        for (path in REQUIRED) {
            val name = path.substringAfterLast("/")
            val dir = path.substringBeforeLast("/", "")
            val present = if (dir == "models") name in available else path in rootAvailable
            if (!present) out.add(path)
        }
        return out
    }

    fun missingMessage(missing: List<String>): String =
        "Measurement models aren't bundled (${missing.joinToString(", ")}). " +
            "See README assets/models/ — place the *.onnx weights there and rebuild."
}
