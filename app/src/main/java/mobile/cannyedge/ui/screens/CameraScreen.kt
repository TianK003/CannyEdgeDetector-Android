package mobile.cannyedge.ui.screens

import mobile.cannyedge.ui.viewmodels.CameraViewModel
import android.Manifest
import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import mobile.cannyedge.ui.components.BackButton
import mobile.cannyedge.ui.components.CameraPreview
import mobile.cannyedge.ui.components.CameraSwitchButton
import mobile.cannyedge.ui.components.CaptureButton
import mobile.cannyedge.ui.components.ProcessingStepSlider
import mobile.cannyedge.ui.components.SettingsButton
import mobile.cannyedge.ui.components.SettingsContent

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onBack: () -> Unit,  // Add this parameter for navigation
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
    val isLoading by viewModel.isLoading.collectAsState()

    // Settings state
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val kernelSize by viewModel.kernelSize.collectAsState()
    val lowOverride by viewModel.lowThreshold.collectAsState()
    val highOverride by viewModel.highThreshold.collectAsState()
    val autoLow by viewModel.autoLow.collectAsState()
    val autoHigh by viewModel.autoHigh.collectAsState()

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
        // Camera preview or captured image (background content)
        if (isCameraInitialized.value) {
            if (!isCaptured) {
                CameraPreview(
                    controller = cameraController,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = "Processing image...", color = Color.White)
                        }
                    }
                }

                processedImage?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
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

        // UI controls (always on top)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isCaptured && isCameraInitialized.value) {
                CameraSwitchButton(
                    onSwitchCamera = { viewModel.switchCamera() },
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                CaptureButton(
                    onCapture = { viewModel.captureImage() }
                )
            } else if (isCaptured) {
                Spacer(modifier = Modifier.height(16.dp))
                ProcessingStepSlider(
                    sliderPosition = sliderPosition,
                    onPositionChanged = { viewModel.updateSliderPosition(it) },
                    modifier = Modifier.fillMaxWidth(0.8f)
                )
            }
        }

        // Back button (always visible on top)
        BackButton(
            onBack = {
                if (isCaptured) {
                    viewModel.resetCapture()
                } else {
                    onBack()
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .zIndex(1f)
        )

        // Settings button (visible only after capture)
        if (isCaptured) {
            SettingsButton(
                onClick = { viewModel.openSettings() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .zIndex(1f) // Ensure it's above other elements
            )
        }

        // Settings bottom sheet
        if (isSettingsOpen) {
            val currentStage = sliderPosition.toInt().coerceIn(0, 4)
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = { viewModel.closeSettings() },
                sheetState = sheetState
            ) {
                SettingsContent(
                    currentStage = currentStage,
                    kernelSize = kernelSize,
                    onKernelChange = { viewModel.setKernelSize(it) },
                    lowOverride = lowOverride,
                    highOverride = highOverride,
                    autoLow = autoLow,
                    autoHigh = autoHigh,
                    onThresholdsChange = { l, h -> viewModel.setThresholds(l, h) },
                    onClose = { viewModel.closeSettings() }
                )
            }
        }
    }
}

private fun initializeCamera(
    context: Context,
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    viewModel: CameraViewModel,
    isCameraInitialized: MutableState<Boolean>
) {
    cameraController.bindToLifecycle(lifecycleOwner)
    cameraController.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    viewModel.setCameraController(cameraController)
    isCameraInitialized.value = true
    cameraController.setImageAnalysisAnalyzer(
        ContextCompat.getMainExecutor(context),
        viewModel.createImageAnalyzer()
    )
}