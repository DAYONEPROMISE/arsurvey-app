package com.example.arsurvey.capture.model

/**
 * One physical object the user is surveying, holding exactly its captured photos.
 *
 * The intended relationship (see the working agreement) is:
 * ```
 * CapturedObject
 *  ├── CapturedPhoto 1 → ARCoreFrameMetadata
 *  ├── CapturedPhoto 2 → ARCoreFrameMetadata
 *  └── CapturedPhoto 3 → ARCoreFrameMetadata
 * ```
 * For this capture-first iteration an object collects a fixed [PHOTOS_PER_OBJECT] photos.
 */
data class CapturedObject(
    /** Stable unique id; also the on-disk directory name for this object's files. */
    val id: String,
    /** Epoch millis when this object's capture session started. */
    val createdAtEpochMs: Long,
    /** The captured photos, ordered by [CapturedPhoto.index]. */
    val photos: List<CapturedPhoto> = emptyList(),
) {
    companion object {
        /** How many photos we collect per object in this iteration. */
        const val PHOTOS_PER_OBJECT = 3
    }
}
