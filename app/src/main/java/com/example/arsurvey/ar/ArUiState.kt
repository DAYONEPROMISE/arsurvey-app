package com.example.arsurvey.ar

/**
 * Immutable snapshot of what the AR session currently knows.
 *
 * This is the single contract the UI layer renders from. Keeping it a plain data
 * class (no ARCore types) means the UI never touches the AR SDK directly — the
 * [com.example.arsurvey.ui.MeasureViewModel] is the only bridge.
 *
 * Milestone 1 populates: tracking state, feature-point count + positions, and
 * whether the Depth API is available on this device.
 * Milestone 2 adds the colorized depth overlay + its on/off toggle.
 */
data class ArUiState(
    /** "TRACKING" / "PAUSED" / "STOPPED" / "INITIALIZING". */
    val trackingState: String = "INITIALIZING",
    /** Human-readable reason ARCore lost tracking, or null when fine. */
    val trackingFailure: String? = null,
    /** null until the session is configured; true if Config.DepthMode.AUTOMATIC is supported. */
    val depthSupported: Boolean? = null,
    /** Number of feature points ARCore is currently confident about. */
    val featurePointCount: Int = 0,
    /** Packed world-space feature points: [x0,y0,z0, x1,y1,z1, ...]. */
    val worldPoints: FloatArray = FloatArray(0),
    /** Column-major 4x4 view-projection matrix for this frame (world -> clip space). */
    val viewProjection: FloatArray = FloatArray(16),
    /** True once [viewProjection] holds a real frame's matrix. */
    val hasProjection: Boolean = false,
    // ---- Milestone 2: depth overlay ----------------------------------------
    /** User toggle: is the depth overlay currently on? Defaults on so depth shows at launch. */
    val showDepth: Boolean = true,
    /** Colorized depth (ARGB_8888), row-major [depthWidth]*[depthHeight]; empty when none. */
    val depthColors: IntArray = IntArray(0),
    /** Depth image width (sensor-landscape orientation); 0 when no depth this frame. */
    val depthWidth: Int = 0,
    /** Depth image height (sensor-landscape orientation); 0 when no depth this frame. */
    val depthHeight: Int = 0,
    // ---- Milestone 3: object detection -------------------------------------
    /** True once the ONNX model has loaded. */
    val detectorReady: Boolean = false,
    /** Inference backend note ("NNAPI"/"CPU"), or null until ready. */
    val detectorBackend: String? = null,
    /** Latest detections, in camera-image pixel space (sensor-landscape). */
    val detections: List<com.example.arsurvey.detect.Detection> = emptyList(),
    /** Index into [detections] of the chosen target object, or -1. */
    val targetIndex: Int = -1,
    /** Width of the image [detections] are expressed in; 0 until first detection. */
    val detImageWidth: Int = 0,
    /** Height of the image [detections] are expressed in; 0 until first detection. */
    val detImageHeight: Int = 0,
    /** Wall-clock ms of the last inference (preprocess + run + decode). */
    val inferenceMs: Long = 0,
    // ---- Milestone 4: segmentation mask ------------------------------------
    /** User toggle: is the target's mask overlay on? Defaults on. */
    val showMask: Boolean = true,
    /** Colorized target mask (ARGB_8888), row-major [maskWidth]*[maskHeight]; empty when none. */
    val maskColors: IntArray = IntArray(0),
    /** Binary target mask (camera-image pixel space), row-major [maskWidth]*[maskHeight]. */
    val maskBinary: BooleanArray = BooleanArray(0),
    /** Mask width (camera-image pixel space); 0 when none. */
    val maskWidth: Int = 0,
    /** Mask height (camera-image pixel space); 0 when none. */
    val maskHeight: Int = 0,
    // ---- Milestone 5: masked point cloud -----------------------------------
    /** User toggle: draw the target's world-space point cloud? Defaults on. */
    val showCloud: Boolean = true,
    /** Packed world-space cloud points [x0,y0,z0,...] from the masked depth. */
    val worldCloud: FloatArray = FloatArray(0),
    /** Number of points in [worldCloud]. */
    val cloudCount: Int = 0,
    // ---- Milestone 6: oriented bounding box --------------------------------
    /** User toggle: draw the OBB wireframe? Defaults on. */
    val showBox: Boolean = true,
    /** Packed world-space OBB corners [x0,y0,z0,...] (8 corners = 24 floats); empty if none. */
    val obbCorners: FloatArray = FloatArray(0),
    // ---- Milestone 7: live dimensions --------------------------------------
    /** Smoothed, gravity-labeled dimensions in meters [width,height,depth]; empty if none. */
    val dimsWhdMeters: FloatArray = FloatArray(0),
    /** Freeze/measure: when true the displayed dimensions are held (stop updating). */
    val frozen: Boolean = false,
    /** TEMP: M5 scale-bug diagnostics for the HUD. */
    val debugInfo: String = "",
    // ---- Multi-view scan (Increment 1) -------------------------------------
    /** Current scan lifecycle state. */
    val scanState: com.example.arsurvey.scan.ScanState = com.example.arsurvey.scan.ScanState.IDLE,
    /** User toggle: draw the accumulated multi-view cloud? Defaults on. */
    val showAccum: Boolean = true,
    /** Packed world-space accumulated (voxel-fused) cloud [x0,y0,z0,…]. */
    val accumCloud: FloatArray = FloatArray(0),
    /** Number of occupied voxels in [accumCloud]. */
    val accumCount: Int = 0,
    /** Total raw observations folded into the accumulator across the scan. */
    val accumObservations: Long = 0L,
    /** 0..1 fraction of angular sectors observed around the object. */
    val coverage: Float = 0f,
    /** Which angular sectors have been observed (coverage-ring debug overlay). */
    val coverageSectors: BooleanArray = BooleanArray(0),
    /** Object horizontal centroid [x,z] (coverage-ring pivot); empty until points exist. */
    val objectCenterXZ: FloatArray = FloatArray(0),
    /** Camera world path while scanning, packed [x0,y0,z0,…] (trajectory debug overlay). */
    val cameraTrajectory: FloatArray = FloatArray(0),
    // ---- Increment 2: floor + height ---------------------------------------
    /** Estimated floor / object-base world Y (meters); only meaningful when [floorValid]. */
    val floorY: Float = 0f,
    /** Estimated object-top world Y (meters). */
    val objectTopY: Float = 0f,
    /** True once the floor/height estimate has enough accumulated points to trust. */
    val floorValid: Boolean = false,
    /** True when the floor was validated by an ARCore plane (vs the cloud-base fallback). */
    val floorFromPlane: Boolean = false,
    // ---- Increment 2: horizontal footprint (min-area rectangle) -------------
    /** Footprint rectangle longer side (meters). */
    val footprintWidth: Float = 0f,
    /** Footprint rectangle shorter side (meters). */
    val footprintDepth: Float = 0f,
    /** 4 footprint corners at floor level, packed world [x0,y0,z0,…] (12 floats); empty if none. */
    val footprintCorners: FloatArray = FloatArray(0),
    /** True once a footprint rectangle has been fit this scan. */
    val footprintValid: Boolean = false,
    // ---- Increment 2: authoritative measurement (floor height + footprint) --
    /** Authoritative [width,height,depth] in meters (NOT PCA); empty until valid. */
    val measuredWhd: FloatArray = FloatArray(0),
    /** Gravity-vertical, footprint-oriented measured box corners [x0,y0,z0,…] (24 floats). */
    val measuredCorners: FloatArray = FloatArray(0),
    /** True once floor + footprint have produced a full W×H×D. */
    val measurementValid: Boolean = false,
    /** 0..1 overall measurement confidence (coverage + stability + density). */
    val confidence: Float = 0f,
    /** Final on-device result, populated when the scan reaches COMPLETE; null otherwise. */
    val result: com.example.arsurvey.measure.ObjectMeasurement? = null,
    // ---- Target locking (object isolation) ---------------------------------
    /** Current target-lock lifecycle state. */
    val lockState: com.example.arsurvey.track.TargetTracker.LockState =
        com.example.arsurvey.track.TargetTracker.LockState.NO_TARGET,
    /** Best candidate's match score against the locked identity (0..1); debug. */
    val lockScore: Float = 0f,
    /** Best candidate's bounding-box IoU vs the locked identity; debug. */
    val lockBboxIou: Float = 0f,
    /** Best candidate's screen-center distance (0..1, image-diagonal normalized); debug. */
    val lockCenterDist: Float = 0f,
    /** Number of detections considered for the lock this frame; debug. */
    val lockCandidateCount: Int = 0,
    /** Horizontal distance (m) of this frame's masked points from the target world anchor; debug. */
    val lockWorldDist: Float = 0f,
    /**
     * Locked target's centroid in ARCore world space [x,y,z]; empty until established. This — not
     * the tap pixel or the initial screen box — is the persistent target identity, so the overlay
     * can be reprojected from the current camera pose every frame and stays glued to the object.
     */
    val targetWorldCentroid: FloatArray = FloatArray(0),
    /** True for one detection cycle after a tap that landed on no object (UI feedback). */
    val tapMissed: Boolean = false,
) {
    // FloatArray needs explicit equals/hashCode. We intentionally use reference
    // identity for the arrays: every frame produces a fresh ArUiState instance, so
    // any real change yields a new object and Compose recomposes correctly.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
