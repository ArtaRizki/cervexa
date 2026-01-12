package com.idn.kmed.cervexa.record

import android.content.Context
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

class RealtimeBitmapEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val outputFile: File,
    private val frameRate: Int = 30,
    private val bitRate: Int = 4_000_000,
    private val iFrameIntervalSec: Int = 1,
    private val queueCapacity: Int = 2,      // <= KUNCI realtime: kecilkan buffer
) {

    companion object {
        private const val TAG = "RealtimeBitmapEncoder"
    }

    // ===== FIX #1: bounded queue (anti backlog) =====
    private val bitmapQueue = ArrayBlockingQueue<android.graphics.Bitmap>(queueCapacity)

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

    fun start() {
        if (running.getAndSet(true)) return

        initEncoder()

        encoderThread = Thread {
            try {
                val frameIntervalMs = (1000L / frameRate).coerceAtLeast(1L)

                while (running.get() || bitmapQueue.isNotEmpty()) {
                    val bm = bitmapQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (bm != null) {
                        drawBitmapToSurface(bm)
                        // drain agar output cepat ditulis → kurangi latency
                        drainEncoder()
                    } else {
                        // tetap drain sesekali walau tidak ada frame
                        drainEncoder()
                    }

                    // throttle sederhana supaya tidak overrun (optional)
                    // kalau mau super low-latency, kamu bisa comment sleep ini
                    Thread.sleep(frameIntervalMs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Encoder thread crashed", t)
            } finally {
                finishEncoding()
            }
        }.apply { start() }
    }

    /**
     * Submit frame terbaru.
     * FIX #2: jangan createScaledBitmap (alloc berat). Scaling dilakukan saat draw ke Surface.
     * FIX #3: drop-frame saat queue penuh (buang frame lama, keep newest).
     */
    fun submitBitmap(bitmap: android.graphics.Bitmap) {
        if (!running.get()) return

        // kalau queue penuh, buang yang lama supaya tidak delay
        if (!bitmapQueue.offer(bitmap)) {
            bitmapQueue.poll()        // drop oldest
            bitmapQueue.offer(bitmap) // keep newest
        }
    }

    fun stop() {
        running.set(false)
        try {
            encoderThread?.join(1500)
        } catch (_: Throwable) {
        }
        encoderThread = null
    }

    private fun initEncoder() {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)

                // optional: beberapa device lebih stabil dengan set ini
                // setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        trackIndex = -1
    }

    private fun drawBitmapToSurface(bitmap: android.graphics.Bitmap) {
        if (!::inputSurface.isInitialized || !inputSurface.isValid) return

        val canvas = inputSurface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC) // bersih & konsisten
            // scaling terjadi di sini (tanpa alloc bitmap baru)
            canvas.drawBitmap(bitmap, null, destRect, null)
        } catch (t: Throwable) {
            Log.e(TAG, "drawBitmapToSurface error", t)
        } finally {
            try {
                inputSurface.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun drainEncoder() {
        try {
            while (true) {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)

                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            // Format changed should only happen once
                            Log.w(TAG, "Format changed twice, ignoring")
                            continue
                        }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outputIndex)

                        if (bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }

                        encoder.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error draining encoder", e)
        }
    }

    private fun finishEncoding() {
        // EOS
        try {
            if (::encoder.isInitialized) {
                runCatching { encoder.signalEndOfInputStream() }
                drainEncoder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling EOS", e)
        }

        // stop/release encoder
        runCatching { encoder.stop() }
        runCatching { encoder.release() }

        // release surface
        runCatching { inputSurface.release() }

        // stop/release muxer
        if (muxerStarted) {
            runCatching { muxer.stop() }.onFailure {
                Log.e(TAG, "Muxer stop failed (maybe no data written)", it)
            }
        }
        runCatching { muxer.release() }

        muxerStarted = false
        trackIndex = -1
    }
}
