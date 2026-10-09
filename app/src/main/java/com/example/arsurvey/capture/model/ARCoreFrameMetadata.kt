package com.example.arsurvey.capture.model

/**
 * All the ARCore geometry we can reliably read off the exact frame a photo was taken on,
 * captured **without** running any ML / segmentation / depth-processing / measurement.
 *
 * This is deliberately a plain, SDK-free snapshot: every field is a primitive/array so it can
 * be serialized to JSON and re-loaded later to reconstruct the camera geometry off-device.
 *
 * **Honesty rule:** nothing here is invented or approximated. A value that ARCore did not
 * expose for this frame (e.g. pose while not tracking, or depth on a non-depth device) is
 * stored as `null` rather than a placeholder.
 *
 * Matrices follow ARCore's convention: **column-major 4x4**, packed into 16 floats.
 */
data class ARCoreFrameMetadata(
    // ---- Timing -----------------------------------------------------------------
    /** ARCore hardware frame timestamp in nanoseconds (`Frame.getTimestamp()`), or null. */
    val frameTimestampNs: Long?,
    /** Wall-clock time (epoch millis) when the shutter fired — always available. */
    val capturedAtEpochMs: Long,

    // ---- Tracking ---------------------------------------------------------------
    /** "TRACKING" / "PAUSED" / "STOPPED", or null if unreadable. */
    val trackingState: String?,

    // ---- Camera pose (world_T_camera) -------------------------------------------
    /** Pose translation [tx, ty, tz] in world meters, or null when not tracking. */
    val poseTranslation: FloatArray?,
    /** Pose rotation quaternion [qx, qy, qz, qw], or null when not tracking. */
    val poseRotationQuaternion: FloatArray?,
    /** camera→world transform (`Pose.toMatrix`), column-major 16 floats, or null. */
    val cameraToWorldMatrix: FloatArray?,
    /** world→camera transform (`Camera.getViewMatrix`), column-major 16 floats, or null. */
    val worldToCameraMatrix: FloatArray?,
    /** Projection matrix (`Camera.getProjectionMatrix`) at [projectionNear]/[projectionFar]. */
    val projectionMatrix: FloatArray?,
    val projectionNear: Float,
    val projectionFar: Float,

    // ---- Camera intrinsics (CPU image space) ------------------------------------
    /** Focal length [fx, fy] in pixels, or null. */
    val intrinsicsFocalLength: FloatArray?,
    /** Principal point [cx, cy] in pixels, or null. */
    val intrinsicsPrincipalPoint: FloatArray?,
    /** Intrinsics reference image dimensions [w, h] in pixels, or null. */
    val intrinsicsImageDimensions: IntArray?,

    // ---- Camera image -----------------------------------------------------------
    /** Captured camera image width in pixels (sensor-landscape), or null. */
    val imageWidth: Int?,
    /** Captured camera image height in pixels (sensor-landscape), or null. */
    val imageHeight: Int?,
    /**
     * Clockwise degrees the saved sensor-landscape JPEG must be rotated to display upright in
     * the portrait-locked UI (derived from the device display rotation; not measurement data).
     */
    val displayRotationDegrees: Int,

    // ---- Depth ------------------------------------------------------------------
    /** Whether the device/session reported Depth API support (config-time probe). */
    val depthSupported: Boolean,
    /** Whether a depth image was actually available for THIS frame. */
    val depthAvailable: Boolean,
    /** Depth image width in pixels (sensor-landscape), or null when no depth this frame. */
    val depthWidth: Int?,
    /** Depth image height in pixels (sensor-landscape), or null when no depth this frame. */
    val depthHeight: Int?,
    /**
     * Relative path (within the object's capture directory) to the raw 16-bit depth dump
     * (little-endian uint16 millimeters, row-major [depthWidth]*[depthHeight]), or null when
     * no depth was persisted for this frame.
     */
    val depthRawPath: String?,
) {
    // Arrays need identity-free equality only if we compared instances; we don't rely on it,
    // so the generated equals/hashCode over arrays would be by-reference. Override for value
    // semantics so tests / de-dup behave intuitively.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ARCoreFrameMetadata) return false
        return frameTimestampNs == other.frameTimestampNs &&
            capturedAtEpochMs == other.capturedAtEpochMs &&
            trackingState == other.trackingState &&
            poseTranslation.contentEqualsN(other.poseTranslation) &&
            poseRotationQuaternion.contentEqualsN(other.poseRotationQuaternion) &&
            cameraToWorldMatrix.contentEqualsN(other.cameraToWorldMatrix) &&
            worldToCameraMatrix.contentEqualsN(other.worldToCameraMatrix) &&
            projectionMatrix.contentEqualsN(other.projectionMatrix) &&
            projectionNear == other.projectionNear &&
            projectionFar == other.projectionFar &&
            intrinsicsFocalLength.contentEqualsN(other.intrinsicsFocalLength) &&
            intrinsicsPrincipalPoint.contentEqualsN(other.intrinsicsPrincipalPoint) &&
            intrinsicsImageDimensions.contentEqualsN(other.intrinsicsImageDimensions) &&
            imageWidth == other.imageWidth &&
            imageHeight == other.imageHeight &&
            displayRotationDegrees == other.displayRotationDegrees &&
            depthSupported == other.depthSupported &&
            depthAvailable == other.depthAvailable &&
            depthWidth == other.depthWidth &&
            depthHeight == other.depthHeight &&
            depthRawPath == other.depthRawPath
    }

    override fun hashCode(): Int = capturedAtEpochMs.hashCode() * 31 + (frameTimestampNs ?: 0L).hashCode()
}

private fun FloatArray?.contentEqualsN(o: FloatArray?): Boolean =
    if (this == null || o == null) this == o else this.contentEquals(o)

private fun IntArray?.contentEqualsN(o: IntArray?): Boolean =
    if (this == null || o == null) this == o else this.contentEquals(o)
