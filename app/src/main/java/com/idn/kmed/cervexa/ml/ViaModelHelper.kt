package com.idn.kmed.cervexa.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer

/**
 * Wrapper around TFLite Interpreter for the VIA model (`via_model.tflite`).
 */
class ViaModelHelper(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var initException: Exception? = null
    private val modelName = "via_model.tflite"
    @Volatile
    private var isClosed = false

    // Based on EfficientNet input size
    private val imageSizeX = 224
    private val imageSizeY = 224

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(imageSizeX, imageSizeY, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        try {
            val tfliteModel: MappedByteBuffer = FileUtil.loadMappedFile(context, modelName)
            val options = Interpreter.Options()
            options.setNumThreads(2)
            interpreter = Interpreter(tfliteModel, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            initException = e
        }
    }

    @Synchronized
    fun detectAbnormality(bitmap: Bitmap): AbnormalityResult.Detected {
        if (isClosed || bitmap.isRecycled) {
            throw IllegalStateException("TFLite interpreter closed or bitmap recycled")
        }
        val currentInterpreter = interpreter
            ?: throw IllegalStateException("TFLite interpreter not loaded — ${initException?.message ?: "model file may be missing or corrupted"}", initException)

        // Prepare input
        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // Prepare output buffer for confidence score (model has shape [1, 2])
        val outputBuffer = TensorBuffer.createFixedSize(
            intArrayOf(1, 2),
            org.tensorflow.lite.DataType.FLOAT32
        )

        // Run inference — exceptions propagate to AiDetector
        currentInterpreter.run(tensorImage.buffer, outputBuffer.buffer.rewind())

        val scores = outputBuffer.floatArray
        val abnormalScore = scores[0]
        Log.d(TAG, "Inference raw output: abnormal=${scores[0]}, normal=${scores[1]}")

        // Klasifikasi berbasis threshold yang dikalibrasi (0.65f) untuk mencegah false-positive berlebih
        val label = if (abnormalScore > CLASSIFICATION_THRESHOLD) {
            Classification.ABNORMAL
        } else {
            Classification.NORMAL
        }

        val calibratedScore = if (label == Classification.ABNORMAL) {
            val normalized = ((abnormalScore - CLASSIFICATION_THRESHOLD) / (1f - CLASSIFICATION_THRESHOLD)).coerceIn(0f, 1f)
            0.55f + (normalized * 0.40f)
        } else {
            (1f - abnormalScore).coerceIn(0.60f, 0.98f)
        }

        // Extract bounding box from model output if available
        val boundingBox = extractBoundingBox(currentInterpreter, label)

        return AbnormalityResult.Detected(
            label = label,
            confidenceScore = calibratedScore,
            boundingBox = boundingBox,
            isFallback = false
        )
    }

    private fun extractBoundingBox(interpreter: Interpreter, label: Classification): RectF? {
        if (label == Classification.NORMAL) return null

        return try {
            val outputTensorCount = interpreter.outputTensorCount
            if (outputTensorCount < 2) return null

            val bboxTensor = interpreter.getOutputTensor(1)
            val bboxShape = bboxTensor.shape()

            if (bboxShape.size == 2 && bboxShape[1] == 4) {
                val bboxBuffer = TensorBuffer.createFixedSize(
                    bboxShape,
                    org.tensorflow.lite.DataType.FLOAT32
                )
                val coords = bboxBuffer.floatArray
                RectF(coords[1], coords[0], coords[3], coords[2])
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Bounding box extraction not supported by this model: ${e.message}")
            null
        }
    }

    @Synchronized
    fun close() {
        if (isClosed) return
        isClosed = true

        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TFLite interpreter: ${e.message}")
        } finally {
            interpreter = null
        }
    }

    companion object {
        private const val TAG = "ViaModelHelper"
        const val CLASSIFICATION_THRESHOLD = 0.65f
    }
}
