package com.example.arsurvey.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.arsurvey.ar.ArUiState
import com.example.arsurvey.measure.Obb
import com.google.ar.core.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView

/**
 * Milestone 1 + 2 screen.
 *
 * - Renders the live camera via SceneView's [ARScene].
 * - Turns on the built-in plane renderer (the "tracks planes" deliverable).
 * - Draws ARCore feature points as an overlay (the "displays feature points"
 *   deliverable) by projecting each 3D world point to 2D screen space.
 * - **M2:** draws the colorized depth map as a toggleable overlay (near = warm,
 *   far = cool) and shows depth resolution in the HUD.
 * - Shows a HUD with tracking state, point count, and Depth API support.
 */
@Composable
fun ArScreen(
    viewModel: MeasureViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // SceneView plumbing (Filament engine, view, camera node, node graph).
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    Box(modifier = modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            collisionSystem = collisionSystem,
            childNodes = childNodes,
            cameraNode = cameraNode,
            // Built-in translucent mesh over detected planes.
            planeRenderer = true,
            sessionConfiguration = { session, config ->
                // Probe Depth support at runtime — the ONLY reliable gate. On the
                // OnePlus Nord 5 this decides whether Milestones 2+ can run.
                val depthSupported =
                    session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                config.depthMode =
                    if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                viewModel.setDepthSupported(depthSupported)
            },
            onTrackingFailureChanged = { reason ->
                viewModel.setTrackingFailure(reason?.name)
            },
            onSessionUpdated = { session, frame ->
                viewModel.onFrame(session, frame)
            },
        )

        // Tap-to-select layer: sits above the camera but below the HUD/controls (which are drawn
        // later, so they win touch priority). Reports the tap in normalized screen coords; the
        // ViewModel resolves which detection's mask it hit. Canvas overlays don't consume touch,
        // so this is the only touch handler over the scene.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val nx = (offset.x / size.width).coerceIn(0f, 1f)
                        val ny = (offset.y / size.height).coerceIn(0f, 1f)
                        viewModel.onTap(nx, ny)
                    }
                }
        )

        // Overlays, bottom to top: depth wash, target mask, cloud, feature dots, boxes, OBB.
        DepthOverlay(uiState, Modifier.fillMaxSize())
        MaskOverlay(uiState, Modifier.fillMaxSize())
        CloudOverlay(uiState, Modifier.fillMaxSize())
        AccumOverlay(uiState, Modifier.fillMaxSize())
        FeaturePointOverlay(uiState, Modifier.fillMaxSize())
        DetectionOverlay(uiState, Modifier.fillMaxSize())
        FootprintOverlay(uiState, Modifier.fillMaxSize())
        CoverageRingOverlay(uiState, Modifier.fillMaxSize())
        TrajectoryOverlay(uiState, Modifier.fillMaxSize())
        BoxOverlay(uiState, Modifier.fillMaxSize())
        MeasuredBoxOverlay(uiState, Modifier.fillMaxSize())
        TargetMarkerOverlay(uiState, Modifier.fillMaxSize())
        Hud(uiState, Modifier.align(Alignment.TopStart).padding(12.dp))
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Multi-view scan controls: Scan starts/stops accumulation; Reset clears it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val active = uiState.scanState == com.example.arsurvey.scan.ScanState.SCANNING ||
                    uiState.scanState == com.example.arsurvey.scan.ScanState.STABILIZING
                ToggleChip(
                    if (active) "Stop scan" else "Scan",
                    active, uiState.detectorReady, viewModel::toggleScan,
                )
                ToggleChip("Reset", false, uiState.accumCount > 0, viewModel::resetScan)
                ToggleChip("Accum", uiState.showAccum, true, viewModel::toggleAccum)
            }
            // Freeze/measure: hold the numbers to read them off.
            ToggleChip(
                if (uiState.frozen) "Frozen" else "Freeze",
                uiState.frozen, uiState.detectorReady, viewModel::toggleFreeze,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Depth", uiState.showDepth, uiState.depthSupported == true, viewModel::toggleDepthOverlay)
                ToggleChip("Mask", uiState.showMask, uiState.detectorReady, viewModel::toggleMaskOverlay)
                ToggleChip("Cloud", uiState.showCloud, uiState.detectorReady, viewModel::toggleCloud)
                ToggleChip("Box", uiState.showBox, uiState.detectorReady, viewModel::toggleBox)
            }
        }
    }
}

/** Human-readable target-lock state for the HUD. */
private fun lockLabel(s: com.example.arsurvey.track.TargetTracker.LockState): String = when (s) {
    com.example.arsurvey.track.TargetTracker.LockState.NO_TARGET -> "—"
    com.example.arsurvey.track.TargetTracker.LockState.ACQUIRING -> "ACQUIRING…"
    com.example.arsurvey.track.TargetTracker.LockState.LOCKED -> "LOCKED 🔒"
    com.example.arsurvey.track.TargetTracker.LockState.TEMPORARILY_LOST -> "LOST — searching"
}

/** Coarse confidence bucket for the HUD. */
private fun confidenceLabel(c: Float): String = when {
    c >= 0.75f -> "High"
    c >= 0.5f -> "Medium"
    else -> "Low"
}

/** Formats a length in meters as "12.3 cm (4.8 in)". */
private fun dimStr(meters: Float): String {
    val cm = meters * 100f
    val inches = meters * 39.3701f
    return "%.1f cm (%.1f in)".format(cm, inches)
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        enabled = enabled,
        label = { Text(label) },
    )
}

/**
 * Draws the masked point cloud (M5): each world-space point projected to screen through
 * the current frame's view-projection matrix (identical math to the feature-point overlay).
 * Because the points are in world space and reprojected every frame, they stay locked to
 * the object as the phone moves — the visual assertion that the back-projection is correct.
 */
@Composable
private fun CloudOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.showCloud || !state.hasProjection || state.cloudCount == 0) return@Canvas
        val w = size.width
        val h = size.height
        val vp = state.viewProjection
        val cloud = state.worldCloud
        val p = FloatArray(4)
        val clip = FloatArray(4)
        var i = 0
        while (i + 2 < cloud.size) {
            p[0] = cloud[i]; p[1] = cloud[i + 1]; p[2] = cloud[i + 2]; p[3] = 1f
            i += 3
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) continue
            val ndcX = clip[0] / cw
            val ndcY = clip[1] / cw
            if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) continue
            val sx = (ndcX * 0.5f + 0.5f) * w
            val sy = (1f - (ndcY * 0.5f + 0.5f)) * h
            drawCircle(color = CloudPointColor, radius = 4f, center = Offset(sx, sy))
        }
    }
}

/**
 * Draws the **accumulated multi-view** cloud (Increment 1): the voxel-fused reconstruction
 * built across viewpoints, projected through the current frame's view-projection matrix. This
 * is the visual assertion of the core experiment — as the user walks around the object, points
 * from every viewpoint should land on the same physical surface and progressively fill it in.
 * Drawn in a distinct color from the per-frame [CloudOverlay] so the two are distinguishable.
 */
@Composable
private fun AccumOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.showAccum || !state.hasProjection || state.accumCount == 0) return@Canvas
        val w = size.width
        val h = size.height
        val vp = state.viewProjection
        val cloud = state.accumCloud
        val p = FloatArray(4)
        val clip = FloatArray(4)
        var i = 0
        while (i + 2 < cloud.size) {
            p[0] = cloud[i]; p[1] = cloud[i + 1]; p[2] = cloud[i + 2]; p[3] = 1f
            i += 3
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) continue
            val ndcX = clip[0] / cw
            val ndcY = clip[1] / cw
            if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) continue
            val sx = (ndcX * 0.5f + 0.5f) * w
            val sy = (1f - (ndcY * 0.5f + 0.5f)) * h
            drawCircle(color = AccumPointColor, radius = 3f, center = Offset(sx, sy))
        }
    }
}

/**
 * Draws the **authoritative** measured box (M-Measurement): a gravity-vertical, footprint-yawed
 * box built from the floor-relative height and the min-area footprint rectangle — NOT PCA. Drawn
 * in cyan and gated on the same Box toggle as the (white) PCA debug box so the two can be
 * compared directly. The W×H×D label is anchored to its topmost visible corner.
 */
@Composable
private fun MeasuredBoxOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.showBox || !state.hasProjection || state.measuredCorners.size < 24) return@Canvas
        val w = size.width
        val h = size.height
        val vp = state.viewProjection
        val p = FloatArray(4)
        val clip = FloatArray(4)
        val sx = FloatArray(8)
        val sy = FloatArray(8)
        val ok = BooleanArray(8)
        for (c in 0 until 8) {
            p[0] = state.measuredCorners[c * 3]; p[1] = state.measuredCorners[c * 3 + 1]
            p[2] = state.measuredCorners[c * 3 + 2]; p[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) { ok[c] = false; continue }
            sx[c] = (clip[0] / cw * 0.5f + 0.5f) * w
            sy[c] = (1f - (clip[1] / cw * 0.5f + 0.5f)) * h
            ok[c] = true
        }
        for (e in Obb.EDGES) {
            val a = e[0]; val b = e[1]
            if (ok[a] && ok[b]) {
                drawLine(MeasuredBoxColor, Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), strokeWidth = 6f)
            }
        }
        if (state.measuredWhd.size == 3) {
            var top = -1
            for (c in 0 until 8) if (ok[c] && (top < 0 || sy[c] < sy[top])) top = c
            if (top >= 0) {
                val d = state.measuredWhd
                val text = "%.0f×%.0f×%.0f cm".format(d[0] * 100, d[1] * 100, d[2] * 100)
                drawIntoCanvas { canvas ->
                    LabelPaint.color = MeasuredBoxColor.toArgb()
                    canvas.nativeCanvas.drawText(text, sx[top] + 6f, sy[top] - 8f, LabelPaint)
                }
            }
        }
    }
}

/**
 * Draws the locked target's world-anchored marker: the target's world-space centroid reprojected
 * through the CURRENT frame's view-projection matrix every frame, as a reticle + `LOCKED 🔒` label.
 *
 * This is the fix for the "bounding box drifts off-screen" problem: the marker's source of truth is
 * a world-space point (screen-independent), so as the camera orbits, the reticle is recomputed from
 * the live camera pose and stays glued to the physical object — it is never the initial screen
 * rectangle nudged around in pixel space. If this reticle stays on the object while the (image-space,
 * inference-lagged) yellow detection box trails behind, that confirms the drift was purely a stale
 * 2D-visualization artifact, not a world-space transform error.
 */
@Composable
private fun TargetMarkerOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val c = state.targetWorldCentroid
        if (c.size < 3 || !state.hasProjection) return@Canvas
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, state.viewProjection, 0, floatArrayOf(c[0], c[1], c[2], 1f), 0)
        val cw = clip[3]
        if (cw <= 0f) return@Canvas
        val px = (clip[0] / cw * 0.5f + 0.5f) * size.width
        val py = (1f - (clip[1] / cw * 0.5f + 0.5f)) * size.height
        val r = 26f
        drawCircle(LockMarkerColor, radius = r, center = Offset(px, py), style = Stroke(width = 4f))
        drawLine(LockMarkerColor, Offset(px - r * 1.6f, py), Offset(px + r * 1.6f, py), strokeWidth = 3f)
        drawLine(LockMarkerColor, Offset(px, py - r * 1.6f), Offset(px, py + r * 1.6f), strokeWidth = 3f)
        drawIntoCanvas { canvas ->
            LabelPaint.color = LockMarkerColor.toArgb()
            canvas.nativeCanvas.drawText("LOCKED 🔒", px + r + 8f, py - r, LabelPaint)
        }
    }
}

/**
 * Draws the viewpoint-coverage ring (M-Coverage debug): a circle of angular sectors on the floor
 * around the object centroid. Observed sectors are bright green, unobserved ones faint — so the
 * user can see, in world space, which sides they still need to walk to. Rendered once the floor
 * is known (it needs a Y to sit on).
 */
@Composable
private fun CoverageRingOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val sectors = state.coverageSectors
        if (sectors.isEmpty() || state.objectCenterXZ.size < 2 || !state.floorValid || !state.hasProjection) {
            return@Canvas
        }
        val cx = state.objectCenterXZ[0]
        val cz = state.objectCenterXZ[1]
        val y = state.floorY
        val n = sectors.size
        val step = (2.0 * PI / n).toFloat()
        val radius = 0.5f                       // 50 cm ring around the object centroid
        val vp = state.viewProjection
        val p = FloatArray(4)
        val clip = FloatArray(4)
        val samplesPerSector = 6
        for (i in 0 until n) {
            val color = if (sectors[i]) CoverageOnColor else CoverageOffColor
            var prevOk = false; var px = 0f; var py = 0f
            for (s in 0..samplesPerSector) {
                val a = i * step + step * (s.toFloat() / samplesPerSector)
                p[0] = cx + radius * cos(a); p[1] = y; p[2] = cz + radius * sin(a); p[3] = 1f
                Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
                val cw = clip[3]
                if (cw <= 0f) { prevOk = false; continue }
                val sxp = (clip[0] / cw * 0.5f + 0.5f) * size.width
                val syp = (1f - (clip[1] / cw * 0.5f + 0.5f)) * size.height
                if (prevOk) drawLine(color, Offset(px, py), Offset(sxp, syp), strokeWidth = 8f)
                px = sxp; py = syp; prevOk = true
            }
        }
    }
}

/**
 * Draws the camera trajectory (debug): the phone's sampled world path while scanning, as a
 * projected polyline. Confirms the user actually orbited the object (and that pose tracking held)
 * — a straight or jumpy path explains poor coverage or misaligned points.
 */
@Composable
private fun TrajectoryOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val t = state.cameraTrajectory
        if (t.size < 6 || !state.hasProjection) return@Canvas
        val vp = state.viewProjection
        val p = FloatArray(4)
        val clip = FloatArray(4)
        var prevOk = false; var px = 0f; var py = 0f
        var i = 0
        while (i + 2 < t.size) {
            p[0] = t[i]; p[1] = t[i + 1]; p[2] = t[i + 2]; p[3] = 1f
            i += 3
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) { prevOk = false; continue }
            val sxp = (clip[0] / cw * 0.5f + 0.5f) * size.width
            val syp = (1f - (clip[1] / cw * 0.5f + 0.5f)) * size.height
            if (prevOk) drawLine(TrajectoryColor, Offset(px, py), Offset(sxp, syp), strokeWidth = 4f)
            px = sxp; py = syp; prevOk = true
        }
    }
}

/**
 * Draws the horizontal footprint rectangle (M-Footprint): the min-area oriented rectangle fit
 * to the accumulated points on the world X/Z plane, drawn at floor level as a closed 4-corner
 * loop. Same VP projection as the cloud, so it lies flat on the floor under the object and is
 * independent of the phone's orientation.
 */
@Composable
private fun FootprintOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.footprintValid || !state.hasProjection || state.footprintCorners.size < 12) return@Canvas
        val w = size.width
        val h = size.height
        val vp = state.viewProjection
        val p = FloatArray(4)
        val clip = FloatArray(4)
        val sx = FloatArray(4)
        val sy = FloatArray(4)
        val ok = BooleanArray(4)
        for (c in 0 until 4) {
            p[0] = state.footprintCorners[c * 3]; p[1] = state.footprintCorners[c * 3 + 1]
            p[2] = state.footprintCorners[c * 3 + 2]; p[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) { ok[c] = false; continue }
            sx[c] = (clip[0] / cw * 0.5f + 0.5f) * w
            sy[c] = (1f - (clip[1] / cw * 0.5f + 0.5f)) * h
            ok[c] = true
        }
        for (c in 0 until 4) {
            val a = c; val b = (c + 1) % 4
            if (ok[a] && ok[b]) {
                drawLine(FootprintColor, Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), strokeWidth = 5f)
            }
        }
    }
}

/**
 * Draws the colorized depth map (M2) stretched over the whole view.
 *
 * ARCore delivers depth in **sensor-landscape** orientation; the activity is
 * portrait-locked, so [depthToPortraitBitmap] rotates it 90° to stand upright, then
 * [ContentScale.FillBounds] stretches it to the preview. Per-pixel alpha (baked in by
 * [com.example.arsurvey.ar.DepthColorizer]) lets the live camera show through, and
 * [FilterQuality.None] keeps the low-res depth blocks crisp rather than smeared.
 */
@Composable
private fun DepthOverlay(state: ArUiState, modifier: Modifier) {
    // remember is called unconditionally (Compose rule); the array identity changes every
    // frame, so a fresh bitmap is built per frame — intended for a live overlay.
    val image = remember(state.depthColors) {
        if (state.depthColors.isEmpty()) null
        else depthToPortraitBitmap(state.depthColors, state.depthWidth, state.depthHeight)
    }
    if (state.showDepth && image != null) {
        Image(
            bitmap = image,
            contentDescription = "Depth map overlay",
            modifier = modifier,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * Packs the ARGB depth colors into a Bitmap and rotates it upright for portrait.
 *
 * If the overlay comes out upside-down or mirrored on a particular device, flip the
 * `postRotate(90f)` to `270f` — sensor mounting varies. (Nord 5 verification pending.)
 */
private fun depthToPortraitBitmap(colors: IntArray, w: Int, h: Int): ImageBitmap {
    val landscape = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    landscape.setPixels(colors, 0, w, 0, 0, w, h)
    val rotate = android.graphics.Matrix().apply { postRotate(90f) }
    val portrait = Bitmap.createBitmap(landscape, 0, 0, w, h, rotate, false)
    return portrait.asImageBitmap()
}

/**
 * Draws the oriented bounding box (M6): projects the 8 world-space OBB corners to screen and
 * connects the 12 edges. Same VP projection as the cloud/feature points, so the wireframe is
 * locked in the world and rotates with the object.
 */
@Composable
private fun BoxOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.showBox || !state.hasProjection || state.obbCorners.size < 24) return@Canvas
        val w = size.width
        val h = size.height
        val vp = state.viewProjection
        val p = FloatArray(4)
        val clip = FloatArray(4)
        // Project all 8 corners; null when behind the camera.
        val sx = FloatArray(8)
        val sy = FloatArray(8)
        val ok = BooleanArray(8)
        for (c in 0 until 8) {
            p[0] = state.obbCorners[c * 3]; p[1] = state.obbCorners[c * 3 + 1]
            p[2] = state.obbCorners[c * 3 + 2]; p[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            val cw = clip[3]
            if (cw <= 0f) { ok[c] = false; continue }
            sx[c] = (clip[0] / cw * 0.5f + 0.5f) * w
            sy[c] = (1f - (clip[1] / cw * 0.5f + 0.5f)) * h
            ok[c] = true
        }
        for (e in Obb.EDGES) {
            val a = e[0]; val b = e[1]
            if (ok[a] && ok[b]) {
                drawLine(BoxColorM6, Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), strokeWidth = 5f)
            }
        }

        // Anchored label (M7): dimensions floated at the box's topmost visible corner.
        if (state.dimsWhdMeters.size == 3) {
            var top = -1
            for (c in 0 until 8) if (ok[c] && (top < 0 || sy[c] < sy[top])) top = c
            if (top >= 0) {
                val d = state.dimsWhdMeters
                val text = "%.0f×%.0f×%.0f cm".format(d[0] * 100, d[1] * 100, d[2] * 100)
                drawIntoCanvas { canvas ->
                    LabelPaint.color = BoxColorM6.toArgb()
                    canvas.nativeCanvas.drawText(text, sx[top] + 6f, sy[top] - 8f, LabelPaint)
                }
            }
        }
    }
}

/**
 * Draws the target object's segmentation mask (M4) — a semi-transparent green fill built by
 * [com.example.arsurvey.segment.MaskProcessor]. Same rotate-90 + fill mapping as the depth
 * overlay (it's in camera-image pixel space), so it lines up with the green target box.
 */
@Composable
private fun MaskOverlay(state: ArUiState, modifier: Modifier) {
    val image = remember(state.maskColors) {
        if (state.maskColors.isEmpty()) null
        else depthToPortraitBitmap(state.maskColors, state.maskWidth, state.maskHeight)
    }
    if (state.showMask && image != null) {
        Image(
            bitmap = image,
            contentDescription = "Segmentation mask overlay",
            modifier = modifier,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * Projects each world-space feature point through the frame's view-projection
 * matrix and draws a dot at its screen location.
 *
 * Projection pipeline (per point p = [x,y,z,1]):
 *   clip = VP * p                     (homogeneous clip space)
 *   ndc  = clip.xyz / clip.w          (normalized device coords, -1..1)
 *   sx   = (ndc.x * 0.5 + 0.5) * W    (pixels; y flipped because screen y is down)
 *   sy   = (1 - (ndc.y * 0.5 + 0.5)) * H
 * Points behind the camera (clip.w <= 0) or outside the -1..1 cube are skipped.
 */
@Composable
private fun FeaturePointOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (!state.hasProjection || state.worldPoints.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val vp = state.viewProjection
        val p = FloatArray(4)
        val clip = FloatArray(4)
        var i = 0
        while (i + 2 < state.worldPoints.size) {
            p[0] = state.worldPoints[i]
            p[1] = state.worldPoints[i + 1]
            p[2] = state.worldPoints[i + 2]
            p[3] = 1f
            Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
            i += 3
            val cw = clip[3]
            if (cw <= 0f) continue
            val ndcX = clip[0] / cw
            val ndcY = clip[1] / cw
            if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) continue
            val sx = (ndcX * 0.5f + 0.5f) * w
            val sy = (1f - (ndcY * 0.5f + 0.5f)) * h
            drawCircle(color = FeaturePointColor, radius = 5f, center = Offset(sx, sy))
        }
    }
}

/**
 * Draws YOLO detection boxes (M3). The target object is highlighted; others are drawn
 * thinner.
 *
 * Coordinate mapping — detections are in the camera image's **sensor-landscape** pixel
 * space, and (as with the depth overlay) the portrait-locked preview is that image
 * rotated 90° and stretched to fill. So for an image point (px,py), normalized
 * u=px/imgW, v=py/imgH:
 *   screenX = (1 - v) * width      screenY = u * height
 * A box maps to two mapped corners; we take their min/max to stay axis-aligned.
 * (If boxes look offset on-device, this is the same rotate/fit assumption as M2's depth —
 * fix both together.)
 */
@Composable
private fun DetectionOverlay(state: ArUiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (state.detImageWidth == 0 || state.detections.isEmpty()) return@Canvas
        val sw = size.width
        val sh = size.height
        val iw = state.detImageWidth.toFloat()
        val ih = state.detImageHeight.toFloat()
        state.detections.forEachIndexed { i, d ->
            val ax = (1f - d.y1 / ih) * sw
            val ay = (d.x1 / iw) * sh
            val bx = (1f - d.y2 / ih) * sw
            val by = (d.x2 / iw) * sh
            val left = min(ax, bx)
            val top = min(ay, by)
            val right = max(ax, bx)
            val bottom = max(ay, by)

            val isTarget = i == state.targetIndex
            // When locked, the target box turns amber while acquiring and green once locked, so
            // it's immediately obvious which object owns the scan. Non-targets stay thin yellow.
            val color = when {
                !isTarget -> BoxColor
                state.lockState == com.example.arsurvey.track.TargetTracker.LockState.ACQUIRING -> LockAcquiringColor
                else -> TargetBoxColor
            }
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = if (isTarget) 6f else 3f),
            )
            val text = "${d.label} ${(d.score * 100).toInt()}%"
            drawIntoCanvas { canvas ->
                LabelPaint.color = color.toArgb()
                canvas.nativeCanvas.drawText(text, left + 6f, top + LabelPaint.textSize + 4f, LabelPaint)
            }
        }
    }
}

@Composable
private fun Hud(state: ArUiState, modifier: Modifier) {
    val depthText = when (state.depthSupported) {
        true -> "supported"
        false -> "NOT supported"
        null -> "checking..."
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xAA000000))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        HudLine("Tracking", state.trackingState)
        HudLine("Feature points", state.featurePointCount.toString())
        HudLine("Depth API", depthText)
        if (state.depthWidth > 0) {
            HudLine("Depth res", "${state.depthWidth}x${state.depthHeight}")
        }
        val detText = if (state.detectorReady) {
            "${state.detections.size} obj · ${state.inferenceMs}ms · ${state.detectorBackend}"
        } else {
            "loading model..."
        }
        HudLine("Detector", detText)
        state.detections.getOrNull(state.targetIndex)?.let {
            HudLine("Target", "${it.label} ${(it.score * 100).toInt()}%")
        }
        // ---- Target lock (object isolation) ----
        HudLine("Lock", lockLabel(state.lockState))
        if (state.lockState == com.example.arsurvey.track.TargetTracker.LockState.NO_TARGET) {
            HudLine("", if (state.tapMissed) "No object there — tap an object" else "Tap an object to measure")
        } else {
            HudLine(
                "Match",
                "s=%.2f iou=%.2f cΔ=%.2f".format(state.lockScore, state.lockBboxIou, state.lockCenterDist),
            )
            HudLine("Candidates", state.lockCandidateCount.toString())
            if (state.lockWorldDist > 0f) HudLine("World Δ", "%.2f m".format(state.lockWorldDist))
            if (state.targetWorldCentroid.size >= 3) {
                val t = state.targetWorldCentroid
                HudLine("Target xyz", "%.2f, %.2f, %.2f".format(t[0], t[1], t[2]))
            }
        }
        if (state.showCloud && state.cloudCount > 0) {
            HudLine("Cloud", "${state.cloudCount} pts")
        }
        // ---- Multi-view scan (Increment 1) ----
        HudLine("Scan", state.scanState.name)
        if (state.accumCount > 0 || state.scanState == com.example.arsurvey.scan.ScanState.SCANNING) {
            HudLine("Coverage", "${(state.coverage * 100).toInt()}%")
            HudLine("Accum", "${state.accumCount} vox · ${state.accumObservations} obs")
            if (state.floorValid) {
                HudLine("Height", dimStr(state.objectTopY - state.floorY))
                HudLine("Floor", if (state.floorFromPlane) "ARCore plane ✓" else "object base (no plane)")
            }
            if (state.footprintValid) {
                HudLine("Footprint", "${(state.footprintWidth * 100).toInt()}×${(state.footprintDepth * 100).toInt()} cm")
            }
        }
        // Authoritative measurement (floor height + min-area footprint). PCA is debug-only below.
        if (state.measuredWhd.size == 3) {
            if (state.scanState == com.example.arsurvey.scan.ScanState.COMPLETE) {
                HudLine("", "— MEASUREMENT READY —")
            }
            HudLine("Width", dimStr(state.measuredWhd[0]))
            HudLine("Height", dimStr(state.measuredWhd[1]))
            HudLine("Depth", dimStr(state.measuredWhd[2]))
            HudLine("Confidence", "${confidenceLabel(state.confidence)} (${(state.confidence * 100).toInt()}%)")
            if (state.frozen) HudLine("", "— FROZEN —")
        }
        if (state.dimsWhdMeters.size == 3) {
            val d = state.dimsWhdMeters
            HudLine("PCA(dbg)", "%.0f×%.0f×%.0f cm".format(d[0] * 100, d[1] * 100, d[2] * 100))
        }
        state.trackingFailure?.let { HudLine("Lost tracking", it) }
        if (state.debugInfo.isNotEmpty()) HudLine("dbg", state.debugInfo)
    }
}

@Composable
private fun HudLine(label: String, value: String) {
    Text(
        text = if (label.isEmpty()) value else "$label: $value",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

private val FeaturePointColor = Color(0xFF00E5FF)
private val BoxColor = Color(0xFFFFEB3B)        // non-target detections: yellow
private val TargetBoxColor = Color(0xFF00E676)  // chosen target object: green
private val LockAcquiringColor = Color(0xFFFFC400) // target being acquired: amber
private val CloudPointColor = Color(0xFFFF00E5)  // per-frame masked cloud: magenta
private val AccumPointColor = Color(0xFF00E676)  // accumulated multi-view cloud: green
private val FootprintColor = Color(0xFFFFC400)   // min-area footprint rectangle: amber
private val CoverageOnColor = Color(0xFF69F0AE)  // observed coverage sector: bright green
private val CoverageOffColor = Color(0x55FFFFFF) // unobserved coverage sector: faint white
private val TrajectoryColor = Color(0xFFFF9800)  // camera path: orange
private val BoxColorM6 = Color(0xFFFFFFFF)        // PCA debug box: white
private val MeasuredBoxColor = Color(0xFF00E5FF)  // authoritative measured box: cyan
private val LockMarkerColor = Color(0xFF00E676)   // world-anchored target reticle: green

/** Reused across draws (set .color per box). Shadow keeps text legible over any scene. */
private val LabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 34f
    setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
}
