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
import kotlin.math.sqrt

/**
 * AI inference pipeline that processes camera frames for abnormality detection.
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
     * Computes a 12x12 color-invariant, brightness/contrast-normalized structural descriptor
     * of the central cervical zone (0.22..0.78, excluding watermarks/timestamps).
     * Because the vector is zero-mean and unit-norm (Z-score normalized), it is mathematically
     * invariant to affine brightness/contrast/saturation changes across snapshots of the same object.
     */
    private fun computeStructuralFingerprint(bmp: Bitmap): FloatArray? {
        if (bmp.isRecycled || bmp.width < 32 || bmp.height < 32) return null
        val grid = 12
        val desc = FloatArray(grid * grid)
        val w = bmp.width
        val h = bmp.height
        val x0 = w * 0.22f
        val y0 = h * 0.22f
        val roiW = w * 0.56f
        val roiH = h * 0.56f
        val cellW = roiW / grid
        val cellH = roiH / grid

        var sum = 0f
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                var cellLuma = 0f
                var samples = 0
                for (sy in 1..3) {
                    val py = (y0 + (gy + sy * 0.25f) * cellH).toInt().coerceIn(0, h - 1)
                    for (sx in 1..3) {
                        val px = (x0 + (gx + sx * 0.25f) * cellW).toInt().coerceIn(0, w - 1)
                        val c = bmp.getPixel(px, py)
                        val r = Color.red(c)
                        val g = Color.green(c)
                        val b = Color.blue(c)
                        cellLuma += 0.299f * r + 0.587f * g + 0.114f * b
                        samples++
                    }
                }
                val v = cellLuma / samples
                desc[gy * grid + gx] = v
                sum += v
            }
        }

        val mean = sum / desc.size
        var normSq = 0f
        for (i in desc.indices) {
            val d = desc[i] - mean
            desc[i] = d
            normSq += d * d
        }
        val norm = sqrt(normSq)
        if (norm < 1e-3f) return null
        for (i in desc.indices) {
            desc[i] /= norm
        }
        return desc
    }

    /**
     * Restores raw camera RGB distribution when analyzing snapshots/videos that have the
     * live stream display color calibration baked in, and normalizes per-image saturation/black-point
     * so multiple snapshots of the same object under different color contours match.
     */
    private fun restoreRawCameraColorsIfNeeded(src: Bitmap): Bitmap {
        if (src.isRecycled || src.width < 16 || src.height < 16) return src

        val stats = measureRoiColorStats(src) ?: return src
        // If frame already has raw high-saturation / deep-shadow camera characteristics, keep as-is
        if (stats.darkRatio >= 0.08f && stats.avgSat >= 0.36f) return src

        val prefs = context.getSharedPreferences(PREFS_COLOR_CALIBRATION, Context.MODE_PRIVATE)
        val prefBright = prefs.getFloat("brightness", DEFAULT_BRIGHTNESS)
        val prefContrast = prefs.getFloat("contrast", DEFAULT_CONTRAST)
        val prefSat = prefs.getFloat("saturation", DEFAULT_SATURATION)
        val prefRed = prefs.getFloat("red", DEFAULT_RED)
        val prefGreen = prefs.getFloat("green", DEFAULT_GREEN)
        val prefBlue = prefs.getFloat("blue", DEFAULT_BLUE)
        val hueOffset = prefs.getFloat("hue", DEFAULT_HUE)

        // Adaptive per-image parameters: combine saved calibration with actual image ROI stats
        // so even snapshots taken under different slider positions are normalized to the same canonical space
        val brightnessOffset = if (stats.minLuma >= 18) {
            max(prefBright, (stats.minLuma - 6).toFloat().coerceIn(0f, 55f))
        } else 0f
        val contrast = prefContrast.coerceIn(0.8f, 1.5f)
        val redBoost = prefRed.coerceIn(0.6f, 1.2f)
        val greenBoost = prefGreen.coerceIn(0.6f, 1.2f)
        val blueBoost = prefBlue.coerceIn(0.6f, 1.2f)
        val saturation = if (stats.avgSat in 0.08f..0.34f) {
            min(prefSat, (stats.avgSat / 0.34f).coerceIn(0.48f, 0.90f))
        } else prefSat

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
            setSaturation(1f / saturation.coerceIn(0.35f, 1.5f))
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

    private data class RoiStats(
        val avgSat: Float,
        val darkRatio: Float,
        val minLuma: Int
    )

    private fun measureRoiColorStats(bmp: Bitmap): RoiStats? {
        val w = bmp.width
        val h = bmp.height
        val startX = (w * 0.20f).toInt()
        val endX = (w * 0.80f).toInt()
        val startY = (h * 0.20f).toInt()
        val endY = (h * 0.78f).toInt()
        val stepX = ((endX - startX) / 16).coerceAtLeast(1)
        val stepY = ((endY - startY) / 16).coerceAtLeast(1)

        var sumSat = 0f
        var count = 0
        var darkPixels = 0
        var minLuma = 255

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
                val luma = (r + g + b) / 3
                if (luma < minLuma) minLuma = luma
                if (maxC < 24) darkPixels++
                val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC.toFloat() else 0f
                sumSat += sat
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return null
        return RoiStats(
            avgSat = sumSat / count,
            darkRatio = darkPixels.toFloat() / count.toFloat(),
            minLuma = minLuma
        )
    }

    suspend fun analyzeImage(bitmap: Bitmap): AbnormalityResult {
        if (bitmap.isRecycled) {
            return AbnormalityResult.Error("Bitmap sudah di-recycle", 998)
        }

        // Check structural similarity cache so multiple snapshots of the exact same object in preview
        // (even with different color contours or timestamps) always return a consistent AI result
        val fingerprint = computeStructuralFingerprint(bitmap)
        if (fingerprint != null) {
            findCachedSceneResult(fingerprint)?.let { cached ->
                Log.d(TAG, "Scene similarity cache hit (>=0.945) — returning consistent AI result")
                return cached
            }
        }

        val aiBitmap = restoreRawCameraColorsIfNeeded(bitmap)
        val finalResult: AbnormalityResult = try {
            // 1. Primary: TFLite classification inference
            val classResult = viaModelHelper.detectAbnormality(aiBitmap)
            if (classResult.label == Classification.ABNORMAL && classResult.contourPoints == null) {
                viaSegmentationHelper?.let { segHelper ->
                    try {
                        val segResult = segHelper.detectAndSegment(aiBitmap, isAlreadyAbnormal = true)
                        if (segResult.label == Classification.NORMAL) {
                            return@let segResult
                        }
                        if (segResult.contourPoints != null) {
                            return@let classResult.copy(
                                contourPoints = segResult.contourPoints,
                                boundingBox = segResult.boundingBox ?: classResult.boundingBox,
                                lesionAreaRatio = segResult.lesionAreaRatio,
                                lesionType = segResult.lesionType
                            )
                        }
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "Segmentation helper error: ${e.message}", e)
                        null
                    }
                } ?: classResult
            } else if (classResult.label == Classification.NORMAL && classResult.confidenceScore <= 0.62f) {
                // Borderline zone: consult YOLOv8-Seg so consecutive snapshots of the same lesion don't flip-flop
                viaSegmentationHelper?.let { segHelper ->
                    runCatching {
                        val segResult = segHelper.detectAndSegment(aiBitmap, isAlreadyAbnormal = false)
                        if (segResult.label == Classification.ABNORMAL && segResult.contourPoints != null) {
                            segResult
                        } else classResult
                    }.getOrDefault(classResult)
                } ?: classResult
            } else {
                classResult
            }
        } catch (e: Exception) {
            Log.w(TAG, "TFLite inference failed, falling back to acetowhite detector: ${e.message}")
            try {
                val acetowhiteResult = acetowhiteDetector.detect(aiBitmap)
                if (acetowhiteResult.label == Classification.ABNORMAL) {
                    viaSegmentationHelper?.let { segHelper ->
                        try {
                            val segResult = segHelper.detectAndSegment(aiBitmap, isAlreadyAbnormal = true)
                            if (segResult.label == Classification.NORMAL) {
                                segResult
                            } else if (segResult.contourPoints != null) {
                                acetowhiteResult.copy(
                                    contourPoints = segResult.contourPoints,
                                    boundingBox = segResult.boundingBox ?: acetowhiteResult.boundingBox,
                                    lesionAreaRatio = segResult.lesionAreaRatio,
                                    lesionType = segResult.lesionType
                                )
                            } else acetowhiteResult
                        } catch (_: Exception) {
                            acetowhiteResult
                        }
                    } ?: acetowhiteResult
                } else {
                    acetowhiteResult
                }
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

        if (fingerprint != null && finalResult is AbnormalityResult.Detected) {
            cacheSceneResult(fingerprint, finalResult)
        }
        return finalResult
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

        private const val SCENE_SIMILARITY_THRESHOLD = 0.945f
        private const val MAX_SCENE_CACHE = 24
        private val sceneCache = ArrayList<Pair<FloatArray, AbnormalityResult.Detected>>()

        @Synchronized
        private fun findCachedSceneResult(fp: FloatArray): AbnormalityResult.Detected? {
            var bestSim = -1f
            var bestRes: AbnormalityResult.Detected? = null
            for ((cachedFp, res) in sceneCache) {
                if (cachedFp.size != fp.size) continue
                var dot = 0f
                for (i in fp.indices) {
                    dot += fp[i] * cachedFp[i]
                }
                if (dot > bestSim) {
                    bestSim = dot
                    bestRes = res
                }
            }
            return if (bestSim >= SCENE_SIMILARITY_THRESHOLD) bestRes else null
        }

        @Synchronized
        private fun cacheSceneResult(fp: FloatArray, res: AbnormalityResult.Detected) {
            if (sceneCache.size >= MAX_SCENE_CACHE) {
                sceneCache.removeAt(0)
            }
            sceneCache.add(Pair(fp, res))
        }
    }
}
