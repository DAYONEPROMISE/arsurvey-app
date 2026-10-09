package com.example.arsurvey.reconstruct

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arsurvey.capture.model.CapturedPhoto
import com.example.arsurvey.reconstruct.ml.SegmentationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the reconstruction screen. */
data class ReconstructionUiState(
    val loading: Boolean = true,
    val progressText: String? = null,
    val result: ReconstructionResult? = null,
    val error: String? = null,
)

/**
 * Runs [ObjectReconstructor] off the main thread and exposes its state. Owns the single
 * [SegmentationPipeline] (YOLOE + EfficientSAM) used for the 3 inferences — loaded lazily, closed in
 * [onCleared].
 */
class ReconstructionViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ReconstructionUiState())
    val state: StateFlow<ReconstructionUiState> = _state.asStateFlow()

    private var pipeline: SegmentationPipeline? = null
    private var started = false
    private var runJob: kotlinx.coroutines.Job? = null

    /** Cancel a running reconstruction (user action). */
    fun cancel() {
        runJob?.cancel()
        _state.update { it.copy(loading = false, progressText = null, error = "Reconstruction cancelled.") }
    }

    /** Idempotent: starts reconstruction once for the given photos. */
    fun start(photos: List<CapturedPhoto>) {
        if (started) return
        started = true
        runJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (photos.size < 3) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Need 3 photos for a measurement " +
                                "(only ${photos.size} saved — retake the missing viewpoints).",
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(loading = true, progressText = "Checking models…") }
                val missing = com.example.arsurvey.reconstruct.ml.ModelAssets
                    .missing(getApplication())
                if (missing.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = com.example.arsurvey.reconstruct.ml.ModelAssets
                                .missingMessage(missing),
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(loading = true, progressText = "Loading models…") }
                val p = pipeline ?: SegmentationPipeline(getApplication()).also { pipeline = it }
                _state.update { it.copy(progressText = "Segmenting…") }
                val result = ObjectReconstructor.reconstruct(photos, p) { idx ->
                    val stage = when (idx) {
                        0 -> "Segmenting"
                        1 -> "Building cloud"
                        else -> "Fitting box"
                    }
                    _state.update { it.copy(progressText = "$stage — photo ${idx + 1} of ${photos.size}…") }
                }
                _state.update { it.copy(progressText = "Stabilizing…") }
                kotlinx.coroutines.delay(50)
                if (result.measurement?.measurement == null) {
                    val reason = result.measurement?.note
                        ?: result.perPhoto.firstOrNull { it.note != null }?.note
                        ?: "Could not measure — retake with more viewpoint spread."
                    _state.update {
                        it.copy(loading = false, result = result, error = reason)
                    }
                } else {
                    Log.i(TAG, "Reconstruction done: ${result.totalPoints} points across ${photos.size} photos")
                    _state.update { it.copy(loading = false, result = result, progressText = null) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Reconstruction failed", t)
                val msg = when (t) {
                    is IllegalStateException -> t.message ?: "reconstruction failed"
                    else -> "Something went wrong during reconstruction — please retake the photos."
                }
                _state.update { it.copy(loading = false, error = msg) }
            }
        }
    }

    override fun onCleared() {
        pipeline?.close()
        pipeline = null
    }

    private companion object {
        const val TAG = "ReconstructionVM"
    }
}
