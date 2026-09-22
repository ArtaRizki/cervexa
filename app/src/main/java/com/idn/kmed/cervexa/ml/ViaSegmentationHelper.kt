package com.idn.kmed.cervexa.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * ViaSegmentationHelper manages TFLite instance segmentation for cervical lesions (VIA).
 *
 * Supports:
 * 1. YOLOv8n-Seg TFLite model (`via_seg_model.tflite`) when available in assets.
 * 2. Intelligent acetowhite color contour extraction fallback when the segmentation model
 *    has not yet been placed into the assets folder.
 */
class ViaSegmentationHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelName = "via_seg_model.tflite"
    private var isClosed = false

    private val inputSize = 384
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    private val acetowhiteDetector = AcetowhiteDetector()

    init {
        loadModelIfAvailable()
    }

    private fun loadModelIfAvailable() {
        try {
            val tfliteModel: MappedByteBuffer = FileUtil.loadMappedFile(context, modelName)
            val cpuCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val options = Interpreter.Options().apply {
                setNumThreads(cpuCores) // Menggunakan hingga 4 core penuh CPU Smart TV / HP
            }
            interpreter = Interpreter(tfliteModel, options)
            Log.i(TAG, "Successfully loaded $modelName with $cpuCores threads")
        } catch (e: Exception) {
            Log.w(TAG, "Model $modelName not present in assets; using acetowhite contour fallback: ${e.message}")
            interpreter = null
        }
    }

    /**
     * Performs abnormality detection and contour polygon segmentation on the given bitmap frame.
     *
     * @param bitmap The frame or photo to analyze.
     * @param isAlreadyAbnormal Set to true if the caller (e.g. [AiDetector]) already confirmed the image
     *        is ABNORMAL via the primary classifier and needs lesion contour extraction.
     */
    fun detectAndSegment(bitmap: Bitmap, isAlreadyAbnormal: Boolean = false): AbnormalityResult.Detected {
        if (isClosed || bitmap.isRecycled) {
            return AbnormalityResult.Detected(
                label = Classification.NORMAL,
                confidenceScore = 0f,
                boundingBox = null,
                contourPoints = null
            )
        }

        val currentInterpreter = interpreter
        if (ENABLE_TFLITE_SEGMENTATION && currentInterpreter != null) {
            try {
                val tfliteResult = runTfliteSegmentation(currentInterpreter, bitmap)
                if (tfliteResult.label == Classification.ABNORMAL && tfliteResult.contourPoints != null) {
                    return tfliteResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "TFLite segmentation error; falling back to heuristic contour", e)
            }
        }

        return runHeuristicContourDetection(bitmap, isAlreadyAbnormal)
    }

    /**
     * Executes neural network inference on YOLOv8n-Seg model.
     */
    private fun runTfliteSegmentation(interpreter: Interpreter, bitmap: Bitmap): AbnormalityResult.Detected {
        val inputShape = interpreter.getInputTensor(0).shape()
        val inputBuffer = prepareInputBuffer(bitmap, inputShape)

        // YOLOv8-Seg standard outputs:
        // Output 0: Detection boxes, scores, and 32 mask coefficients [1, 37, 3024]
        // Output 1: Prototype masks [1, 32, 96, 96]
        val outputMap = HashMap<Int, Any>()
        val outputBoxShape = interpreter.getOutputTensor(0).shape()
        val outputMaskShape = if (interpreter.outputTensorCount > 1) interpreter.getOutputTensor(1).shape() else null

        val boxBuffer = TensorBuffer.createFixedSize(outputBoxShape, interpreter.getOutputTensor(0).dataType())
        outputMap[0] = boxBuffer.buffer.rewind()

        val maskBuffer = if (outputMaskShape != null) {
            val buf = TensorBuffer.createFixedSize(outputMaskShape, interpreter.getOutputTensor(1).dataType())
            outputMap[1] = buf.buffer.rewind()
            buf
        } else null

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        // Parse highest confidence detection
        val rawBoxes = boxBuffer.floatArray
        val numChannels = outputBoxShape[1]
        val numPredictions = outputBoxShape[2]

        val isNCHW = inputShape.size == 4 && inputShape[1] == 3
        val inputW = (if (isNCHW) inputShape[3] else inputShape[2]).toFloat()
        val inputH = (if (isNCHW) inputShape[2] else inputShape[1]).toFloat()

        var bestScore = 0f
        var bestIdx = -1
        var bestCx = 0f
        var bestCy = 0f
        var bestW = 0f
        var bestH = 0f

        for (i in 0 until numPredictions) {
            val score = rawBoxes[4 * numPredictions + i] // class 0 (abnormal lesion score)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
                bestCx = rawBoxes[0 * numPredictions + i] / inputW
                bestCy = rawBoxes[1 * numPredictions + i] / inputH
                bestW = rawBoxes[2 * numPredictions + i] / inputW
                bestH = rawBoxes[3 * numPredictions + i] / inputH
            }
        }

        val isAbnormal = bestScore >= ViaModelHelper.CLASSIFICATION_THRESHOLD
        if (!isAbnormal || bestIdx == -1) {
            return AbnormalityResult.Detected(
                label = Classification.NORMAL,
                confidenceScore = (1f - bestScore).coerceIn(0.60f, 0.98f),
                boundingBox = null,
                contourPoints = null
            )
        }

        val boundingBox = RectF(
            (bestCx - bestW / 2f).coerceIn(0f, 1f),
            (bestCy - bestH / 2f).coerceIn(0f, 1f),
            (bestCx + bestW / 2f).coerceIn(0f, 1f),
            (bestCy + bestH / 2f).coerceIn(0f, 1f)
        )

        // Decode prototype mask from Output 1 using 32 mask coefficients from Output 0
        val maskWeights = FloatArray(32)
        if (numChannels >= 37) {
            for (m in 0 until 32) {
                maskWeights[m] = rawBoxes[(5 + m) * numPredictions + bestIdx]
            }
        }

        val contourPoints = if (maskBuffer != null && numChannels >= 37 && outputMaskShape != null) {
            decodeMaskToContour(maskBuffer.floatArray, maskWeights, outputMaskShape, boundingBox)
                ?: generateContourFromBox(boundingBox)
        } else {
            generateContourFromBox(boundingBox)
        }

        val lesionAreaRatio = boundingBox.width() * boundingBox.height()

        return AbnormalityResult.Detected(
            label = Classification.ABNORMAL,
            confidenceScore = bestScore,
            boundingBox = boundingBox,
            contourPoints = contourPoints,
            lesionAreaRatio = lesionAreaRatio,
            isFallback = false,
            lesionType = "ACETOWHITE"
        )
    }

    /**
     * Decodes the YOLOv8-Seg prototype masks into a precise boundary contour
     * using Moore Neighborhood boundary tracing on the decoded binary mask.
     */
    private fun decodeMaskToContour(
        protoMasks: FloatArray,
        maskWeights: FloatArray,
        maskShape: IntArray,
        box: RectF
    ): List<PointF>? {
        if (maskShape.size < 4) return null
        val numProtos = maskShape[1]
        val maskH = maskShape[2]
        val maskW = maskShape[3]
        val maskArea = maskH * maskW

        // Bounding box in mask coordinates
        val minX = (box.left * maskW).toInt().coerceIn(0, maskW - 1)
        val maxX = (box.right * maskW).toInt().coerceIn(minX, maskW - 1)
        val minY = (box.top * maskH).toInt().coerceIn(0, maskH - 1)
        val maxY = (box.bottom * maskH).toInt().coerceIn(minY, maskH - 1)

        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1
        if (cropW < 3 || cropH < 3) return null

        // Build binary mask within the bounding box region
        val binaryMask = BooleanArray(cropW * cropH)
        for (y in minY..maxY) {
            val yOffset = y * maskW
            for (x in minX..maxX) {
                var logit = 0f
                for (k in 0 until numProtos) {
                    logit += maskWeights[k] * protoMasks[k * maskArea + yOffset + x]
                }
                if (logit > 0f) {
                    binaryMask[(y - minY) * cropW + (x - minX)] = true
                }
            }
        }

        // Trace boundary using Moore Neighborhood algorithm
        val boundaryPixels = mooreBoundaryTrace(binaryMask, cropW, cropH) ?: return null

        // Convert pixel coordinates to normalized [0, 1] coordinates
        val contour = boundaryPixels.map { (px, py) ->
            PointF(
                ((minX + px).toFloat() / maskW).coerceIn(0.01f, 0.99f),
                ((minY + py).toFloat() / maskH).coerceIn(0.01f, 0.99f)
            )
        }

        // Simplify contour with Douglas-Peucker to reduce point count while preserving shape
        val epsilon = 1.2f / maxOf(maskW, maskH).toFloat()
        val simplified = douglasPeucker(contour, epsilon)

        return if (simplified.size >= 4) simplified else contour
    }

    /**
     * Prepares normalized Float32 direct ByteBuffer supporting both NCHW and NHWC formats.
     */
    private fun prepareInputBuffer(bitmap: Bitmap, inputShape: IntArray): java.nio.ByteBuffer {
        val isNCHW = inputShape.size == 4 && inputShape[1] == 3
        val targetWidth = if (isNCHW) inputShape[3] else inputShape[2]
        val targetHeight = if (isNCHW) inputShape[2] else inputShape[1]

        val resizedBitmap = if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }

        val totalPixels = targetWidth * targetHeight
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(1 * 3 * totalPixels * 4).apply {
            order(java.nio.ByteOrder.nativeOrder())
        }

        val intValues = IntArray(totalPixels)
        resizedBitmap.getPixels(intValues, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        if (isNCHW) {
            // [1, 3, H, W] - All Red, then All Green, then All Blue
            for (pixel in intValues) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                byteBuffer.putFloat(r)
            }
            for (pixel in intValues) {
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                byteBuffer.putFloat(g)
            }
            for (pixel in intValues) {
                val b = (pixel and 0xFF) / 255.0f
                byteBuffer.putFloat(b)
            }
        } else {
            // [1, H, W, 3] - RGB interleaved
            for (pixel in intValues) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * Fallback method using dual-characteristic (acetowhite & erythematous/erosion) color thresholding
     * in the cervical transformation zone (20% - 80%) to locate lesion clusters
     * and construct an organic polygon boundary.
     */
    fun runHeuristicContourDetection(bitmap: Bitmap, isAlreadyAbnormal: Boolean = false): AbnormalityResult.Detected {
        val baseDetection = if (!isAlreadyAbnormal) {
            val d = acetowhiteDetector.detect(bitmap)
            if (d.label == Classification.NORMAL) {
                return d
            }
            d
        } else {
            AbnormalityResult.Detected(
                label = Classification.ABNORMAL,
                confidenceScore = 0.75f,
                boundingBox = null,
                isFallback = true
            )
        }

        // Downsample to fast analysis grid (width 320px) to eliminate JNI getPixel overhead on large images
        val sampleW = 320
        val sampleH = ((sampleW.toFloat() * bitmap.height) / bitmap.width).toInt().coerceAtLeast(180)
        val sampleBmp = if (bitmap.width > sampleW || bitmap.height > sampleH) {
            Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, false)
        } else {
            bitmap
        }

        val pixels = IntArray(sampleW * sampleH)
        sampleBmp.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        if (sampleBmp != bitmap && !sampleBmp.isRecycled) {
            sampleBmp.recycle()
        }

        val startX = (sampleW * 0.20).toInt()
        val endX = (sampleW * 0.80).toInt()
        val startY = (sampleH * 0.20).toInt()
        val endY = (sampleH * 0.80).toInt()

        val redPoints = ArrayList<PointF>()
        val whitePoints = ArrayList<PointF>()
        val step = 2

        for (y in startY until endY step step) {
            val rowOffset = y * sampleW
            for (x in startX until endX step step) {
                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // 1. True Acetowhite check (bercak putih susu/asam asetat terang murni)
                // Harus benar-benar putih terang dan tidak bias pink/merah
                val isAcetowhite = r > 145 && g > 135 && b > 125 && Math.abs(r - g) < 35 && Math.abs(g - b) < 35

                // 2. True Erythematous / central erosion check (lesi merah pekat/vaskular kontras tinggi)
                // Menolak mukosa pink normal yang selisih merah-hijaunya rendah
                val isErythematous = r > 125 && (r - g) > 42 && (r - b) > 24

                if (isErythematous) {
                    redPoints.add(PointF(x.toFloat() / sampleW, y.toFloat() / sampleH))
                } else if (isAcetowhite) {
                    whitePoints.add(PointF(x.toFloat() / sampleW, y.toFloat() / sampleH))
                }
            }
        }

        // Pilih kluster lesi yang dominan agar garis tidak melebar ke jaringan normal
        val isRedDominant = (redPoints.size >= 12 && redPoints.size >= whitePoints.size) || (redPoints.isNotEmpty() && whitePoints.size < 12)
        val targetPoints = if (isRedDominant) redPoints else whitePoints

        if (targetPoints.size < 6) {
            if (isAlreadyAbnormal) {
                val defaultBox = RectF(0.40f, 0.40f, 0.60f, 0.60f)
                val defaultContour = generateContourFromBox(defaultBox)
                return AbnormalityResult.Detected(
                    label = Classification.ABNORMAL,
                    confidenceScore = baseDetection.confidenceScore,
                    boundingBox = defaultBox,
                    contourPoints = defaultContour,
                    lesionAreaRatio = 0.04f,
                    isFallback = false,
                    lesionType = "EROSION" // Default sentral di OUE
                )
            }
            return baseDetection
        }

        // Compute centroid kluster lesi
        var sumX = 0f
        var sumY = 0f
        for (pt in targetPoints) {
            sumX += pt.x
            sumY += pt.y
        }
        val center = PointF(sumX / targetPoints.size, sumY / targetPoints.size)

        // Tentukan klasifikasi lesi 3-kategori (Putih, Erosi, atau Bercak Merah):
        // 1. ACETOWHITE: Bercak putih susu / IVA+
        // 2. EROSION: Bercak merah di area sentral sekitar mulut rahim / OUE (radius <= 0.18 dari tengah 0.5, 0.5)
        // 3. ERYTHEMA: Bercak merah di area perifer / fokal ektoserviks
        val detectedLesionType = if (!isRedDominant) {
            "ACETOWHITE"
        } else {
            val distFromOs = Math.hypot((center.x - 0.5f).toDouble(), (center.y - 0.5f).toDouble())
            if (distFromOs <= 0.18) "EROSION" else "ERYTHEMA"
        }

        // Build binary grid from target points and trace boundary for precise contour
        val gridSize = 48
        val grid = BooleanArray(gridSize * gridSize)
        for (pt in targetPoints) {
            val gx = (pt.x * (gridSize - 1)).toInt().coerceIn(0, gridSize - 1)
            val gy = (pt.y * (gridSize - 1)).toInt().coerceIn(0, gridSize - 1)
            grid[gy * gridSize + gx] = true
        }

        // Morphological dilation to connect nearby detected pixels and fill gaps
        val dilated = morphDilate(grid, gridSize, gridSize)

        // Trace boundary using Moore Neighborhood algorithm for organic, precise contour
        val boundaryPixels = mooreBoundaryTrace(dilated, gridSize, gridSize)

        val contour: List<PointF> = if (boundaryPixels != null && boundaryPixels.size >= 4) {
            val rawContour = boundaryPixels.map { (px, py) ->
                PointF(
                    (px.toFloat() / (gridSize - 1)).coerceIn(0.05f, 0.95f),
                    (py.toFloat() / (gridSize - 1)).coerceIn(0.05f, 0.95f)
                )
            }
            val epsilon = 1.5f / gridSize
            val simplified = douglasPeucker(rawContour, epsilon)
            if (simplified.size >= 4) simplified else rawContour
        } else {
            // Fallback: generate contour from bounding box of target points
            val pMinX = targetPoints.minOf { it.x }
            val pMaxX = targetPoints.maxOf { it.x }
            val pMinY = targetPoints.minOf { it.y }
            val pMaxY = targetPoints.maxOf { it.y }
            generateContourFromBox(RectF(pMinX, pMinY, pMaxX, pMaxY))
        }

        val minX = contour.minOf { it.x }
        val maxX = contour.maxOf { it.x }
        val minY = contour.minOf { it.y }
        val maxY = contour.maxOf { it.y }
        val box = RectF(minX, minY, maxX, maxY)

        return AbnormalityResult.Detected(
            label = Classification.ABNORMAL,
            confidenceScore = baseDetection.confidenceScore,
            boundingBox = box,
            contourPoints = contour,
            lesionAreaRatio = box.width() * box.height(),
            isFallback = false,
            lesionType = detectedLesionType
        )
    }

    private fun generateContourFromBox(box: RectF): List<PointF> {
        val cx = box.centerX()
        val cy = box.centerY()
        val rx = box.width() / 2f
        val ry = box.height() / 2f

        val points = ArrayList<PointF>()
        val slices = 16
        for (i in 0 until slices) {
            val angle = (i.toFloat() / slices) * 2 * Math.PI
            val wobble = 1.0f + 0.08f * sin(angle * 3).toFloat()
            val px = (cx + rx * wobble * cos(angle).toFloat()).coerceIn(0.01f, 0.99f)
            val py = (cy + ry * wobble * sin(angle).toFloat()).coerceIn(0.01f, 0.99f)
            points.add(PointF(px, py))
        }
        return points
    }

    /**
     * Moore Neighborhood boundary tracing algorithm.
     * Traces the outer boundary of a connected region in a binary mask,
     * returning an ordered list of boundary pixel coordinates that form a closed contour.
     */
    private fun mooreBoundaryTrace(mask: BooleanArray, width: Int, height: Int): List<Pair<Int, Int>>? {
        // Find first positive pixel (scan top-to-bottom, left-to-right)
        var startX = -1
        var startY = -1
        outer@ for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x]) {
                    startX = x
                    startY = y
                    break@outer
                }
            }
        }
        if (startX == -1) return null

        // Moore neighbor offsets (8-connected), clockwise:
        // 0=right, 1=bottom-right, 2=bottom, 3=bottom-left,
        // 4=left, 5=top-left, 6=top, 7=top-right
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        val boundary = ArrayList<Pair<Int, Int>>()
        boundary.add(Pair(startX, startY))

        var cx = startX
        var cy = startY
        // Entry was from the left (scanning left-to-right), so backtrack direction is 4 (left)
        var backtrackDir = 4

        val maxIterations = width * height * 2
        var iterations = 0

        do {
            val searchStart = (backtrackDir + 1) % 8
            var found = false

            for (i in 0 until 8) {
                val dir = (searchStart + i) % 8
                val nx = cx + dx[dir]
                val ny = cy + dy[dir]

                if (nx in 0 until width && ny in 0 until height && mask[ny * width + nx]) {
                    cx = nx
                    cy = ny
                    backtrackDir = (dir + 4) % 8
                    found = true

                    if (cx != startX || cy != startY) {
                        boundary.add(Pair(cx, cy))
                    }
                    break
                }
            }

            if (!found) break
            iterations++
        } while ((cx != startX || cy != startY) && iterations < maxIterations)

        return if (boundary.size >= 4) boundary else null
    }

    /**
     * Morphological dilation (4-connected) to fill small gaps between nearby pixels.
     */
    private fun morphDilate(grid: BooleanArray, w: Int, h: Int): BooleanArray {
        val result = grid.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!grid[y * w + x]) continue
                if (x > 0) result[y * w + (x - 1)] = true
                if (x < w - 1) result[y * w + (x + 1)] = true
                if (y > 0) result[(y - 1) * w + x] = true
                if (y < h - 1) result[(y + 1) * w + x] = true
            }
        }
        return result
    }

    /**
     * Douglas-Peucker polygon simplification algorithm.
     * Reduces the number of points in a contour while preserving the overall shape.
     */
    private fun douglasPeucker(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size <= 2) return points

        var maxDist = 0f
        var maxIdx = 0
        val first = points.first()
        val last = points.last()

        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], first, last)
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        return if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, maxIdx + 1), epsilon)
            val right = douglasPeucker(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val ldx = lineEnd.x - lineStart.x
        val ldy = lineEnd.y - lineStart.y
        val lengthSq = ldx * ldx + ldy * ldy
        if (lengthSq < 1e-10f) {
            val px = point.x - lineStart.x
            val py = point.y - lineStart.y
            return Math.sqrt((px * px + py * py).toDouble()).toFloat()
        }
        return (Math.abs(
            (ldy * point.x - ldx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x).toDouble()
        ) / Math.sqrt(lengthSq.toDouble())).toFloat()
    }

    fun close() {
        if (!isClosed) {
            isClosed = true
            interpreter?.close()
            interpreter = null
        }
    }

    companion object {
        private const val TAG = "ViaSegmentationHelper"

        /**
         * Aktifkan eksekusi YOLOv8n-Seg model TFLite (via_seg_model.tflite).
         */
        const val ENABLE_TFLITE_SEGMENTATION = true
    }
}
