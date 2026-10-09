package com.example.arsurvey.reconstruct.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Small helpers for moving float data in and out of ONNX Runtime with as little allocation as
 * practical. ONNX Runtime needs a **direct** [FloatBuffer] to build a tensor without copying, so
 * input buffers are allocated direct and reused across inferences. Ported from prod
 * `data/ml/OnnxBuffers.kt`.
 */
internal object OnnxBuffers {

    /** Allocates a reusable direct float buffer with room for [capacity] floats. */
    fun directFloatBuffer(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    /** Copies [source] into a fresh direct buffer positioned at 0 (for one-shot tensors). */
    fun directFloatBufferOf(source: FloatArray): FloatBuffer =
        directFloatBuffer(source.size).apply {
            put(source)
            rewind()
        }
}

/** Reads an output tensor's contents into a plain [FloatArray] for postprocessing. */
internal fun OnnxTensor.readFloats(): FloatArray {
    val buffer = floatBuffer
    val out = FloatArray(buffer.remaining())
    buffer.get(out)
    return out
}

/**
 * Owns a **single** ONNX Runtime session, loaded once from a bundled app asset and reused for every
 * inference. Sessions are expensive (they parse the model and allocate native weight buffers), so
 * they must never be recreated per call. Ported (DI-free, synchronous) from prod `data/ml/OnnxModel
 * .kt` + `InferenceTuning.kt`; the reconstruction pipeline runs serially on one background
 * coroutine, so a plain lazy-load with a synchronized guard is enough (no coroutine Mutex needed).
 */
internal class OnnxSession(
    private val environment: OrtEnvironment,
    private val context: Context,
    private val assetPath: String,
) {
    @Volatile
    private var cached: OrtSession? = null

    /** The loaded session, building it exactly once on first call. */
    fun session(): OrtSession {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load().also { cached = it }
        }
    }

    private fun load(): OrtSession = try {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Bound the intra-op pool to a couple of cores so this heavy offline inference doesn't
            // monopolize every CPU (prod's `configureForInference`).
            val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            setIntraOpNumThreads(threads)
        }
        environment.createSession(bytes, options).also { session ->
            Log.i(TAG, "Loaded '$assetPath' inputs=${session.inputNames} outputs=${session.outputNames}")
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load ONNX model '$assetPath'", t)
        throw IllegalStateException("Could not load ONNX model asset '$assetPath': ${t.message}", t)
    }

    /** Releases native resources. Safe to call multiple times. */
    fun close() {
        runCatching { cached?.close() }
        cached = null
    }

    private companion object {
        const val TAG = "OnnxSession"
    }
}
