package com.idn.kmed.cervexa.record

import android.content.Context
import android.graphics.*
import android.media.*
import android.view.Surface
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RealtimeBitmapEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val outputFile: File,
    private val frameRate: Int = 30
) {

    private val bitmapQueue = LinkedBlockingQueue<Bitmap>()
    private var encoderThread: Thread? = null
    private var isRunning = false

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private lateinit var inputSurface: Surface

    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L

    fun start() {
        isRunning = true
        initEncoder()

        encoderThread = Thread {
            while (isRunning || bitmapQueue.isNotEmpty()) {
                val bitmap = bitmapQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                drawBitmapToSurface(bitmap)
                drainEncoder()
                presentationTimeUs += 1_000_000L / frameRate
            }
            finishEncoding()
        }.apply { start() }
    }

    fun submitBitmap(bitmap: Bitmap) {
        if (isRunning) {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, false)
            bitmapQueue.offer(scaled)
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun initEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun drawBitmapToSurface(bitmap: Bitmap) {
        val canvas = inputSurface.lockCanvas(null)
        canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
        val destRect = Rect(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destRect, null)
        inputSurface.unlockCanvasAndPost(canvas)
    }

    private fun drainEncoder() {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (bufferInfo.size != 0 && muxerStarted) {
                        outputBuffer?.position(bufferInfo.offset)
                        outputBuffer?.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer!!, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun finishEncoding() {
        encoder.signalEndOfInputStream()
        drainEncoder()
        encoder.stop()
        encoder.release()
        inputSurface.release()
        muxer.stop()
        muxer.release()
    }
}
