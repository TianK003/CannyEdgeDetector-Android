package mobile.cannyedge.ui.screens

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import mobile.cannyedge.ui.components.ProcessingStepSlider
import mobile.cannyedge.ui.components.SettingsButton
import mobile.cannyedge.ui.components.SettingsContent
import mobile.cannyedge.ui.viewmodels.LivePreviewViewModel

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LivePreviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LivePreviewViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { LifecycleCameraController(context) }
    val hasPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    val isCameraInitialized = remember { mutableStateOf(false) }

    // Live state
    val frameBitmap by viewModel.processedFrame.collectAsState()
    val sliderPosition by viewModel.sliderPosition.collectAsState()

    // Settings state
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val kernelSize by viewModel.kernelSize.collectAsState()
    val lowOverride by viewModel.lowThreshold.collectAsState()
    val highOverride by viewModel.highThreshold.collectAsState()
    val autoLow by viewModel.autoLow.collectAsState()
    val autoHigh by viewModel.autoHigh.collectAsState()

    // always reset to original level
    LaunchedEffect(Unit) {
        viewModel.resetStage()
    }

    LaunchedEffect(Unit) {
        if (hasPermission.status.isGranted) {
            initializeLiveCamera(
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
            initializeLiveCamera(
                context = context,
                cameraController = cameraController,
                lifecycleOwner = lifecycleOwner,
                viewModel = viewModel,
                isCameraInitialized = isCameraInitialized
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // 1) Camera Preview (background)
        if (isCameraInitialized.value) {
            CameraPreview(
                controller = cameraController,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }

        // 2) Processed frame overlay (live)
        frameBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Live processed",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3) Top-left Back
        BackButton(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .zIndex(1f)
        )

        // 4) Top-right Settings (always visible in live)
        SettingsButton(
            onClick = { viewModel.openSettings() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .zIndex(1f)
        )

        // 5) Bottom controls: switch above slider
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CameraSwitchButton(
                onSwitchCamera = { viewModel.switchCamera() },
                modifier = Modifier.padding(bottom = 16.dp)
            )
            ProcessingStepSlider(
                sliderPosition = sliderPosition,
                onPositionChanged = { viewModel.updateSliderPosition(it) },
                modifier = Modifier.fillMaxWidth(0.9f)
            )
        }

        // 6) Settings bottom sheet
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

private fun initializeLiveCamera(
    context: Context,
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    viewModel: LivePreviewViewModel,
    isCameraInitialized: MutableState<Boolean>
) {
    cameraController.bindToLifecycle(lifecycleOwner)
    cameraController.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    viewModel.setCameraController(cameraController)
    isCameraInitialized.value = true
    cameraController.setImageAnalysisAnalyzer(
        ContextCompat.getMainExecutor(context),
        viewModel.createLiveAnalyzer()
    )
}

