package mobile.cannyedge.ui.viewmodels

import android.graphics.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

class CameraViewModel : ViewModel() {
    private var cameraController: LifecycleCameraController? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val _sliderPosition = MutableStateFlow(0f)
    val sliderPosition: StateFlow<Float> = _sliderPosition.asStateFlow()

    private val _processedImage = MutableStateFlow<Bitmap?>(null)
    val processedImage: StateFlow<Bitmap?> = _processedImage.asStateFlow()

    private val _isCaptured = MutableStateFlow(false)
    val isCaptured: StateFlow<Boolean> = _isCaptured.asStateFlow()

    private var capturedBitmap: Bitmap? = null

    fun setCameraController(controller: LifecycleCameraController) {
        cameraController = controller
        updateCameraSelector()
    }

    fun captureImage() {
        // Simply show the last analyzed frame
        _isCaptured.value = true
        capturedBitmap?.let { _processedImage.value = it }
    }

    fun resetCapture() {
        _isCaptured.value = false
        _processedImage.value = null
        capturedBitmap = null
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        updateCameraSelector()
    }

    fun updateSliderPosition(position: Float) {
        _sliderPosition.value = position
        capturedBitmap?.let {
            _processedImage.value = it
        }
    }

    fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { image ->
            try {
                if (!_isCaptured.value) {
                    val bitmap = imageProxyToBitmap(image, lensFacing)
                    capturedBitmap = bitmap
                }
            } finally {
                image.close()
            }
        }
    }

    private fun updateCameraSelector() {
        cameraController?.cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    /**
     * Convert ImageProxy (YUV_420_888) → NV21 (stride-correct) → JPEG → Bitmap,
     * then rotate and mirror (for front camera) to match PreviewView.
     */
    private fun imageProxyToBitmap(image: ImageProxy, lensFacing: Int): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        bitmap = rotateAndMirrorBitmap(bitmap, image.imageInfo.rotationDegrees.toFloat(), lensFacing)
        return bitmap
    }

    /**
     * Correctly copies Y, U, and V planes taking rowStride/pixelStride into account.
     * Produces NV21 layout (YYYY... VU VU ...).
     */
    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        // --- Copy Y plane ---
        val yPlane = image.planes[0]
        copyPlane(
            src = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride, // should be 1 but don't assume
            width = width,
            height = height,
            out = nv21,
            outOffset = 0
        )

        // --- Copy interleaved VU for NV21 (V then U) ---
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var outputOffset = ySize
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                // NV21 wants V first, then U
                nv21[outputOffset++] = vBuffer.get(vIndex)
                nv21[outputOffset++] = uBuffer.get(uIndex)
            }
        }

        return nv21
    }

    /**
     * Copies a plane into the destination taking into account strides.
     */
    private fun copyPlane(
        src: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int
    ) {
        var offset = outOffset
        // We do absolute indexed gets so position/limit aren't critical
        for (row in 0 until height) {
            var colSrcIndex = row * rowStride
            for (col in 0 until width) {
                out[offset++] = src.get(colSrcIndex)
                colSrcIndex += pixelStride
            }
        }
    }

    private fun rotateAndMirrorBitmap(
        bitmap: Bitmap,
        rotationDegrees: Float,
        lensFacing: Int
    ): Bitmap {
        if (rotationDegrees == 0f && lensFacing != CameraSelector.LENS_FACING_FRONT) {
            return bitmap
        }
        val m = Matrix()
        // Rotate to upright
        if (rotationDegrees != 0f) m.postRotate(rotationDegrees)
        // Mirror for front camera so the still matches the preview
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            m.postScale(-1f, 1f)
            // After mirroring we also translate to keep it in view
            m.postTranslate(bitmap.width.toFloat(), 0f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
