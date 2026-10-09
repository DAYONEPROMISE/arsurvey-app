package com.example.arsurvey.segment

import com.example.arsurvey.detect.DetectionResult
import kotlin.math.exp

/** Binary instance mask in **camera-image** pixel space (true = object pixel). */
class Mask(val binary: BooleanArray, val width: Int, val height: Int)

/** Colorized instance mask ready to overlay: ARGB, row-major, in image-pixel space. */
class MaskOverlay(val colors: IntArray, val width: Int, val height: Int)

/**
 * Turns one detection's 32 mask coefficients + the frame's mask prototypes into a binary
 * instance mask (Milestone 4), which Milestone 5 back-projects and M4 also colorizes for
 * display.
 *
 * The math (YOLO-Seg "prototype masks"):
 *   mask(y,x) = sigmoid( Σ_k coeff[k] · proto[k](y,x) )   at the 160×160 prototype grid,
 * then **cropped to the detection's box** and **thresholded at 0.5**, and finally
 * **nearest-upsampled** to image-pixel space (never bilinear — that blurs the hard edge).
 *
 * Coordinate chain: image px → letterboxed 640 (`x·scale + pad`) → prototype grid
 * (`/stride`, stride = 640/160 = 4).
 */
object MaskProcessor {

    private const val INPUT = 640
    private const val THRESHOLD = 0.5f
    private const val ALPHA = 0x80          // ~50% overlay
    private const val RGB = 0x00E676        // green — matches the target box

    /**
     * True if the detection at [detIndex]'s segmentation mask covers image-pixel ([imgX],[imgY]).
     *
     * Single-point evaluation (no full mask build): the point must lie in the detection box, then
     * we evaluate `sigmoid(coeffs·protos) > 0.5` at just the one prototype cell it maps to. Cheap
     * enough to test every detection on a tap. Same coordinate chain as [buildTargetMask].
     */
    fun maskContainsPoint(result: DetectionResult, detIndex: Int, imgX: Float, imgY: Float): Boolean {
        if (detIndex !in result.detections.indices) return false
        val det = result.detections[detIndex]
        // Masks are cropped to the detection box; a point outside it can't be inside the mask.
        if (imgX < det.x1 || imgX > det.x2 || imgY < det.y1 || imgY > det.y2) return false
        val coeffs = det.maskCoeffs
        val pc = result.protoChannels
        if (coeffs.size < pc) return false

        val protos = result.protos
        val pw = result.protoWidth
        val ph = result.protoHeight
        val plane = pw * ph
        val stride = INPUT.toFloat() / pw
        val px = ((imgX * result.letterboxScale + result.letterboxPadX) / stride).toInt()
        val py = ((imgY * result.letterboxScale + result.letterboxPadY) / stride).toInt()
        if (px < 0 || px >= pw || py < 0 || py >= ph) return false

        val idx = py * pw + px
        var s = 0f
        var k = 0
        while (k < pc) {
            s += coeffs[k] * protos[k * plane + idx]
            k++
        }
        return 1f / (1f + exp(-s)) > THRESHOLD
    }

    /**
     * Tap-to-select: the detection whose mask covers ([imgX],[imgY]), preferring the **smallest**
     * containing detection so tapping a small object on top of a big one selects the small one.
     * Returns -1 when the tap lands on no object (caller should then select nothing).
     */
    fun pickDetectionAtPoint(result: DetectionResult, imgX: Float, imgY: Float): Int {
        var best = -1
        var bestArea = Float.MAX_VALUE
        result.detections.forEachIndexed { i, d ->
            if (d.area < bestArea && maskContainsPoint(result, i, imgX, imgY)) {
                bestArea = d.area
                best = i
            }
        }
        return best
    }

    /** Binary instance mask for [targetIndex] in image-pixel space, or null if no target. */
    fun buildTargetMask(result: DetectionResult, targetIndex: Int): Mask? {
        if (targetIndex !in result.detections.indices) return null
        val det = result.detections[targetIndex]
        val coeffs = det.maskCoeffs
        val pc = result.protoChannels
        if (coeffs.size < pc) return null

        val protos = result.protos
        val pw = result.protoWidth
        val ph = result.protoHeight
        val plane = pw * ph
        val scale = result.letterboxScale
        val padX = result.letterboxPadX
        val padY = result.letterboxPadY
        val stride = INPUT.toFloat() / pw   // 640 / 160 = 4

        // Detection box mapped into the prototype grid (clamped) — evaluate only inside it.
        val pbx1 = ((det.x1 * scale + padX) / stride).toInt().coerceIn(0, pw - 1)
        val pby1 = ((det.y1 * scale + padY) / stride).toInt().coerceIn(0, ph - 1)
        val pbx2 = ((det.x2 * scale + padX) / stride).toInt().coerceIn(0, pw - 1)
        val pby2 = ((det.y2 * scale + padY) / stride).toInt().coerceIn(0, ph - 1)

        // 1) sigmoid(coeffs · protos) > 0.5, inside the box → binary at prototype res.
        val proto = BooleanArray(plane)
        for (py in pby1..pby2) {
            val row = py * pw
            for (px in pbx1..pbx2) {
                val idx = row + px
                var s = 0f
                var k = 0
                while (k < pc) {
                    s += coeffs[k] * protos[k * plane + idx]
                    k++
                }
                if (1f / (1f + exp(-s)) > THRESHOLD) proto[idx] = true
            }
        }

        // 2) nearest-neighbour upsample to image-pixel binary (only inside the box).
        val w = result.imageWidth
        val h = result.imageHeight
        val binary = BooleanArray(w * h)
        val iy1 = det.y1.toInt().coerceIn(0, h - 1)
        val iy2 = det.y2.toInt().coerceIn(0, h - 1)
        val ix1 = det.x1.toInt().coerceIn(0, w - 1)
        val ix2 = det.x2.toInt().coerceIn(0, w - 1)
        for (iy in iy1..iy2) {
            val py = ((iy * scale + padY) / stride).toInt()
            if (py < 0 || py >= ph) continue
            val prow = py * pw
            val orow = iy * w
            for (ix in ix1..ix2) {
                val px = ((ix * scale + padX) / stride).toInt()
                if (px in 0 until pw && proto[prow + px]) binary[orow + ix] = true
            }
        }
        return Mask(binary, w, h)
    }

    /** Colorizes a binary [mask] into a translucent-green ARGB overlay. */
    fun colorize(mask: Mask): MaskOverlay {
        val color = (ALPHA shl 24) or RGB
        val out = IntArray(mask.binary.size)
        for (i in out.indices) if (mask.binary[i]) out[i] = color
        return MaskOverlay(out, mask.width, mask.height)
    }
}
