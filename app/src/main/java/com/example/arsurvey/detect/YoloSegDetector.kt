package com.example.arsurvey.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.arsurvey.ar.YuvImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLO11-Seg object detector on ONNX Runtime.
 *
 * Milestone 3 uses only the **detection** half of the model. The exported graph
 * (`yolo11n-seg.onnx`, 640×640) has two outputs:
 *   - `output0` `[1, 116, 8400]` — per anchor: 4 box (cx,cy,w,h in 640-px) + 80 class
 *     probs + 32 mask coefficients. **Feature-major**: value(channel, anchor) lives at
 *     flat index `channel*8400 + anchor`.
 *   - `output1` `[1, 32, 160, 160]` — mask prototypes. **Ignored until Milestone 4.**
 *
 * Boxes come out already decoded (DFL applied, anchors added) and class scores already
 * sigmoid'd, so post-processing is just: pick best class → threshold → xywh→xyxy →
 * un-letterbox → NMS. Output boxes are in the **camera image's** pixel space.
 */
class YoloSegDetector(context: Context) : ObjectDetector {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val labels: List<String>
    override val backend: String

    // Reused across frames (single-flight on one background thread → safe to reuse).
    private val inputBuf: FloatBuffer =
        ByteBuffer.allocateDirect(3 * IMG * IMG * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var rgb = IntArray(0)
    private var protos = FloatArray(0)   // reused copy of output1 each frame
    private var loggedFirst = false

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        labels = context.assets.open(LABELS_ASSET).bufferedReader().use { r ->
            r.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        // Try NNAPI, then a warmup run to (a) pay the EP's graph-compile cost now, during
        // "loading model...", instead of stalling the first real frame, and (b) prove NNAPI
        // can actually EXECUTE this graph — addNnapi() succeeding doesn't guarantee run
        // succeeds. If warmup throws, fall back to plain CPU, which reliably runs YOLO ops.
        // Reliability > peak fps for this PoC.
        var s: OrtSession
        var b: String
        try {
            s = env.createSession(modelBytes, OrtSession.SessionOptions().apply { addNnapi() })
            warmup(s)
            b = "NNAPI"
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI EP unusable (${t.message}); falling back to CPU", t)
            s = env.createSession(modelBytes, OrtSession.SessionOptions())
            warmup(s)
            b = "CPU"
        }
        session = s
        backend = b
        Log.i(TAG, "Detector ready: backend=$backend, ${labels.size} labels, input ${IMG}x$IMG")
    }

    /** One dummy run on zeros so the first real frame doesn't pay EP compile latency. */
    private fun warmup(sess: OrtSession) {
        val t0 = SystemClock.elapsedRealtime()
        inputBuf.clear()
        var i = 0
        val cap = inputBuf.capacity()
        while (i < cap) { inputBuf.put(i, 0f); i++ }
        inputBuf.rewind()
        val input = OnnxTensor.createTensor(env, inputBuf, INPUT_SHAPE)
        try { sess.run(mapOf(INPUT_NAME to input)).close() } finally { input.close() }
        Log.i(TAG, "warmup run: ${SystemClock.elapsedRealtime() - t0} ms")
    }

    override fun detect(image: YuvImage): DetectionResult {
        val w = image.width
        val h = image.height
        if (rgb.size != w * h) rgb = IntArray(w * h)
        yuvToRgb(image, rgb)

        // Letterbox: scale the (landscape) image to fit 640×640 keeping aspect, center-pad.
        val scale = min(IMG / w.toFloat(), IMG / h.toFloat())
        val newW = (w * scale).roundToInt()
        val newH = (h * scale).roundToInt()
        val padX = (IMG - newW) / 2
        val padY = (IMG - newH) / 2
        fillTensor(rgb, w, h, scale, padX, padY)

        val input = OnnxTensor.createTensor(env, inputBuf, INPUT_SHAPE)
        val results = session.run(mapOf(INPUT_NAME to input))
        val out = try {
            val detections = decode((results.get(0) as OnnxTensor).floatBuffer, scale, padX, padY, w, h)
            // output1: mask prototypes [1,32,160,160]. Copy them out before the Result closes.
            val protoBuf = (results.get(1) as OnnxTensor).floatBuffer
            if (protos.size != protoBuf.remaining()) protos = FloatArray(protoBuf.remaining())
            protoBuf.get(protos)
            DetectionResult(
                detections = detections,
                protos = protos,
                protoChannels = NUM_MASK,
                protoHeight = PROTO,
                protoWidth = PROTO,
                letterboxScale = scale,
                letterboxPadX = padX,
                letterboxPadY = padY,
                imageWidth = w,
                imageHeight = h,
            )
        } finally {
            results.close()
            input.close()
        }
        if (!loggedFirst) {
            loggedFirst = true
            Log.i(TAG, "first inference on ${w}x$h image: ${out.detections.size} detections")
        }
        return out
    }

    /** Nearest-neighbour letterbox straight into the CHW, RGB, /255 input buffer. */
    private fun fillTensor(rgb: IntArray, w: Int, h: Int, scale: Float, padX: Int, padY: Int) {
        val plane = IMG * IMG
        for (ty in 0 until IMG) {
            val syF = (ty - padY) / scale
            val sy = syF.toInt()
            val rowInside = syF >= 0f && sy < h
            for (tx in 0 until IMG) {
                var r = PAD
                var g = PAD
                var b = PAD
                if (rowInside) {
                    val sxF = (tx - padX) / scale
                    val sx = sxF.toInt()
                    if (sxF >= 0f && sx < w) {
                        val c = rgb[sy * w + sx]
                        r = ((c shr 16) and 0xFF) / 255f
                        g = ((c shr 8) and 0xFF) / 255f
                        b = (c and 0xFF) / 255f
                    }
                }
                val p = ty * IMG + tx
                inputBuf.put(p, r)
                inputBuf.put(plane + p, g)
                inputBuf.put(2 * plane + p, b)
            }
        }
        inputBuf.rewind()
    }

    /** output0 → thresholded, un-letterboxed, NMS'd detections in image-pixel space. */
    private fun decode(
        fb: FloatBuffer, scale: Float, padX: Int, padY: Int, imgW: Int, imgH: Int,
    ): List<Detection> {
        val candidates = ArrayList<Detection>()
        for (a in 0 until NUM_ANCHORS) {
            // Best class for this anchor.
            var best = 0f
            var bestClass = -1
            var c = 0
            while (c < NUM_CLASSES) {
                val s = fb.get((4 + c) * NUM_ANCHORS + a)
                if (s > best) {
                    best = s
                    bestClass = c
                }
                c++
            }
            if (best < CONF_THRESHOLD || bestClass < 0) continue

            val cx = fb.get(a)
            val cy = fb.get(NUM_ANCHORS + a)
            val bw = fb.get(2 * NUM_ANCHORS + a)
            val bh = fb.get(3 * NUM_ANCHORS + a)

            // 640 letterbox space → original image space.
            val x1 = ((cx - bw / 2f) - padX) / scale
            val y1 = ((cy - bh / 2f) - padY) / scale
            val x2 = ((cx + bw / 2f) - padX) / scale
            val y2 = ((cy + bh / 2f) - padY) / scale

            // 32 mask coefficients live in channels 84..115 for this anchor.
            val coeffs = FloatArray(NUM_MASK)
            var k = 0
            while (k < NUM_MASK) {
                coeffs[k] = fb.get((4 + NUM_CLASSES + k) * NUM_ANCHORS + a)
                k++
            }

            candidates.add(
                Detection(
                    x1 = x1.coerceIn(0f, imgW.toFloat()),
                    y1 = y1.coerceIn(0f, imgH.toFloat()),
                    x2 = x2.coerceIn(0f, imgW.toFloat()),
                    y2 = y2.coerceIn(0f, imgH.toFloat()),
                    score = best,
                    classId = bestClass,
                    label = labels.getOrElse(bestClass) { "cls$bestClass" },
                    maskCoeffs = coeffs,
                )
            )
        }
        return nms(candidates, IOU_THRESHOLD)
    }

    /** Greedy per-class non-max suppression. */
    private fun nms(list: List<Detection>, iouThr: Float): List<Detection> {
        val sorted = list.sortedByDescending { it.score }
        val removed = BooleanArray(sorted.size)
        val kept = ArrayList<Detection>()
        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            kept.add(a)
            if (kept.size >= MAX_DETECTIONS) break
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                if (b.classId == a.classId && iou(a, b) > iouThr) removed[j] = true
            }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** BT.601 full-range YUV_420_888 → packed ARGB (opaque). */
    private fun yuvToRgb(img: YuvImage, out: IntArray) {
        val w = img.width
        val h = img.height
        for (row in 0 until h) {
            val yBase = row * img.yRowStride
            val uvRow = row shr 1
            val uBase = uvRow * img.uRowStride
            val vBase = uvRow * img.vRowStride
            for (col in 0 until w) {
                val yy = img.y[yBase + col].toInt() and 0xFF
                val uvCol = col shr 1
                val uu = (img.u[uBase + uvCol * img.uPixelStride].toInt() and 0xFF) - 128
                val vv = (img.v[vBase + uvCol * img.vPixelStride].toInt() and 0xFF) - 128
                val r = (yy + (1.370705f * vv)).toInt().coerceIn(0, 255)
                val g = (yy - (0.337633f * uu) - (0.698001f * vv)).toInt().coerceIn(0, 255)
                val b = (yy + (1.732446f * uu)).toInt().coerceIn(0, 255)
                out[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "YoloSegDetector"
        private const val MODEL_ASSET = "yolo11n-seg.onnx"
        private const val LABELS_ASSET = "coco_labels.txt"
        private const val INPUT_NAME = "images"
        private const val IMG = 640
        private const val NUM_ANCHORS = 8400
        private const val NUM_CLASSES = 80
        private const val NUM_MASK = 32     // mask coefficients / prototype channels
        private const val PROTO = 160       // prototype spatial resolution (640 / 4)
        private const val CONF_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 30
        private const val PAD = 114f / 255f   // ultralytics letterbox gray
        private val INPUT_SHAPE = longArrayOf(1, 3, IMG.toLong(), IMG.toLong())
    }
}
