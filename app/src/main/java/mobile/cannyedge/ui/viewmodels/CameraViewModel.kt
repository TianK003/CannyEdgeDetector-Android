package mobile.cannyedge.ui.viewmodels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

class CameraViewModel : ViewModel() {
    private var cameraController: LifecycleCameraController? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val _sliderPosition = MutableStateFlow(0f)
    val sliderPosition: StateFlow<Float> = _sliderPosition.asStateFlow()

    private val _processedImage = MutableStateFlow<Bitmap?>(null)
    val processedImage: StateFlow<Bitmap?> = _processedImage.asStateFlow()

    private var processingStep = 0

    // OpenCV Mats for intermediate processing
    private var grayMat = Mat()
    private var blurredMat = Mat()
    private var gradX = Mat()
    private var gradY = Mat()
    private var magnitudeMat = Mat()
    private var edgesMat = Mat()

    // Threshold values for Canny
    private val lowThreshold = 50.0
    private val highThreshold = 150.0

    fun setCameraController(controller: LifecycleCameraController) {
        cameraController = controller
        updateCameraSelector()
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
    }

    fun setProcessingStep(step: Int) {
        processingStep = step
    }

    fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            override fun analyze(image: ImageProxy) {
                if (processingStep == 0) {
                    image.close()
                    return
                }

                val bitmap = imageProxyToBitmap(image)
                image.close()

                if (bitmap != null) {
                    viewModelScope.launch {
                        processImageWithOpenCV(bitmap)
                    }
                }
            }
        }
    }

    private fun updateCameraSelector() {
        cameraController?.cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    private suspend fun processImageWithOpenCV(bitmap: Bitmap) =
        withContext(Dispatchers.Default) {
            val srcMat = Mat().apply {
                Utils.bitmapToMat(bitmap, this)
            }

            try {
                // Step 1: Convert to grayscale
                Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

                // Display grayscale for all steps
                setProcessedImage(grayMat)

                // Continue processing but don't use results yet
                Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 1.4, 1.4)

                // These will all show grayscale now
                when (processingStep) {
                    1 -> showGradientMagnitude()
                    2 -> showNonMaxSuppression()
                    3 -> showDoubleThreshold()
                    4 -> showFinalEdges()
                }
            } finally {
                srcMat.release()
            }
        }

    private suspend fun showGradientMagnitude() = setProcessedImage(grayMat)
    private suspend fun showNonMaxSuppression() = setProcessedImage(grayMat)
    private suspend fun showDoubleThreshold() = setProcessedImage(grayMat)
    private suspend fun showFinalEdges() = setProcessedImage(grayMat)

    private suspend fun setProcessedImage(mat: Mat) {
        // Create a new Mat to avoid modifying the original
        val displayMat = Mat()
        try {
            // Convert grayscale to RGBA for display
            Imgproc.cvtColor(mat, displayMat, Imgproc.COLOR_GRAY2RGBA)

            val bitmap = createBitmap(displayMat.cols(), displayMat.rows())
            Utils.matToBitmap(displayMat, bitmap)

            withContext(Dispatchers.Main) {
                _processedImage.value = bitmap
            }
        } finally {
            displayMat.release()
        }
    }

    private fun traceAndMark(edges: Mat, finalEdges: Mat, r: Int, c: Int) {
        // Check 8-connected neighbors
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue

                val nr = r + dr
                val nc = c + dc

                // Check bounds
                if (nr < 1 || nr >= edges.rows() - 1 ||
                    nc < 1 || nc >= edges.cols() - 1) continue

                // Check if it's a weak edge and not already processed
                val value = edges.get(nr, nc)[0]
                if (value in (lowThreshold + 1)..highThreshold &&
                    finalEdges.get(nr, nc)[0] < 1.0) {

                    // Mark as final edge
                    finalEdges.put(nr, nc, 255.0)

                    // Recursively trace connected weak edges
                    traceAndMark(edges, finalEdges, nr, nc)
                }
            }
        }
    }

    override fun onCleared() {
        // Release OpenCV Mats when ViewModel is cleared
        grayMat.release()
        blurredMat.release()
        gradX.release()
        gradY.release()
        magnitudeMat.release()
        edgesMat.release()
        super.onCleared()
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // Proper NV21: Y + interleaved VU (V first then U for each 2x2 block)
        val nv21 = ByteArray(ySize + image.width * image.height / 2)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave V and U planes
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride

        var offset = ySize
        var vPos = 0
        var uPos = 0

        // Process 2x2 chroma blocks
        for (row in 0 until image.height / 2) {
            for (col in 0 until image.width / 2) {
                nv21[offset++] = vBuffer.get(vPos)
                nv21[offset++] = uBuffer.get(uPos)
                vPos += vPixelStride
                uPos += uPixelStride
            }
            vPos += vRowStride - (image.width / 2 * vPixelStride)
            uPos += uRowStride - (image.width / 2 * uPixelStride)
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }
}