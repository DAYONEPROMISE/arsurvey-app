package com.example.arsurvey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.arsurvey.capture.CaptureViewModel
import com.example.arsurvey.capture.ui.CaptureFlowScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CameraPermissionGate {
                        // Capture-first workflow (this branch): take 3 photos + ARCore metadata per
                        // object, then review. The live-measurement path (MeasureViewModel/ArScreen)
                        // is left intact but is not the entry point here — revert this branch to
                        // restore it.
                        val vm: CaptureViewModel = viewModel()
                        val ctx = LocalContext.current
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE)
                                as android.view.WindowManager
                            @Suppress("DEPRECATION")
                            val rot = wm.defaultDisplay.rotation
                            val deg = when (rot) {
                                android.view.Surface.ROTATION_0 -> 0
                                android.view.Surface.ROTATION_90 -> 90
                                android.view.Surface.ROTATION_180 -> 180
                                else -> 270
                            }
                            // Sensor is landscape; portrait display => 90. Wire real value
                            // instead of leaving the hardcoded default.
                            vm.setDisplayRotationDegrees(if (deg == 0) 90 else deg)
                        }
                        CaptureFlowScreen(vm)
                    }
                }
            }
        }
    }
}

/**
 * Renders [content] only once the CAMERA permission is granted. ARCore cannot
 * open a session without it, so this gate must sit above [ArScreen].
 */
@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    if (granted) {
        content()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                // First-run: explain camera + AR before the system prompts.
                Text(
                    "AR Measure needs the camera to track the room and capture 3 photos of an object.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    "If ARCore is missing, the AR view will offer to install it. Devices without depth can still browse the catalog.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Grant camera access to start AR")
                }
            }
        }
    }
}
