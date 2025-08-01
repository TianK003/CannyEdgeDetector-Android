package mobile.cannyedge.ui.screens

import mobile.cannyedge.ui.components.CameraPreview
import mobile.cannyedge.ui.components.CameraSwitchButton
import mobile.cannyedge.ui.viewmodels.CameraViewModel
import android.Manifest
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import mobile.cannyedge.ui.components.ProcessingStepSlider

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { LifecycleCameraController(context) }
    val hasPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val showCamera = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasPermission.status.isGranted) {
            hasPermission.launchPermissionRequest()
        } else {
            showCamera.value = true
        }
    }

    LaunchedEffect(hasPermission.status) {
        if (hasPermission.status.isGranted) {
            cameraController.bindToLifecycle(lifecycleOwner)
            viewModel.setCameraController(cameraController)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (showCamera.value) {
            CameraPreview(
                controller = cameraController,
                modifier = Modifier.fillMaxSize()
            )

            CameraSwitchButton(
                onSwitchCamera = { viewModel.switchCamera() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )

            ProcessingStepSlider(
                positions = 4,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }
    }
}

