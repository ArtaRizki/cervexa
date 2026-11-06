package com.idn.kmed.cervexa.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * AI inference pipeline that processes camera frames for abnormality detection.
 *
 * Uses a coroutine-based frame queue (Channel with capacity 3, DROP_OLDEST)
 * to manage incoming frames without blocking the UI thread. The pipeline
 * attempts TFLite inference via [ViaModelHelper] first, falling back to
 * [AcetowhiteDetector] if TFLite fails.
 */
class AiDetector(
    private val context: Context,
    private val viaModelHelper: ViaModelHelper,
    private val acetowhiteDetector: AcetowhiteDetector,
    private val analysisModeManager: AnalysisModeManager,
    private val viaSegmentationHelper: ViaSegmentationHelper? = null
) {

    private val frameChannel = Channel<Bitmap>(
        capacity = 3,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var analysisJob: Job? = null

    private val _result = MutableStateFlow<AbnormalityResult>(AbnormalityResult.Idle)

    /** Observable result of the latest inference. */
    val result: StateFlow<AbnormalityResult> = _result.asStateFlow()

    fun startAnalysis(scope: CoroutineScope) {
        analysisJob?.cancel()

        analysisJob = scope.launch(Dispatchers.Default) {
            for (frame in frameChannel) {
                val analysisResult = analyzeImage(frame)
                _result.value = analysisResult
            }
        }
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        frameChannel.close()
    }

    /**
     * Releases underlying TFLite interpreters to prevent native memory/thread leaks.
     */
    fun close() {
        stopAnalysis()
        runCatching { viaModelHelper.close() }
        runCatching { viaSegmentationHelper?.close() }
    }

    fun submitFrame(bitmap: Bitmap) {
        frameChannel.trySend(bitmap)
    }

    /**
     * Restores raw camera RGB distribution when analyzing snapshots/videos that have the
     * live stream display color calibration baked in, ensuring AI classification and
     * contour segmentation remain invariant to display color contour adjustments.
     */
    private fun restoreRawCameraColorsIfNeeded(src: Bitmap): Bitmap {
        if (src.isRecycled || src.width < 16 || src.height < 16) return src

        val prefs = context.getSharedPreferences(PREFS_COLOR_CALIBRATION, Context.MODE_PRIVATE)
        val brightnessOffset = prefs.getFloat("brightness", DEFAULT_BRIGHTNESS)
        val contrast = prefs.getFloat("contrast", DEFAULT_CONTRAST)
        val saturation = prefs.getFloat("saturation", DEFAULT_SATURATION)
        val redBoost = prefs.getFloat("red", DEFAULT_RED)
        val greenBoost = prefs.getFloat("green", DEFAULT_GREEN)
        val blueBoost = prefs.getFloat("blue", DEFAULT_BLUE)
        val hueOffset = prefs.getFloat("hue", DEFAULT_HUE)

        if (!isCalibratedFrame(src, brightnessOffset)) return src

        val sr = 1f / (contrast * redBoost).coerceAtLeast(0.1f)
        val sg = 1f / (contrast * greenBoost).coerceAtLeast(0.1f)
        val sb = 1f / (contrast * blueBoost).coerceAtLeast(0.1f)

        // 1. Undo brightness, contrast, and RGB channel scaling
        val invMatrix = ColorMatrix(
            floatArrayOf(
                sr, 0f, 0f, 0f, -brightnessOffset * sr,
                0f, sg, 0f, 0f, -brightnessOffset * sg,
                0f, 0f, sb, 0f, -brightnessOffset * sb,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // 2. Undo hue rotation if any
        if (hueOffset != 0f) {
            val theta = -Math.PI * hueOffset / 180.0
            val c = cos(theta).toFloat()
            val s = sin(theta).toFloat()
            val invHue = ColorMatrix(
                floatArrayOf(
                    0.213f + 0.787f * c - 0.213f * s, 0.715f - 0.715f * c - 0.715f * s, 0.072f - 0.072f * c + 0.928f * s, 0f, 0f,
                    0.213f - 0.213f * c + 0.143f * s, 0.715f + 0.285f * c + 0.140f * s, 0.072f - 0.072f * c - 0.283f * s, 0f, 0f,
                    0.213f - 0.213f * c - 0.787f * s, 0.715f - 0.715f * c + 0.715f * s, 0.072f + 0.928f * c + 0.072f * s, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            invMatrix.postConcat(invHue)
        }

        // 3. Undo saturation compression
        val invSat = ColorMatrix().apply {
            setSaturation(1f / saturation.coerceIn(0.2f, 2.0f))
        }
        invMatrix.postConcat(invSat)

        return try {
            val restored = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(restored)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(invMatrix)
                isAntiAlias = false
                isDither = false
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            restored
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create inverse-calibrated bitmap for AI: ${e.message}")
            src
        }
    }

    /**
     * Checks whether the frame exhibits the lifted-black / softened-saturation profile
     * produced by the live stream color calibration matrix.
     */
    private fun isCalibratedFrame(bmp: Bitmap, brightnessOffset: Float): Boolean {
        val w = bmp.width
        val h = bmp.height
        val startX = (w * 0.20f).toInt()
        val endX = (w * 0.80f).toInt()
        val startY = (h * 0.20f).toInt()
        val endY = (h * 0.80f).toInt()
        val stepX = ((endX - startX) / 16).coerceAtLeast(1)
        val stepY = ((endY - startY) / 16).coerceAtLeast(1)

        var sumSat = 0f
        var count = 0
        var darkPixels = 0
        val minExpectedLuma = (brightnessOffset * 0.65f).toInt().coerceAtLeast(18)

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                if (maxC < minExpectedLuma) darkPixels++
                val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC.toFloat() else 0f
                sumSat += sat
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val avgSat = sumSat / count
        val darkRatio = darkPixels.toFloat() / count.toFloat()
        // Calibrated frames have almost no deep-black pixels in the central ROI and lower mean saturation
        return darkRatio < 0.05f && avgSat < 0.42f
    }

    suspend fun analyzeImage(bitmap: Bitmap): AbnormalityResult {
        if (bitmap.isRecycled) {
            return AbnormalityResult.Error("Bitmap sudah di-recycle", 998)
        }
        val aiBitmap = restoreRawCameraColorsIfNeeded(bitmap)
        return try {
            // 1. Primary: TFLite classification inference — returns AbnormalityResult.Detected directly
            val classResult = viaModelHelper.detectAbnormality(aiBitmap)
            if (classResult.label == Classification.ABNORMAL && classResult.contourPoints == null) {
                viaSegmentationHelper?.let { segHelper ->
                    try {
                        val segResult = segHelper.detectAndSegment(aiBitmap, isAlreadyAbnormal = true)
                        if (segResult.label == Classification.NORMAL) {
                            return segResult
                        }
                        if (segResult.contourPoints != null) {
                            return classResult.copy(
                                contourPoints = segResult.contourPoints,
                                boundingBox = segResult.boundingBox ?: classResult.boundingBox,
                                lesionAreaRatio = segResult.lesionAreaRatio,
                                lesionType = segResult.lesionType
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Segmentation helper error: ${e.message}", e)
                    }
                }
            }
            classResult
        } catch (e: Exception) {
            Log.w(TAG, "TFLite inference failed, falling back to acetowhite detector: ${e.message}")
            try {
                val acetowhiteResult = acetowhiteDetector.detect(aiBitmap)
                if (acetowhiteResult.label == Classification.ABNORMAL) {
                    viaSegmentationHelper?.let { segHelper ->
                        try {
                            val segResult = segHelper.detectAndSegment(aiBitmap, isAlreadyAbnormal = true)
                            if (segResult.label == Classification.NORMAL) {
                                return segResult
                            }
                            if (segResult.contourPoints != null) {
                                return acetowhiteResult.copy(
                                    contourPoints = segResult.contourPoints,
                                    boundingBox = segResult.boundingBox ?: acetowhiteResult.boundingBox,
                                    lesionAreaRatio = segResult.lesionAreaRatio,
                                    lesionType = segResult.lesionType
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
                acetowhiteResult
            } catch (fallbackError: Exception) {
                analysisModeManager.deactivate()
                AbnormalityResult.Error(
                    message = "AI Error: ${e.javaClass.simpleName} - ${e.message}",
                    errorCode = 999
                )
            }
        } finally {
            if (aiBitmap !== bitmap && !aiBitmap.isRecycled) {
                aiBitmap.recycle()
            }
        }
    }

    fun validateImage(width: Int, height: Int): String? {
        return if (width < MIN_IMAGE_SIZE || height < MIN_IMAGE_SIZE) {
            "Gambar terlalu kecil untuk dianalisis (minimum ${MIN_IMAGE_SIZE}x${MIN_IMAGE_SIZE} piksel)"
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "AiDetector"
        const val CLASSIFICATION_THRESHOLD = 0.5f
        const val MIN_IMAGE_SIZE = 64
        const val ERROR_CODE_ALL_DETECTORS_FAILED = -2

        const val PREFS_COLOR_CALIBRATION = "cervexa_color_calibration"
        const val DEFAULT_BRIGHTNESS = 35f
        const val DEFAULT_CONTRAST = 1.15f
        const val DEFAULT_SATURATION = 0.60f
        const val DEFAULT_RED = 0.80f
        const val DEFAULT_GREEN = 0.88f
        const val DEFAULT_BLUE = 0.92f
        const val DEFAULT_HUE = 0f
    }
}
