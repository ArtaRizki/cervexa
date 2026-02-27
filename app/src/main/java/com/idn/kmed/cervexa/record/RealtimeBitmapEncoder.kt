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
import java.util.concurrent.atomic.AtomicLong

/**
 * RealtimeBitmapEncoder — WALL-CLOCK BASED PTS (fix video duration)
 *
 * Root cause bug durasi pendek:
 *   Counter-based PTS mengasumsikan frame datang tepat setiap 1/fps detik.
 *   Tapi withContext(Dispatchers.Main) untuk grab bitmap bisa lambat ~200ms
 *   saat main thread sibuk render VLC → hanya ~5fps frame yang masuk.
 *   500 frame yang diharapkan → hanya 100 frame → PTS 0..3960ms = 4 detik!
 *
 * Fix: WALL-CLOCK BASED PTS
 *   ptsUs = (System.nanoTime() - recordingStartNs) / 1000
 *   PTS mencerminkan waktu nyata kapan frame di-submit.
 *   Jika record 20 detik → durasi video 20 detik, terlepas dari berapa fps aktual.
 *
 * Frame rate dideklarasikan ke encoder sebagai "target", tapi PTS pakai waktu nyata.
 * Ini standar industri untuk variable frame rate / realtime capture.
 */
class RealtimeBitmapEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val outputFile: File,
    private val frameRate: Int = 25,
    private val bitRate: Int = 4_000_000,
    private val iFrameIntervalSec: Int = 2,
    private val queueCapacity: Int = 8,
) {
    companion object {
        private const val TAG = "RealtimeBitmapEncoder"
    }

    /** Wrapper bitmap + timestamp wall clock saat submit */
    private data class TimedBitmap(val bitmap: Bitmap, val ptsUs: Long)

    private val bitmapQueue = ArrayBlockingQueue<Bitmap>(queueCapacity)
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
    private var startPtsUs = -1L
    private var lastPtsUs = -1L

    /** Timestamp mulai recording (nanoseconds) */
    @Volatile
    private var recordingStartNs = 0L

    /** Counter hanya untuk logging */
    private val frameCount = AtomicLong(0L)

    fun start() {
        if (running.getAndSet(true)) return
        frameCount.set(0L)
        recordingStartNs = System.nanoTime()

        try {
            initEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init encoder", e)
            running.set(false)
            return
        }

        encoderThread = Thread {
            val frameIntervalMs = 1000L / frameRate
            var lastBitmap: Bitmap? = null // Simpan memori frame terakhir

            try {
                while (running.get()) {
                    val loopStartMs = System.currentTimeMillis()

                    // 1. Cek secara singkat apakah ada frame baru di antrean
                    val newBm = bitmapQueue.poll(10, TimeUnit.MILLISECONDS)
                    if (newBm != null) {
                        lastBitmap?.recycle() // Hapus frame lama
                        lastBitmap = newBm    // Perbarui dengan frame baru
                    }

                    // 2. SELALU GAMBAR FRAME! Jika tidak ada frame baru,
                    // ini otomatis menduplikasi frame terakhir ke video agar FPS konstan.
                    lastBitmap?.let { bmp ->
                        if (!bmp.isRecycled) {
                            drawBitmapToSurface(bmp)
                            drainEncoder()
                            frameCount.incrementAndGet()
                        }
                    }

                    // 3. Tidur sebentar untuk menjaga ritme ketat 25 FPS (~40ms per siklus)
                    val elapsedMs = System.currentTimeMillis() - loopStartMs
                    val sleepMs = frameIntervalMs - elapsedMs
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Encoder thread crashed", t)
            } finally {
                finishEncoding()
                lastBitmap?.recycle()
                bitmapQueue.forEach { if (!it.isRecycled) it.recycle() }
                bitmapQueue.clear()
                startPtsUs = -1L
                lastPtsUs = -1L
            }
        }.apply {
            name = "BitmapEncoderThread"
            priority = Thread.MAX_PRIORITY - 1
            start()
        }

        Log.d(TAG, "Started: ${width}x${height} @ ${frameRate}fps ${bitRate / 1000}kbps")
    }

    /**
     * Submit bitmap ke encoder queue.
     * PTS dihitung saat ini (wall clock), bukan saat frame diproses encoder.
     * Ini memastikan durasi video = durasi recording nyata.
     */
    fun submitBitmap(bitmap: Bitmap) {
        if (!running.get()) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }

        if (!bitmapQueue.offer(bitmap)) {
            // Queue penuh — buang frame lama (oldest), masukkan frame baru
            val old = bitmapQueue.poll()
            old?.let { if (!it.isRecycled) it.recycle() }
            bitmapQueue.offer(bitmap)
        }
    }

    fun stop() {
        running.set(false)
        try {
            encoderThread?.join(3000)
        } catch (_: Throwable) {
        }
        encoderThread = null
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

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 1)
            }
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

    private fun drainEncoder() { // Hapus parameter (ptsUs: Long)
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
                            // Normalisasi PTS otomatis bawaan Surface agar mulai dari 0
                            if (startPtsUs == -1L) {
                                startPtsUs = bufferInfo.presentationTimeUs
                            }
                            var currentPtsUs = bufferInfo.presentationTimeUs - startPtsUs

                            // Keamanan ekstra: Cegah timestamp mundur atau kembar
                            // yang bisa membuat pemutar video bingung / muxer crash
                            if (currentPtsUs <= lastPtsUs) {
                                currentPtsUs = lastPtsUs + 1000L
                            }
                            lastPtsUs = currentPtsUs
                            bufferInfo.presentationTimeUs = currentPtsUs

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
        val totalFrames = frameCount.get()
        val durationSec = (System.nanoTime() - recordingStartNs) / 1_000_000_000.0

        try {
            if (::encoder.isInitialized) {
                runCatching { encoder.signalEndOfInputStream() }
                val finalPtsUs = (System.nanoTime() - recordingStartNs) / 1000L
                drainEncoder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "finishEncoding error", e)
        }

        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { inputSurface.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }

        muxerStarted = false
        trackIndex = -1
        Log.d(
            TAG,
            "Finished: ${outputFile.name} | $totalFrames frames | ${
                String.format(
                    "%.1f",
                    durationSec
                )
            }s | actual ${String.format("%.1f", totalFrames / durationSec)}fps"
        )
    }
}