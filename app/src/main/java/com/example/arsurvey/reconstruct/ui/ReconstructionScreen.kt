package com.example.arsurvey.reconstruct.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arsurvey.capture.model.CapturedPhoto
import com.example.arsurvey.measure.Obb
import com.example.arsurvey.reconstruct.PhotoCloud
import com.example.arsurvey.reconstruct.PredictedMeasurement
import com.example.arsurvey.reconstruct.ReconstructionResult
import com.example.arsurvey.reconstruct.ReconstructionViewModel
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distinct color per photo (also used for the combined view). */
private val PhotoColors = listOf(
    Color(0xFFFF4081), // photo 1 — pink
    Color(0xFF18FFFF), // photo 2 — cyan
    Color(0xFFFFEA00), // photo 3 — yellow
    Color(0xFF76FF03), // extras (defensive)
    Color(0xFFB388FF),
)
private val AxisX = Color(0xFFFF5252)
private val AxisY = Color(0xFF69F0AE)
private val AxisZ = Color(0xFF448AFF)

/** Amber wireframe for the measured oriented bounding box. */
private val MeasuredBox = Color(0xFFFFC107)

/**
 * Developer-facing screen that reconstructs the combined world-space point cloud from the given
 * captured [photos] and renders it in an interactive 3D viewer with per-photo toggles and stats.
 */
@Composable
fun ReconstructionScreen(photos: List<CapturedPhoto>, onBack: () -> Unit) {
    val vm: ReconstructionViewModel = viewModel()
    LaunchedEffect(Unit) { vm.start(photos) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("3D Reconstruction", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        when {
            state.error != null -> ErrorCard(
                message = state.error!!,
                details = state.result?.perPhoto?.mapNotNull { pc ->
                    pc.note?.let { "Photo ${pc.index + 1}: $it" }
                } ?: emptyList(),
                onBack = onBack,
            )
            state.loading -> LoadingView(text = state.progressText ?: "Working…", onCancel = vm::cancel)
            state.result != null -> ResultView(state.result!!)
            else -> CenterText("No data")
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun LoadingView(text: String, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = MaterialTheme.colorScheme.onBackground)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** Designed error state: icon + one-line cause + per-photo details + one action. Never a raw exception string. */
@Composable
private fun ErrorCard(message: String, details: List<String>, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("⚠", fontSize = 40.sp)
            Text(
                friendlyError(message),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            details.forEach { line ->
                Text(
                    "• $line",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            TextButton(onClick = onBack) { Text("Back to photos") }
        }
    }
}

private fun friendlyError(raw: String): String = when {
    raw.contains("model", ignoreCase = true) && raw.contains("missing", ignoreCase = true) ->
        "Measurement models aren't bundled — see README assets/models/."
    raw.contains("cancel", ignoreCase = true) -> "Reconstruction cancelled."
    raw.contains("3 photos", ignoreCase = true) -> raw
    raw.contains("3 good viewpoints", ignoreCase = true) -> raw
    raw.contains("spread", ignoreCase = true) -> raw
    raw.contains("too few points", ignoreCase = true) -> raw
    raw.contains("no valid floor", ignoreCase = true) -> raw
    raw.contains("no footprint", ignoreCase = true) -> raw
    raw.contains("no detection", ignoreCase = true) -> raw
    raw.contains("no target", ignoreCase = true) -> raw
    raw.contains("mask", ignoreCase = true) -> raw
    raw.contains("depth", ignoreCase = true) ->
        "A photo has no depth — retake it from a steadier angle."
    raw.contains("tracking", ignoreCase = true) ->
        "Tracking was lost for this set — retake with slower motion."
    raw.startsWith("Failed", ignoreCase = true) -> raw
    // Every other note is already a designed string from the pipeline — show it verbatim
    // so the cause is never hidden behind a generic line again.
    else -> raw
}

/** What the top viewport shows: the 3D cloud, or a per-photo image with box/mask overlays. */
private enum class ViewMode { CLOUD, BOXES, MASKS }

@Composable
private fun ResultView(result: ReconstructionResult) {
    // Default: oriented-box overlay on the photo (amber wireframe) + viewpoint switcher.
    // The 3D cloud / masks / stats live under Diagnostics, not the default view.
    var mode by remember(result) { mutableStateOf(ViewMode.BOXES) }
    // null = combined (All); otherwise a specific photo index. (Image modes fall back to photo 1.)
    var selected by remember(result) { mutableStateOf<Int?>(null) }
    var showDiagnostics by remember(result) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        PredictedMeasurementCard(result.measurement, modifier = Modifier.fillMaxWidth())

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (mode == ViewMode.CLOUD) {
                PointCloudViewer(
                    result = result,
                    selected = selected,
                    box = result.measurement?.box,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Image overlays are per-photo; "All" isn't meaningful, so fall back to the first photo.
                val idx = selected ?: result.perPhoto.firstOrNull()?.index ?: 0
                val pc = result.perPhoto.firstOrNull { it.index == idx }
                if (pc != null) {
                    ImageOverlayView(pc = pc, mode = mode, modifier = Modifier.fillMaxSize())
                } else {
                    CenterText("No image")
                }
            }
        }

        // Photo selector: viewpoint switcher for the overlay.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            result.perPhoto.forEach { pc ->
                FilterChip(
                    selected = (selected ?: result.perPhoto.firstOrNull()?.index) == pc.index,
                    onClick = { selected = pc.index },
                    label = { Text("Photo ${pc.index + 1}") },
                )
            }
        }

        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "▼ Hide diagnostics" else "▸ Diagnostics")
        }
        if (showDiagnostics) {
            // Mode selector: 3D cloud vs. the two 2D debug overlays.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = mode == ViewMode.CLOUD, onClick = { mode = ViewMode.CLOUD }, label = { Text("3D cloud") })
                FilterChip(selected = mode == ViewMode.BOXES, onClick = { mode = ViewMode.BOXES }, label = { Text("Boxes") })
                FilterChip(selected = mode == ViewMode.MASKS, onClick = { mode = ViewMode.MASKS }, label = { Text("Masks") })
            }
            if (mode == ViewMode.CLOUD) {
                FilterChip(
                    selected = selected == null,
                    onClick = { selected = null },
                    label = { Text("All") },
                )
            }
            StatsPanel(
                result = result,
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

/**
 * Debug overlay: the captured JPEG with either the YOLOE detection boxes or the EfficientSAM mask
 * drawn on top. The overlays are **baked into a copy of the raw JPEG** (in its own pixel space) and
 * only then rotated upright for display — so alignment is exact regardless of sensor orientation,
 * and this doubles as the check for whether the mask actually lands on the object.
 */
@Composable
private fun ImageOverlayView(pc: PhotoCloud, mode: ViewMode, modifier: Modifier) {
    val bitmap = remember(pc.index, mode, pc.imagePath) { buildOverlayBitmap(pc, mode) }
    Box(modifier = modifier.background(Color(0xFF101014)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Photo ${pc.index + 1} ${mode.name.lowercase()} overlay",
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            CenterText("Image unavailable")
        }
        val caption = when (mode) {
            ViewMode.BOXES -> "${pc.detections.size} detections" +
                (pc.detections.firstOrNull { it.isTarget }?.let { " · target: ${it.label} ${(it.confidence * 100).toInt()}%" } ?: "")
            ViewMode.MASKS -> if (pc.maskBinary != null) "mask ${pc.maskWidth}×${pc.maskHeight} · ${pc.maskPixelCount} px" else "no mask"
            ViewMode.CLOUD -> ""
        }
        if (caption.isNotEmpty()) {
            Text(
                caption,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Decodes the raw JPEG, draws the requested overlay into it at raw resolution (boxes/mask are
 * normalized to that space), then rotates the composite upright with the recorded display rotation.
 * Baking before rotating keeps the overlay pixel-aligned to the image for free.
 */
private fun buildOverlayBitmap(pc: PhotoCloud, mode: ViewMode): androidx.compose.ui.graphics.ImageBitmap? {
    val decoded = BitmapFactory.decodeFile(pc.imagePath) ?: return null
    val base = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    if (decoded !== base) decoded.recycle()
    val bw = base.width.toFloat()
    val bh = base.height.toFloat()
    val canvas = android.graphics.Canvas(base)

    when (mode) {
        ViewMode.MASKS -> pc.maskBinary?.let { binary ->
            if (pc.maskWidth > 0 && pc.maskHeight > 0) {
                val maskBmp = Bitmap.createBitmap(pc.maskWidth, pc.maskHeight, Bitmap.Config.ARGB_8888)
                val px = IntArray(pc.maskWidth * pc.maskHeight)
                for (i in px.indices) px[i] = if (binary[i]) 0x8000E676.toInt() else 0 // translucent green
                maskBmp.setPixels(px, 0, pc.maskWidth, 0, 0, pc.maskWidth, pc.maskHeight)
                // Scale the full-frame mask up to the image (normalized → same rect).
                canvas.drawBitmap(
                    maskBmp,
                    Rect(0, 0, pc.maskWidth, pc.maskHeight),
                    RectF(0f, 0f, bw, bh),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                maskBmp.recycle()
            }
        }
        ViewMode.BOXES -> {
            val other = Paint().apply {
                style = Paint.Style.STROKE; color = 0xFFB0BEC5.toInt(); strokeWidth = bw * 0.004f + 1f
            }
            val targetPaint = Paint().apply {
                style = Paint.Style.STROKE; color = 0xFF00E676.toInt(); strokeWidth = bw * 0.008f + 2f
            }
            val labelPaint = Paint().apply {
                color = 0xFF00E676.toInt(); textSize = bh * 0.03f + 12f; isAntiAlias = true
            }
            // Draw non-targets first so the green target box stays on top.
            pc.detections.filter { !it.isTarget }.forEach { d ->
                canvas.drawRect(d.left * bw, d.top * bh, d.right * bw, d.bottom * bh, other)
            }
            pc.detections.filter { it.isTarget }.forEach { d ->
                canvas.drawRect(d.left * bw, d.top * bh, d.right * bw, d.bottom * bh, targetPaint)
                val ty = (d.top * bh - 6f).coerceAtLeast(labelPaint.textSize)
                canvas.drawText("${d.label} ${(d.confidence * 100).toInt()}%", d.left * bw, ty, labelPaint)
            }
        }
        ViewMode.CLOUD -> {}
    }

    // The raw JPEG is sensor-landscape; rotate the (already-overlaid) composite upright with the same
    // recorded degrees ReviewPhoto uses, so overlays stay pixel-aligned and the image reads upright.
    val deg = pc.displayRotationDegrees
    if (deg % 360 == 0) return base.asImageBitmap()
    val matrix = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
    val rotated = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
    return rotated.asImageBitmap()
}

/**
 * Software point-cloud renderer. Builds a synthetic orbit-camera view-projection (the same
 * `VP·p → NDC → screen` math the AR overlays use) and projects every world point through it.
 * The camera is a pure viewing transform — it never mutates the reconstructed points.
 *
 * Gestures: **one finger orbits** (azimuth/elevation), **two fingers pinch-zoom and pan**.
 */
@Composable
private fun PointCloudViewer(result: ReconstructionResult, selected: Int?, box: Obb?, modifier: Modifier) {
    // Fit the camera to the cloud once per result.
    val center = remember(result) { floatArrayOf(
        (result.minX + result.maxX) / 2f,
        (result.minY + result.maxY) / 2f,
        (result.minZ + result.maxZ) / 2f,
    ) }
    val radius = remember(result) {
        val r = 0.5f * sqrt(
            result.extentX * result.extentX + result.extentY * result.extentY + result.extentZ * result.extentZ
        )
        if (r.isFinite() && r > 0.01f) r else 0.5f
    }

    var azimuth by remember(result) { mutableStateOf(0.7f) }
    var elevation by remember(result) { mutableStateOf(0.5f) }
    var distance by remember(result) { mutableStateOf(radius * 3f + 0.1f) }
    var tx by remember(result) { mutableStateOf(center[0]) }
    var ty by remember(result) { mutableStateOf(center[1]) }
    var tz by remember(result) { mutableStateOf(center[2]) }

    Box(
        modifier = modifier
            .background(Color(0xFF101014))
            .pointerInput(result) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size == 1 -> {
                                val d = pressed[0].positionChange()
                                if (d != Offset.Zero) {
                                    azimuth -= d.x * ORBIT_SENS
                                    elevation = (elevation + d.y * ORBIT_SENS).coerceIn(-1.5f, 1.5f)
                                    pressed[0].consume()
                                }
                            }
                            pressed.size >= 2 -> {
                                val a = pressed[0]; val b = pressed[1]
                                val prev = dist(a.previousPosition, b.previousPosition)
                                val cur = dist(a.position, b.position)
                                if (prev > 0f && cur > 0f) {
                                    distance = (distance * (prev / cur))
                                        .coerceIn(radius * 0.05f + 0.02f, radius * 40f + 2f)
                                }
                                // Pan: translate the look-at target in the camera's right/up plane.
                                val panX = ((a.position.x + b.position.x) - (a.previousPosition.x + b.previousPosition.x)) / 2f
                                val panY = ((a.position.y + b.position.y) - (a.previousPosition.y + b.previousPosition.y)) / 2f
                                val basis = cameraBasis(azimuth, elevation)
                                val k = distance * PAN_SENS
                                tx += (-basis[0] * panX + basis[3] * panY) * k
                                ty += (-basis[1] * panX + basis[4] * panY) * k
                                tz += (-basis[2] * panX + basis[5] * panY) * k
                                a.consume(); b.consume()
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w < 1f || h < 1f) return@Canvas

            // Orbit camera → view, projection, VP.
            val dir = cameraDir(azimuth, elevation)
            val eye = floatArrayOf(tx + distance * dir[0], ty + distance * dir[1], tz + distance * dir[2])
            val view = FloatArray(16)
            Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], tx, ty, tz, 0f, 1f, 0f)
            val proj = FloatArray(16)
            val far = distance + radius * 6f + 5f
            Matrix.perspectiveM(proj, 0, 45f, w / h, 0.02f, far)
            val vp = FloatArray(16)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

            // World XYZ axes anchored at the cloud center, so orientation is readable near the object.
            val axisLen = maxOf(0.1f, radius)
            drawAxis(vp, center, floatArrayOf(axisLen, 0f, 0f), AxisX, w, h)
            drawAxis(vp, center, floatArrayOf(0f, axisLen, 0f), AxisY, w, h)
            drawAxis(vp, center, floatArrayOf(0f, 0f, axisLen), AxisZ, w, h)

            // The measured oriented bounding box (floor + footprint) as an amber wireframe.
            if (box != null) drawObb(vp, box, MeasuredBox, w, h)

            // Points, colored per photo. Stride-sample only the rendering when the cloud is huge.
            val stride = renderStride(result.totalPoints)
            val clip = FloatArray(4)
            val p = FloatArray(4)
            for (pc in result.perPhoto) {
                if (selected != null && pc.index != selected) continue
                val color = PhotoColors[pc.index % PhotoColors.size]
                val pts = pc.worldPoints
                var i = 0
                var counter = 0
                while (i + 2 < pts.size) {
                    if (counter % stride == 0) {
                        p[0] = pts[i]; p[1] = pts[i + 1]; p[2] = pts[i + 2]; p[3] = 1f
                        Matrix.multiplyMV(clip, 0, vp, 0, p, 0)
                        val cw = clip[3]
                        if (cw > 0f) {
                            val sx = (clip[0] / cw * 0.5f + 0.5f) * w
                            val sy = (1f - (clip[1] / cw * 0.5f + 0.5f)) * h
                            if (sx >= 0f && sx < w && sy >= 0f && sy < h) {
                                drawCircle(color, radius = 2.5f, center = Offset(sx, sy))
                            }
                        }
                    }
                    counter++
                    i += 3
                }
            }
        }
    }
}

/** Projects and draws a single world-space axis segment from [origin] by [delta]. */
private fun DrawScope.drawAxis(vp: FloatArray, origin: FloatArray, delta: FloatArray, color: Color, w: Float, h: Float) {
    val a = project(vp, origin[0], origin[1], origin[2], w, h) ?: return
    val b = project(vp, origin[0] + delta[0], origin[1] + delta[1], origin[2] + delta[2], w, h) ?: return
    drawLine(color, a, b, strokeWidth = 3f)
}

/** Projects the 8 [Obb] corners and draws its 12 edges — the same VP math as the points/axes. */
private fun DrawScope.drawObb(vp: FloatArray, obb: Obb, color: Color, w: Float, h: Float) {
    val c = obb.corners()
    for (edge in Obb.EDGES) {
        val i = edge[0] * 3
        val j = edge[1] * 3
        val a = project(vp, c[i], c[i + 1], c[i + 2], w, h) ?: continue
        val b = project(vp, c[j], c[j + 1], c[j + 2], w, h) ?: continue
        drawLine(color, a, b, strokeWidth = 4f)
    }
}

/** world→screen via VP; null if behind camera. Same math as the AR overlays. */
private fun project(vp: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float): Offset? {
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
    val cw = clip[3]
    if (cw <= 0f) return null
    return Offset((clip[0] / cw * 0.5f + 0.5f) * w, (1f - (clip[1] / cw * 0.5f + 0.5f)) * h)
}

/** Unit direction from target to eye for azimuth/elevation. */
private fun cameraDir(az: Float, el: Float): FloatArray =
    floatArrayOf(cos(el) * sin(az), sin(el), cos(el) * cos(az))

/** Returns [rightX,rightY,rightZ, upX,upY,upZ] for the current orbit orientation. */
private fun cameraBasis(az: Float, el: Float): FloatArray {
    val dir = cameraDir(az, el)              // target→eye
    val fwd = floatArrayOf(-dir[0], -dir[1], -dir[2]) // eye→target
    // right = fwd × worldUp
    val rx = fwd[1] * 0f - fwd[2] * 1f
    val ry = fwd[2] * 0f - fwd[0] * 0f
    val rz = fwd[0] * 1f - fwd[1] * 0f
    val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-4f)
    val r = floatArrayOf(rx / rl, ry / rl, rz / rl)
    // up = right × fwd
    val ux = r[1] * fwd[2] - r[2] * fwd[1]
    val uy = r[2] * fwd[0] - r[0] * fwd[2]
    val uz = r[0] * fwd[1] - r[1] * fwd[0]
    return floatArrayOf(r[0], r[1], r[2], ux, uy, uz)
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

private fun renderStride(total: Int): Int = if (total <= MAX_RENDER_POINTS) 1 else (total / MAX_RENDER_POINTS) + 1

private const val ORBIT_SENS = 0.008f
private const val PAN_SENS = 0.0016f
private const val MAX_RENDER_POINTS = 30000

/**
 * The user-facing result: the predicted Width × Height × Depth in centimeters, plus a confidence /
 * provenance line. Shows an "unavailable" state (with the reason) rather than a fabricated number
 * when the cloud was too sparse to fit a box.
 */
@Composable
private fun PredictedMeasurementCard(prediction: PredictedMeasurement?, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "PREDICTED MEASUREMENT",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        )
        val m = prediction?.measurement
        if (m != null) {
            val level = if (m.confidence >= 0.75) "high" else if (m.confidence >= 0.45) "medium" else "low"
            Text(
                "W %.0f × H %.0f × D %.0f cm · %s confidence · saved to catalog".format(
                    m.widthCm, m.heightCm, m.depthCm, level,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "W × H × D · ✓ floor locked (${if (prediction.fromFloorPlane) "ARCore plane" else "object base"})",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            val floorSrc = if (prediction.fromFloorPlane) "ARCore plane" else "object base"
            Text(
                "Confidence: %.2f · %d/%d views · floor: %s".format(
                    m.confidence, prediction.viewsUsed, prediction.totalViews, floorSrc,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                "Measurement unavailable" + (prediction?.note?.let { " — $it" } ?: ""),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StatsPanel(result: ReconstructionResult, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text("Combined", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        Mono("Total points: ${result.totalPoints}")
        if (result.totalPoints > 0) {
            Mono("X: %.3f … %.3f  (%.1f cm)".format(result.minX, result.maxX, result.extentX * 100))
            Mono("Y: %.3f … %.3f  (%.1f cm)".format(result.minY, result.maxY, result.extentY * 100))
            Mono("Z: %.3f … %.3f  (%.1f cm)".format(result.minZ, result.maxZ, result.extentZ * 100))
        } else {
            Mono("No 3D points — see per-photo notes below.")
        }
        result.perPhoto.forEach { pc -> PhotoStats(pc) }
    }
}

@Composable
private fun PhotoStats(pc: PhotoCloud) {
    val color = PhotoColors[pc.index % PhotoColors.size]
    Text(
        "Photo ${pc.index + 1}",
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Mono("Mask pixels:      ${pc.maskPixelCount}")
    Mono("Valid depth px:   ${pc.validDepthCount}")
    Mono("3D points:        ${pc.pointCount}")
    Mono("Depth mm min/med/max: ${pc.depthMinMm} / ${pc.depthMedianMm} / ${pc.depthMaxMm}")
    pc.note?.let { Mono("⚠ $it") }
}

@Composable
private fun Mono(text: String) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}
