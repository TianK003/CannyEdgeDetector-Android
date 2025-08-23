package mobile.cannyedge.ui.screens

import mobile.cannyedge.ui.components.CameraPreview
import mobile.cannyedge.ui.components.CameraSwitchButton
import mobile.cannyedge.ui.viewmodels.CameraViewModel
import android.Manifest
import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import mobile.cannyedge.R
import mobile.cannyedge.ui.components.ProcessingStepSlider

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { LifecycleCameraController(context) }
    val hasPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val isCameraInitialized = remember { mutableStateOf(false) }
    val sliderPosition by viewModel.sliderPosition.collectAsState()
    val processedImage by viewModel.processedImage.collectAsState()
    val isCaptured by viewModel.isCaptured.collectAsState()

    LaunchedEffect(Unit) {
        if (hasPermission.status.isGranted) {
            initializeCamera(
                context = context,
                cameraController = cameraController,
                lifecycleOwner = lifecycleOwner,
                viewModel = viewModel,
                isCameraInitialized = isCameraInitialized
            )
        } else {
            hasPermission.launchPermissionRequest()
        }
    }

    LaunchedEffect(hasPermission.status) {
        if (hasPermission.status.isGranted && !isCameraInitialized.value) {
            initializeCamera(
                context = context,
                cameraController = cameraController,
                lifecycleOwner = lifecycleOwner,
                viewModel = viewModel,
                isCameraInitialized = isCameraInitialized
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isCameraInitialized.value) {
            if (!isCaptured) {
                CameraPreview(
                    controller = cameraController,
                    modifier = Modifier.fillMaxSize()
                )

                // Camera switch button (top right, only shown before capture)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.White, CircleShape)
                        .size(48.dp)
                ) {
                    CameraSwitchButton(
                        onSwitchCamera = { viewModel.switchCamera() },
                        modifier = Modifier.size(36.dp)
                    )
                }
            } else {
                processedImage?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Back button (top left, only shown after capture)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { viewModel.resetCapture() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "Back to camera"
                        )
                    }
                }

            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isCaptured) {
                    Button(
                        onClick = { viewModel.captureImage() },
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text("Capture")
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))

                    ProcessingStepSlider(
                        positions = 4,
                        sliderPosition = sliderPosition,
                        onPositionChanged = { viewModel.updateSliderPosition(it) },
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private fun initializeCamera(
    context: Context,
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    viewModel: CameraViewModel,
    isCameraInitialized: androidx.compose.runtime.MutableState<Boolean>
) {
    cameraController.bindToLifecycle(lifecycleOwner)
    // Keep only the latest frame so we "freeze" a recent frame at capture
    cameraController.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST

    viewModel.setCameraController(cameraController)
    isCameraInitialized.value = true

    cameraController.setImageAnalysisAnalyzer(
        ContextCompat.getMainExecutor(context),
        viewModel.createImageAnalyzer()
    )
}