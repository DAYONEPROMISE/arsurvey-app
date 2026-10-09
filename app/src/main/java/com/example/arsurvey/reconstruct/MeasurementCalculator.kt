package com.example.arsurvey.reconstruct

import com.example.arsurvey.measure.FloorEstimator
import com.example.arsurvey.measure.FootprintEstimator
import com.example.arsurvey.measure.MeasurementEstimator
import com.example.arsurvey.measure.Obb
import com.example.arsurvey.measure.ObjectMeasurement
import com.example.arsurvey.measure.OutlierFilter

/**
 * The post-capture dimension estimate for one reconstructed object: the cm result plus the oriented
 * box (for the 3D overlay) and enough provenance to be honest about how much to trust it.
 *
 * [measurement] is null when the cloud was too sparse / degenerate to fit a box — [note] then says
 * why, and the UI shows "unavailable" instead of a fabricated number.
 */
data class PredictedMeasurement(
    /** The width/height/depth (cm) + confidence/coverage, or null when unmeasurable. */
    val measurement: ObjectMeasurement?,
    /** Gravity-vertical, footprint-oriented box for the 3D wireframe overlay (null when unmeasurable). */
    val box: Obb?,
    /** How many of the object's photos contributed any 3D points. */
    val viewsUsed: Int,
    /** Total photos in the object (normally 3). */
    val totalViews: Int,
    /** Whether the floor came from a validated ARCore plane (always false here — see [compute]). */
    val fromFloorPlane: Boolean,
    /** Human-readable reason when [measurement] is null. */
    val note: String?,
)

/**
 * Runs the **existing** authoritative measurement stack (`measure/`) over the reconstructed
 * world-space cloud. This is pure glue: it introduces no new geometry — it concatenates the
 * per-photo clouds [ObjectReconstructor] already produced and feeds them through the same
 * outlier-reject → floor/height → min-area footprint → assemble sequence the live pipeline
 * (`ui/MeasureViewModel`) uses, minus the live-only temporal stabilizer (there are no frames to
 * smooth over — reconstruction is one shot).
 */
object MeasurementCalculator {

    /**
     * Point budget at which the density term of the confidence heuristic saturates. **Uncalibrated
     * placeholder** — picked without on-device point-density data, so treat [ObjectMeasurement.confidence]
     * as a rough ordering signal (more views + denser cloud = higher), never as a calibrated
     * probability. Re-tune once real masked-object densities are logged on-device.
     */
    private const val TARGET_POINTS = 3000f

    /**
     * Minimum angular spread between any two usable viewpoints (degrees, measured at the cloud
     * centroid) for the 3-view reconstruction to be trusted. Three shots from nearly the same spot
     * add no parallax — the footprint collapses to one viewing direction and W/D are underestimated.
     * Skipped when fewer than 2 usable views carry a camera position (nothing to compare).
     */
    private const val MIN_SPREAD_DEG = 20f

    fun compute(result: ReconstructionResult): PredictedMeasurement {
        val totalViews = result.perPhoto.size
        val viewsUsed = result.perPhoto.count { it.pointCount > 0 }

        if (totalViews < 3 || viewsUsed < 3) {
            return unavailable(
                viewsUsed, totalViews,
                "Need 3 good viewpoints (only $viewsUsed of $totalViews usable) — " +
                    "retake with ~30° viewpoint spread and valid depth.",
            )
        }

        // Concatenate every photo's world points into one packed [x0,y0,z0,…] cloud.
        val total = result.perPhoto.sumOf { it.worldPoints.size }
        val cloud = FloatArray(total)
        var o = 0
        for (pc in result.perPhoto) {
            val p = pc.worldPoints
            System.arraycopy(p, 0, cloud, o, p.size)
            o += p.size
        }

        if (cloud.size < 3 * MIN_POINTS) {
            return unavailable(viewsUsed, totalViews, "too few points (${cloud.size / 3})")
        }

        // Viewpoint-spread guard: the usable views must look at the object from genuinely
        // different directions, or the combined cloud is single-view in disguise.
        val spread = maxViewpointSpread(result, cloud)
        if (spread != null && spread < MIN_SPREAD_DEG) {
            return unavailable(
                viewsUsed, totalViews,
                "Viewpoints too close together (max spread ${"%.0f".format(spread)}°, " +
                    "need ≥${"%.0f".format(MIN_SPREAD_DEG)}°) — " +
                    "retake with ~30° steps around the object.",
            )
        }

        // Same order as the live path: drop stray depth points, then floor+footprint.
        // No ARCore floor plane is saved with a capture (only the live FrameExtractor computes it),
        // so we pass null and FloorEstimator falls back to the object's own cloud-base percentile.
        val inliers = OutlierFilter.filter(cloud)
        val floor = FloorEstimator.estimate(inliers, floorPlaneY = null)
        val footprint = FootprintEstimator.estimate(inliers)
        val measured = MeasurementEstimator.assemble(floor, footprint)
            ?: return unavailable(
                viewsUsed, totalViews,
                if (!floor.valid) "no valid floor/height" else "no footprint rectangle",
            )

        val inlierPoints = inliers.size / 3
        val coverage = if (totalViews > 0) viewsUsed.toFloat() / totalViews else 0f
        val density = (inlierPoints / TARGET_POINTS).coerceIn(0f, 1f)
        val confidence = 0.5f * coverage + 0.5f * density

        val objectMeasurement = ObjectMeasurement.from(
            whdMeters = floatArrayOf(measured.widthM, measured.heightM, measured.depthM),
            confidence = confidence,
            coverage = coverage,
        )
        return PredictedMeasurement(
            measurement = objectMeasurement,
            box = measured.box,
            viewsUsed = viewsUsed,
            totalViews = totalViews,
            fromFloorPlane = floor.fromPlane,
            note = null,
        )
    }

    private fun unavailable(viewsUsed: Int, totalViews: Int, note: String) = PredictedMeasurement(
        measurement = null, box = null, viewsUsed = viewsUsed, totalViews = totalViews,
        fromFloorPlane = false, note = note,
    )

    /** Below this the floor/footprint estimators won't trust the cloud anyway (their own minPoints). */
    private const val MIN_POINTS = 40

    /**
     * Largest angle (degrees) subtended at the cloud centroid by any pair of usable viewpoints'
     * camera positions, or null when fewer than 2 usable views carry a position.
     */
    private fun maxViewpointSpread(result: ReconstructionResult, cloud: FloatArray): Float? {
        var cx = 0f; var cy = 0f; var cz = 0f
        val n = cloud.size / 3
        var i = 0
        while (i < cloud.size) { cx += cloud[i]; cy += cloud[i + 1]; cz += cloud[i + 2]; i += 3 }
        cx /= n; cy /= n; cz /= n

        val dirs = ArrayList<FloatArray>()
        for (pc in result.perPhoto) {
            if (pc.pointCount == 0) continue
            val p = pc.cameraPosition
            if (p == null || p.size < 3) continue
            val dx = p[0] - cx; val dy = p[1] - cy; val dz = p[2] - cz
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len > 1e-6f) dirs.add(floatArrayOf(dx / len, dy / len, dz / len))
        }
        if (dirs.size < 2) return null
        var best = 0f
        for (a in dirs.indices) for (b in a + 1 until dirs.size) {
            val dot = (dirs[a][0] * dirs[b][0] + dirs[a][1] * dirs[b][1] + dirs[a][2] * dirs[b][2])
                .coerceIn(-1f, 1f)
            val deg = Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
            if (deg > best) best = deg
        }
        return best
    }
}
