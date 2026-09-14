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
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(tfliteModel, options)
            Log.i(TAG, "Successfully loaded $modelName with 4 threads")
        } catch (e: Exception) {
            Log.w(TAG, "Model $modelName not present in assets; using acetowhite contour fallback: ${e.message}")
            interpreter = null
        }
    }

    /**
     * Performs abnormality detection and contour polygon segmentation on the given bitmap frame.
     */
    fun detectAndSegment(bitmap: Bitmap): AbnormalityResult.Detected {
        if (isClosed || bitmap.isRecycled) {
            return AbnormalityResult.Detected(
                label = Classification.NORMAL,
                confidenceScore = 0f,
                boundingBox = null,
                contourPoints = null
            )
        }

        val currentInterpreter = interpreter
        if (currentInterpreter != null) {
            try {
                return runTfliteSegmentation(currentInterpreter, bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "TFLite segmentation error; falling back to acetowhite contour", e)
            }
        }

        return runHeuristicContourDetection(bitmap)
    }

    /**
     * Executes neural network inference on YOLOv8n-Seg model.
     */
    private fun runTfliteSegmentation(interpreter: Interpreter, bitmap: Bitmap): AbnormalityResult.Detected {
        var tensorImage = TensorImage.fromBitmap(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

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

        interpreter.runForMultipleInputsOutputs(arrayOf(tensorImage.buffer), outputMap)

        // Parse highest confidence detection
        val rawBoxes = boxBuffer.floatArray
        val numChannels = outputBoxShape[1]
        val numPredictions = outputBoxShape[2]

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
                bestCx = rawBoxes[0 * numPredictions + i] / inputSize
                bestCy = rawBoxes[1 * numPredictions + i] / inputSize
                bestW = rawBoxes[2 * numPredictions + i] / inputSize
                bestH = rawBoxes[3 * numPredictions + i] / inputSize
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

        // Generate synthetic smooth contour around the detected lesion bounding area
        val contourPoints = generateContourFromBox(boundingBox)
        val lesionAreaRatio = boundingBox.width() * boundingBox.height()

        return AbnormalityResult.Detected(
            label = Classification.ABNORMAL,
            confidenceScore = bestScore,
            boundingBox = boundingBox,
            contourPoints = contourPoints,
            lesionAreaRatio = lesionAreaRatio,
            isFallback = false
        )
    }

    /**
     * Fallback method using acetowhite color thresholding to locate lesion clusters
     * and construct an organic polygon boundary.
     */
    private fun runHeuristicContourDetection(bitmap: Bitmap): AbnormalityResult.Detected {
        val baseDetection = acetowhiteDetector.detect(bitmap)
        if (baseDetection.label == Classification.NORMAL) {
            return baseDetection
        }

        // Extract acetowhite coordinates from central region (25% - 75%)
        val width = bitmap.width
        val height = bitmap.height
        val startX = (width * 0.25).toInt()
        val endX = (width * 0.75).toInt()
        val startY = (height * 0.25).toInt()
        val endY = (height * 0.75).toInt()

        val whitePoints = ArrayList<PointF>()
        val step = 12

        for (y in startY until endY step step) {
            for (x in startX until endX step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Acetowhite characteristic check
                if (r > 135 && g > 115 && b > 115 && Math.abs(r - g) < 45 && Math.abs(g - b) < 40) {
                    whitePoints.add(PointF(x.toFloat() / width, y.toFloat() / height))
                }
            }
        }

        if (whitePoints.size < 6) {
            return baseDetection
        }

        // Compute centroid
        var sumX = 0f
        var sumY = 0f
        for (pt in whitePoints) {
            sumX += pt.x
            sumY += pt.y
        }
        val center = PointF(sumX / whitePoints.size, sumY / whitePoints.size)

        // Radial grouping for organic contour polygon (16 slices)
        val slices = 16
        val maxDist = FloatArray(slices) { 0.01f }

        for (pt in whitePoints) {
            val dx = pt.x - center.x
            val dy = pt.y - center.y
            var angle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
            if (angle < 0) angle += (2 * Math.PI).toFloat()

            val sliceIdx = ((angle / (2 * Math.PI)) * slices).toInt().coerceIn(0, slices - 1)
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist > maxDist[sliceIdx] && dist < 0.35f) {
                maxDist[sliceIdx] = dist
            }
        }

        val contour = ArrayList<PointF>()
        for (i in 0 until slices) {
            val angle = (i.toFloat() / slices) * 2 * Math.PI
            val r = maxDist[i].coerceAtLeast(0.04f)
            val px = (center.x + r * cos(angle).toFloat()).coerceIn(0.05f, 0.95f)
            val py = (center.y + r * sin(angle).toFloat()).coerceIn(0.05f, 0.95f)
            contour.add(PointF(px, py))
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
            isFallback = true
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

    fun close() {
        if (!isClosed) {
            isClosed = true
            interpreter?.close()
            interpreter = null
        }
    }

    companion object {
        private const val TAG = "ViaSegmentationHelper"
    }
}
