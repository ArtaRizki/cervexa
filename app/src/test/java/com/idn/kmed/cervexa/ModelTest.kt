package com.idn.kmed.cervexa

import io.kotest.core.spec.style.StringSpec
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

class ModelTest : StringSpec({
    "test model output shape" {
        val file = File("src/main/assets/via_model.tflite")
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        val byteBuffer: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        
        val interpreter = Interpreter(byteBuffer)
        println("=== TFLITE INFO ===")
        println("Input count: " + interpreter.getInputTensorCount())
        println("Output count: " + interpreter.getOutputTensorCount())
        for (i in 0 until interpreter.getOutputTensorCount()) {
            val outputTensor = interpreter.getOutputTensor(i)
            println("Output " + i + " shape: " + outputTensor.shape().joinToString(","))
        }
        println("===================")
        interpreter.close()
    }
})
