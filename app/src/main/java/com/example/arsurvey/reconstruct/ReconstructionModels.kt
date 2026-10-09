package com.example.arsurvey.reconstruct

/**
 * One YOLOE detection kept for the debug overlay: its normalized box (0..1 in the **raw** captured
 * JPEG's coordinate space), the label + confidence, and whether it was the object the pipeline chose
 * to segment.
 */
data class DetectionBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String,
    val confidence: Float,
    val isTarget: Boolean,
)

/**
 * One photo's contribution to the combined reconstruction: its world-space points plus the
 * per-stage statistics the task asks us to surface, so a failing stage is visible (not hidden). Also
 * carries the debug-overlay inputs (source image, YOLOE boxes, EfficientSAM mask) so the viewer can
 * show *what was detected/segmented* on each image, not just the resulting cloud.
 */
data class PhotoCloud(
    /** 0-based photo index within the object. */
    val index: Int,
    /** Packed world-space points [x0,y0,z0, x1,y1,z1, …]; empty when nothing was reconstructed. */
    val worldPoints: FloatArray,
    /** Number of pixels inside the EfficientSAM object mask (mask-grid resolution). */
    val maskPixelCount: Int,
    /** Depth samples inside the mask with a valid (non-zero) reading (depth resolution). */
    val validDepthCount: Int,
    /** Number of generated 3D points ( = worldPoints.size / 3 ). */
    val pointCount: Int,
    /** Min / median / max of the valid masked depth readings, in millimeters (0 when none). */
    val depthMinMm: Int,
    val depthMedianMm: Int,
    val depthMaxMm: Int,
    /** Human-readable note when a stage yielded nothing (e.g. "no detection", "no depth"). */
    val note: String?,
    // ---- Debug-overlay inputs (for the Boxes / Masks view modes) ----
    /** Absolute path to the saved JPEG this cloud came from (for the overlay backdrop). */
    val imagePath: String = "",
    /** Raw JPEG dimensions (overlay boxes/mask are normalized against these). */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Clockwise degrees to rotate the raw JPEG upright for display (matches ReviewPhoto). */
    val displayRotationDegrees: Int = 0,
    /** All YOLOE detections for this photo (normalized to the raw JPEG); empty if none/failed. */
    val detections: List<DetectionBox> = emptyList(),
    /** Full-frame EfficientSAM mask (row-major, true = object) at [maskWidth]×[maskHeight], or null. */
    val maskBinary: BooleanArray? = null,
    val maskWidth: Int = 0,
    val maskHeight: Int = 0,
    /**
     * World-space camera position [tx, ty, tz] (meters) this photo was taken from, or null when
     * ARCore was not tracking. Used only for the viewpoint-spread guard — never for geometry.
     */
    val cameraPosition: FloatArray? = null,
)

/**
 * The combined reconstruction across all photos: each photo's cloud plus aggregate spatial stats
 * over the union of all world-space points. Extents are in meters (ARCore world units).
 */
data class ReconstructionResult(
    val perPhoto: List<PhotoCloud>,
    val totalPoints: Int,
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
    /**
     * The oriented W×H×D dimension estimate over the combined cloud (cm + box), or null until
     * computed. The min/max fields above are the raw axis-aligned bounds (debug); this is the
     * authoritative measurement. Attached by [ObjectReconstructor] via [MeasurementCalculator].
     */
    val measurement: PredictedMeasurement? = null,
) {
    val extentX: Float get() = maxX - minX
    val extentY: Float get() = maxY - minY
    val extentZ: Float get() = maxZ - minZ

    companion object {
        /** Builds a result from per-photo clouds, computing the combined bounds over all points. */
        fun from(perPhoto: List<PhotoCloud>): ReconstructionResult {
            var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
            var total = 0
            for (pc in perPhoto) {
                val p = pc.worldPoints
                var i = 0
                while (i + 2 < p.size) {
                    val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                    total++
                    i += 3
                }
            }
            if (total == 0) {
                return ReconstructionResult(perPhoto, 0, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            return ReconstructionResult(perPhoto, total, minX, minY, minZ, maxX, maxY, maxZ)
        }
    }
}
