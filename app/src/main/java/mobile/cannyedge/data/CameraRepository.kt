package mobile.cannyedge.data

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraRepository {

    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var lifecycleOwner: LifecycleOwner? = null

    suspend fun initializeCamera(context: Context, owner: LifecycleOwner) {
        lifecycleOwner = owner
        cameraProvider = suspendCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                continuation.resume(cameraProviderFuture.get())
            }, ContextCompat.getMainExecutor(context))
        }

        previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView!!.surfaceProvider)
        }

        startCamera()
    }

    fun getPreviewView(): PreviewView? = previewView

    fun switchCamera(newLensFacing: Int) {
        currentLensFacing = newLensFacing
        cameraProvider?.unbindAll()
        startCamera()
    }

    private fun startCamera() {
        val owner = lifecycleOwner ?: return
        try {
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build()

            cameraProvider?.bindToLifecycle(
                owner,
                cameraSelector,
                preview
            )
        } catch (e: Exception) {
            // Handle camera start error
        }
    }
}