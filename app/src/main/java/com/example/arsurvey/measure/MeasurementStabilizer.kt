package com.example.arsurvey.measure

import kotlin.math.abs
import kotlin.math.max

/**
 * Temporal convergence for the authoritative W×H×D (Increment 2, M-Stability).
 *
 * Two jobs:
 *  - **Smooth** the reported dimensions with a per-axis exponential moving average so the HUD
 *    doesn't flicker between frames.
 *  - **Decide convergence** by watching a short window of recent smoothed values: once every
 *    dimension's relative spread across the window is below [convergenceThreshold], the
 *    measurement is considered stable enough to complete.
 *
 * A [stability] score in 0..1 (1 = rock-steady) also feeds the confidence estimate.
 * Not thread-safe: driven only from the render loop.
 */
class MeasurementStabilizer(
    private val alpha: Float = 0.2f,
    private val windowSize: Int = 8,
    /** Max per-axis relative spread (range/mean) across the window to count as converged (2%). */
    private val convergenceThreshold: Float = 0.02f,
) {
    private var smoothed: FloatArray? = null
    private val window = ArrayDeque<FloatArray>()

    /** Feed a raw [w,h,d] (meters); returns the smoothed [w,h,d]. */
    fun update(raw: FloatArray): FloatArray {
        val prev = smoothed
        val next = if (prev == null) raw.copyOf()
        else FloatArray(3) { prev[it] + alpha * (raw[it] - prev[it]) }
        smoothed = next

        window.addLast(next.copyOf())
        while (window.size > windowSize) window.removeFirst()
        return next.copyOf()
    }

    fun current(): FloatArray? = smoothed?.copyOf()

    /** Largest per-axis relative spread across the window; small = stable. */
    private fun relativeSpread(): Float {
        if (window.size < windowSize) return Float.MAX_VALUE
        var worst = 0f
        for (axis in 0..2) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var sum = 0f
            for (v in window) { lo = minOf(lo, v[axis]); hi = maxOf(hi, v[axis]); sum += v[axis] }
            val mean = sum / window.size
            if (mean > 1e-4f) worst = max(worst, abs(hi - lo) / mean)
        }
        return worst
    }

    /** 0..1 stability score (1 when the window is perfectly steady). */
    fun stability(): Float {
        val spread = relativeSpread()
        if (spread == Float.MAX_VALUE) return 0f
        // Full score at 0 spread, zero score at 3× the convergence threshold.
        return (1f - spread / (convergenceThreshold * 3f)).coerceIn(0f, 1f)
    }

    fun converged(): Boolean = relativeSpread() <= convergenceThreshold

    fun reset() {
        smoothed = null
        window.clear()
    }
}
