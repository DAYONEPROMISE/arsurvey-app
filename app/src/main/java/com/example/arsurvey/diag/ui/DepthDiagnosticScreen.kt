package com.example.arsurvey.diag.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arsurvey.capture.model.CapturedPhoto
import com.example.arsurvey.diag.DepthReprojectionDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Overlay render mode for the right-hand image. */
private enum class DiagMode { OVERLAY, POINTS_ONLY }

/** Everything computed off-thread for one selected photo: the stats + the unrotated base bitmap. */
private data class Analysis(
    val result: DepthReprojectionDiagnostic.Result,
    /** Decoded JPEG in sensor-landscape orientation (unrotated), or null if it couldn't be decoded. */
    val baseBitmap: Bitmap?,
    val displayRotationDegrees: Int,
)

/**
 * Developer diagnostic screen (FIRST test): reproject saved ARCore depth back into the saved RGB
 * image and see whether the points land on the real scene.
 *
 * LEFT  — the original captured RGB.
 * RIGHT — the RGB with every valid depth pixel reprojected through `depth → camera XYZ → RGB pixel`
 *          and drawn as a depth-colored dot (near = red … far = blue).
 *
 * Controls: photo selection (1/2/3), overlay vs points-only, adjustable point size. No SAM, no YOLO,
 * no PCA, no world transform — see [DepthReprojectionDiagnostic].
 */
@Composable
fun DepthDiagnosticScreen(
    photos: List<CapturedPhoto>,
    onBack: () -> Unit,
) {
    val ordered = remember(photos) { photos.sortedBy { it.index } }
    var selected by remember(ordered) { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(DiagMode.OVERLAY) }
    var pointSizeDp by remember { mutableFloatStateOf(2.5f) }

    val photo = ordered.getOrNull(selected)

    // Analyze + decode the base bitmap off the main thread whenever the selected photo changes.
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    LaunchedEffect(photo?.imagePath) {
        analysis = null
        val p = photo ?: return@LaunchedEffect
        analysis = withContext(Dispatchers.Default) {
            val result = DepthReprojectionDiagnostic.analyze(p)
            val base = BitmapFactory.decodeFile(p.imagePath)
            Analysis(result, base, p.metadata.displayRotationDegrees)
        }
    }

    // Render the two display bitmaps whenever the analysis or the display controls change.
    var rendered by remember { mutableStateOf<Pair<ImageBitmap?, ImageBitmap?>?>(null) }
    LaunchedEffect(analysis, mode, pointSizeDp) {
        val a = analysis
        rendered = if (a?.baseBitmap == null) null else withContext(Dispatchers.Default) {
            renderPair(a, mode, pointSizeDp)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                "Depth → RGB reprojection",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            // --- Photo selector (1/2/3) ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ordered.forEachIndexed { i, p ->
                    Chip(
                        label = "Photo ${p.index + 1}",
                        selected = i == selected,
                        onClick = { selected = i },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // --- Mode + point size controls ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Overlay", mode == DiagMode.OVERLAY) { mode = DiagMode.OVERLAY }
                Chip("Points only", mode == DiagMode.POINTS_ONLY) { mode = DiagMode.POINTS_ONLY }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Point size ${"%.1f".format(pointSizeDp)}",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 12.sp,
                    modifier = Modifier.width(120.dp),
                )
                Slider(
                    value = pointSizeDp,
                    onValueChange = { pointSizeDp = it },
                    valueRange = 1f..8f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // --- Report ---
            ReportCard(analysis?.result)
            Spacer(Modifier.height(10.dp))

            // --- Images: LEFT original · RIGHT reprojected overlay ---
            val pair = rendered
            if (analysis == null) {
                LoadingBox("Analyzing…")
            } else if (analysis?.baseBitmap == null) {
                LoadingBox("Image unavailable")
            } else if (pair == null) {
                LoadingBox("Rendering…")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LabeledImage("Original", pair.first, Modifier.weight(1f))
                    LabeledImage(
                        if (mode == DiagMode.OVERLAY) "Reprojected" else "Depth points",
                        pair.second,
                        Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReportCard(r: DepthReprojectionDiagnostic.Result?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        if (r == null) {
            ReportRow("Status", "analyzing…")
        } else {
            ReportRow("Status", r.note ?: "OK")
            ReportRow("RGB dimensions", "${r.rgbWidth} × ${r.rgbHeight}")
            ReportRow("Intrinsics ref size", "${r.refWidth} × ${r.refHeight}")
            ReportRow("Depth dimensions", "${r.depthWidth} × ${r.depthHeight}")
            ReportRow("Valid depth count", "${r.validDepthCount}")
            ReportRow("Projected count", "${r.projectedCount}")
            ReportRow("Inside RGB", "${r.insideCount}  (${"%.1f".format(r.insidePercent)}%)")
            if (r.validDepthCount > 0) {
                ReportRow("Depth range (13-bit)", "${r.depthMinMm} … ${r.depthMaxMm} mm")
                if (r.confidenceBitsPresent) {
                    val pct = 100f * r.confidenceBitPixels / r.validDepthCount
                    ReportRow("Confidence bits set", "${"%.0f".format(pct)}% · raw max ${r.rawMaxMm} mm")
                    ReportRow(
                        "DEPTH16 decode",
                        "13-bit mm masked (pipeline uses Depth16 — confidence bits excluded)",
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            fontSize = 12.sp,
            modifier = Modifier.width(150.dp),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LabeledImage(label: String, image: ImageBitmap?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = label,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp).background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { Text("—", color = Color.White) }
        }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = MaterialTheme.colorScheme.onBackground) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// -------------------------------------------------------------------------------------------------
// Rendering (off-thread): draw the reprojected points onto the RGB (sensor-landscape) bitmap, then
// rotate both the original and the overlay upright by the recorded display rotation.
// -------------------------------------------------------------------------------------------------

private fun renderPair(a: Analysis, mode: DiagMode, pointSizeDp: Float): Pair<ImageBitmap?, ImageBitmap?> {
    val base = a.baseBitmap ?: return null to null
    val r = a.result

    // Point radius scaled to the image resolution so it reads similarly regardless of RGB size.
    // ~1 dp ≈ the base scaled to a nominal 1080px long-edge; keep it simple and resolution-relative.
    val longEdge = maxOf(base.width, base.height).toFloat()
    val radius = (pointSizeDp * longEdge / 720f).coerceAtLeast(1f)

    // LEFT: original, rotated upright.
    val original = rotate(base, a.displayRotationDegrees).asImageBitmap()

    // RIGHT: overlay in sensor-landscape space, then rotated the same way.
    val canvasBmp: Bitmap = if (mode == DiagMode.OVERLAY) {
        base.copy(Bitmap.Config.ARGB_8888, true)
    } else {
        Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(AColor.BLACK)
        }
    }
    if (r.ok && r.validDepthCount > 0) {
        val canvas = Canvas(canvasBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val minMm = r.depthMinMm
        val span = (r.depthMaxMm - r.depthMinMm).coerceAtLeast(1)
        val hsv = FloatArray(3).also { it[1] = 1f; it[2] = 1f }
        for (i in r.px.indices) {
            val t = (r.depthMm[i] - minMm).toFloat() / span   // 0 near … 1 far
            hsv[0] = 240f * t.coerceIn(0f, 1f)                // near red → far blue
            paint.color = AColor.HSVToColor(hsv)
            canvas.drawCircle(r.px[i], r.py[i], radius, paint)
        }
    }
    val overlay = rotate(canvasBmp, a.displayRotationDegrees).asImageBitmap()

    return original to overlay
}

/** Rotates [src] clockwise by [deg]; returns [src] unchanged when there's nothing to do. */
private fun rotate(src: Bitmap, deg: Int): Bitmap {
    if (deg % 360 == 0) return src
    val matrix = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}
