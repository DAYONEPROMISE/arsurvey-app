package com.example.arsurvey.scan

/**
 * The multi-view scan lifecycle.
 *
 * ```
 * IDLE → SCANNING → STABILIZING → COMPLETE
 * ```
 *
 * - **IDLE** — nothing is being accumulated; the accumulator/coverage are empty or held.
 * - **SCANNING** — every (throttled) frame we segment, back-project, transform to world,
 *   add points to the [PointCloudAccumulator] and mark the viewpoint in the [CoverageTracker].
 * - **STABILIZING** — enough coverage exists, but the dimensions are still converging.
 * - **COMPLETE** — coverage + geometry are stable enough to report a final measurement.
 *
 * The IDLE→SCANNING transition is user-driven (a Scan button). SCANNING→STABILIZING→COMPLETE
 * are decided by the measurement pipeline (Increment 2); Increment 1 only drives IDLE↔SCANNING
 * so the core multi-view-alignment experiment can be run and verified first.
 */
enum class ScanState {
    IDLE,
    SCANNING,
    STABILIZING,
    COMPLETE,
}
