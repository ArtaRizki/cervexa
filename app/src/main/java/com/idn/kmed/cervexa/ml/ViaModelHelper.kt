package com.idn.kmed.cervexa.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
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
     * Menjalankan deteksi. 
     * Jika model TFLite belum siap (dummy), maka otomatis beralih ke Deteksi Warna (Acetowhite).
     */
    fun detectAbnormality(bitmap: Bitmap): Float {
        // Jika model masih dummy atau belum di-load, gunakan deteksi warna sebagai fallback
        if (interpreter == null) {
            return detectByColor(bitmap)
        }

        return try {
            // Prepare input
            var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            // Prepare output
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 1), org.tensorflow.lite.DataType.FLOAT32)

            // Run inference
            interpreter?.run(tensorImage.buffer, outputBuffer.buffer.rewind())

            outputBuffer.floatArray[0]
        } catch (e: Exception) {
            Log.e("ViaModelHelper", "TFLite Error: ${e.message}")
            detectByColor(bitmap) // Fallback ke warna jika TFLite gagal
        }
    }

    /**
     * Deteksi Berdasarkan Warna (Acetowhite Detection).
     * Mencari bercak putih tebal yang menjadi ciri khas lesi pra-kanker pada metode VIA.
     */
    private fun detectByColor(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        
        // Sampling area tengah (fokus ke serviks)
        val startX = (width * 0.25).toInt()
        val endX = (width * 0.75).toInt()
        val startY = (height * 0.25).toInt()
        val endY = (height * 0.75).toInt()
        
        var whitePixels = 0
        var totalPixels = 0
        
        // Sampling setiap 5 piksel agar ringan
        for (y in startY until endY step 5) {
            for (x in startX until endX step 5) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Algoritma Acetowhite Sederhana: 
                // Jaringan abnormal biasanya putih/abu terang (R, G, B tinggi & seimbang)
                // Jaringan normal biasanya merah/pink (R >> G, B)
                val isWhite = (r > 150 && g > 150 && b > 130 && Math.abs(r - g) < 45 && Math.abs(g - b) < 45)
                if (isWhite) {
                    whitePixels++
                }
                totalPixels++
            }
        }
        
        if (totalPixels == 0) return 0f
        val ratio = whitePixels.toFloat() / totalPixels.toFloat()
        
        // Skala: 15% area putih dianggap 100% abnormal (1.0)
        return (ratio / 0.15f).coerceIn(0f, 1f)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
