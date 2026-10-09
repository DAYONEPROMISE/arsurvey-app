package com.example.arsurvey.diag

import android.graphics.BitmapFactory
import android.util.Log
import com.example.arsurvey.capture.model.CapturedPhoto
import java.io.File
import java.nio.ByteOrder

/**
 * FIRST diagnostic test for the capture-first pipeline.
 *
 * Verifies — for **one** captured photo, in isolation — that the saved ARCore depth image and the
 * saved camera intrinsics correctly correspond to the saved RGB JPEG. It answers exactly one
 * question, the very first stage of `docs/scanning-ui.md`'s failure-source list:
 *
 * ```
 *   depth pixel  →  camera-space XYZ  →  RGB pixel
 * ```
 *
 * If reprojected depth points do not land on the real scene features in the RGB image, then depth,
 * intrinsics, resolution scaling, or rotation is wrong, and every downstream stage (segmentation,
 * world transform, accumulation, measurement) is built on sand.
 *
 * **Deliberately NOT used here** (per the test brief): SAM, YOLO, PCA, and any world-space
 * transform. No `cameraToWorldMatrix`, no pose. Purely the intra-frame pinhole round-trip.
 *
 * The math is the **exact inverse** of [com.example.arsurvey.cloud.PointCloudBuilder] so this test
 * exercises the same back-projection the reconstruction relies on:
 * ```
 *   Z  = depth_mm / 1000                       // mm → m
 *   Xc = (u - cx_d) * Z / fx_d                  // right,  intrinsics scaled to DEPTH resolution
 *   Yc = -(v - cy_d) * Z / fy_d                 // up  (image v grows downward → negate)
 *   Zc = -Z                                     // ARCore camera looks down -Z
 * ```
 * then forward-projects that camera-space point back into the RGB image with the **full-resolution**
 * intrinsics:
 * ```
 *   Zf     = -Zc                                // positive range
 *   u_rgb  = cx + Xc * fx / Zf
 *   v_rgb  = cy - Yc * fy / Zf
 * ```
 *
 * Everything is computed in the camera's native **sensor-landscape** space — the space the JPEG,
 * the depth dump and the intrinsics were all saved in. Display rotation is applied only later, at
 * render time, to both the image and the overlaid points together, so the geometry stays honest.
 */
object DepthReprojectionDiagnostic {

    private const val TAG = "DepthReproj"

    /**
     * Result of one photo's reprojection. Points are in **RGB-bitmap pixel space** (already scaled
     * from the intrinsics reference resolution to the decoded JPEG's resolution), sensor-landscape
     * orientation, ready for the renderer to plot and then rotate for display.
     */
    data class Result(
        /** Non-null when a required input was missing; [px]/[py] are then empty. Never faked. */
        val note: String?,
        // ---- Resolutions ----
        /** Decoded JPEG pixel size (sensor-landscape). */
        val rgbWidth: Int,
        val rgbHeight: Int,
        /** Intrinsics reference image size the fx/fy/cx/cy are expressed against. */
        val refWidth: Int,
        val refHeight: Int,
        /** Saved depth image size (sensor-landscape). */
        val depthWidth: Int,
        val depthHeight: Int,
        // ---- Counts ----
        /** Depth samples with a valid (non-zero) reading. */
        val validDepthCount: Int,
        /** Valid samples that produced a finite reprojected pixel (== validDepthCount here). */
        val projectedCount: Int,
        /** Reprojected pixels that landed inside the RGB image bounds. */
        val insideCount: Int,
        // ---- Depth encoding (DEPTH16: bits 0..12 = mm, bits 13..15 = confidence) ----
        /** Valid samples whose top-3 confidence bits are non-zero (raw > 0x1FFF). */
        val confidenceBitPixels: Int,
        /** Max raw 16-bit value seen (the value the existing pipeline reads as millimeters). */
        val rawMaxMm: Int,
        // ---- Reprojected points, RGB-bitmap pixel space (sensor-landscape) ----
        val px: FloatArray,
        val py: FloatArray,
        /** Physically-correct depth in mm (raw masked to the low 13 bits). */
        val depthMm: IntArray,
        val depthMinMm: Int,
        val depthMaxMm: Int,
    ) {
        /** True when DEPTH16 confidence bits are packed above the 13-bit depth (raw > 0x1FFF). */
        val confidenceBitsPresent: Boolean get() = confidenceBitPixels > 0
        /** % of valid depth points that reprojected inside the RGB image. */
        val insidePercent: Float
            get() = if (validDepthCount == 0) 0f else 100f * insideCount / validDepthCount

        val ok: Boolean get() = note == null
    }

    /** A failed result carrying only the explanation (and whatever resolutions were known). */
    private fun fail(
        note: String,
        rgbW: Int = 0, rgbH: Int = 0, refW: Int = 0, refH: Int = 0, dW: Int = 0, dH: Int = 0,
    ) = Result(
        note = note, rgbWidth = rgbW, rgbHeight = rgbH, refWidth = refW, refHeight = refH,
        depthWidth = dW, depthHeight = dH, validDepthCount = 0, projectedCount = 0, insideCount = 0,
        confidenceBitPixels = 0, rawMaxMm = 0,
        px = FloatArray(0), py = FloatArray(0), depthMm = IntArray(0), depthMinMm = 0, depthMaxMm = 0,
    )

    /**
     * Runs the reprojection for [photo]. Pure CPU + a light JPEG bounds decode — safe off the main
     * thread. Returns a [Result] whose [Result.note] is non-null when the photo lacks the inputs the
     * test needs (no depth, missing intrinsics, unreadable image); those are reported, not faked.
     */
    fun analyze(photo: CapturedPhoto): Result {
        val m = photo.metadata

        // --- RGB size: decode bounds only (cheap; we don't need pixels for the math). ---
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.imagePath, bounds)
        val rgbW = bounds.outWidth
        val rgbH = bounds.outHeight
        if (rgbW <= 0 || rgbH <= 0) return fail("RGB image unreadable: ${photo.imagePath}")

        // --- Intrinsics (fx,fy,cx,cy) expressed against intrinsicsImageDimensions. ---
        val focal = m.intrinsicsFocalLength
        val principal = m.intrinsicsPrincipalPoint
        val dims = m.intrinsicsImageDimensions
        if (focal == null || focal.size < 2 || principal == null || principal.size < 2 ||
            dims == null || dims.size < 2
        ) {
            return fail("Missing camera intrinsics", rgbW, rgbH)
        }
        val refW = dims[0]
        val refH = dims[1]
        val fx = focal[0]; val fy = focal[1]
        val cx = principal[0]; val cy = principal[1]

        // --- Depth for THIS frame. ---
        val depthW = m.depthWidth
        val depthH = m.depthHeight
        val depthPath = m.depthRawPath
        if (!m.depthAvailable || depthW == null || depthH == null || depthPath == null) {
            return fail("No depth saved for this photo", rgbW, rgbH, refW, refH)
        }
        val depthFile = File(File(photo.imagePath).parentFile, depthPath)
        val depth = readDepthRaw(depthFile, depthW * depthH)
            ?: return fail("Depth file missing: ${depthFile.name}", rgbW, rgbH, refW, refH, depthW, depthH)

        // Intrinsics scaled from the reference (RGB) resolution DOWN to depth resolution — the same
        // sx/sy PointCloudBuilder/FrameExtractor use. Never apply RGB intrinsics to a depth pixel.
        val sxD = depthW.toFloat() / refW
        val syD = depthH.toFloat() / refH
        val fxD = fx * sxD; val fyD = fy * syD
        val cxD = cx * sxD; val cyD = cy * syD

        // Reference-pixel → decoded-bitmap-pixel scale (handles any RGB/intrinsics size difference).
        val bx = rgbW.toFloat() / refW
        val by = rgbH.toFloat() / refH

        val px = FloatArray(depthW * depthH)
        val py = FloatArray(depthW * depthH)
        val dmm = IntArray(depthW * depthH)
        var n = 0
        var inside = 0
        var minMm = Int.MAX_VALUE
        var maxMm = 0
        var confPixels = 0
        var rawMax = 0

        for (v in 0 until depthH) {
            val row = v * depthW
            for (u in 0 until depthW) {
                val raw = depth[row + u].toInt() and 0xFFFF
                if (raw == 0) continue                      // 0 = unknown / invalid

                // DEPTH16: low 13 bits = mm, top 3 bits = confidence. The existing pipeline reads the
                // full 16 bits as mm (over-reading by the confidence bits); here we decode correctly
                // and separately flag the confidence bits so that discrepancy is visible.
                val mm = raw and 0x1FFF
                if (raw > 0x1FFF) confPixels++
                if (raw > rawMax) rawMax = raw
                if (mm == 0) continue                       // depth bits all zero → no range

                // depth pixel → camera-space XYZ (identical to PointCloudBuilder's inverse model)
                val z = mm / 1000f
                val xc = (u - cxD) * z / fxD
                val yc = -(v - cyD) * z / fyD
                val zc = -z

                // camera-space XYZ → RGB pixel, full-resolution intrinsics (reference space)
                val zf = -zc                                // == z; kept explicit for honesty
                val uRef = cx + xc * fx / zf
                val vRef = cy - yc * fy / zf

                // scale reference-space pixel to the decoded bitmap
                val bxPx = uRef * bx
                val byPx = vRef * by

                px[n] = bxPx
                py[n] = byPx
                dmm[n] = mm
                n++

                if (uRef >= 0f && uRef < refW && vRef >= 0f && vRef < refH) inside++
                if (mm < minMm) minMm = mm
                if (mm > maxMm) maxMm = mm
            }
        }

        val validCount = n
        if (validCount == 0) {
            return fail("Depth saved but every sample was invalid (0 mm)", rgbW, rgbH, refW, refH, depthW, depthH)
        }

        Log.i(
            TAG,
            "photo ${photo.index}: rgb=${rgbW}x$rgbH ref=${refW}x$refH depth=${depthW}x$depthH " +
                "valid=$validCount inside=$inside (${"%.1f".format(100f * inside / validCount)}%) " +
                "depthMm(13bit)=[$minMm..$maxMm] confBits=$confPixels/$validCount rawMax=$rawMax",
        )

        return Result(
            note = null,
            rgbWidth = rgbW, rgbHeight = rgbH,
            refWidth = refW, refHeight = refH,
            depthWidth = depthW, depthHeight = depthH,
            validDepthCount = validCount,
            projectedCount = validCount,
            insideCount = inside,
            confidenceBitPixels = confPixels,
            rawMaxMm = rawMax,
            px = px.copyOf(n), py = py.copyOf(n), depthMm = dmm.copyOf(n),
            depthMinMm = minMm, depthMaxMm = maxMm,
        )
    }

    /** Reads a little-endian uint16 depth dump (row-major, [count] samples). Same as the reconstructor. */
    private fun readDepthRaw(file: File, count: Int): ShortArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val n = minOf(count, bytes.size / 2)
        val shorts = ShortArray(count)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, n)
        return shorts
    }
}
