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
    private val frameRate: Int = 30, // Target FPS
    private val bitRate: Int = 2_500_000, // TURUNKAN DIKIT (4Mbps agak berat buat software draw, 2.5Mbps cukup buat 720p)
    private val iFrameIntervalSec: Int = 1,
    private val queueCapacity: Int = 2,
) {

    companion object {
        private const val TAG = "RealtimeBitmapEncoder"
    }

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

        try {
            initEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init encoder", e)
            running.set(false)
            return
        }

        encoderThread = Thread {
            try {
                // Kalkulasi waktu sleep agar FPS terjaga stabil (30fps ~= 33ms)
                val frameIntervalMs = (1000L / frameRate).coerceAtLeast(10L)

                while (running.get()) {
                    val startProcess = System.currentTimeMillis()

                    // Ambil frame dari antrian
                    val bm = bitmapQueue.poll(100, TimeUnit.MILLISECONDS)

                    if (bm != null) {
                        drawBitmapToSurface(bm)
                        drainEncoder()

                        // === [FIX PENTING UNTUK XIAOMI STICK] ===
                        // Segera hancurkan bitmap setelah dipakai agar RAM lega
                        if (!bm.isRecycled) {
                            bm.recycle()
                        }
                        // ========================================
                    } else {
                        // Jika tidak ada frame baru, tetap drain encoder agar buffer tidak macet
                        drainEncoder()
                    }

                    // Jaga timing agar tidak terlalu ngebut memakan CPU
                    val processTime = System.currentTimeMillis() - startProcess
                    val sleepTime = (frameIntervalMs - processTime).coerceAtLeast(0)
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Encoder thread crashed", t)
            } finally {
                finishEncoding()
                // Bersihkan sisa queue jika ada
                bitmapQueue.forEach { it.recycle() }
                bitmapQueue.clear()
            }
        }.apply { start() }
    }

    fun submitBitmap(bitmap: android.graphics.Bitmap) {
        if (!running.get()) {
            bitmap.recycle() // Recycle jika encoder sudah mati
            return
        }

        // Logic Drop Frame: Jika penuh, buang yang lama
        if (!bitmapQueue.offer(bitmap)) {
            val old = bitmapQueue.poll()
            old?.recycle() // Recycle frame yang dibuang!
            bitmapQueue.offer(bitmap)
        }
    }

    fun stop() {
        running.set(false)
        try {
            encoderThread?.join(1000)
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
                // Profil Baseline lebih ringan untuk chipset entry-level
                // setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                // setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
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

        val canvas = try {
            inputSurface.lockCanvas(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock canvas", e)
            return
        }

        try {
            // Clear background (hitam)
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
            // Gambar bitmap (otomatis scaling sesuai destRect)
            canvas.drawBitmap(bitmap, null, destRect, null)
        } catch (t: Throwable) {
            Log.e(TAG, "drawBitmapToSurface error", t)
        } finally {
            try {
                inputSurface.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to unlock canvas", t)
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
                        if (muxerStarted) return
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
            // Log.e(TAG, "Drain error (benign if stopping)", e)
        }
    }

    private fun finishEncoding() {
        try {
            if (::encoder.isInitialized) {
                // Coba kirim EOS (End of Stream)
                // Note: pada input surface + canvas, kadang signalEndOfInputStream() tidak efektif
                // jika kita tidak menggambar frame lagi, tapi tetap dicoba.
                runCatching { encoder.signalEndOfInputStream() }
                drainEncoder()
            }
        } catch (e: Exception) {
        }

        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { inputSurface.release() }

        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }

        muxerStarted = false
        trackIndex = -1
    }
}