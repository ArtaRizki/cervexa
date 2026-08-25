package com.idn.kmed.cervexa.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.chrisbanes.photoview.PhotoView
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.ml.AbnormalityResult
import com.idn.kmed.cervexa.ml.AcetowhiteDetector
import com.idn.kmed.cervexa.ml.AiDetector
import com.idn.kmed.cervexa.ml.AnalysisModeManager
import com.idn.kmed.cervexa.ml.Classification
import com.idn.kmed.cervexa.ml.OverlayRenderer
import com.idn.kmed.cervexa.ml.ViaModelHelper
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

class MediaPageFragment : Fragment() {

    private var aiDetector: AiDetector? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var originalBitmap: Bitmap? = null

    // Video AI real-time loop
    private var videoAiJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoTextureView: TextureView? = null
    private var isVideoAiActive = false

    companion object {
        private const val TAG = "MediaPageFragment"
        private const val AI_TIMEOUT_MS = 3000L
        private const val VIDEO_AI_INTERVAL_MS = 500L // analyze every 500ms

        fun newInstance(path: String, type: String): MediaPageFragment {
            val f = MediaPageFragment()
            f.arguments = Bundle().apply {
                putString("path", path)
                putString("type", type)
            }
            return f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.page_media, container, false)

        val path = requireArguments().getString("path") ?: return root
        val type = requireArguments().getString("type") ?: "IMAGE"
        val file = File(path)

        // --- Bind Views ---
        val imageMode = root.findViewById<View>(R.id.imageMode)
        val videoMode = root.findViewById<View>(R.id.videoMode)

        val photo = root.findViewById<PhotoView>(R.id.photoView)
        val tvVideoTexture = root.findViewById<TextureView>(R.id.tvVideoTexture)
        val ivAiOverlayVideo = root.findViewById<ImageView>(R.id.ivAiOverlayVideo)
        val ivPlayPause = root.findViewById<ImageView>(R.id.ivPlayPause)
        val sbVideoSeek = root.findViewById<SeekBar>(R.id.sbVideoSeek)

        // AI Views (shared)
        val btnAnalisisAi = root.findViewById<LinearLayout>(R.id.btnAnalisisAi)
        val btnHapusOverlay = root.findViewById<LinearLayout>(R.id.btnHapusOverlay)
        val pbAiLoading = root.findViewById<ProgressBar>(R.id.pbAiLoading)

        // --- Cek Validitas File ---
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "File media rusak/hilang", Toast.LENGTH_SHORT).show()
            return root
        }

        // Initialize AI components (shared for image & video)
        initAiDetector()

        if (type.equals("IMAGE", ignoreCase = true)) {
            // --- MODE GAMBAR ---
            imageMode.visibility = View.VISIBLE
            videoMode.visibility = View.GONE

            photo.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            photo.minimumScale = 0.5f

            // Decode bitmap with EXIF rotation
            runCatching {
                val bmp = decodeBitmapWithExifRotation(file)
                originalBitmap = bmp
                photo.setImageBitmap(bmp)
            }.onFailure {
                photo.setImageURI(Uri.fromFile(file))
            }

            // --- "Analisis AI" button click (Image) ---
            btnAnalisisAi.setOnClickListener {
                performImageAiAnalysis(file, photo, btnAnalisisAi, btnHapusOverlay, pbAiLoading)
            }

            // --- "Hapus Overlay" button click (Image) ---
            btnHapusOverlay.setOnClickListener {
                originalBitmap?.let { bmp ->
                    photo.setImageBitmap(bmp)
                }
                btnHapusOverlay.visibility = View.GONE
                btnAnalisisAi.visibility = View.VISIBLE
            }

        } else {
            // --- MODE VIDEO ---
            imageMode.visibility = View.GONE
            videoMode.visibility = View.VISIBLE
            videoTextureView = tvVideoTexture

            val uri = Uri.fromFile(file)
            val durationStr = getSafeDuration(file)

            if (durationStr == "00:00") {
                Toast.makeText(context, "Video corrupt", Toast.LENGTH_SHORT).show()
            } else {
                setupVideoPlayer(file, tvVideoTexture, ivPlayPause, sbVideoSeek)
            }

            // Video tap to play/pause
            val togglePlay = {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    mp.pause()
                    ivPlayPause.visibility = View.VISIBLE
                } else if (mp != null) {
                    mp.start()
                    ivPlayPause.visibility = View.GONE
                    startSeekBarUpdater(mp, sbVideoSeek)
                }
            }

            tvVideoTexture.setOnClickListener { togglePlay() }
            ivPlayPause.setOnClickListener { togglePlay() }

            // --- "Analisis AI" button click (Video) ---
            btnAnalisisAi.setOnClickListener {
                startVideoAiAnalysis(tvVideoTexture, ivAiOverlayVideo, btnAnalisisAi, btnHapusOverlay, pbAiLoading)
            }

            // --- "Hapus Overlay" button click (Video) ---
            btnHapusOverlay.setOnClickListener {
                stopVideoAiAnalysis()
                ivAiOverlayVideo.setImageBitmap(null)
                ivAiOverlayVideo.visibility = View.GONE
                btnHapusOverlay.visibility = View.GONE
                btnAnalisisAi.visibility = View.VISIBLE
            }
        }
        return root
    }

    // =====================================================================
    // AI DETECTOR INIT
    // =====================================================================

    private fun initAiDetector() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("cervexa_ai_prefs", Context.MODE_PRIVATE)
        val viaModelHelper = ViaModelHelper(ctx)
        val acetowhiteDetector = AcetowhiteDetector()
        val analysisModeManager = AnalysisModeManager(prefs)
        aiDetector = AiDetector(ctx, viaModelHelper, acetowhiteDetector, analysisModeManager)
        overlayRenderer = OverlayRenderer()
    }

    // =====================================================================
    // IMAGE AI ANALYSIS
    // =====================================================================

    private fun performImageAiAnalysis(
        file: File,
        photo: PhotoView,
        btnAnalisisAi: LinearLayout,
        btnHapusOverlay: LinearLayout,
        pbAiLoading: ProgressBar
    ) {
        val detector = aiDetector ?: return
        val renderer = overlayRenderer ?: return

        val bitmap = try {
            decodeBitmapWithExifRotation(file)
        } catch (e: Exception) {
            Toast.makeText(context, "Gambar rusak atau format tidak didukung", Toast.LENGTH_SHORT).show()
            return
        }

        val validationError = detector.validateImage(bitmap.width, bitmap.height)
        if (validationError != null) {
            Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
            return
        }

        btnAnalisisAi.visibility = View.GONE
        pbAiLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withTimeoutOrNull(AI_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    detector.analyzeImage(bitmap)
                }
            }

            pbAiLoading.visibility = View.GONE

            if (result == null) {
                Toast.makeText(context, "Analisis AI timeout, coba lagi", Toast.LENGTH_SHORT).show()
                btnAnalisisAi.visibility = View.VISIBLE
                return@launch
            }

            when (result) {
                is AbnormalityResult.Detected -> {
                    val overlayBitmap = renderer.renderOverlay(bitmap, result)
                    photo.setImageBitmap(overlayBitmap)
                    btnHapusOverlay.visibility = View.VISIBLE
                    showAiReportDialog(result)
                }
                is AbnormalityResult.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    btnAnalisisAi.visibility = View.VISIBLE
                }
                is AbnormalityResult.Idle -> {
                    btnAnalisisAi.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showAiReportDialog(result: AbnormalityResult.Detected) {
        val ctx = context ?: return
        val percentage = (if (result.label == Classification.ABNORMAL) {
            result.confidenceScore * 100
        } else {
            (1 - result.confidenceScore) * 100
        }).roundToInt()

        val statusText = if (result.label == Classification.ABNORMAL) "ABNORMAL" else "NORMAL"
        val rekomendasiText = if (result.label == Classification.ABNORMAL) {
            "• Terdeteksi pola visual indikasi lesi serviks / Acetowhite.\n• Harap lakukan pemeriksaan klinis lanjutan (Kolposkopi / Biopsi) untuk konfirmasi diagnosis."
        } else {
            "• Jaringan serviks tampak normal (tidak ditemukan tanda lesi signifikan).\n• Lanjutkan pemeriksaan rutin sesuai jadwal."
        }
        val modeText = if (result.isFallback) "Deteksi Acetowhite (Fallback)" else "Cervex AI Model (TFLite)"
        val timeStr = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

        val message = """
            |Status Diagnosis: $statusText ($percentage%)
            |Mode Analisis: $modeText
            |Waktu Pemeriksaan: $timeStr
            |
            |KETERANGAN KLINIS:
            |• Tipe Analisis: Klasifikasi Citra Keseluruhan (Whole-Image)
            |• Catatan: Model menganalisis keseluruhan gambar serviks secara klasifikasi medis.
            |
            |REKOMENDASI:
            |$rekomendasiText
        """.trimMargin()

        AlertDialog.Builder(ctx)
            .setTitle("Laporan Hasil Analisis AI")
            .setMessage(message)
            .setPositiveButton("Tutup / Mengerti") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Salin Laporan") { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Laporan AI Cervexa", message)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(ctx, "Laporan berhasil disalin", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // =====================================================================
    // VIDEO PLAYER (TextureView + MediaPlayer)
    // =====================================================================

    private fun setupVideoPlayer(
        file: File,
        textureView: TextureView,
        ivPlayPause: ImageView,
        seekBar: SeekBar
    ) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val mp = MediaPlayer()
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setSurface(Surface(st))
                    mp.isLooping = false
                    mp.setOnPreparedListener { player ->
                        seekBar.max = player.duration
                        player.start()
                        ivPlayPause.visibility = View.GONE
                        startSeekBarUpdater(player, seekBar)
                    }
                    mp.setOnCompletionListener {
                        ivPlayPause.visibility = View.VISIBLE
                        seekBar.progress = seekBar.max
                    }
                    mp.setOnErrorListener { _, _, _ -> true }
                    mp.prepareAsync()
                    mediaPlayer = mp
                } catch (e: Exception) {
                    Log.e(TAG, "MediaPlayer error", e)
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                mediaPlayer?.release()
                mediaPlayer = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startSeekBarUpdater(player: MediaPlayer, seekBar: SeekBar) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            while (isActive && player.isPlaying) {
                try {
                    seekBar.progress = player.currentPosition
                } catch (_: Exception) { break }
                delay(500)
            }
        }
    }

    // =====================================================================
    // VIDEO AI REAL-TIME ANALYSIS (Optimized Zero-Stutter)
    // =====================================================================

    private var reusableAiBmp: Bitmap? = null
    private var reusableOverlayBmp: Bitmap? = null

    private fun startVideoAiAnalysis(
        textureView: TextureView,
        ivOverlay: ImageView,
        btnAnalisisAi: LinearLayout,
        btnHapusOverlay: LinearLayout,
        pbAiLoading: ProgressBar
    ) {
        val detector = aiDetector ?: return
        val renderer = overlayRenderer ?: return

        isVideoAiActive = true
        btnAnalisisAi.visibility = View.GONE
        btnHapusOverlay.visibility = View.VISIBLE
        ivOverlay.visibility = View.VISIBLE
        pbAiLoading.visibility = View.VISIBLE

        videoAiJob = viewLifecycleOwner.lifecycleScope.launch {
            var firstResult = true
            while (isActive && isVideoAiActive) {
                // 1. Grab low-res frame directly matching model input (224x224) using reusable bitmap
                // This takes <1ms and generates ZERO GC garbage per tick
                val frameBmp = withContext(Dispatchers.Main) {
                    if (!isAdded || textureView.width <= 0 || textureView.height <= 0) return@withContext null
                    if (reusableAiBmp == null || reusableAiBmp!!.isRecycled) {
                        reusableAiBmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
                    }
                    textureView.getBitmap(reusableAiBmp!!)
                } ?: run { delay(VIDEO_AI_INTERVAL_MS); continue }

                // 2. Run AI inference on background thread
                val result = withContext(Dispatchers.Default) {
                    try {
                        detector.analyzeImage(frameBmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Video AI error", e)
                        null
                    }
                }

                // 3. Render ONLY transparent HUD overlay (border + label) on main thread
                // Video underneath in TextureView continues playing at full 30/60 FPS without stutter
                withContext(Dispatchers.Main) {
                    if (!isAdded || !isVideoAiActive) return@withContext
                    if (firstResult) {
                        pbAiLoading.visibility = View.GONE
                        firstResult = false
                    }

                    when (result) {
                        is AbnormalityResult.Detected -> {
                            val w = textureView.width
                            val h = textureView.height
                            if (w > 0 && h > 0) {
                                val overlayBmp = renderer.renderTransparentOverlay(w, h, result, reusableOverlayBmp)
                                reusableOverlayBmp = overlayBmp
                                ivOverlay.setImageBitmap(overlayBmp)
                            }
                        }
                        else -> {
                            // Normal / no detection — clear overlay
                            ivOverlay.setImageBitmap(null)
                        }
                    }
                }

                delay(VIDEO_AI_INTERVAL_MS)
            }
        }
    }

    private fun stopVideoAiAnalysis() {
        isVideoAiActive = false
        videoAiJob?.cancel()
        videoAiJob = null
        reusableAiBmp?.recycle()
        reusableAiBmp = null
        reusableOverlayBmp?.recycle()
        reusableOverlayBmp = null
    }

    // =====================================================================
    // LIFECYCLE
    // =====================================================================

    override fun onPause() {
        super.onPause()
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopVideoAiAnalysis()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
        aiDetector = null
        overlayRenderer = null
        originalBitmap = null
        videoTextureView = null
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun getSafeDuration(file: File): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val timeInMillis = time?.toLongOrNull() ?: 0L
            if (timeInMillis == 0L) return "00:00"
            val minutes = TimeUnit.MILLISECONDS.toMinutes(timeInMillis)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(timeInMillis) % 60
            String.format("%02d:%02d", minutes, seconds)
        } catch (e: Exception) {
            Log.e("MediaPage", "Gagal baca durasi: ${file.name}")
            "00:00"
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun decodeBitmapWithExifRotation(file: File): android.graphics.Bitmap {
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Gagal decode bitmap")

        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val rot = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (rot == 0) return bmp

        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}