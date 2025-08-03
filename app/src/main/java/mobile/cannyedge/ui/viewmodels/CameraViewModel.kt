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
            // Convert Bitmap to OpenCV Mat
            val srcMat = Mat().apply {
                Utils.bitmapToMat(bitmap, this)
            }

            try {
                // Step 1: Convert to grayscale
                Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGB2GRAY)

                // Step 2: Apply Gaussian blur
                Imgproc.GaussianBlur(
                    grayMat,
                    blurredMat,
                    Size(5.0, 5.0),
                    1.4,
                    1.4
                )

                // Process based on selected step
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

    private fun showGradientMagnitude() {
        // Calculate derivatives
        Imgproc.Sobel(blurredMat, gradX, CvType.CV_16S, 1, 0, 3, 1.0, 0.0)
        Imgproc.Sobel(blurredMat, gradY, CvType.CV_16S, 0, 1, 3, 1.0, 0.0)

        // Calculate gradient magnitude
        Core.convertScaleAbs(gradX, gradX)
        Core.convertScaleAbs(gradY, gradY)
        Core.addWeighted(gradX, 0.5, gradY, 0.5, 0.0, magnitudeMat)

        // Convert to 4-channel BGRA for display
        val displayMat = Mat()
        Imgproc.cvtColor(magnitudeMat, displayMat, Imgproc.COLOR_GRAY2BGRA)

        // Convert to bitmap and update state
        val outputBitmap = Bitmap.createBitmap(
            displayMat.cols(),
            displayMat.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, outputBitmap)
        _processedImage.value = outputBitmap

        // Release temporary Mat
        displayMat.release()
    }

    private fun showNonMaxSuppression() {
        // Calculate derivatives in float format
        Imgproc.Sobel(blurredMat, gradX, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(blurredMat, gradY, CvType.CV_32F, 0, 1)

        // Calculate magnitude and direction
        Core.magnitude(gradX, gradY, magnitudeMat)
        val angleMat = Mat()
        Core.phase(gradX, gradY, angleMat, true)

        // Apply non-max suppression
        val suppressed = nonMaxSuppression(magnitudeMat, angleMat)

        // Convert to 4-channel BGRA for display
        val displayMat = Mat()
        Imgproc.cvtColor(suppressed, displayMat, Imgproc.COLOR_GRAY2BGRA)

        // Convert to bitmap and update state
        val outputBitmap = Bitmap.createBitmap(
            displayMat.cols(),
            displayMat.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, outputBitmap)
        _processedImage.value = outputBitmap

        // Release temporary Mats
        angleMat.release()
        displayMat.release()
        suppressed.release()
    }

    private fun showDoubleThreshold() {
        // Calculate edges using Canny
        Imgproc.Canny(blurredMat, edgesMat, lowThreshold, highThreshold)

        // Apply double threshold
        val thresholded = applyDoubleThreshold(edgesMat)

        // Convert to 4-channel BGRA for display
        val displayMat = Mat()
        Imgproc.cvtColor(thresholded, displayMat, Imgproc.COLOR_GRAY2BGRA)

        // Convert to bitmap and update state
        val outputBitmap = Bitmap.createBitmap(
            displayMat.cols(),
            displayMat.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, outputBitmap)
        _processedImage.value = outputBitmap

        // Release temporary Mats
        displayMat.release()
        thresholded.release()
    }

    private fun showFinalEdges() {
        // Calculate final edges using Canny
        Imgproc.Canny(blurredMat, edgesMat, lowThreshold, highThreshold)

        // Apply hysteresis
        val finalEdges = applyHysteresis(edgesMat)

        // Convert to 4-channel BGRA for display
        val displayMat = Mat()
        Imgproc.cvtColor(finalEdges, displayMat, Imgproc.COLOR_GRAY2BGRA)

        // Convert to bitmap and update state
        val outputBitmap = Bitmap.createBitmap(
            displayMat.cols(),
            displayMat.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(displayMat, outputBitmap)
        _processedImage.value = outputBitmap

        // Release temporary Mats
        displayMat.release()
        finalEdges.release()
    }

    private fun nonMaxSuppression(magnitude: Mat, angle: Mat): Mat {
        val suppressed = Mat.zeros(magnitude.size(), CvType.CV_8U)
        val rows = magnitude.rows()
        val cols = magnitude.cols()

        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val dir = angle.get(r, c)[0]
                val mag = magnitude.get(r, c)[0]

                // Determine neighbors to compare based on gradient direction
                val (r1, c1) = when {
                    dir < 22.5 || dir >= 157.5 -> Pair(r, c - 1)    // 0° (horizontal)
                    dir < 67.5 -> Pair(r - 1, c + 1)                 // 45°
                    dir < 112.5 -> Pair(r - 1, c)                    // 90° (vertical)
                    else -> Pair(r - 1, c - 1)                       // 135°
                }

                val (r2, c2) = when {
                    dir < 22.5 || dir >= 157.5 -> Pair(r, c + 1)
                    dir < 67.5 -> Pair(r + 1, c - 1)
                    dir < 112.5 -> Pair(r + 1, c)
                    else -> Pair(r + 1, c + 1)
                }

                // Suppress non-maximum pixels
                if (mag >= magnitude.get(r1, c1)[0] &&
                    mag >= magnitude.get(r2, c2)[0]) {
                    // Convert float magnitude to byte (0-255) and store
                    val byteValue = (mag * 255).toInt().coerceIn(0, 255).toByte()
                    suppressed.put(r, c, byteArrayOf(byteValue))
                }
            }
        }
        return suppressed
    }

    private fun applyDoubleThreshold(edges: Mat): Mat {
        val thresholded = Mat.zeros(edges.size(), CvType.CV_8U)
        val rows = edges.rows()
        val cols = edges.cols()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val value = edges.get(r, c)[0]
                when {
                    value > highThreshold -> thresholded.put(r, c, 255.0)  // Strong edge
                    value > lowThreshold -> thresholded.put(r, c, 100.0)   // Weak edge
                }
            }
        }
        return thresholded
    }

    private fun applyHysteresis(edges: Mat): Mat {
        val finalEdges = Mat.zeros(edges.size(), CvType.CV_8U)
        val rows = edges.rows()
        val cols = edges.cols()

        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                if (edges.get(r, c)[0] > highThreshold) {
                    // Strong edge - mark as final edge
                    finalEdges.put(r, c, 255.0)

                    // Trace connected weak edges
                    traceAndMark(edges, finalEdges, r, c)
                }
            }
        }
        return finalEdges
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
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y
        yBuffer.get(nv21, 0, ySize)

        // U
        vBuffer.get(nv21, ySize, vSize)

        // V
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21, ImageFormat.NV21, image.width, image.height, null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}