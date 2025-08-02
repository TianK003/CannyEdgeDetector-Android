package mobile.cannyedge.ui.screens

import mobile.cannyedge.ui.components.CameraPreview
import mobile.cannyedge.ui.components.CameraSwitchButton
import mobile.cannyedge.ui.viewmodels.CameraViewModel
import android.Manifest
import android.content.Context
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
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

    // Initialize camera and set up analysis
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

    // Update processing step when slider changes
    LaunchedEffect(sliderPosition) {
        viewModel.setProcessingStep(sliderPosition.toInt())
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isCameraInitialized.value) {
            if (sliderPosition == 0f) {
                // Show original camera preview
                CameraPreview(
                    controller = cameraController,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (processedImage != null) {
                // Show processed image
                Image(
                    bitmap = processedImage!!.asImageBitmap(),
                    contentDescription = "Processed Image",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Position the switch button above the slider in the center
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Camera switch button with white background
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(Color.White, CircleShape)
                        .size(48.dp)
                ) {
                    CameraSwitchButton(
                        onSwitchCamera = { viewModel.switchCamera() },
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Slider
                ProcessingStepSlider(
                    positions = 4,
                    sliderPosition = sliderPosition,
                    onPositionChanged = { viewModel.updateSliderPosition(it) },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                )
            }
        } else {
            // Show loading indicator while initializing camera
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
    context: Context,  // Added context parameter
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    viewModel: CameraViewModel,
    isCameraInitialized: MutableState<Boolean>
) {
    cameraController.bindToLifecycle(lifecycleOwner)
    viewModel.setCameraController(cameraController)
    isCameraInitialized.value = true

    // Set up image analysis
    cameraController.setImageAnalysisAnalyzer(
        ContextCompat.getMainExecutor(context),  // Now using the context parameter
        viewModel.createImageAnalyzer()
    )
}
