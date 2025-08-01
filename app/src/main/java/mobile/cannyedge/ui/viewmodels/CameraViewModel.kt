package mobile.cannyedge.ui.viewmodels

import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
    private var cameraController: LifecycleCameraController? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    fun setCameraController(controller: LifecycleCameraController) {
        cameraController = controller
        // Set initial camera lens facing
        cameraController?.cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        cameraController?.cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }
}