package com.idn.kmed.cervexa.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer

class ViaModelHelper(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val modelName = "via_model.tflite"

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
            e.printStackTrace()
        }
    }

    /**
     * Runs inference on the given bitmap.
     * @return Probability of being "Abnormal" (0.0 to 1.0) or -1.0 if failed.
     */
    fun detectAbnormality(bitmap: Bitmap): Float {
        interpreter?.let { tflite ->
            try {
                // Prepare input
                var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
                tensorImage.load(bitmap)
                tensorImage = imageProcessor.process(tensorImage)

                // Prepare output
                val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 1), org.tensorflow.lite.DataType.FLOAT32)

                // Run inference
                tflite.run(tensorImage.buffer, outputBuffer.buffer.rewind())

                return outputBuffer.floatArray[0]
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return -1.0f
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
