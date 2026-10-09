package com.example.arsurvey.reconstruct.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log

/**
 * Open-vocabulary object detector backed by the bundled **YOLOE-11s-seg (prompt-free, 4585
 * classes)** ONNX model: decode → preprocess → session.run → postprocess. Ported (DI-free,
 * synchronous) from prod `data/engine/OnnxObjectDetector.kt`.
 *
 * Unlike the live path's `yolo11n-seg` (80 COCO classes, which can't name a plain cardboard box),
 * YOLOE's huge open vocabulary reliably fires on everyday household objects — that's the whole point
 * of the swap. The 32 mask coefficients it also predicts are ignored; the actual silhouette comes
 * from EfficientSAM ([EfficientSamSegmenter]).
 */
class YoloeDetector(
    private val environment: OrtEnvironment,
    context: Context,
) {
    private val model = OnnxSession(environment, context, MODEL_ASSET)
    private val preprocessor = YoloPreprocessor(INPUT_SIZE)
    private val postprocessor = YoloPostprocessor()
    private val labels: List<String> =
        context.assets.open(LABELS_ASSET).bufferedReader().use { r -> r.readLines().map { it.trim() } }

    private val scratch = FloatArray(preprocessor.inputElements)
    private val inputBuffer = OnnxBuffers.directFloatBuffer(preprocessor.inputElements)

    /** Runs detection on one image. Heavy — call off the main thread. */
    fun detect(image: ProcessingImage): List<Detection> {
        val session = model.session()
        val inputName = session.inputNames.first()

        val transform = preprocessor.preprocess(image, scratch)
        inputBuffer.clear()
        inputBuffer.put(scratch)
        inputBuffer.rewind()

        val inputTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(OUTPUT_BOXES).get() as OnnxTensor
                val shape = output.info.shape // [1, channels, anchors]
                val detections = postprocessor.parse(
                    output = output.floatBuffer,
                    channels = shape[1].toInt(),
                    anchors = shape[2].toInt(),
                    labels = labels,
                    transform = transform,
                )
                Log.d(TAG, "YOLOE produced ${detections.size} detections")
                return detections
            }
        }
    }

    fun close() = model.close()

    private companion object {
        const val TAG = "YoloeDetector"
        const val MODEL_ASSET = "models/yoloe-11s-seg-pf.onnx"
        const val LABELS_ASSET = "models/yoloe_labels.txt"
        const val OUTPUT_BOXES = "output0"
        const val INPUT_SIZE = 640
    }
}
