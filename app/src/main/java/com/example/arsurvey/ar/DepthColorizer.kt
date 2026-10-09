package com.example.arsurvey.ar

/**
 * Turns ARCore's 16-bit depth image (millimeters) into ARGB colors for display.
 *
 * Milestone 2 uses this purely as a *visual assertion*: if the colorized overlay
 * tracks scene geometry (near objects one color, far another), the depth stream is
 * healthy and Milestones 5+ can trust it. It does NOT do any geometry — it only maps
 * scalar depth → color.
 *
 * Design choices:
 * - **Fixed display range** ([NEAR_MM]..[FAR_MM]) instead of per-frame auto-ranging.
 *   Auto-ranging makes the whole scene flicker as the min/max wobble frame to frame;
 *   a fixed band keeps a given real-world distance the same color every frame.
 * - **near = warm, far = cool** — the convention requested in the plan.
 * - **invalid (0 mm) → fully transparent**, so holes show the live camera underneath
 *   rather than a false "very near" color.
 */
object DepthColorizer {

    /** Distances mapped to the two ends of the colormap (millimeters). */
    private const val NEAR_MM = 300f   // 0.30 m — closer than this clamps to "nearest"
    private const val FAR_MM = 5000f   // 5.00 m — farther than this clamps to "farthest"

    /** Overlay opacity for valid pixels (0..255). Lets the camera show through a bit. */
    private const val ALPHA = 0xB0

    // Warm → cool control stops (index 0 = nearest, last = farthest). Turbo-ish ramp.
    private val STOPS = intArrayOf(
        0xF44336, // red      (nearest)
        0xFF9800, // orange
        0xFFEB3B, // yellow
        0x4CAF50, // green
        0x00BCD4, // cyan
        0x2196F3, // blue     (farthest)
    )

    /**
     * @param depth  row-major 16-bit depth samples, [width]*[height], millimeters
     *               (0 = invalid / no return). Read as unsigned.
     * @return ARGB_8888 packed colors, one per sample. Invalid samples are transparent.
     */
    fun colorize(depth: ShortArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        val span = FAR_MM - NEAR_MM
        for (i in out.indices) {
            val mm = depth[i].toInt() and com.example.arsurvey.depth.Depth16.DEPTH_MASK // DEPTH16 low 13 bits
            if (mm == 0) {
                out[i] = 0                          // transparent hole
                continue
            }
            val t = ((mm - NEAR_MM) / span).coerceIn(0f, 1f)
            out[i] = sample(t)
        }
        return out
    }

    /** Piecewise-linear interpolation across [STOPS]; [t] in 0..1 (0 = near). */
    private fun sample(t: Float): Int {
        val segments = STOPS.size - 1
        val scaled = t * segments
        val idx = scaled.toInt().coerceIn(0, segments - 1)
        val f = scaled - idx
        val c0 = STOPS[idx]
        val c1 = STOPS[idx + 1]
        val r = lerp((c0 shr 16) and 0xFF, (c1 shr 16) and 0xFF, f)
        val g = lerp((c0 shr 8) and 0xFF, (c1 shr 8) and 0xFF, f)
        val b = lerp(c0 and 0xFF, c1 and 0xFF, f)
        return (ALPHA shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f + 0.5f).toInt()
}
