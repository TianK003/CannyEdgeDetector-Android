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
import java.io.ByteArrayOutputStream

class CameraViewModel : ViewModel() {
    private var cameraController: LifecycleCameraController? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val _sliderPosition = MutableStateFlow(0f)
    val sliderPosition: StateFlow<Float> = _sliderPosition.asStateFlow()

    private val _processedImage = MutableStateFlow<Bitmap?>(null)
    val processedImage: StateFlow<Bitmap?> = _processedImage.asStateFlow()

    private var processingStep = 0

    fun setCameraController(controller: LifecycleCameraController) {
        cameraController = controller
        // Set initial camera lens facing
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
                        processImage(bitmap)
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

    private suspend fun processImage(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        // Convert to grayscale
        val grayBitmap = toGrayscale(bitmap)

        // Apply Gaussian blur
        val blurred = applyGaussianBlur(grayBitmap)

        // Calculate derivatives
        val (derivativeX, derivativeY) = calculateDerivatives(blurred)

        // Calculate gradient magnitude
        val gradient = calculateGradientMagnitude(derivativeX, derivativeY)

        // Update UI with processed image
        _processedImage.value = gradient
    }

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                grayBitmap.setPixel(x, y, (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray)
            }
        }

        return grayBitmap
    }

    private fun applyGaussianBlur(bitmap: Bitmap): Array<FloatArray> {
        val width = bitmap.width
        val height = bitmap.height
        val matrix = Array(width) { FloatArray(height) }

        // Convert to float matrix
        for (x in 0 until width) {
            for (y in 0 until height) {
                matrix[x][y] = (bitmap.getPixel(x, y) and 0xFF).toFloat()
            }
        }

        // Simple 3x3 Gaussian kernel
        val kernel = arrayOf(
            floatArrayOf(1f, 2f, 1f),
            floatArrayOf(2f, 4f, 2f),
            floatArrayOf(1f, 2f, 1f)
        )
        val kernelSum = 16f

        // Apply convolution
        val result = Array(width) { FloatArray(height) }

        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                var sum = 0f
                for (i in -1..1) {
                    for (j in -1..1) {
                        sum += matrix[x + i][y + j] * kernel[i + 1][j + 1]
                    }
                }
                result[x][y] = sum / kernelSum
            }
        }

        return result
    }

    private fun calculateDerivatives(matrix: Array<FloatArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
        val width = matrix.size
        val height = matrix[0].size
        val derivativeX = Array(width) { FloatArray(height) }
        val derivativeY = Array(width) { FloatArray(height) }

        // Simple derivative kernels
        val kernelX = arrayOf(floatArrayOf(-1f, 0f, 1f))
        val kernelY = arrayOf(floatArrayOf(-1f), floatArrayOf(0f), floatArrayOf(1f))

        // Calculate X derivative
        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                derivativeX[x][y] = matrix[x - 1][y] * kernelX[0][0] +
                        matrix[x][y] * kernelX[0][1] +
                        matrix[x + 1][y] * kernelX[0][2]
            }
        }

        // Calculate Y derivative
        for (x in 1 until width - 1) {
            for (y in 1 until height - 1) {
                derivativeY[x][y] = matrix[x][y - 1] * kernelY[0][0] +
                        matrix[x][y] * kernelY[1][0] +
                        matrix[x][y + 1] * kernelY[2][0]
            }
        }

        return Pair(derivativeX, derivativeY)
    }

    private fun calculateGradientMagnitude(
        derivativeX: Array<FloatArray>,
        derivativeY: Array<FloatArray>
    ): Bitmap {
        val width = derivativeX.size
        val height = derivativeX[0].size
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maxGradient = findMaxGradient(derivativeX, derivativeY)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val gx = derivativeX[x][y]
                val gy = derivativeY[x][y]
                val magnitude = Math.sqrt((gx * gx + gy * gy).toDouble())

                // Normalize to 0-255
                val normalized = (magnitude * 255 / maxGradient).toInt().coerceIn(0, 255)
                output.setPixel(
                    x,
                    y,
                    (0xFF shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
                )
            }
        }

        return output
    }

    private fun findMaxGradient(
        derivativeX: Array<FloatArray>,
        derivativeY: Array<FloatArray>
    ): Double {
        var max = 0.0
        for (x in 0 until derivativeX.size) {
            for (y in 0 until derivativeX[0].size) {
                val gx = derivativeX[x][y]
                val gy = derivativeY[x][y]
                val magnitude = Math.sqrt((gx * gx + gy * gy).toDouble())
                if (magnitude > max) max = magnitude
            }
        }
        return if (max > 0) max else 1.0 // Avoid division by zero
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