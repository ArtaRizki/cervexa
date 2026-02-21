package com.idn.kmed.cervexa.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RealtimeBitmapEncoder — SMOOTH FINAL
 *
 * Fix kecepatan: PTS distamp di submitBitmap() (waktu capture),
 *   bukan di drainEncoder() yang bisa delay.
 *
 * Fix smooth motion:
 *   - VBR bitrate: encoder pakai bitrate lebih saat ada gerakan
 *   - KEY_PRIORITY non-realtime: encoder lebih agresif dalam motion estimation
 *   - Thread priority MAX-1: timing konsisten
 *   - iFrameInterval 2s: lebih banyak P-frame = transisi lebih mulus
 *
 * Catatan: frameRate HARUS sama dengan ENCODER_FPS di startFrameGrabber()
 */
class RealtimeBitmapEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val outputFile: File,
    private val frameRate: Int = 25,         // HARUS sama dengan ENCODER_FPS di startFrameGrabber
    private val bitRate: Int = 4_000_000,    // 4Mbps VBR — naik saat ada gerakan
    private val iFrameIntervalSec: Int = 2,  // 2s = lebih banyak P-frame = lebih smooth
    private val queueCapacity: Int = 4,
) {
    companion object {
        private const val TAG = "RealtimeBitmapEncoder"
    }

    private data class TimestampedBitmap(val bitmap: Bitmap, val captureNs: Long)

    private val bitmapQueue = ArrayBlockingQueue<TimestampedBitmap>(queueCapacity)
    private var encoderThread: Thread? = null
    private val running = AtomicBoolean(false)

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private lateinit var inputSurface: Surface

    private var trackIndex = -1
    @Volatile
    private var muxerStarted = false

    private val bufferInfo = MediaCodec.BufferInfo()
    private val destRect = Rect(0, 0, width, height)

    @Volatile
    private var startCaptureNs = -1L
    @Volatile
    private var lastPtsUs = 0L

    fun start() {
        if (running.getAndSet(true)) return
        try {
            initEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init encoder", e)
            running.set(false)
            return
        }

        startCaptureNs = -1L
        lastPtsUs = 0L

        encoderThread = Thread {
            try {
                val frameIntervalNs = 1_000_000_000L / frameRate
                var nextFrameNs = System.nanoTime()

                while (running.get()) {
                    val item = bitmapQueue.poll(50, TimeUnit.MILLISECONDS)

                    if (item != null) {
                        val ptsUs = computePtsUs(item.captureNs)
                        drawBitmapToSurface(item.bitmap)
                        drainEncoder(ptsUs)
                        if (!item.bitmap.isRecycled) item.bitmap.recycle()
                    } else {
                        drainEncoder(lastPtsUs)
                    }

                    nextFrameNs += frameIntervalNs
                    val sleepNs = nextFrameNs - System.nanoTime()
                    when {
                        sleepNs > 1_000_000L ->
                            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())

                        sleepNs < -frameIntervalNs ->
                            nextFrameNs = System.nanoTime()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Encoder thread crashed", t)
            } finally {
                finishEncoding()
                bitmapQueue.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                bitmapQueue.clear()
            }
        }.apply {
            name = "BitmapEncoderThread"
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /**
     * Dipanggil dari startFrameGrabber().
     * PTS distamp DISINI — tepat saat frame di-capture.
     */
    fun submitBitmap(bitmap: Bitmap) {
        if (!running.get()) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val captureNs = System.nanoTime()
        if (startCaptureNs == -1L) startCaptureNs = captureNs

        val item = TimestampedBitmap(bitmap, captureNs)
        if (!bitmapQueue.offer(item)) {
            val old = bitmapQueue.poll()
            old?.let { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            bitmapQueue.offer(item)
        }
    }

    fun stop() {
        running.set(false)
        try {
            encoderThread?.join(2000)
        } catch (_: Throwable) {
        }
        encoderThread = null
    }

    private fun computePtsUs(captureNs: Long): Long {
        val baseline = if (startCaptureNs == -1L) captureNs else startCaptureNs
        return ((captureNs - baseline) / 1000L).coerceAtLeast(lastPtsUs + 1)
    }

    private fun initEncoder() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)

            // Non-realtime: encoder lebih agresif dalam motion estimation
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 1)
            }
            // VBR: alokasi bitrate dinamis sesuai kompleksitas frame
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        trackIndex = -1
        Log.d(
            TAG,
            "Encoder: ${width}x${height} @ ${frameRate}fps ${bitRate / 1000}kbps VBR iFrame=${iFrameIntervalSec}s"
        )
    }

    private fun drawBitmapToSurface(bitmap: Bitmap) {
        if (!::inputSurface.isInitialized || !inputSurface.isValid) return
        val canvas = try {
            inputSurface.lockCanvas(null)
        } catch (e: Exception) {
            Log.e(TAG, "lockCanvas failed", e); return
        }
        try {
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
            canvas.drawBitmap(bitmap, null, destRect, null)
        } catch (t: Throwable) {
            Log.e(TAG, "draw error", t)
        } finally {
            try {
                inputSurface.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.e(TAG, "unlockCanvas failed", t)
            }
        }
    }

    private fun drainEncoder(ptsUs: Long) {
        try {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started")
                    }

                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)
                        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                        if (bufferInfo.size > 0 && muxerStarted && buf != null && !isConfig) {
                            bufferInfo.presentationTimeUs = ptsUs
                            lastPtsUs = ptsUs
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, buf, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun finishEncoding() {
        try {
            if (::encoder.isInitialized) {
                runCatching { encoder.signalEndOfInputStream() }
                drainEncoder(lastPtsUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "finishEncoding error", e)
        }

        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { inputSurface.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }

        muxerStarted = false; trackIndex = -1; startCaptureNs = -1L; lastPtsUs = 0L
        Log.d(TAG, "Finished: ${outputFile.name}")
    }
}