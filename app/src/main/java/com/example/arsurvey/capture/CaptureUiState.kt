package com.example.arsurvey.capture

import com.example.arsurvey.capture.model.CapturedObject
import com.example.arsurvey.capture.model.CapturedPhoto

/** Which screen of the capture-first flow is showing. */
enum class CaptureScreen { CAPTURE, REVIEW, CATALOG, DETAIL, RECONSTRUCT, DIAGNOSTIC }

/**
 * Immutable snapshot the capture-first UI renders from. Deliberately minimal — the user-facing
 * surface is just a live camera, a shutter, and a "N of 3" counter. ARCore/debug values are NOT
 * exposed to the user here; they go to logs + the persisted manifest.
 */
data class CaptureUiState(
    /** Capture screen vs after-capture review. */
    val screen: CaptureScreen = CaptureScreen.CAPTURE,
    /** ARCore tracking state, shown only as a lightweight "hold still / move to track" hint. */
    val trackingState: String = "INITIALIZING",
    /** null until the session is configured. */
    val depthSupported: Boolean? = null,
    /** How many of [CapturedObject.PHOTOS_PER_OBJECT] photos have been captured + persisted. */
    val photosTaken: Int = 0,
    /** True from shutter press until the frame is captured (waiting for a valid frame). */
    val capturing: Boolean = false,
    /** The persisted photos, populated for the review screen. */
    val photos: List<CapturedPhoto> = emptyList(),
    /** Transient developer/status note (e.g. "Saved photo 2 of 3"); user-friendly, terse. */
    val statusMessage: String? = null,
    /** Object id shown on the DETAIL screen (set when navigating from the catalog). */
    val selectedObjectId: String? = null,
    /** Photos handed to the RECONSTRUCT screen (from Review or a catalog object). */
    val reconstructPhotos: List<CapturedPhoto> = emptyList(),
    /** Screen to return to when leaving RECONSTRUCT (Review or Detail). */
    val reconstructReturnTo: CaptureScreen = CaptureScreen.REVIEW,
    /** Photos handed to the DIAGNOSTIC screen (depth→RGB reprojection test). */
    val diagnosticPhotos: List<CapturedPhoto> = emptyList(),
    /** Screen to return to when leaving DIAGNOSTIC (Review or Detail). */
    val diagnosticReturnTo: CaptureScreen = CaptureScreen.REVIEW,
    /** First-run coaching card visible until dismissed (in-memory; per install is enough for v1). */
    val showOnboarding: Boolean = true,
) {
    val photosRemaining: Int get() = CapturedObject.PHOTOS_PER_OBJECT - photosTaken
    val canCapture: Boolean get() = !capturing && photosTaken < CapturedObject.PHOTOS_PER_OBJECT
    /** User-facing reason the shutter is disabled, or null when it can fire. */
    val shutterReason: String? get() = when {
        capturing -> statusMessage ?: "Capturing…"
        photosTaken >= CapturedObject.PHOTOS_PER_OBJECT -> "All 3 captured"
        depthSupported == null -> "Starting AR session…"
        trackingState != "TRACKING" -> "Waiting for tracking…"
        else -> null
    }
}
