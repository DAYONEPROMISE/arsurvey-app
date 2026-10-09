package com.example.arsurvey.reconstruct.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.example.arsurvey.segment.Mask

/**
 * Promptable segmenter backed by **EfficientSAM (ViT-S)** ONNX encoder + decoder. Ported (DI-free,
 * synchronous) from prod `data/engine/OnnxSegmenter.kt`, but with one deliberate difference: prod
 * crops+resamples the mask to a small box-aligned 128-grid for rendering, whereas the reconstruction
 * needs a **full-frame** mask so it lines up with the depth image by normalized position — exactly
 * what `PointCloudBuilder.build`'s `sx = maskW/dw` mapping expects. So [decodeFullFrame] keeps the
 * decoder's native `[H,W]` grid and just thresholds the logits (>0) of the best-IoU mask.
 *
 * Performance contract: the encoder runs once per image; the decoder runs once per box prompt.
 */
class EfficientSamSegmenter(
    private val environment: OrtEnvironment,
    context: Context,
) {
    private val encoder = OnnxSession(environment, context, ENCODER_ASSET)
    private val decoder = OnnxSession(environment, context, DECODER_ASSET)
    private val preprocessor = SamPreprocessor()

    private val encoderScratch = FloatArray(preprocessor.inputElements)
    private val encoderInput = OnnxBuffers.directFloatBuffer(preprocessor.inputElements)

    /**
     * Full-frame binary [Mask] for the object at [box] (normalized), or null if the decoder produced
     * nothing usable. The mask grid is the decoder's native `[width,height]` and spans the whole
     * frame in normalized coordinates.
     */
    fun segment(image: ProcessingImage, box: BoundingBox): Mask? {
        val encoderSession = encoder.session()
        val decoderSession = decoder.session()
        val encoderInputName = encoderSession.inputNames.first()

        // 1) Encode the image once.
        preprocessor.encodeImage(image, encoderScratch)
        encoderInput.clear()
        encoderInput.put(encoderScratch)
        encoderInput.rewind()

        val embedding = OnnxTensor.createTensor(
            environment,
            encoderInput,
            longArrayOf(1, 3, SamPreprocessor.INPUT_SIZE.toLong(), SamPreprocessor.INPUT_SIZE.toLong()),
        ).use { imageTensor ->
            encoderSession.run(mapOf(encoderInputName to imageTensor)).use { result ->
                val emb = result.get(OUTPUT_EMBEDDINGS).get() as OnnxTensor
                // Copy into a tensor we own so it survives past this result.
                OnnxTensor.createTensor(
                    environment,
                    OnnxBuffers.directFloatBufferOf(emb.readFloats()),
                    emb.info.shape,
                )
            }
        }

        // 2) Decode one mask for the prompt box, reusing the embedding.
        return embedding.use { emb -> decodeFullFrame(decoderSession, emb, box) }
    }

    private fun decodeFullFrame(
        decoderSession: OrtSession,
        embedding: OnnxTensor,
        box: BoundingBox,
    ): Mask? {
        val coords = OnnxTensor.createTensor(
            environment,
            OnnxBuffers.directFloatBufferOf(preprocessor.boxPointCoords(box)),
            longArrayOf(1, 1, SamPreprocessor.POINTS_PER_BOX.toLong(), 2),
        )
        val labels = OnnxTensor.createTensor(
            environment,
            OnnxBuffers.directFloatBufferOf(preprocessor.boxPointLabels()),
            longArrayOf(1, 1, SamPreprocessor.POINTS_PER_BOX.toLong()),
        )

        return coords.use { c ->
            labels.use { l ->
                decoderSession.run(
                    mapOf(
                        INPUT_EMBEDDINGS to embedding,
                        INPUT_COORDS to c,
                        INPUT_LABELS to l,
                    ),
                ).use { result ->
                    val masks = result.get(OUTPUT_MASKS).get() as OnnxTensor
                    val iou = result.get(OUTPUT_IOU).get() as OnnxTensor
                    val shape = masks.info.shape // [1, queries, numMasks, H, W]
                    fullFrameMask(
                        masks = masks.readFloats(),
                        numMasks = shape[2].toInt(),
                        height = shape[3].toInt(),
                        width = shape[4].toInt(),
                        iou = iou.readFloats(),
                    )
                }
            }
        }
    }

    /**
     * Picks the highest-IoU candidate mask and thresholds its logits (>0) over the native `[H,W]`
     * grid into a full-frame binary [Mask]. Single query, so the leading dims are 1 and the best
     * mask lives at `best * H*W` (mirrors prod's `MaskPostprocessor`, minus the box crop).
     */
    private fun fullFrameMask(
        masks: FloatArray,
        numMasks: Int,
        height: Int,
        width: Int,
        iou: FloatArray,
    ): Mask? {
        if (numMasks <= 0 || height <= 0 || width <= 0) return null
        val plane = height * width

        var best = 0
        for (m in 1 until numMasks) {
            if (iou.getOrElse(m) { Float.NEGATIVE_INFINITY } > iou.getOrElse(best) { Float.NEGATIVE_INFINITY }) {
                best = m
            }
        }
        val offset = best * plane
        val binary = BooleanArray(plane)
        for (i in 0 until plane) {
            binary[i] = masks[offset + i] > MASK_LOGIT_THRESHOLD
        }
        Log.d(TAG, "EfficientSAM mask ${width}x$height iou=${iou.getOrElse(best) { 0f }} px=${binary.count { it }}")
        return Mask(binary, width, height)
    }

    fun close() {
        encoder.close()
        decoder.close()
    }

    private companion object {
        const val TAG = "EfficientSamSegmenter"
        const val ENCODER_ASSET = "models/efficient_sam_vits_encoder.onnx"
        const val DECODER_ASSET = "models/efficient_sam_vits_decoder.onnx"
        const val OUTPUT_EMBEDDINGS = "image_embeddings"
        const val INPUT_EMBEDDINGS = "image_embeddings"
        const val INPUT_COORDS = "batched_point_coords"
        const val INPUT_LABELS = "batched_point_labels"
        const val OUTPUT_MASKS = "masks"
        const val OUTPUT_IOU = "iou_predictions"
        const val MASK_LOGIT_THRESHOLD = 0f
    }
}
