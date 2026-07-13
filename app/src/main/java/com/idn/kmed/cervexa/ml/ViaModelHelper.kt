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
 *
 * Responsibilities:
 * - Load and manage the TFLite model lifecycle
 * - Run inference on bitmap frames and return [AbnormalityResult.Detected]
 * - Extract bounding box coordinates from model output (if supported)
 * - Propagate exceptions to the caller (AiDetector handles fallback)
 *
 * This class does NOT handle fallback logic — that is the responsibility of [AiDetector].
 */
class ViaModelHelper(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val modelName = "via_model.tflite"
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
            options.setNumThreads(4)
            interpreter = Interpreter(tfliteModel, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}")
            // Don't catch — interpreter remains null, detectAbnormality will throw
        }
    }

    /**
     * Runs abnormality detection inference on the given bitmap.
     *
     * Returns [AbnormalityResult.Detected] containing classification label,
     * confidence score, and bounding box (if the model output supports it).
     *
     * @param bitmap The input frame to analyze
     * @return [AbnormalityResult.Detected] with inference results
     * @throws IllegalStateException if the TFLite interpreter is not loaded
     * @throws Exception if inference fails (propagated for AiDetector to handle fallback)
     */
    fun detectAbnormality(bitmap: Bitmap): AbnormalityResult.Detected {
        val currentInterpreter = interpreter
            ?: throw IllegalStateException("TFLite interpreter not loaded — model file may be missing or corrupted")

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
        // Class 0 = abnormal (alphabetically 'abnormal' comes before 'normal')
        // Class 1 = normal
        val abnormalScore = scores[0]
        val normalScore = scores[1]
        Log.d(TAG, "Inference raw output: abnormal=$abnormalScore, normal=$normalScore")

        // Classify based on threshold
        val label = if (abnormalScore > CLASSIFICATION_THRESHOLD) {
            Classification.ABNORMAL
        } else {
            Classification.NORMAL
        }

        // Extract bounding box from model output if available
        val boundingBox = extractBoundingBox(currentInterpreter, label)

        return AbnormalityResult.Detected(
            label = label,
            confidenceScore = abnormalScore,
            boundingBox = boundingBox,
            isFallback = false
        )
    }

    /**
     * Attempts to extract bounding box coordinates from the model output.
     *
     * If the model has a second output tensor with shape [1, 4] (representing
     * normalized coordinates [top, left, bottom, right]), it will be extracted.
     * Otherwise returns null.
     *
     * Bounding box is only relevant for ABNORMAL classifications.
     *
     * @param interpreter The active TFLite interpreter
     * @param label The classification result
     * @return [RectF] with normalized coordinates (0-1), or null if not available/NORMAL
     */
    private fun extractBoundingBox(interpreter: Interpreter, label: Classification): RectF? {
        if (label == Classification.NORMAL) return null

        return try {
            // Check if model has a second output tensor for bounding box
            val outputTensorCount = interpreter.outputTensorCount
            if (outputTensorCount < 2) return null

            val bboxTensor = interpreter.getOutputTensor(1)
            val bboxShape = bboxTensor.shape()

            // Expected shape: [1, 4] for [top, left, bottom, right]
            if (bboxShape.size == 2 && bboxShape[1] == 4) {
                val bboxBuffer = TensorBuffer.createFixedSize(
                    bboxShape,
                    org.tensorflow.lite.DataType.FLOAT32
                )
                val coords = bboxBuffer.floatArray
                // Coordinates are normalized [0, 1]: top, left, bottom, right
                RectF(coords[1], coords[0], coords[3], coords[2])
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Bounding box extraction not supported by this model: ${e.message}")
            null
        }
    }

    /**
     * Closes the TFLite interpreter and releases resources.
     *
     * Safe to call multiple times — subsequent calls after the first are no-ops.
     * If interpreter close fails, the error is logged and the interpreter reference
     * is still cleared to prevent resource leaks.
     */
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
        const val CLASSIFICATION_THRESHOLD = 0.5f
    }
}
