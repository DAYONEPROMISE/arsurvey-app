package com.example.arsurvey.capture.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.arsurvey.capture.CaptureScreen
import com.example.arsurvey.capture.CaptureViewModel
import com.example.arsurvey.capture.model.ARCoreFrameMetadata
import com.example.arsurvey.capture.model.CapturedObject
import com.example.arsurvey.capture.model.CapturedPhoto
import com.example.arsurvey.diag.ui.DepthDiagnosticScreen
import com.example.arsurvey.reconstruct.ui.ReconstructionScreen
import com.google.ar.core.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView

/**
 * Host for the capture-first workflow. Switches between the live [CaptureView] (take 3 photos), the
 * after-capture [ReviewView], the [CatalogView] (all saved objects), and the per-object
 * [ObjectDetailView]. This lightweight `when` on a screen enum stands in for a navigation library —
 * the flow is a handful of screens, so a nav dependency would be overkill and less isolated.
 */
@Composable
fun CaptureFlowScreen(viewModel: CaptureViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize()) {
        when (state.screen) {
            CaptureScreen.CAPTURE -> CaptureView(viewModel)
            CaptureScreen.REVIEW -> ReviewView(
                photos = state.photos,
                onRetake = viewModel::startNewObject,
                onRetakePhoto = viewModel::retakePhoto,
                onOpenCatalog = viewModel::openCatalog,
                onReconstruct = { viewModel.openReconstruct(state.photos, CaptureScreen.REVIEW) },
                onDiagnostic = { viewModel.openDiagnostic(state.photos, CaptureScreen.REVIEW) },
            )
            CaptureScreen.CATALOG -> CatalogView(
                viewModel = viewModel,
                onOpenObject = viewModel::openObject,
                onBack = viewModel::backToCapture,
            )
            CaptureScreen.DETAIL -> ObjectDetailView(
                viewModel = viewModel,
                objectId = state.selectedObjectId,
                onBack = viewModel::backToCatalog,
                onReconstruct = { photos -> viewModel.openReconstruct(photos, CaptureScreen.DETAIL) },
                onDiagnostic = { photos -> viewModel.openDiagnostic(photos, CaptureScreen.DETAIL) },
            )
            CaptureScreen.RECONSTRUCT -> ReconstructionScreen(
                photos = state.reconstructPhotos,
                onBack = viewModel::backFromReconstruct,
            )
            CaptureScreen.DIAGNOSTIC -> DepthDiagnosticScreen(
                photos = state.diagnosticPhotos,
                onBack = viewModel::backFromDiagnostic,
            )
        }
    }
}

/**
 * Live capture screen: the silent ARCore session renders the camera, and the shutter persists the
 * current frame's image + metadata. No overlays, no debug values — just a shutter and an "N of 3"
 * counter, per the "keep the UI as simple as possible" constraint.
 */
@Composable
private fun CaptureView(viewModel: CaptureViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // SceneView plumbing — identical to the measurement screen so we reuse the same ARCore session
    // wiring; the difference is purely that no ML/measurement runs on the frames here.
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    Box(modifier = Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            collisionSystem = collisionSystem,
            childNodes = childNodes,
            cameraNode = cameraNode,
            planeRenderer = false,
            sessionConfiguration = { session, config ->
                // Keep depth on when supported so we can persist raw depth with each photo; still
                // NO depth *processing* happens during capture — we only read + store it.
                val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                config.depthMode =
                    if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                viewModel.setDepthSupported(depthSupported)
            },
            onSessionUpdated = { _, frame -> viewModel.onFrame(frame) },
        )

        // Top hint: 3-state tracking indicator (dot + coaching), not a binary hint.
        val (dot, hint) = when (state.trackingState) {
            "TRACKING" -> Color(0xFF00E676) to "Point at the object and tap to capture"
            "PAUSED" -> Color(0xFFFFC107) to "Initializing — hold still…"
            "STOPPED" -> Color(0xFFFF5252) to "Tracking lost — move slowly to recover"
            else -> Color(0xFFFFC107) to "Move your phone slowly to start tracking…"
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text = hint, color = Color.White, fontSize = 15.sp)
        }
        // First-run coaching card: explains camera + AR before the user shoots.
        if (state.showOnboarding) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 76.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xDD000000))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Slowly pan around the object — take 3 photos ~30° apart", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::dismissOnboarding) { Text("Got it") }
            }
        }

        // Entry point to the catalog of previously captured objects.
        Text(
            text = "Catalog",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xAA000000))
                .clickable { viewModel.openCatalog() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        // Depth-unsupported devices: clean state, catalog still browsable.
        if (state.depthSupported == false) {
            Text(
                text = "No depth sensor on this device — photos save without depth",
                color = Color(0xFFFFC107),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // Bottom: capture status (while a shot is pending) + "N of 3" counter + shutter.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // While capturing, show the live status so the user knows a shot is queued and why it may
            // be waiting (e.g. "Hold still — establishing tracking…" from the tracking gate).
            if (state.capturing && state.statusMessage != null) {
                Text(
                    text = state.statusMessage!!,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            // Per-shot checklist coaching after the first photo.
            if (!state.capturing && state.photosTaken in 1 until CapturedObject.PHOTOS_PER_OBJECT) {
                Text(
                    text = "Photo ${state.photosTaken + 1}/3 — move ~30° sideways",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Text(
                text = "${state.photosTaken} of ${CapturedObject.PHOTOS_PER_OBJECT}",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // Shutter disabled reason, so a dead button never looks broken.
            val reason = state.shutterReason
            if (!state.canCapture && reason != null && state.photosTaken < CapturedObject.PHOTOS_PER_OBJECT) {
                Text(
                    text = reason,
                    color = Color(0xFFFFC107),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (state.capturing) {
                TextButton(onClick = viewModel::cancelCapture) { Text("Cancel", color = Color.White) }
            }
            // Once 3/3 is reached the shutter is dead — offer a reset so you can capture the next
            // object without being stuck (this screen is also where "‹ Camera" from the catalog lands).
            if (state.photosTaken >= CapturedObject.PHOTOS_PER_OBJECT) {
                Button(onClick = viewModel::startNewObject) { Text("＋ Start new object") }
            } else {
                ShutterButton(enabled = state.canCapture, onClick = viewModel::onShutter)
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color(0x55FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (enabled) Color(0xFFE0E0E0) else Color(0x33FFFFFF))
        )
        // Whole area is tappable via an overlaid transparent button so the target is large.
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {}
    }
}

/**
 * After-capture review: shows each saved image together with the **full** ARCore metadata that was
 * persisted for that exact frame, so everything can be inspected on-device (not just in the logs /
 * `object.json`). Bulky 4x4 matrices are behind a per-photo expander so the page stays readable.
 */
@Composable
private fun ReviewView(
    photos: List<CapturedPhoto>,
    onRetake: () -> Unit,
    onRetakePhoto: (Int) -> Unit,
    onOpenCatalog: () -> Unit,
    onReconstruct: () -> Unit,
    onDiagnostic: () -> Unit,
) {
    val sorted = photos.sortedBy { it.index }
    // Warn when two adjacent shots are near-identical viewpoints (#1 accuracy killer).
    val dupWarning = findNearDuplicate(sorted)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Captured ${photos.size} photos", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        // Primary next actions kept at the TOP so they're reachable without scrolling past 3 full
        // metadata panels — previously the only "new object" button was buried at the very bottom,
        // leaving the capture screen stuck at "3 of 3".
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onReconstruct, enabled = photos.isNotEmpty()) { Text("Reconstruct 3D") }
            androidx.compose.material3.OutlinedButton(onClick = onRetake) { Text("＋ New object") }
        }
        if (dupWarning != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "⚠ Photos ${dupWarning.first + 1} & ${dupWarning.second + 1} look near-identical — retake one from a different angle",
                color = Color(0xFFFFC107),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(onClick = onDiagnostic, enabled = photos.isNotEmpty()) {
            Text("Depth reprojection test")
        }
        Spacer(Modifier.height(12.dp))
        sorted.forEach { photo ->
            ReviewPhoto(photo = photo, prev = sorted.firstOrNull { it.index == photo.index - 1 }, onRetake = { onRetakePhoto(photo.index) })
            Spacer(Modifier.height(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetake) { Text("＋ New object") }
            androidx.compose.material3.OutlinedButton(onClick = onOpenCatalog) { Text("View catalog") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Catalog: every captured object from Room (newest first), each as a card with a thumbnail of its
 * first photo, the capture date, and its photo count. Tapping a card opens its detail. This is the
 * proof that captures persist across app restarts (the list is Room-backed, not the in-memory
 * session).
 */
@Composable
private fun CatalogView(
    viewModel: CaptureViewModel,
    onOpenObject: (String) -> Unit,
    onBack: () -> Unit,
) {
    val objects by viewModel.objects.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Camera") }
            Text(
                "Catalog (${objects.size})",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (objects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No captures yet.\nTake 3 photos of an object to add it here.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(objects, key = { it.id }) { obj ->
                    CatalogCard(obj = obj, onOpenObject = onOpenObject,
                        onDelete = { viewModel.deleteObject(obj.id) })
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(obj: CapturedObject, onOpenObject: (String) -> Unit, onDelete: () -> Unit) {
    val thumb = remember(obj.id) {
        obj.photos.minByOrNull { it.index }?.let { loadRotatedBitmap(it) }
    }
    val depthOk = obj.photos.count { it.metadata.depthAvailable }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { onOpenObject(obj.id) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = "Object thumbnail",
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF)))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                formatEpoch(obj.createdAtEpochMs),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
            )
            Text(
                "${obj.photos.size} photos · depth ${depthOk}/${obj.photos.size} · id ${obj.id.take(8)}…",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

/**
 * Object detail: loads one object from Room by id and reuses [ReviewPhoto] (image + full metadata
 * panel) for each of its photos, identical to the after-capture review.
 */
@Composable
private fun ObjectDetailView(
    viewModel: CaptureViewModel,
    objectId: String?,
    onBack: () -> Unit,
    onReconstruct: (List<CapturedPhoto>) -> Unit,
    onDiagnostic: (List<CapturedPhoto>) -> Unit,
) {
    var obj by remember(objectId) { mutableStateOf<CapturedObject?>(null) }
    LaunchedEffect(objectId) { obj = objectId?.let { viewModel.loadObject(it) } }

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
            TextButton(onClick = onBack) { Text("‹ Catalog") }
            Text("Object", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        }
        val current = obj
        if (current == null) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onBackground)
            }
        } else {
            Column(modifier = Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Result-card header: capture summary on top, photos below.
                val depthOk = current.photos.count { it.metadata.depthAvailable }
                Text(
                    "Captured ${formatEpoch(current.createdAtEpochMs)} · ${current.photos.size} photos · depth ${depthOk}/${current.photos.size}",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(
                    onClick = { onReconstruct(current.photos) },
                    enabled = current.photos.isNotEmpty(),
                ) { Text("Reconstruct 3D") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { onDiagnostic(current.photos) },
                        enabled = current.photos.isNotEmpty(),
                    ) { Text("Depth test") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            val id = current.id
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                viewModel.exportObjectCsv(id)
                            }
                        },
                    ) { Text("Export CSV") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { viewModel.deleteObject(current.id); onBack() },
                    ) { Text("Delete") }
                }
                Spacer(Modifier.height(12.dp))
                current.photos.sortedBy { it.index }.forEach { photo ->
                    ReviewPhoto(photo = photo, prev = null, onRetake = null)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ReviewPhoto(photo: CapturedPhoto, prev: CapturedPhoto?, onRetake: (() -> Unit)?) {
    val bitmap = remember(photo.imagePath) { loadRotatedBitmap(photo) }
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Captured photo ${photo.index + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(320.dp).background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { Text("Image unavailable", color = Color.White) }
        }
        QualityBadgeRow(photo = photo, prev = prev)
        if (onRetake != null) {
            TextButton(onClick = onRetake) { Text("Retake this photo") }
        }
        Text(
            text = if (showDetails) "▼ Hide details" else "▸ Details",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            modifier = Modifier.clickable { showDetails = !showDetails }.padding(vertical = 4.dp),
        )
        if (showDetails) MetadataPanel(index = photo.index, m = photo.metadata)
    }
}

/** Per-photo quality badge: tracking ✓, depth ✓/✗, pose-delta vs previous shot. */
@Composable
private fun QualityBadgeRow(photo: CapturedPhoto, prev: CapturedPhoto?) {
    val m = photo.metadata
    val trackingOk = m.trackingState == "TRACKING"
    val depthOk = m.depthAvailable
    val deltaDeg = if (prev != null) poseAngleDeg(prev.metadata, m) else null
    val dup = deltaDeg != null && deltaDeg < 5f
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(if (trackingOk) "tracking ✓" else "tracking ✗", if (trackingOk) Color(0xFF00E676) else Color(0xFFFF5252))
        Badge(if (depthOk) "depth ✓" else "depth ✗", if (depthOk) Color(0xFF00E676) else Color(0xFFFF5252))
        if (deltaDeg != null) {
            Badge("Δ %.0f°".format(deltaDeg), if (dup) Color(0xFFFFC107) else Color(0xFF9E9E9E))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x33000000)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Angle (degrees) between two camera orientations, or null when poses are missing. */
private fun poseAngleDeg(a: ARCoreFrameMetadata, b: ARCoreFrameMetadata): Float? {
    val qa = a.poseRotationQuaternion
    val qb = b.poseRotationQuaternion
    if (qa == null || qb == null || qa.size < 4 || qb.size < 4) return null
    val dot = (qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]).coerceIn(-1f, 1f)
    return Math.toDegrees(2.0 * Math.acos(kotlin.math.abs(dot).toDouble())).toFloat()
}

/** Returns the index pair of near-duplicate adjacent viewpoints (<5° apart), or null. */
private fun findNearDuplicate(sorted: List<CapturedPhoto>): Pair<Int, Int>? {
    for (i in 1 until sorted.size) {
        val d = poseAngleDeg(sorted[i - 1].metadata, sorted[i].metadata)
        if (d != null && d < 5f) return sorted[i - 1].index to sorted[i].index
    }
    return null
}

/**
 * Renders every persisted [ARCoreFrameMetadata] field for one photo in a readable, labeled layout.
 * Unavailable values are shown explicitly as "unavailable" (they were stored null, never faked).
 */
@Composable
private fun MetadataPanel(index: Int, m: ARCoreFrameMetadata) {
    var showMatrices by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            "Photo ${index + 1} — ARCore metadata",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        MetaSectionLabel("Timing")
        MetaRow("Captured at", formatEpoch(m.capturedAtEpochMs))
        MetaRow("Frame timestamp", m.frameTimestampNs?.let { "$it ns" } ?: UNAVAILABLE)

        MetaSectionLabel("Tracking")
        MetaRow("State", m.trackingState ?: UNAVAILABLE)

        MetaSectionLabel("Camera pose (world)")
        MetaRow("Position x,y,z (m)", formatVec(m.poseTranslation, "%.3f"))
        MetaRow("Rotation quaternion", formatVec(m.poseRotationQuaternion, "%.3f"))

        MetaSectionLabel("Camera intrinsics")
        MetaRow("Focal length fx,fy (px)", formatVec(m.intrinsicsFocalLength, "%.1f"))
        MetaRow("Principal point cx,cy (px)", formatVec(m.intrinsicsPrincipalPoint, "%.1f"))
        MetaRow("Intrinsics image size", formatIntVec(m.intrinsicsImageDimensions))

        MetaSectionLabel("Camera image")
        MetaRow("Size (w×h px)", if (m.imageWidth != null && m.imageHeight != null) "${m.imageWidth} × ${m.imageHeight}" else UNAVAILABLE)
        MetaRow("Display rotation", "${m.displayRotationDegrees}°")

        MetaSectionLabel("Depth")
        MetaRow("Supported", if (m.depthSupported) "yes" else "no")
        MetaRow("Available this frame", if (m.depthAvailable) "yes" else "no")
        MetaRow("Resolution (w×h px)", if (m.depthAvailable) "${m.depthWidth} × ${m.depthHeight}" else UNAVAILABLE)
        MetaRow("Raw depth file", m.depthRawPath ?: UNAVAILABLE)

        Text(
            text = if (showMatrices) "▼ Hide transform matrices" else "▸ Show transform matrices",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(top = 10.dp)
                .clickable { showMatrices = !showMatrices },
        )
        if (showMatrices) {
            MatrixBlock("Camera → world (column-major)", m.cameraToWorldMatrix)
            MatrixBlock("World → camera / view", m.worldToCameraMatrix)
            MatrixBlock("Projection (near ${m.projectionNear}, far ${m.projectionFar})", m.projectionMatrix)
        }
    }
}

@Composable
private fun MetaSectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.US),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            fontSize = 12.sp,
            modifier = Modifier.width(160.dp),
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/** Renders a 4x4 column-major matrix as 4 rows, horizontally scrollable so long numbers don't wrap. */
@Composable
private fun MatrixBlock(label: String, m: FloatArray?) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (m == null || m.size < 16) {
            // NOTE: never `return` from inside a composable content lambda — a non-local return out
            // of the Column {} block corrupts Compose's slot table and crashes at runtime. Use a
            // plain if/else so both branches emit within the same composable scope.
            Text(UNAVAILABLE, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        } else {
            // Column-major storage: element (row r, col c) is at index c*4 + r. Print in row order.
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (r in 0 until 4) {
                    val row = (0 until 4).joinToString("  ") { c -> "% .4f".format(m[c * 4 + r]) }
                    Text(row, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

private const val UNAVAILABLE = "unavailable"

private fun formatVec(v: FloatArray?, fmt: String): String =
    if (v == null) UNAVAILABLE else v.joinToString(", ") { fmt.format(it) }

private fun formatIntVec(v: IntArray?): String =
    if (v == null) UNAVAILABLE else v.joinToString(" × ")

private fun formatEpoch(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

/** Decodes the saved JPEG and rotates it upright for display using the recorded rotation. */
private fun loadRotatedBitmap(photo: CapturedPhoto): ImageBitmap? {
    val decoded = BitmapFactory.decodeFile(photo.imagePath) ?: return null
    val deg = photo.metadata.displayRotationDegrees
    val upright = if (deg % 360 == 0) {
        decoded
    } else {
        val matrix = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
    return upright.asImageBitmap()
}
