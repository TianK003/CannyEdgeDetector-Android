package mobile.cannyedge.ui.viewmodels

import android.graphics.*
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
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

class LivePreviewViewModel : ViewModel() {
    private var cameraController: LifecycleCameraController? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val _sliderPosition = MutableStateFlow(0f)
    val sliderPosition: StateFlow<Float> = _sliderPosition.asStateFlow()

    private val _processedFrame = MutableStateFlow<Bitmap?>(null)
    val processedFrame: StateFlow<Bitmap?> = _processedFrame.asStateFlow()

    // Settings state (same contract as CameraViewModel)
    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _kernelSize = MutableStateFlow(5)
    val kernelSize: StateFlow<Int> = _kernelSize.asStateFlow()

    private val _lowThreshold = MutableStateFlow<Int?>(null)
    val lowThreshold: StateFlow<Int?> = _lowThreshold.asStateFlow()

    private val _highThreshold = MutableStateFlow<Int?>(null)
    val highThreshold: StateFlow<Int?> = _highThreshold.asStateFlow()

    private val _autoLow = MutableStateFlow(40)
    val autoLow: StateFlow<Int> = _autoLow.asStateFlow()

    private val _autoHigh = MutableStateFlow(100)
    val autoHigh: StateFlow<Int> = _autoHigh.asStateFlow()

    // Pipeline Mats (reused)
    private var matRgba = Mat()
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

    // FPS throttle (~30fps)
    private var lastProcessNs = 0L
    private val frameIntervalNs = 33_000_000L

    init { OpenCVLoader.initDebug() }

    fun resetStage() {
        _sliderPosition.value = 0f
    }

    fun setCameraController(controller: LifecycleCameraController) {
        cameraController = controller
        updateCameraSelector()
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        updateCameraSelector()
    }

    fun updateSliderPosition(position: Float) {
        _sliderPosition.value = position
        // Clear the overlay immediately when returning to "Original"
        if (position.roundToInt() == 0) {
            _processedFrame.value = null
        }
    }

    fun openSettings() { _isSettingsOpen.value = true }
    fun closeSettings() { _isSettingsOpen.value = false }

    fun setKernelSize(ks: Int) {
        val k = (if (ks % 2 == 0) ks + 1 else ks).coerceIn(3, 25)
        _kernelSize.value = k
    }

    fun setThresholds(low: Int?, high: Int?) {
        val l = low?.coerceIn(0, 255)
        val h = high?.coerceIn(0, 255)
        val (ll, hh) = if (l != null && h != null && l > h) h to l else l to h
        _lowThreshold.value = ll
        _highThreshold.value = hh
    }

    fun createLiveAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { image ->
            val now = System.nanoTime()
            if (now - lastProcessNs < frameIntervalNs) { image.close(); return@Analyzer }
            lastProcessNs = now

            try {
                val bmp = imageProxyToBitmap(image, lensFacing)
                viewModelScope.launch(Dispatchers.Default) {
                    val stage = _sliderPosition.value.roundToInt().coerceIn(0, 4)
                    if (stage == 0) {
                        // Original image: no overlay bitmap; let the CameraPreview show through
                        _processedFrame.value = null
                        return@launch
                    }
                    val out = processBitmapForStage(bmp, stage)
                    _processedFrame.value = out
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

    // ---------------- Per-frame pipeline (partial compute by stage) ----------------

    private fun processBitmapForStage(source: Bitmap, stage: Int): Bitmap {
        // Convert to RGBA Mat
        bitmapToRgbaMat(source, matRgba)

        // Gray
        Imgproc.cvtColor(matRgba, matGray, Imgproc.COLOR_RGBA2GRAY)

        // Smoothing
        val k = kernelSize.value.let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(3)
        Imgproc.GaussianBlur(matGray, matBlur, Size(k.toDouble(), k.toDouble()), 0.0, 0.0)
        if (stage == 1) return matToBitmap(matBlur)

        // Gradients + magnitude (8U for display)
        Imgproc.Sobel(matBlur, matGradX, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(matBlur, matGradY, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(matGradX, matGradY, matMag)
        val magMax = max(1.0, Core.minMaxLoc(matMag).maxVal)
        matMag.convertTo(matMag8U, CvType.CV_8U, 255.0 / magMax)
        if (stage == 2) return matToBitmap(matMag8U)

        // Direction + NMS
        if (matDir.empty() || matDir.size() != matGradX.size()) {
            matDir = Mat(matGradX.size(), CvType.CV_32F)
        }
        computeDirection(matGradX, matGradY, matDir)
        matNms = nonMaximumSuppression(matMag, matDir)
        if (stage == 3) return matToBitmap(matNms)

        // Auto thresholds (unless overridden)
        val (aLow, aHigh) = autoThresholds(matNms)
        _autoLow.value = aLow
        _autoHigh.value = aHigh
        val useLow = lowThreshold.value ?: aLow
        val useHigh = highThreshold.value ?: aHigh

        // Double threshold + hysteresis (final)
        matThresh = doubleThreshold(matNms, useLow, useHigh)
        matEdges = hysteresis(matThresh)
        return matToBitmap(matEdges)
    }

    // ---------- Helpers (same logic as your CameraViewModel) ----------

    private fun nonMaximumSuppression(mag: Mat, dir: Mat): Mat {
        val rows = mag.rows(); val cols = mag.cols()
        val total = rows * cols

        val magData = FloatArray(total); mag.get(0, 0, magData)
        val dirData = FloatArray(total); dir.get(0, 0, dirData)
        val out = Mat(rows, cols, CvType.CV_8U)
        val outData = ByteArray(total)

        val maxVal = max(1.0, Core.minMaxLoc(mag).maxVal)
        fun idx(r: Int, c: Int) = r * cols + c
        fun magAt(r: Int, c: Int) = magData[idx(r, c)]

        for (r in 1 until rows - 1) {
            for (c in 1 until cols - 1) {
                val m = magAt(r, c)
                if (m <= 0f) { outData[idx(r,c)] = 0; continue }

                var angle = Math.toDegrees(dirData[idx(r, c)].toDouble())
                if (angle < 0) angle += 180.0

                val (n1r, n1c, n2r, n2c) = when {
                    (angle < 22.5 || angle >= 157.5) -> Quad(r, c - 1, r, c + 1)
                    angle < 67.5 -> Quad(r - 1, c + 1, r + 1, c - 1)
                    angle < 112.5 -> Quad(r - 1, c, r + 1, c)
                    else -> Quad(r - 1, c - 1, r + 1, c + 1)
                }

                val m1 = magAt(n1r, n1c); val m2 = magAt(n2r, n2c)
                outData[idx(r,c)] =
                    if (m >= m1 && m >= m2)
                        ((m * 255.0 / maxVal).toInt().coerceIn(0,255)).toByte()
                    else 0
            }
        }
        out.put(0, 0, outData)
        return out
    }

    private data class Quad(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    private fun autoThresholds(nms8u: Mat): Pair<Int, Int> {
        val mm = Core.minMaxLoc(nms8u)
        val high = max(30.0, mm.maxVal * 0.25).toInt().coerceIn(1, 255)
        val low = (high * 0.4).toInt().coerceAtLeast(1)
        return low to high
    }

    private fun doubleThreshold(nms8u: Mat, low: Int, high: Int): Mat {
        val total = nms8u.rows() * nms8u.cols()
        val inData = ByteArray(total); nms8u.get(0, 0, inData)
        val outData = ByteArray(total)
        val weak = 75; val strong = 255
        for (i in 0 until total) {
            val v = inData[i].toInt() and 0xFF
            outData[i] = when {
                v >= high -> strong.toByte()
                v >= low  -> weak.toByte()
                else      -> 0
            }
        }
        val out = Mat(nms8u.size(), CvType.CV_8U)
        out.put(0, 0, outData)
        return out
    }

    private fun hysteresis(thresh: Mat): Mat {
        val rows = thresh.rows(); val cols = thresh.cols()
        val weak = 75; val strong = 255
        val total = rows * cols

        val inData = ByteArray(total); thresh.get(0, 0, inData)
        val outData = ByteArray(total) { i ->
            val v = inData[i].toInt() and 0xFF
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
                            if ((outData[idx(r+dr, c+dc)].toInt() and 0xFF) == strong) { promote = true; break@loop }
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

    private fun bitmapToRgbaMat(bmp: Bitmap, out: Mat) {
        val safeBmp = if (bmp.config != Bitmap.Config.ARGB_8888)
            bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        if (out.empty() || out.rows() != safeBmp.height || out.cols() != safeBmp.width) {
            out.create(safeBmp.height, safeBmp.width, CvType.CV_8UC4)
        }
        val buffer = ByteBuffer.allocate(safeBmp.byteCount)
        safeBmp.copyPixelsToBuffer(buffer)
        out.put(0, 0, buffer.array())
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

    // -------- CameraX: ImageProxy → Bitmap (same approach as your CameraViewModel) --------

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
