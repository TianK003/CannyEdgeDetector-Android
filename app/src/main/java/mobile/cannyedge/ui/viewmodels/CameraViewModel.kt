package mobile.cannyedge.ui.viewmodels

import android.graphics.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.max

class CameraViewModel : ViewModel() {
    private var cameraController: LifecycleCameraController? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val _sliderPosition = MutableStateFlow(0f)
    val sliderPosition: StateFlow<Float> = _sliderPosition.asStateFlow()

    private val _processedImage = MutableStateFlow<Bitmap?>(null)
    val processedImage: StateFlow<Bitmap?> = _processedImage.asStateFlow()

    private val _isCaptured = MutableStateFlow(false)
    val isCaptured: StateFlow<Boolean> = _isCaptured.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // NEW: settings state
    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _kernelSize = MutableStateFlow(5)               // odd: 3,5,7,9…
    val kernelSize: StateFlow<Int> = _kernelSize.asStateFlow()

    private val _lowThreshold = MutableStateFlow<Int?>(null)    // null means "auto"
    val lowThreshold: StateFlow<Int?> = _lowThreshold.asStateFlow()

    private val _highThreshold = MutableStateFlow<Int?>(null)   // null means "auto"
    val highThreshold: StateFlow<Int?> = _highThreshold.asStateFlow()

    // Auto thresholds (read-only to UI)
    private val _autoLow = MutableStateFlow(40)
    val autoLow: StateFlow<Int> = _autoLow.asStateFlow()

    private val _autoHigh = MutableStateFlow(100)
    val autoHigh: StateFlow<Int> = _autoHigh.asStateFlow()


    // Remember last auto thresholds so we can display defaults in UI
    private var lastAutoLow = 40
    private var lastAutoHigh = 100

    private var latestFrameBitmap: Bitmap? = null
    private var originalCaptured: Bitmap? = null

    // Pipeline mats
    private var matGray = Mat()
    private var matBlur = Mat()
    private var matGradX = Mat()
    private var matGradY = Mat()
    private var matMag = Mat()
    private var matDir = Mat()
    private var matMag8U = Mat()
    private var matNms = Mat()
    private var matThresh = Mat()
    private var matEdges = Mat()

    private var computeJob: Job? = null

    init { OpenCVLoader.initDebug() }

    fun setCameraController(controller: LifecycleCameraController) {
        cameraController = controller
        updateCameraSelector()
    }

    fun captureImage() {
        val bmp = latestFrameBitmap ?: return
        _isCaptured.value = true
        _isLoading.value = true

        // NEW: reset slider to 0 on every capture
        _sliderPosition.value = 0f

        originalCaptured = bmp.copy(Bitmap.Config.ARGB_8888, false)

        computeJob?.cancel()
        computeJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                runFullPipeline(originalCaptured!!)
                renderStage(0)
            } catch (_: Throwable) {
                _processedImage.value = originalCaptured
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetCapture() {
        _isCaptured.value = false
        _processedImage.value = null
        _isLoading.value = false
        _isSettingsOpen.value = false
        originalCaptured = null
        listOf(matGray, matBlur, matGradX, matGradY, matMag, matDir, matMag8U, matNms, matThresh, matEdges)
            .forEach { if (!it.empty()) it.release() }
        matGray = Mat(); matBlur = Mat(); matGradX = Mat(); matGradY = Mat()
        matMag = Mat(); matDir = Mat(); matMag8U = Mat(); matNms = Mat(); matThresh = Mat(); matEdges = Mat()
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
        if (_isCaptured.value) {
            viewModelScope.launch(Dispatchers.Default) { renderStage(position.roundToInt()) }
        } else {
            latestFrameBitmap?.let { _processedImage.value = it }
        }
    }

    // NEW: Settings visibility
    fun openSettings() { _isSettingsOpen.value = true }
    fun closeSettings() { _isSettingsOpen.value = false }

    // NEW: Change kernel size (odd clamp)
    fun setKernelSize(ks: Int) {
        val k = if (ks % 2 == 0) ks + 1 else ks
        _kernelSize.value = k.coerceIn(3, 25) // reasonable safeguard
        if (_isCaptured.value && !matGray.empty()) {
            viewModelScope.launch(Dispatchers.Default) {
                recomputeFromSmoothing()
                renderStage(_sliderPosition.value.roundToInt())
            }
        }
    }

    // NEW: Set thresholds (null = auto). Ensures low<=high and 0..255
    fun setThresholds(low: Int?, high: Int?) {
        val l = low?.coerceIn(0, 255)
        val h = high?.coerceIn(0, 255)
        val (ll, hh) = if (l != null && h != null && l > h) h to l else l to h
        _lowThreshold.value = ll
        _highThreshold.value = hh
        if (_isCaptured.value && !matNms.empty()) {
            viewModelScope.launch(Dispatchers.Default) {
                recomputeFromThresholds()
                renderStage(_sliderPosition.value.roundToInt())
            }
        }
    }

    fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { image ->
            try {
                if (!_isCaptured.value) {
                    val bitmap = imageProxyToBitmap(image, lensFacing)
                    latestFrameBitmap = bitmap
                    if (_processedImage.value == null || _sliderPosition.value.roundToInt() == 0) {
                        _processedImage.value = bitmap
                    }
                }
            } finally { image.close() }
        }
    }

    private fun updateCameraSelector() {
        cameraController?.cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    // ---------------- Pipeline ----------------

    private fun runFullPipeline(source: Bitmap) {
        val srcMat = bitmapToMat(source)
        if (srcMat.channels() == 3 || srcMat.channels() == 4) {
            Imgproc.cvtColor(srcMat, matGray, Imgproc.COLOR_RGBA2GRAY)
        } else matGray = srcMat.clone()

        // Smoothing with current kernel size
        applySmoothing()

        // Gradients
        Imgproc.Sobel(matBlur, matGradX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(matBlur, matGradY, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(matGradX, matGradY, matMag)

        matDir = Mat(matGradX.size(), CvType.CV_32F)
        computeDirection(matGradX, matGradY, matDir)

        // For display of gradients
        matMag8U = Mat()
        val magMax = max(1.0, Core.minMaxLoc(matMag).maxVal)
        matMag.convertTo(matMag8U, CvType.CV_8U, 255.0 / magMax)

        // NMS
        matNms = nonMaximumSuppression(matMag, matDir)

        // Thresholds (auto unless user set them)
        val (low, high) = autoThresholds(matNms)
        _autoLow.value = low
        _autoHigh.value = high
        lastAutoLow = low
        lastAutoHigh = high

        val useLow = _lowThreshold.value ?: low
        val useHigh = _highThreshold.value ?: high

        matThresh = doubleThreshold(matNms, useLow, useHigh)
        matEdges = hysteresis(matThresh)

        srcMat.release()
    }

    private fun recomputeFromSmoothing() {
        // Called after kernel size changes
        applySmoothing()
        Imgproc.Sobel(matBlur, matGradX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(matBlur, matGradY, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(matGradX, matGradY, matMag)
        matDir = Mat(matGradX.size(), CvType.CV_32F)
        computeDirection(matGradX, matGradY, matDir)

        matMag8U = Mat()
        val magMax = max(1.0, Core.minMaxLoc(matMag).maxVal)
        matMag.convertTo(matMag8U, CvType.CV_8U, 255.0 / magMax)

        matNms = nonMaximumSuppression(matMag, matDir)
        recomputeFromThresholds()
    }

    private fun recomputeFromThresholds() {
        val (autoLow, autoHigh) = autoThresholds(matNms)
        _autoLow.value = autoLow
        _autoHigh.value = autoHigh
        lastAutoLow = autoLow
        lastAutoHigh = autoHigh
        val useLow = _lowThreshold.value ?: autoLow
        val useHigh = _highThreshold.value ?: autoHigh
        matThresh = doubleThreshold(matNms, useLow, useHigh)
        matEdges = hysteresis(matThresh)
    }

    private fun applySmoothing() {
        val k = _kernelSize.value.coerceAtLeast(3).let { if (it % 2 == 0) it + 1 else it }
        Imgproc.GaussianBlur(matGray, matBlur, Size(k.toDouble(), k.toDouble()), 0.0, 0.0)
    }

    // ---------- NMS / thresholds / hysteresis helpers ----------

    private fun nonMaximumSuppression(mag: Mat, dir: Mat): Mat {
        val rows = mag.rows()
        val cols = mag.cols()

        val magData = FloatArray(rows * cols); mag.get(0, 0, magData)
        val dirData = FloatArray(rows * cols); dir.get(0, 0, dirData)

        val out = Mat(rows, cols, CvType.CV_8U)
        val outData = ByteArray(rows * cols)

        val maxVal = max(1.0, Core.minMaxLoc(mag).maxVal)
        fun magAt(r: Int, c: Int) = magData[r * cols + c]

        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val m = magAt(r, c)
                if (m <= 0f) { outData[r * cols + c] = 0; continue }

                var angle = Math.toDegrees(dirData[r * cols + c].toDouble())
                if (angle < 0) angle += 180.0

                val (n1r, n1c, n2r, n2c) = when {
                    (angle < 22.5 || angle >= 157.5) -> Quad(r, c - 1, r, c + 1)
                    angle < 67.5 -> Quad(r - 1, c + 1, r + 1, c - 1)
                    angle < 112.5 -> Quad(r - 1, c, r + 1, c)
                    else -> Quad(r - 1, c - 1, r + 1, c + 1)
                }

                val m1 = magAt(n1r, n1c)
                val m2 = magAt(n2r, n2c)

                outData[r * cols + c] =
                    if (m >= m1 && m >= m2) ((m * 255.0 / maxVal).roundToInt().coerceIn(0, 255)).toByte()
                    else 0
            }
        }

        out.put(0, 0, outData)
        return out
    }

    private data class Quad(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    private fun autoThresholds(nms8u: Mat): Pair<Int, Int> {
        // Heuristic: take a conservative high ~ 25% of max, low = 40% of high
        val mm = Core.minMaxLoc(nms8u)
        val high = max(30.0, mm.maxVal * 0.25).toInt().coerceIn(1, 255)
        val low = (high * 0.4).toInt().coerceAtLeast(1)
        return low to high
    }

    private fun doubleThreshold(nms8u: Mat, low: Int, high: Int): Mat {
        val out = Mat(nms8u.size(), CvType.CV_8U)
        val weak = 75; val strong = 255
        val total = nms8u.rows() * nms8u.cols()
        val inData = ByteArray(total); nms8u.get(0, 0, inData)
        val outData = ByteArray(total)
        for (i in 0 until total) {
            val v = inData[i].toInt() and 0xFF
            outData[i] = when {
                v >= high -> strong.toByte()
                v >= low -> weak.toByte()
                else -> 0
            }
        }
        out.put(0, 0, outData)
        return out
    }

    private fun hysteresis(thresh: Mat): Mat {
        val rows = thresh.rows()
        val cols = thresh.cols()
        val weak = 75; val strong = 255

        val inData = ByteArray(rows * cols); thresh.get(0, 0, inData)
        val outData = ByteArray(rows * cols) { idx ->
            val v = inData[idx].toInt() and 0xFF
            if (v == strong) 255.toByte() else 0.toByte()
        }

        fun idx(r: Int, c: Int) = r * cols + c
        var changed: Boolean
        do {
            changed = false
            for (r in 1 until rows - 1) {
                for (c in 1 until cols - 1) {
                    val i = idx(r, c)
                    if ((inData[i].toInt() and 0xFF) == weak && (outData[i].toInt() and 0xFF) == 0) {
                        var promote = false
                        loop@ for (dr in -1..1) for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            if ((outData[idx(r + dr, c + dc)].toInt() and 0xFF) == strong) { promote = true; break@loop }
                        }
                        if (promote) { outData[i] = strong.toByte(); changed = true }
                    }
                }
            }
        } while (changed)

        val out = Mat(rows, cols, CvType.CV_8U)
        out.put(0, 0, outData)
        return out
    }

    private fun computeDirection(gx: Mat, gy: Mat, outDir: Mat) {
        val total = gx.rows() * gx.cols()
        val gxData = FloatArray(total); gx.get(0, 0, gxData)
        val gyData = FloatArray(total); gy.get(0, 0, gyData)
        val dirData = FloatArray(total)
        for (i in 0 until total) dirData[i] = atan2(gyData[i], gxData[i]).toFloat()
        outDir.put(0, 0, dirData)
    }

    private fun renderStage(stage: Int) {
        val bmp: Bitmap? = when (stage) {
            0 -> originalCaptured
            1 -> if (matBlur.empty()) null else matToBitmap(matBlur)
            2 -> if (matMag8U.empty()) null else matToBitmap(matMag8U)
            3 -> if (matNms.empty()) null else matToBitmap(matNms)
            4 -> if (matEdges.empty()) null else matToBitmap(matEdges)
            else -> originalCaptured
        }
        bmp?.let { _processedImage.value = it }
    }

    // ---------- Bitmap/Mat + CameraX helpers (same as before) ----------

    private fun bitmapToMat(bmp: Bitmap): Mat {
        val safeBmp = if (bmp.config != Bitmap.Config.ARGB_8888) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val mat = Mat(safeBmp.height, safeBmp.width, CvType.CV_8UC4)
        val buffer = ByteBuffer.allocate(safeBmp.byteCount)
        safeBmp.copyPixelsToBuffer(buffer)
        mat.put(0, 0, buffer.array())
        return mat
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val rgba = when (mat.type()) {
            CvType.CV_8UC1 -> Mat().also { Imgproc.cvtColor(mat, it, Imgproc.COLOR_GRAY2RGBA) }
            CvType.CV_8UC3 -> Mat().also { Imgproc.cvtColor(mat, it, Imgproc.COLOR_BGR2RGBA) }
            CvType.CV_8UC4 -> mat
            else -> {
                val norm = Mat(); val tmp8 = Mat(); val out = Mat()
                Core.normalize(mat, norm, 0.0, 255.0, Core.NORM_MINMAX)
                norm.convertTo(tmp8, CvType.CV_8U)
                Imgproc.cvtColor(tmp8, out, Imgproc.COLOR_GRAY2RGBA)
                norm.release(); tmp8.release(); out
            }
        }
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        val data = ByteArray(rgba.rows() * rgba.cols() * rgba.channels())
        rgba.get(0, 0, data)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        if (rgba !== mat && !rgba.empty()) rgba.release()
        return bmp
    }

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

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width; val height = image.height
        val ySize = width * height; val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)
        val yPlane = image.planes[0]
        copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, nv21, 0)
        val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val chromaWidth = width / 2; val chromaHeight = height / 2
        val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride; val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride; val vPixelStride = vPlane.pixelStride
        var outputOffset = ySize
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride; val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPixelStride
                val vIndex = vRowStart + col * vPixelStride
                nv21[outputOffset++] = vBuffer.get(vIndex)
                nv21[outputOffset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    private fun copyPlane(
        src: ByteBuffer, rowStride: Int, pixelStride: Int,
        width: Int, height: Int, out: ByteArray, outOffset: Int
    ) {
        var offset = outOffset
        for (row in 0 until height) {
            var colSrcIndex = row * rowStride
            for (col in 0 until width) {
                out[offset++] = src.get(colSrcIndex)
                colSrcIndex += pixelStride
            }
        }
    }

    private fun rotateAndMirrorBitmap(bitmap: Bitmap, rotationDegrees: Float, lensFacing: Int): Bitmap {
        if (rotationDegrees == 0f && lensFacing != CameraSelector.LENS_FACING_FRONT) return bitmap
        val m = Matrix()
        if (rotationDegrees != 0f) m.postRotate(rotationDegrees)
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) { m.postScale(-1f, 1f); m.postTranslate(bitmap.width.toFloat(), 0f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}
