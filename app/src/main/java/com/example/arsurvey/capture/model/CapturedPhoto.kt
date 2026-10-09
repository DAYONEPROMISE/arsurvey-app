package com.example.arsurvey.capture.model

/**
 * One captured photo: the full-resolution image on disk plus the exact ARCore frame metadata
 * that was true when the shutter fired.
 *
 * The image itself is stored as a file (JPEG) rather than inlined, so the persisted record stays
 * small; [imagePath] is an absolute path on app-internal storage.
 */
data class CapturedPhoto(
    /** 0-based position within the parent object's 3-photo set. */
    val index: Int,
    /** Absolute path to the full-resolution JPEG on app-internal storage. */
    val imagePath: String,
    /** ARCore geometry captured for this exact frame (no ML/measurement was run). */
    val metadata: ARCoreFrameMetadata,
)
