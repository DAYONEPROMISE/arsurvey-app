package com.example.arsurvey.reconstruct

import android.graphics.BitmapFactory
import android.util.Log
import com.example.arsurvey.capture.model.CapturedPhoto
import com.example.arsurvey.cloud.PointCloudBuilder
import com.example.arsurvey.depth.Depth16
import com.example.arsurvey.reconstruct.ml.ProcessingImage
import com.example.arsurvey.reconstruct.ml.SegmentationPipeline
import java.io.File
import java.nio.ByteOrder

/**
 * Turns the 3 captured photos + their saved ARCore metadata into one combined world-space point
 * cloud. Runs AFTER capture; does no ML during capture and adds no new geometry — [PointCloudBuilder]
 * still owns every transform (unproject, mm→m, invalid-depth reject, camera→world).
 *
 * Per photo the pipeline is:
 * ```
 * JPEG → ProcessingImage → SegmentationPipeline (YOLOE detect → pick target → EfficientSAM mask)
 *   → saved depth.raw + saved intrinsics (scaled to depth res) + saved cameraToWorldMatrix
 *   → PointCloudBuilder.build → world-space points
 * ```
 * The only change from the earlier version is *which* mask feeds the back-projection: YOLOE's
 * open-vocabulary detector + EfficientSAM replace `yolo11n-seg` (whose 80 COCO classes couldn't
 * name a plain cardboard box). The EfficientSAM mask is **full-frame** in normalized coordinates, so
 * it aligns with the depth image by normalized position — exactly [PointCloudBuilder]'s assumption.
 */
object ObjectReconstructor {

    private const val TAG = "ObjectReconstructor"

    /**
     * @param onProgress invoked with the 0-based index of the photo about to be processed.
     */
    fun reconstruct(
        photos: List<CapturedPhoto>,
        pipeline: SegmentationPipeline,
        onProgress: (Int) -> Unit = {},
    ): ReconstructionResult {
        val clouds = photos.sortedBy { it.index }.map { photo ->
            onProgress(photo.index)
            reconstructPhoto(photo, pipeline)
        }
        val base = ReconstructionResult.from(clouds)
        // Post-capture dimension estimate over the combined cloud (reuses the measure/ stack).
        return base.copy(measurement = MeasurementCalculator.compute(base))
    }

    private fun reconstructPhoto(photo: CapturedPhoto, pipeline: SegmentationPipeline): PhotoCloud {
        val m = photo.metadata

        // --- 1. Decode the saved JPEG into a plain ARGB ProcessingImage. ---
        val bmp = BitmapFactory.decodeFile(photo.imagePath)
            ?: return blank(photo, "image unreadable")
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        val image = ProcessingImage(w, h, pixels)

        // --- 2. YOLOE detect → pick target → EfficientSAM full-frame mask. ---
        val seg = try {
            pipeline.run(image)
        } catch (t: Throwable) {
            Log.e(TAG, "Segmentation failed for photo ${photo.index}", t)
            val hint = if ((t.message ?: "").contains("onnx", ignoreCase = true) ||
                t is IllegalStateException
            ) {
                "measurement models missing — see README assets/models/"
            } else {
                "segmentation failed — retake this photo"
            }
            return blank(photo, hint, w, h)
        }

        // Keep every detection (and which one we picked) for the debug overlay, whatever happens next.
        val detBoxes = seg.detections.map { d ->
            DetectionBox(
                left = d.box.left, top = d.box.top, right = d.box.right, bottom = d.box.bottom,
                label = d.label, confidence = d.confidence, isTarget = d === seg.target,
            )
        }
        val mask = seg.mask
        // Builds a PhotoCloud that always carries the overlay inputs (image, boxes, mask).
        fun result(
            note: String?, world: FloatArray = FloatArray(0), maskPx: Int = mask?.binary?.count { it } ?: 0,
            valid: Int = 0, dMin: Int = 0, dMed: Int = 0, dMax: Int = 0,
        ) = PhotoCloud(
            index = photo.index, worldPoints = world, maskPixelCount = maskPx,
            validDepthCount = valid, pointCount = world.size / 3,
            depthMinMm = dMin, depthMedianMm = dMed, depthMaxMm = dMax, note = note,
            imagePath = photo.imagePath, imageWidth = w, imageHeight = h,
            displayRotationDegrees = m.displayRotationDegrees,
            detections = detBoxes,
            maskBinary = mask?.binary, maskWidth = mask?.width ?: 0, maskHeight = mask?.height ?: 0,
            cameraPosition = m.poseTranslation?.takeIf { it.size >= 3 }?.copyOf(3),
        )

        if (seg.detections.isEmpty()) return result("no detection (YOLOE found no object)")
        val target = seg.target ?: return result("no target")
        if (mask == null) return result("mask build failed")
        val maskPx = mask.binary.count { it }
        Log.i(TAG, "photo ${photo.index}: target=${target.label} conf=${target.confidence} maskPx=$maskPx (${mask.width}x${mask.height})")

        // --- 3. Saved depth for THIS frame. ---
        val depthW = m.depthWidth
        val depthH = m.depthHeight
        val depthPath = m.depthRawPath
        if (!m.depthAvailable || depthW == null || depthH == null || depthPath == null) {
            return result("no depth for this photo — retake it")
        }
        val depthFile = File(File(photo.imagePath).parentFile, depthPath)
        val depth = readDepthRaw(depthFile, depthW * depthH) ?: return result("depth file missing")

        // --- 4. Saved intrinsics, scaled from RGB resolution down to depth resolution ---
        // (identical to ar/FrameExtractor: sx = depthW/imgW, sy = depthH/imgH).
        val focal = m.intrinsicsFocalLength
        val principal = m.intrinsicsPrincipalPoint
        val dims = m.intrinsicsImageDimensions
        val pose = m.cameraToWorldMatrix
        if (focal == null || principal == null || dims == null || pose == null || dims.size < 2) {
            return result("missing intrinsics/pose")
        }
        val sx = depthW.toFloat() / dims[0]
        val sy = depthH.toFloat() / dims[1]
        val fx = focal[0] * sx; val fy = focal[1] * sy
        val cx = principal[0] * sx; val cy = principal[1] * sy

        // --- 5. EXISTING back-projection: masked depth → camera-space → world-space. ---
        val world = PointCloudBuilder.build(
            depth, depthW, depthH, fx, fy, cx, cy, pose, mask.binary, mask.width, mask.height,
        )

        val depthStats = maskedDepthStats(depth, depthW, depthH, mask.binary, mask.width, mask.height)
        return result(
            note = if (world.isEmpty()) "no valid masked depth" else null,
            world = world, maskPx = maskPx, valid = depthStats.count,
            dMin = depthStats.min, dMed = depthStats.median, dMax = depthStats.max,
        )
    }

    /** A PhotoCloud with no geometry, used when we fail before/at detection (carries image path). */
    private fun blank(photo: CapturedPhoto, note: String, w: Int = 0, h: Int = 0) = PhotoCloud(
        index = photo.index, worldPoints = FloatArray(0), maskPixelCount = 0,
        validDepthCount = 0, pointCount = 0, depthMinMm = 0, depthMedianMm = 0, depthMaxMm = 0,
        note = note, imagePath = photo.imagePath, imageWidth = w, imageHeight = h,
        displayRotationDegrees = photo.metadata.displayRotationDegrees,
    )

    /** Reads a little-endian uint16 depth dump (row-major, [count] samples). */
    private fun readDepthRaw(file: File, count: Int): ShortArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val n = minOf(count, bytes.size / 2)
        val shorts = ShortArray(count)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, n)
        return shorts
    }

    private class DepthStats(val count: Int, val min: Int, val median: Int, val max: Int)

    /**
     * Collects valid (non-zero) depth readings that fall inside the mask, using the SAME
     * depth→mask mapping [PointCloudBuilder] uses (`sx = maskW/dw, sy = maskH/dh`), and returns
     * count + min/median/max in millimeters. Purely statistics — no geometry is duplicated here.
     */
    private fun maskedDepthStats(
        depth: ShortArray, dw: Int, dh: Int, maskBinary: BooleanArray, maskW: Int, maskH: Int,
    ): DepthStats {
        val sx = maskW.toFloat() / dw
        val sy = maskH.toFloat() / dh
        val values = ArrayList<Int>()
        for (v in 0 until dh) {
            val iy = (v * sy).toInt()
            if (iy < 0 || iy >= maskH) continue
            for (u in 0 until dw) {
                val ix = (u * sx).toInt()
                if (ix < 0 || ix >= maskW) continue
                if (!maskBinary[iy * maskW + ix]) continue
                val mm = depth[v * dw + u].toInt() and Depth16.DEPTH_MASK // DEPTH16: low 13 bits
                if (mm == 0) continue
                values.add(mm)
            }
        }
        if (values.isEmpty()) return DepthStats(0, 0, 0, 0)
        values.sort()
        return DepthStats(values.size, values.first(), values[values.size / 2], values.last())
    }
}
