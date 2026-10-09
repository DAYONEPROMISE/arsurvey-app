package com.example.arsurvey.capture

import android.util.Log
import com.example.arsurvey.ar.YuvImage
import com.example.arsurvey.capture.model.ARCoreFrameMetadata
import com.example.arsurvey.depth.Depth16
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * Reads everything we need to persist for a single captured photo directly off the ARCore
 * [Frame] — the full-resolution camera image, the raw depth (if available) and the camera
 * geometry ([ARCoreFrameMetadata]).
 *
 * Hard constraint for the capture-first workflow: this runs **no** ML inference, no
 * segmentation, no PCA, no measurement. It only copies frame data out of the SDK. The heavy
 * JPEG encode and disk I/O happen later, off the render thread, from the copies made here.
 *
 * Must be called on the AR render thread while [frame] is still valid (image/depth acquisition
 * borrows native buffers that are only live for the current frame).
 */
object CaptureFrameExtractor {

    private const val TAG = "CaptureFrameExtractor"

    /** Everything copied out of one frame; all buffers are owned copies safe to use off-thread. */
    data class RawFrameCapture(
        /** Camera image copy, to be JPEG-encoded off-thread. */
        val yuv: YuvImage,
        /**
         * Raw DEPTH16 samples (13-bit mm + 3-bit confidence), row-major [depthWidth]*[depthHeight],
         * or null when depth was unavailable **or rejected for having too few valid samples**.
         */
        val rawDepthMm: ShortArray?,
        val depthWidth: Int?,
        val depthHeight: Int?,
        /** Count of valid (non-zero, 13-bit-decoded) depth samples in this frame (0 when no depth). */
        val validDepthCount: Int,
        /**
         * Frame geometry. [ARCoreFrameMetadata.depthRawPath] is left null here — the repository
         * fills it in after it decides where (and whether) to write the raw depth dump.
         */
        val metadata: ARCoreFrameMetadata,
    )

    private const val NEAR = 0.1f
    private const val FAR = 30f

    /**
     * Minimum valid (non-zero) DEPTH16 samples for a frame's depth to be persisted. Below this the
     * depth is treated as unavailable (the known first-frame "all zero" case) rather than saved as if
     * usable. Deliberately low — we only reject essentially-empty depth, not merely sparse depth.
     */
    private const val MIN_VALID_DEPTH_SAMPLES = 100

    /**
     * @param depthSupported result of the config-time `isDepthModeSupported` probe.
     * @param displayRotationDegrees clockwise degrees to rotate the sensor-landscape image
     *        upright for the portrait UI (recorded, not used to alter the saved pixels).
     * @return the copied frame data, or null if the camera image was not yet available this
     *         frame (the caller should keep the capture pending and retry on the next frame).
     */
    fun extract(
        frame: Frame,
        depthSupported: Boolean,
        displayRotationDegrees: Int,
    ): RawFrameCapture? {
        val camera = frame.camera
        val tracking = camera.trackingState
        val trackingName = tracking.name

        // ---- Camera image (required) — copy planes, or bail to retry next frame. ----------
        val yuv: YuvImage = try {
            frame.acquireCameraImage().use { YuvImage.from(it) }
        } catch (e: NotYetAvailableException) {
            return null
        }

        // ---- Intrinsics (always available) ------------------------------------------------
        val ci = camera.imageIntrinsics
        val focal = ci.focalLength.copyOf()
        val principal = ci.principalPoint.copyOf()
        val dims = ci.imageDimensions.copyOf()

        // ---- Pose + matrices (only meaningful while tracking) -----------------------------
        var translation: FloatArray? = null
        var quaternion: FloatArray? = null
        var cameraToWorld: FloatArray? = null
        var worldToCamera: FloatArray? = null
        var projection: FloatArray? = null
        if (tracking == TrackingState.TRACKING) {
            val pose = camera.pose
            translation = pose.translation.copyOf()          // tx,ty,tz
            quaternion = pose.rotationQuaternion.copyOf()     // qx,qy,qz,qw
            cameraToWorld = FloatArray(16).also { pose.toMatrix(it, 0) }
            worldToCamera = FloatArray(16).also { camera.getViewMatrix(it, 0) }
            projection = FloatArray(16).also { camera.getProjectionMatrix(it, 0, NEAR, FAR) }
        }

        // ---- Depth (optional) — read raw 16-bit mm honoring plane strides -----------------
        var rawDepth: ShortArray? = null
        var depthW: Int? = null
        var depthH: Int? = null
        var validDepthCount = 0
        if (depthSupported && tracking == TrackingState.TRACKING) {
            try {
                frame.acquireDepthImage16Bits().use { image ->
                    val w = image.width
                    val h = image.height
                    val plane = image.planes[0]
                    val buf = plane.buffer.order(ByteOrder.nativeOrder())
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val shorts = ShortArray(w * h)
                    var o = 0
                    for (y in 0 until h) {
                        val rowBase = y * rowStride
                        for (x in 0 until w) {
                            shorts[o++] = buf.getShort(rowBase + x * pixelStride)
                        }
                    }

                    // Decode with the corrected DEPTH16 mask and count valid samples. The first frame
                    // of a capture often comes back all-zero; such a frame is unusable and must not be
                    // saved as valid depth.
                    val valid = Depth16.countValid(shorts)
                    validDepthCount = valid
                    val mid = shorts[(h / 2) * w + w / 2]
                    Log.i(
                        TAG,
                        "Depth ${w}x$h: valid=$valid/${w * h} center " +
                            "raw=0x${Integer.toHexString(mid.toInt() and 0xFFFF)} " +
                            "mm=${Depth16.millimeters(mid)} conf=${Depth16.confidence(mid)}",
                    )

                    if (valid >= MIN_VALID_DEPTH_SAMPLES) {
                        rawDepth = shorts
                        depthW = w
                        depthH = h
                    } else {
                        // Mark unavailable so no depth file is written and reconstruction skips it.
                        Log.w(
                            TAG,
                            "Depth rejected: $valid valid samples < $MIN_VALID_DEPTH_SAMPLES — marking unavailable",
                        )
                    }
                }
            } catch (e: NotYetAvailableException) {
                // Depth for this exact frame wasn't ready; the photo still captures fine.
                Log.w(TAG, "Depth not yet available this frame (NotYetAvailableException)")
            } catch (t: Throwable) {
                // Any other failure from the depth path (device-specific). Log it rather than let it
                // bubble up and abort the whole capture — the photo + geometry still persist. This is
                // the diagnostic for devices (e.g. Nord CE6) where depth is unexpectedly missing.
                Log.w(TAG, "Depth acquisition failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        val metadata = ARCoreFrameMetadata(
            frameTimestampNs = frame.timestamp,
            capturedAtEpochMs = System.currentTimeMillis(),
            trackingState = trackingName,
            poseTranslation = translation,
            poseRotationQuaternion = quaternion,
            cameraToWorldMatrix = cameraToWorld,
            worldToCameraMatrix = worldToCamera,
            projectionMatrix = projection,
            projectionNear = NEAR,
            projectionFar = FAR,
            intrinsicsFocalLength = focal,
            intrinsicsPrincipalPoint = principal,
            intrinsicsImageDimensions = dims,
            imageWidth = yuv.width,
            imageHeight = yuv.height,
            displayRotationDegrees = displayRotationDegrees,
            depthSupported = depthSupported,
            depthAvailable = rawDepth != null,
            depthWidth = depthW,
            depthHeight = depthH,
            depthRawPath = null, // repository assigns once it writes the dump
        )

        Log.i(
            TAG,
            "Captured frame: tracking=$trackingName depthSupported=$depthSupported " +
                "image=${yuv.width}x${yuv.height} " +
                "depth=${if (rawDepth != null) "${depthW}x$depthH" else "unavailable"} " +
                "pose=${if (translation != null) "yes" else "null(not tracking)"}",
        )

        return RawFrameCapture(yuv, rawDepth, depthW, depthH, validDepthCount, metadata)
    }
}
