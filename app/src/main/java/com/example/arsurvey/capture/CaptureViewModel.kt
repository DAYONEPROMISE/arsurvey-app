package com.example.arsurvey.capture

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arsurvey.capture.model.CapturedObject
import com.example.arsurvey.capture.model.CapturedPhoto
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the capture-first workflow: a silent ARCore session runs in the background, and when the
 * user taps the shutter we persist the full-resolution image plus that exact frame's ARCore
 * metadata — with **no** ML inference, segmentation, PCA, depth processing or measurement.
 *
 * This is intentionally separate from the live-measurement `MeasureViewModel`; none of the
 * measurement pipeline is touched, so the whole feature stays isolated on this branch and easy to
 * revert.
 */
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private val repository = CaptureRepository(app)

    /** All captured objects (newest first) from Room — the catalog screen observes this. */
    val objects: Flow<List<CapturedObject>> = repository.observeAllObjects()

    /** The object currently being captured. Mutated only from the persistence coroutine. */
    @Volatile
    private var currentObject: CapturedObject = newObject()

    /** Set by the shutter; consumed by the next frame that yields a valid camera image. */
    private val captureRequested = AtomicBoolean(false)
    /** Guards against launching two persistence coroutines for the same shutter press. */
    private val persisting = AtomicBoolean(false)

    /**
     * Frames re-tried for the current shutter press while waiting for a usable depth frame. The first
     * depth frame of a capture is often all-zero; rather than save unusable depth we hold the capture
     * pending for a bounded window so RGB+depth+pose stay from one good frame. Reset per shutter press.
     */
    @Volatile
    private var depthRetries = 0

    /**
     * Clockwise rotation to display the saved sensor-landscape JPEG upright. The activity is
     * portrait-locked, so this is 90° on typical rear sensors; recorded in metadata (not baked
     * into pixels) and overridable if a device mounts its sensor differently.
     */
    @Volatile
    private var displayRotationDegrees: Int = 90

    fun setDisplayRotationDegrees(deg: Int) { displayRotationDegrees = deg }

    /** Called once when the AR session is configured. */
    fun setDepthSupported(supported: Boolean) {
        _uiState.update { it.copy(depthSupported = supported) }
    }

    /** When the current pending capture started (uptime ms); null when idle. For timeout. */
    @Volatile
    private var captureStartMs: Long? = null

    /** Dismiss the first-run coaching card. */
    fun dismissOnboarding() = _uiState.update { it.copy(showOnboarding = false) }

    /** Shutter: request a capture of the next valid frame (ignored once 3 are taken / mid-capture). */
    fun onShutter() {
        if (!_uiState.value.canCapture) return
        // Gate: only fire when tracking; otherwise explain why (button also shows the reason).
        if (_uiState.value.trackingState != TrackingState.TRACKING.name) {
            _uiState.update { it.copy(statusMessage = "Waiting for tracking…") }
            return
        }
        _uiState.update { it.copy(capturing = true, statusMessage = "Capturing…") }
        depthRetries = 0
        captureStartMs = android.os.SystemClock.uptimeMillis()
        captureRequested.set(true)
    }

    /** Cancel a pending capture (timeout or user action) — never waits indefinitely. */
    fun cancelCapture() {
        captureRequested.set(false)
        persisting.set(false)
        depthRetries = 0
        captureStartMs = null
        _uiState.update { it.copy(capturing = false, statusMessage = "Capture cancelled") }
    }

    /** Start a brand-new object (used by "Retake" from the review screen). */
    fun startNewObject() {
        currentObject = newObject()
        captureRequested.set(false)
        persisting.set(false)
        depthRetries = 0
        _uiState.update {
            CaptureUiState(
                screen = CaptureScreen.CAPTURE,
                trackingState = it.trackingState,
                depthSupported = it.depthSupported,
                showOnboarding = it.showOnboarding,
            )
        }
    }

    // ---- Catalog navigation --------------------------------------------------------------
    /** Show the catalog of all captured objects. */
    fun openCatalog() = _uiState.update { it.copy(screen = CaptureScreen.CATALOG) }

    /** Open one object's detail (its 3 photos + metadata). */
    fun openObject(objectId: String) =
        _uiState.update { it.copy(screen = CaptureScreen.DETAIL, selectedObjectId = objectId) }

    /** Detail → back to the catalog list. */
    fun backToCatalog() =
        _uiState.update { it.copy(screen = CaptureScreen.CATALOG, selectedObjectId = null) }

    /** Return to the live capture screen. */
    fun backToCapture() = _uiState.update { it.copy(screen = CaptureScreen.CAPTURE) }

    /** Open the 3D reconstruction viewer for [photos], remembering where to return to. */
    fun openReconstruct(photos: List<CapturedPhoto>, returnTo: CaptureScreen) =
        _uiState.update {
            it.copy(
                screen = CaptureScreen.RECONSTRUCT,
                reconstructPhotos = photos,
                reconstructReturnTo = returnTo,
            )
        }

    /** Leave the reconstruction viewer, back to whichever screen opened it. */
    fun backFromReconstruct() =
        _uiState.update { it.copy(screen = it.reconstructReturnTo, reconstructPhotos = emptyList()) }

    /** Open the depth→RGB reprojection diagnostic for [photos], remembering where to return to. */
    fun openDiagnostic(photos: List<CapturedPhoto>, returnTo: CaptureScreen) =
        _uiState.update {
            it.copy(
                screen = CaptureScreen.DIAGNOSTIC,
                diagnosticPhotos = photos,
                diagnosticReturnTo = returnTo,
            )
        }

    /** Leave the diagnostic viewer, back to whichever screen opened it. */
    fun backFromDiagnostic() =
        _uiState.update { it.copy(screen = it.diagnosticReturnTo, diagnosticPhotos = emptyList()) }

    /** Load one object by id for the detail screen (null while loading / if missing). */
    suspend fun loadObject(objectId: String): CapturedObject? = repository.getObject(objectId)

    /** Per-photo retake for the in-progress object: drops photo [index] so it can be re-shot. */
    fun retakePhoto(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.deletePhoto(currentObject.id, index) } catch (_: Throwable) {}
            val kept = currentObject.photos.filter { it.index != index }
            currentObject = currentObject.copy(photos = kept)
            captureRequested.set(false)
            persisting.set(false)
            captureStartMs = null
            _uiState.update {
                it.copy(
                    screen = CaptureScreen.CAPTURE,
                    photos = kept,
                    photosTaken = kept.size,
                    capturing = false,
                    statusMessage = "Retaking photo ${index + 1} — move ~30° sideways",
                )
            }
        }
    }

    /** Delete a saved object (catalog card action). */
    fun deleteObject(objectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.deleteObject(objectId) } catch (_: Throwable) {}
        }
    }

    /** Export one object's metadata as CSV into app files; returns the file or null. */
    suspend fun exportObjectCsv(objectId: String): java.io.File? =
        try { repository.exportCsv(objectId) } catch (_: Throwable) { null }

    /**
     * AR render-loop callback (SceneView `onSessionUpdated`). Keeps the tracking hint fresh and,
     * when a capture is pending, copies the current frame's image + metadata off the SDK. All heavy
     * work (JPEG encode, disk I/O) is handed to a background coroutine from those copies.
     */
    fun onFrame(frame: Frame) {
        val tracking = frame.camera.trackingState
        val trackingName = tracking.name
        if (trackingName != _uiState.value.trackingState) {
            _uiState.update { it.copy(trackingState = trackingName) }
        }

        if (!captureRequested.get()) return

        // Timeout: never wait indefinitely on "Hold still…". 15 s pending → auto-cancel.
        val started = captureStartMs
        if (started != null && android.os.SystemClock.uptimeMillis() - started > CAPTURE_TIMEOUT_MS) {
            captureRequested.set(false)
            captureStartMs = null
            _uiState.update { it.copy(capturing = false, statusMessage = "Capture timed out — try again") }
            return
        }

        // Tracking gate: only ever capture from a TRACKING frame. Pose, the transform matrices and
        // depth are all null on a non-tracking frame, so committing early (as we used to, the moment
        // the camera image was ready) recorded them as "unavailable" — the Nord CE6 symptom. Hold the
        // request pending and retry on a later frame until ARCore is actually tracking.
        if (tracking != TrackingState.TRACKING) {
            _uiState.update { it.copy(statusMessage = "Hold still — establishing tracking…") }
            return
        }

        val depthSupported = _uiState.value.depthSupported == true
        val capture = CaptureFrameExtractor.extract(frame, depthSupported, displayRotationDegrees)
            ?: return // camera image not ready this frame; keep the request pending and retry.

        // Depth gate: if depth is supported but this frame produced no usable depth (the known
        // first-frame all-zero case, rejected in the extractor), hold the capture pending and retry a
        // bounded number of frames so the saved RGB, depth and pose all come from the SAME good frame.
        // After the budget we commit anyway with depth marked unavailable — the photo is never blocked.
        if (depthSupported && !capture.metadata.depthAvailable && depthRetries < MAX_DEPTH_RETRIES) {
            depthRetries++
            _uiState.update { it.copy(statusMessage = "Waiting for depth…") }
            return
        }

        // We have a valid frame copy — stop retrying and persist exactly once.
        captureRequested.set(false)
        depthRetries = 0
        captureStartMs = null
        if (!persisting.compareAndSet(false, true)) return

        val index = currentObject.photos.size
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jpeg = CameraImageJpegEncoder.encode(capture.yuv)
                val photo = repository.writePhoto(currentObject.id, index, jpeg, capture)
                // Insert the object row alongside its first photo; subsequent photos just add rows.
                if (index == 0) repository.addObject(currentObject)
                repository.addPhoto(currentObject.id, photo)
                currentObject = currentObject.copy(photos = currentObject.photos + photo)

                val taken = currentObject.photos.size
                val complete = taken >= CapturedObject.PHOTOS_PER_OBJECT
                Log.i(TAG, "Photo $taken/${CapturedObject.PHOTOS_PER_OBJECT} persisted for object ${currentObject.id}")
                _uiState.update {
                    it.copy(
                        capturing = false,
                        photosTaken = taken,
                        photos = currentObject.photos,
                        screen = if (complete) CaptureScreen.REVIEW else CaptureScreen.CAPTURE,
                        statusMessage = if (complete) "All 3 photos captured" else "Saved photo $taken of 3",
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist captured photo #$index", t)
                _uiState.update { it.copy(capturing = false, statusMessage = "Capture failed — try again") }
            } finally {
                persisting.set(false)
            }
        }
    }

    private fun newObject(): CapturedObject =
        CapturedObject(id = UUID.randomUUID().toString(), createdAtEpochMs = System.currentTimeMillis())

    private companion object {
        const val TAG = "CaptureViewModel"
        /** Bounded frames to wait for a usable depth frame before committing without depth (~1s @30fps). */
        const val MAX_DEPTH_RETRIES = 30
        /** Pending-capture timeout so the shutter never waits indefinitely. */
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
