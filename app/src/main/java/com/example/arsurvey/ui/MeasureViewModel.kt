package com.example.arsurvey.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arsurvey.ar.ArUiState
import com.example.arsurvey.ar.FrameExtractor
import com.example.arsurvey.cloud.PointCloudBuilder
import com.example.arsurvey.measure.DimensionSmoother
import com.example.arsurvey.measure.FloorEstimator
import com.example.arsurvey.measure.FootprintEstimator
import com.example.arsurvey.measure.MeasurementEstimator
import com.example.arsurvey.measure.MeasurementStabilizer
import com.example.arsurvey.measure.ObbFitter
import com.example.arsurvey.measure.ObjectMeasurement
import com.example.arsurvey.measure.OutlierFilter
import com.example.arsurvey.detect.ObjectDetector
import com.example.arsurvey.detect.YoloSegDetector
import com.example.arsurvey.scan.CoverageTracker
import com.example.arsurvey.scan.PointCloudAccumulator
import com.example.arsurvey.scan.ScanState
import com.example.arsurvey.segment.MaskProcessor
import com.example.arsurvey.track.TargetIsolation
import com.example.arsurvey.track.TargetTracker
import com.google.ar.core.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/**
 * MVVM ViewModel: the single owner of [ArUiState] and the only place that talks to the
 * AR SDK and the detector. The UI observes [uiState]; the AR session pushes frames in via
 * [onFrame]. It stays an **orchestrator** — the heavy detection logic lives in
 * [ObjectDetector]; here we just decide when to run it and merge results into state.
 *
 * AndroidViewModel so we can load the ONNX asset with the application context.
 */
class MeasureViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private var detector: ObjectDetector? = null
    /** Single-flight guard: at most one inference in flight so we never queue up frames. */
    private val inferenceBusy = AtomicBoolean(false)
    /** Temporal low-pass for the reported dimensions (M7). Touched only on the render loop. */
    private val smoother = DimensionSmoother()

    // ---- Multi-view scan (Increment 1). Touched only on the render loop. -----------------
    /** Bounded voxel grid fusing masked world points across viewpoints. */
    private val accumulator = PointCloudAccumulator()
    /** Angular viewpoint coverage around the object. */
    private val coverage = CoverageTracker()
    /** Locks the scan onto one physical object so surrounding furniture can't contaminate it. */
    private val tracker = TargetTracker()
    /** Pending tap [nx,ny] in normalized screen coords, consumed by the next inference. */
    private val pendingTap = AtomicReference<FloatArray?>(null)
    /** Temporal convergence for the authoritative W×H×D. */
    private val measurementStabilizer = MeasurementStabilizer()
    /** Camera world path sampled while scanning (packed x,y,z), bounded ring for the overlay. */
    private val trajectory = ArrayList<Float>(3 * MAX_TRAJECTORY_POINTS)
    /** Throttle: fold at most one frame per interval into the accumulator. */
    private var lastAccumMs = 0L

    init {
        // Model load + first ORT session build is slow; keep it off the main thread.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = YoloSegDetector(getApplication())
                detector = d
                _uiState.update { it.copy(detectorReady = true, detectorBackend = d.backend) }
            } catch (t: Throwable) {
                Log.e(TAG, "Detector init failed", t)
            }
        }
    }

    /** Called once when the session is configured. */
    fun setDepthSupported(supported: Boolean) {
        _uiState.update { it.copy(depthSupported = supported) }
    }

    /** Called by SceneView whenever tracking is lost/regained. */
    fun setTrackingFailure(reason: String?) {
        _uiState.update { it.copy(trackingFailure = reason) }
    }

    /** HUD button: flip the depth overlay on/off. */
    fun toggleDepthOverlay() {
        _uiState.update { it.copy(showDepth = !it.showDepth) }
    }

    /** HUD button: flip the segmentation-mask overlay on/off. */
    fun toggleMaskOverlay() {
        _uiState.update { it.copy(showMask = !it.showMask) }
    }

    /** HUD button: flip the masked point-cloud overlay on/off. */
    fun toggleCloud() {
        _uiState.update {
            if (it.showCloud) it.copy(showCloud = false, worldCloud = FloatArray(0), cloudCount = 0)
            else it.copy(showCloud = true)
        }
    }

    /** HUD button: flip the OBB wireframe on/off. */
    fun toggleBox() {
        _uiState.update {
            if (it.showBox) it.copy(showBox = false, obbCorners = FloatArray(0), dimsWhdMeters = FloatArray(0))
            else it.copy(showBox = true)
        }
    }

    /** HUD button: freeze/measure — hold the current dimensions, or resume live updating. */
    fun toggleFreeze() {
        _uiState.update { it.copy(frozen = !it.frozen) }
    }

    /**
     * Tap-to-select: the user tapped the preview at normalized screen coords ([nx],[ny]) in
     * [0,1]. Stored and consumed by the next inference (which holds the mask prototypes needed to
     * test which detection's mask covers the tap). This is the preferred way to choose the target.
     */
    fun onTap(nx: Float, ny: Float) {
        pendingTap.set(floatArrayOf(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f)))
    }

    /** HUD button: draw the accumulated multi-view cloud on/off. */
    fun toggleAccum() {
        _uiState.update { it.copy(showAccum = !it.showAccum) }
    }

    /**
     * Scan button: IDLE → SCANNING starts a fresh accumulation; SCANNING/etc → IDLE stops and
     * holds the accumulated cloud so it can be inspected. Resetting the grid only happens on a
     * fresh start so a stopped scan can be reviewed.
     */
    fun toggleScan() {
        _uiState.update {
            if (it.scanState == ScanState.IDLE || it.scanState == ScanState.COMPLETE) {
                accumulator.clear()
                coverage.clear()
                measurementStabilizer.reset()
                trajectory.clear()
                lastAccumMs = 0L
                // Keep a target the user already tap-selected; only fall back to center-pick
                // acquisition when nothing is locked yet.
                if (tracker.state == TargetTracker.LockState.NO_TARGET) tracker.arm()
                it.copy(
                    scanState = ScanState.SCANNING,
                    accumCloud = FloatArray(0), accumCount = 0, accumObservations = 0L,
                    coverage = 0f, coverageSectors = BooleanArray(coverage.sectorCount),
                    objectCenterXZ = FloatArray(0), cameraTrajectory = FloatArray(0),
                    floorValid = false, floorFromPlane = false,
                footprintValid = false, footprintCorners = FloatArray(0),
                    measurementValid = false, measuredCorners = FloatArray(0), measuredWhd = FloatArray(0),
                    confidence = 0f, result = null,
                )
            } else {
                // Stopping a scan: drop the lock so preview returns to passive center-pick.
                tracker.reset()
                it.copy(scanState = ScanState.IDLE, targetWorldCentroid = FloatArray(0))
            }
        }
    }

    /** Clear the accumulated reconstruction and return to IDLE. */
    fun resetScan() {
        accumulator.clear()
        coverage.clear()
        measurementStabilizer.reset()
        trajectory.clear()
        tracker.reset()
        lastAccumMs = 0L
        _uiState.update {
            it.copy(
                scanState = ScanState.IDLE,
                accumCloud = FloatArray(0), accumCount = 0, accumObservations = 0L,
                coverage = 0f, coverageSectors = BooleanArray(0),
                objectCenterXZ = FloatArray(0), cameraTrajectory = FloatArray(0),
                floorValid = false, floorFromPlane = false,
                footprintValid = false, footprintCorners = FloatArray(0),
                measurementValid = false, measuredCorners = FloatArray(0), measuredWhd = FloatArray(0),
                confidence = 0f, result = null, targetWorldCentroid = FloatArray(0),
            )
        }
    }

    /** Called every frame (~30 fps) from the AR render loop. */
    fun onFrame(session: com.google.ar.core.Session, frame: Frame) {
        val current = _uiState.value
        val wantDepth = current.depthSupported == true && current.showDepth
        // Only grab the camera image when the detector is loaded AND not already busy —
        // otherwise we'd copy an image just to throw it away.
        val wantCamera = current.detectorReady && !inferenceBusy.get()
        // Actively collecting in both SCANNING and STABILIZING (still refining toward COMPLETE).
        val collecting =
            current.scanState == ScanState.SCANNING || current.scanState == ScanState.STABILIZING
        // Build the cloud once we have a target mask to intersect the depth with — needed for
        // the per-frame overlay AND to feed the multi-view accumulator while collecting.
        val wantCloud =
            (current.showCloud || collecting) && current.depthSupported == true && current.maskWidth > 0

        val data = FrameExtractor.extract(session, frame, wantDepth, wantCamera, wantCloud)

        // Back-project this frame's masked depth into world space (M5). Cheap (~depthW*depthH
        // px) so we do it inline; using THIS frame's depth + pose keeps the cloud world-locked
        // even though the mask itself lags detection by a frame or two.
        var cloud = current.worldCloud
        var cloudN = current.cloudCount
        var obbCorners = current.obbCorners
        var dims = current.dimsWhdMeters
        var accumCloud = current.accumCloud
        var accumCount = current.accumCount
        var accumObs = current.accumObservations
        var coveragePct = current.coverage
        var coverageSectors = current.coverageSectors
        var objectCenterXZ = current.objectCenterXZ
        var cameraTrajectory = current.cameraTrajectory
        var floorY = current.floorY
        var objectTopY = current.objectTopY
        var floorValid = current.floorValid
        var floorFromPlane = current.floorFromPlane
        var footprintWidth = current.footprintWidth
        var footprintDepth = current.footprintDepth
        var footprintCorners = current.footprintCorners
        var footprintValid = current.footprintValid
        var measuredWhd = current.measuredWhd
        var measuredCorners = current.measuredCorners
        var measurementValid = current.measurementValid
        var confidence = current.confidence
        var finalResult = current.result
        var scanStateNext = current.scanState
        var lockWorldDist = current.lockWorldDist
        var targetWorldCentroid = current.targetWorldCentroid
        val raw = data.rawDepth
        if (wantCloud && raw != null && current.maskBinary.isNotEmpty()) {
            cloud = PointCloudBuilder.build(
                raw, data.depthWidth, data.depthHeight,
                data.depthFx, data.depthFy, data.depthCx, data.depthCy,
                data.cameraPose,
                current.maskBinary, current.maskWidth, current.maskHeight,
            )
            cloudN = cloud.size / 3
            // Fit the OBB (M6): drop floor/wall bleed, then PCA. Cheap (O(points)).
            if (current.showBox) {
                val obb = ObbFitter.fit(OutlierFilter.filter(cloud))
                obbCorners = obb?.corners() ?: FloatArray(0)
                // Dimensions (M7): smoothed + gravity-labeled, unless frozen (hold last value).
                if (obb != null && !current.frozen) dims = smoother.update(obb)
            }

            // Filter once per frame; reused for the world anchor and for accumulation.
            val locked = current.lockState == TargetTracker.LockState.LOCKED
            val filtered =
                if (cloud.isNotEmpty() && (locked || collecting)) OutlierFilter.filter(cloud)
                else FloatArray(0)

            // ---- Target world identity: the locked object's centroid in ARCore world space.
            // This is the persistent, screen-independent representation of the target (NOT the tap
            // pixel or the initial screen box). Established from the first locked frame's masked
            // points and tracked with a light EMA; the object is stationary while the phone moves,
            // so it stays put. Drives both the isolation gate and the world-anchored overlay, which
            // is why the target box no longer drifts as the camera orbits.
            if (locked && filtered.isNotEmpty()) {
                val fc = centroid3(filtered)
                targetWorldCentroid = if (targetWorldCentroid.size < 3) {
                    Log.i(TAG, "TARGET_LOCKED worldCentroid=(" +
                        "${"%.2f".format(fc[0])},${"%.2f".format(fc[1])},${"%.2f".format(fc[2])})")
                    fc
                } else {
                    floatArrayOf(
                        emaWorld(targetWorldCentroid[0], fc[0]),
                        emaWorld(targetWorldCentroid[1], fc[1]),
                        emaWorld(targetWorldCentroid[2], fc[2]),
                    )
                }
            }

            // ---- Multi-view accumulation (Increment 1): fuse THIS frame's world points into
            // the bounded voxel grid and mark the current viewpoint. Throttled so we don't
            // thrash the grid at full frame rate.
            if (collecting && data.trackingState == "TRACKING") {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAccumMs >= ACCUM_INTERVAL_MS && filtered.isNotEmpty()) {
                    lastAccumMs = now
                    // Object isolation anchor: the locked target's world centroid (screen-independent),
                    // falling back to the accumulator centroid before one is established.
                    val anchorXZ: FloatArray? = when {
                        targetWorldCentroid.size >= 3 ->
                            floatArrayOf(targetWorldCentroid[0], targetWorldCentroid[2])
                        else -> accumulator.centroidXZ()
                    }
                    val fcXZ = horizontalCentroid(filtered)
                    lockWorldDist = if (anchorXZ != null) {
                        hypot(fcXZ[0] - anchorXZ[0], fcXZ[1] - anchorXZ[1])
                    } else {
                        0f
                    }
                    // World veto: if this frame's masked points sit far from the target's world
                    // position, the mask jumped to another object — skip the whole frame rather than
                    // contaminate. Better fewer points than wrong-object points.
                    if (anchorXZ != null && lockWorldDist > WORLD_LOST_TOL) {
                        Log.i(TAG, "TARGET_FRAME_REJECTED worldDist=${"%.2f".format(lockWorldDist)}")
                    } else {
                    // Drop points outside a horizontal radius of the anchor so a neighbouring
                    // object's depth can't leak into THIS reconstruction.
                    val gated = if (anchorXZ != null) {
                        TargetIsolation.filterHorizontalRadius(filtered, anchorXZ[0], anchorXZ[1], TARGET_RADIUS_M)
                    } else {
                        filtered
                    }
                    accumulator.addWorldPoints(gated)
                    // Coverage: camera world position (pose translation) vs object centroid.
                    val camX = data.cameraPose[12]
                    val camY = data.cameraPose[13]
                    val camZ = data.cameraPose[14]
                    val c = accumulator.centroidXZ()
                    if (c != null) {
                        coverage.observe(camX, camZ, c[0], c[1])
                        objectCenterXZ = c
                    }
                    // Camera trajectory (bounded ring buffer) for the debug overlay.
                    trajectory.add(camX); trajectory.add(camY); trajectory.add(camZ)
                    while (trajectory.size > 3 * MAX_TRAJECTORY_POINTS) {
                        trajectory.removeAt(0); trajectory.removeAt(0); trajectory.removeAt(0)
                    }
                    cameraTrajectory = trajectory.toFloatArray()
                    accumCloud = accumulator.snapshot()
                    accumCount = accumulator.size
                    accumObs = accumulator.observationCount
                    coveragePct = coverage.fraction()
                    coverageSectors = coverage.sectors()

                    // Floor + height (M-Floor): robust gravity-aligned vertical measurement on
                    // the whole accumulated reconstruction, not any single frame.
                    val floor = FloorEstimator.estimate(accumCloud, data.floorPlaneY)
                    floorY = floor.floorY
                    objectTopY = floor.topY
                    floorValid = floor.valid
                    floorFromPlane = floor.fromPlane

                    // Horizontal footprint (M-Footprint): min-area oriented rectangle on X/Z.
                    val fp = FootprintEstimator.estimate(accumCloud)
                    if (fp != null) {
                        footprintWidth = fp.width
                        footprintDepth = fp.depth
                        footprintValid = true
                        // Lift the 4 corners to floor level for the world-space overlay.
                        val fc = FloatArray(12)
                        for (k in 0 until 4) {
                            fc[k * 3] = fp.cornersXZ[k * 2]
                            fc[k * 3 + 1] = floor.floorY
                            fc[k * 3 + 2] = fp.cornersXZ[k * 2 + 1]
                        }
                        footprintCorners = fc
                    }

                    // Authoritative measurement (M-Measurement): stitch floor height + footprint
                    // into W×H×D + a gravity-vertical measured box. PCA box stays debug-only.
                    val measurement = MeasurementEstimator.assemble(floor, fp)
                    if (measurement != null) {
                        // Temporal smoothing (M-Stability): report the converged value, not raw.
                        measuredWhd = measurementStabilizer.update(
                            floatArrayOf(measurement.widthM, measurement.heightM, measurement.depthM)
                        )
                        measuredCorners = measurement.box.corners()
                        measurementValid = true

                        // Confidence = coverage + temporal stability + point density.
                        val density = (accumCount / TARGET_VOXELS.toFloat()).coerceIn(0f, 1f)
                        confidence = (0.4f * coveragePct + 0.4f * measurementStabilizer.stability() +
                            0.2f * density).coerceIn(0f, 1f)

                        // Scan state machine (M-Stability): advance toward COMPLETE once enough
                        // viewpoints + points + a valid floor exist and the dimensions converge.
                        val readyToStabilize = coverage.hasMinimumViews() &&
                            accumCount >= MIN_VOXELS_FOR_MEASURE && floorValid
                        // COMPLETE additionally requires a validated ARCore floor plane — the
                        // docs mandate floor validation before a final measurement.
                        val floorValidated = !REQUIRE_FLOOR_PLANE || floorFromPlane
                        when (scanStateNext) {
                            ScanState.SCANNING ->
                                if (readyToStabilize) scanStateNext = ScanState.STABILIZING
                            ScanState.STABILIZING ->
                                if (readyToStabilize && floorValidated && measurementStabilizer.converged()) {
                                    scanStateNext = ScanState.COMPLETE
                                    finalResult = ObjectMeasurement.from(measuredWhd, confidence, coveragePct)
                                }
                            else -> {}
                        }
                    }
                    } // end world-veto else (frame accepted)
                }
            }
        }

        // Merge per-frame tracking + depth + cloud onto the LATEST state (update {}, not
        // value = snapshot.copy) so a detection result posted by the background
        // coroutine mid-extract isn't clobbered. Detections carry over untouched.
        _uiState.update {
            it.copy(
                trackingState = data.trackingState,
                featurePointCount = data.pointCount,
                worldPoints = data.worldPoints,
                viewProjection = data.viewProjection,
                hasProjection = true,
                depthColors = data.depthColors,
                depthWidth = data.depthWidth,
                depthHeight = data.depthHeight,
                worldCloud = cloud,
                cloudCount = cloudN,
                obbCorners = obbCorners,
                dimsWhdMeters = dims,
                accumCloud = accumCloud,
                accumCount = accumCount,
                accumObservations = accumObs,
                coverage = coveragePct,
                coverageSectors = coverageSectors,
                objectCenterXZ = objectCenterXZ,
                cameraTrajectory = cameraTrajectory,
                floorY = floorY,
                objectTopY = objectTopY,
                floorValid = floorValid,
                floorFromPlane = floorFromPlane,
                footprintWidth = footprintWidth,
                footprintDepth = footprintDepth,
                footprintCorners = footprintCorners,
                footprintValid = footprintValid,
                measuredWhd = measuredWhd,
                measuredCorners = measuredCorners,
                measurementValid = measurementValid,
                confidence = confidence,
                result = finalResult,
                scanState = scanStateNext,
                lockWorldDist = lockWorldDist,
                targetWorldCentroid = targetWorldCentroid,
                debugInfo = data.debugInfo,
            )
        }

        val image = data.cameraImage
        val det = detector
        if (image != null && det != null && inferenceBusy.compareAndSet(false, true)) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    val result = det.detect(image)
                    val ms = SystemClock.elapsedRealtime() - t0
                    val detections = result.detections
                    // Tap-to-select (preferred): if the user tapped, lock onto the detection whose
                    // segmentation mask covers the tap (smallest wins). A tap on empty space selects
                    // nothing. Otherwise fall through to continuous tracking of the locked identity.
                    val tap = pendingTap.getAndSet(null)
                    var tapMissedNow = false
                    var clearCentroid = false
                    val target: Int = if (tap != null) {
                        // Screen(normalized) → image-pixel space, inverting the overlay's rotate-90
                        // mapping (screenX↔imageY, screenY↔imageX). See DetectionOverlay.
                        val imgX = tap[1] * result.imageWidth
                        val imgY = (1f - tap[0]) * result.imageHeight
                        val hit = if (detections.isEmpty()) -1
                            else MaskProcessor.pickDetectionAtPoint(result, imgX, imgY)
                        if (hit >= 0) {
                            tracker.lockTo(detections[hit])
                            clearCentroid = true   // re-anchor world geometry to the new object
                            hit
                        } else {
                            tracker.reset()
                            tapMissedNow = true
                            Log.i(TAG, "TARGET_SELECT_MISS tap=(${imgX.toInt()},${imgY.toInt()})")
                            -1
                        }
                    } else {
                        // Target locking: keep the scan on ONE object. Maintains a persistent
                        // identity and returns -1 (→ no mask → paused accumulation) rather than ever
                        // switching objects; while idle it previews the object nearest screen center.
                        tracker.update(detections, image.width, image.height)
                    }
                    val lockState = tracker.state
                    val lockScore = tracker.lastScore
                    val lockIou = tracker.lastBboxIou
                    val lockCenter = tracker.lastCenterDist
                    val lockCand = tracker.candidateCount
                    // Build the target's binary mask on this background thread while we hold
                    // the prototypes. It's needed for the point cloud (M5) regardless of the
                    // overlay toggle; colorize for display only when the overlay is on.
                    val mask = if (target >= 0) MaskProcessor.buildTargetMask(result, target) else null
                    val overlay = if (mask != null && _uiState.value.showMask) {
                        MaskProcessor.colorize(mask)
                    } else {
                        null
                    }
                    _uiState.update {
                        it.copy(
                            detections = detections,
                            targetIndex = target,
                            detImageWidth = image.width,
                            detImageHeight = image.height,
                            inferenceMs = ms,
                            maskColors = overlay?.colors ?: IntArray(0),
                            maskBinary = mask?.binary ?: BooleanArray(0),
                            maskWidth = mask?.width ?: 0,
                            maskHeight = mask?.height ?: 0,
                            lockState = lockState,
                            lockScore = lockScore,
                            lockBboxIou = lockIou,
                            lockCenterDist = lockCenter,
                            lockCandidateCount = lockCand,
                            tapMissed = tapMissedNow,
                            // Drop the world anchor on a fresh tap so it re-establishes on the new
                            // object; also clear it when nothing is selected.
                            targetWorldCentroid =
                                if (clearCentroid || target < 0) FloatArray(0) else it.targetWorldCentroid,
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Inference failed", t)
                } finally {
                    inferenceBusy.set(false)
                }
            }
        }
    }

    /** Mean [x,z] of a packed world-point array; caller guarantees it is non-empty. */
    private fun horizontalCentroid(packed: FloatArray): FloatArray {
        var sx = 0.0; var sz = 0.0; var n = 0
        var i = 0
        while (i + 2 < packed.size) {
            sx += packed[i]; sz += packed[i + 2]; n++
            i += 3
        }
        return if (n == 0) floatArrayOf(0f, 0f) else floatArrayOf((sx / n).toFloat(), (sz / n).toFloat())
    }

    /** Mean [x,y,z] of a packed world-point array; caller guarantees it is non-empty. */
    private fun centroid3(packed: FloatArray): FloatArray {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var n = 0
        var i = 0
        while (i + 2 < packed.size) {
            sx += packed[i]; sy += packed[i + 1]; sz += packed[i + 2]; n++
            i += 3
        }
        return if (n == 0) floatArrayOf(0f, 0f, 0f)
        else floatArrayOf((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat())
    }

    /** EMA one axis of the target world centroid toward the latest frame's centroid. */
    private fun emaWorld(prev: Float, cur: Float) = prev + WORLD_CENTROID_EMA * (cur - prev)

    override fun onCleared() {
        detector?.close()
        detector = null
    }

    private companion object {
        const val TAG = "MeasureViewModel"
        /** Min ms between accumulator folds while scanning (~12 Hz). */
        const val ACCUM_INTERVAL_MS = 80L
        /** Minimum accumulated voxels before we trust a measurement enough to stabilize. */
        const val MIN_VOXELS_FOR_MEASURE = 400
        /** Voxel count treated as "fully dense" for the confidence density term. */
        const val TARGET_VOXELS = 4000
        /** Require a validated ARCore floor plane before a scan may reach COMPLETE. */
        const val REQUIRE_FLOOR_PLANE = true
        /**
         * Object-isolation radius (m): points farther than this horizontally from the locked
         * target's established center are dropped before entering the accumulator. Generous
         * enough for a typical household object seen from any side, tight enough to reject a
         * neighbouring item. Tunable once real point density is observed.
         */
        const val TARGET_RADIUS_M = 0.9f
        /** EMA weight for tracking the target world centroid (low = stable, high = responsive). */
        const val WORLD_CENTROID_EMA = 0.2f
        /**
         * If a frame's masked-cloud centroid is farther than this (m) from the target world
         * centroid, the mask has jumped to a different object — reject the whole frame rather
         * than accumulate it.
         */
        const val WORLD_LOST_TOL = 0.6f
        /** Max sampled camera positions kept for the trajectory overlay (~25 s at 12 Hz). */
        const val MAX_TRAJECTORY_POINTS = 300
    }
}
