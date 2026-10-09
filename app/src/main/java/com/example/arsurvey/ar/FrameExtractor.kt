package com.example.arsurvey.ar

import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * Pulls the per-frame data our milestones need out of an ARCore [Frame], and
 * converts it into SDK-free primitives the UI can consume.
 *
 * Why a separate object: the Compose layer should not know how to walk an ARCore
 * PointCloud FloatBuffer or a DEPTH16 [android.media.Image]. Isolating that here
 * keeps the screen composable declarative and this logic unit-testable later.
 */
object FrameExtractor {

    private const val TAG = "FrameExtractor"
    private const val NEAR = 0.1f
    private const val FAR = 30f

    /** Log intrinsics + resolutions exactly once per process — cheap, and the #1 debugging aid. */
    private var loggedIntrinsics = false

    /** Result of reading one frame. All fields are plain values / arrays. */
    data class FrameData(
        val trackingState: String,
        val pointCount: Int,
        val worldPoints: FloatArray,
        val viewProjection: FloatArray,
        // ---- Milestone 2: depth ------------------------------------------------
        /** Colorized depth (ARGB_8888), row-major, [depthWidth]*[depthHeight]; empty if none. */
        val depthColors: IntArray,
        /** Depth image width (sensor-landscape). 0 when no depth this frame. */
        val depthWidth: Int,
        /** Depth image height (sensor-landscape). 0 when no depth this frame. */
        val depthHeight: Int,
        // ---- Milestone 3: camera image for detection ---------------------------
        /** Raw YUV camera image for this frame, or null when not requested/available. */
        val cameraImage: YuvImage?,
        // ---- Milestone 5: raw depth + pose + depth intrinsics ------------------
        /** Raw 16-bit depth (mm), row-major [depthWidth]*[depthHeight]; null unless wantCloud. */
        val rawDepth: ShortArray?,
        /** Depth-resolution intrinsics (fx,fy,cx,cy); valid only when [rawDepth] != null. */
        val depthFx: Float,
        val depthFy: Float,
        val depthCx: Float,
        val depthCy: Float,
        /** Column-major world_T_camera; valid only when [rawDepth] != null. */
        val cameraPose: FloatArray,
        /** World Y of the detected ARCore floor plane, or null when none is tracking yet. */
        val floorPlaneY: Float?,
        /** TEMP M5 scale-bug diagnostics for the HUD; "" when not computed. */
        val debugInfo: String,
    )

    /**
     * @param wantDepth  acquire + colorize the depth image this frame. Skipped when the
     *                   overlay is off or the device lacks Depth support — acquiring depth
     *                   isn't free, so we only do it when it'll be shown.
     * @param wantCamera acquire the YUV camera image for object detection. Only requested
     *                   when the detector is idle, so we don't copy an image we'll drop.
     * @param wantCloud  also keep the raw depth + camera pose + depth-scaled intrinsics so
     *                   the ViewModel can back-project the masked depth into a world cloud.
     */
    fun extract(
        session: Session,
        frame: Frame,
        wantDepth: Boolean,
        wantCamera: Boolean,
        wantCloud: Boolean,
    ): FrameData {
        val camera = frame.camera
        val state = camera.trackingState.name

        // ---- View-Projection matrix (world -> clip) ----------------------------
        // ARCore hands us OpenGL-style column-major matrices. VP = Projection * View.
        val view = FloatArray(16)
        val proj = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, NEAR, FAR)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // ---- Feature points ----------------------------------------------------
        // Only meaningful while TRACKING; otherwise the cloud is stale/empty.
        var points = FloatArray(0)
        var count = 0
        if (camera.trackingState == TrackingState.TRACKING) {
            frame.acquirePointCloud().use { cloud ->
                // ARCore packs each point as 4 floats: x, y, z, confidence.
                val buf = cloud.points
                count = buf.remaining() / 4
                points = FloatArray(count * 3)
                var j = 0
                var i = 0
                val start = buf.position()
                while (i < count) {
                    val base = start + i * 4
                    points[j++] = buf.get(base)
                    points[j++] = buf.get(base + 1)
                    points[j++] = buf.get(base + 2)
                    i++
                }
            }
        }

        // ---- Depth image (Milestone 2 overlay + Milestone 5 raw) ---------------
        var depthColors = IntArray(0)
        var depthW = 0
        var depthH = 0
        var rawDepth: ShortArray? = null
        var depthFx = 0f; var depthFy = 0f; var depthCx = 0f; var depthCy = 0f
        val cameraPose = FloatArray(16)
        var debugInfo = ""
        if ((wantDepth || wantCloud) && camera.trackingState == TrackingState.TRACKING) {
            try {
                frame.acquireDepthImage16Bits().use { image ->
                    depthW = image.width
                    depthH = image.height
                    val plane = image.planes[0]
                    // DEPTH16 is native byte order (little-endian on ARM); each pixel packs 13-bit
                    // mm depth + 3-bit confidence (decode via Depth16, never the raw 16 bits).
                    // Rows may be padded, so honor rowStride/pixelStride.
                    val buf = plane.buffer.order(ByteOrder.nativeOrder())
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val shorts = ShortArray(depthW * depthH)
                    var o = 0
                    for (y in 0 until depthH) {
                        val rowBase = y * rowStride
                        for (x in 0 until depthW) {
                            shorts[o++] = buf.getShort(rowBase + x * pixelStride)
                        }
                    }
                    if (wantDepth) depthColors = DepthColorizer.colorize(shorts, depthW, depthH)

                    // Depth-resolution intrinsics: scale the CPU-image intrinsics from their
                    // resolution down to the depth resolution (depth has its OWN intrinsics —
                    // never use the RGB fx/cx directly on the lower-res depth image).
                    val ci = camera.imageIntrinsics
                    val dim = ci.imageDimensions
                    val fl = ci.focalLength
                    val pp = ci.principalPoint
                    if (wantCloud) {
                        rawDepth = shorts
                        val sx = depthW.toFloat() / dim[0]
                        val sy = depthH.toFloat() / dim[1]
                        depthFx = fl[0] * sx; depthFy = fl[1] * sy
                        depthCx = pp[0] * sx; depthCy = pp[1] * sy
                        camera.pose.toMatrix(cameraPose, 0)   // world_T_camera
                        val midMm = com.example.arsurvey.depth.Depth16.millimeters(
                            shorts[(depthH / 2) * depthW + depthW / 2]
                        )
                        debugInfo = "rgb${dim[0]}x${dim[1]} rgbFx${fl[0].toInt()} dFx${depthFx.toInt()} " +
                            "dCx${depthCx.toInt()} midMm$midMm"
                    }

                    if (!loggedIntrinsics) {
                        Log.i(
                            TAG,
                            "Depth image ${depthW}x$depthH | RGB image ${dim[0]}x${dim[1]} " +
                                "fx=${fl[0]} fy=${fl[1]} cx=${pp[0]} cy=${pp[1]} " +
                                "(depth intrinsics = these scaled to depth res)"
                        )
                        loggedIntrinsics = true
                    }
                }
            } catch (e: NotYetAvailableException) {
                // Depth for this exact frame isn't ready yet (common in the first frames
                // and occasionally mid-stream). Leave depth empty; next frame will have it.
            }
        }

        // ---- Camera image (Milestone 3) ----------------------------------------
        // Copy the YUV planes out and close the native Image immediately — the heavy
        // YUV→RGB + inference happens off-thread from this copy.
        var cameraImage: YuvImage? = null
        if (wantCamera && camera.trackingState == TrackingState.TRACKING) {
            try {
                frame.acquireCameraImage().use { image ->
                    cameraImage = YuvImage.from(image)
                }
            } catch (e: NotYetAvailableException) {
                // CPU image not ready this frame; try again next frame.
            }
        }

        // ---- Floor plane (Increment 3) -----------------------------------------
        // The floor is taken as the largest horizontal, upward-facing, currently-tracking plane
        // (not subsumed by a merge). Its world Y validates/refines the cloud-based floor estimate.
        // Only queried when we're measuring (wantCloud) — plane state is cheap but not free.
        var floorPlaneY: Float? = null
        if (wantCloud && camera.trackingState == TrackingState.TRACKING) {
            try {
                var bestArea = -1f
                for (plane in session.getAllTrackables(Plane::class.java)) {
                    if (plane.trackingState != TrackingState.TRACKING) continue
                    if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
                    if (plane.subsumedBy != null) continue
                    val area = plane.extentX * plane.extentZ
                    if (area > bestArea) {
                        bestArea = area
                        floorPlaneY = plane.centerPose.ty()
                    }
                }
            } catch (t: Throwable) {
                // Trackable enumeration can throw if the session state changes mid-iteration;
                // just skip the floor plane for this frame.
            }
        }

        return FrameData(
            state, count, points, vp, depthColors, depthW, depthH, cameraImage,
            rawDepth, depthFx, depthFy, depthCx, depthCy, cameraPose, floorPlaneY, debugInfo,
        )
    }
}
