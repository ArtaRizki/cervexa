package com.idn.kmed.cervexa.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.VideoView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.github.chrisbanes.photoview.PhotoView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.ml.AbnormalityResult
import com.idn.kmed.cervexa.ml.AcetowhiteDetector
import com.idn.kmed.cervexa.ml.AiDetector
import com.idn.kmed.cervexa.ml.AnalysisModeManager
import com.idn.kmed.cervexa.ml.OverlayRenderer
import com.idn.kmed.cervexa.ml.ViaModelHelper
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

class MediaPageFragment : Fragment() {

    private var aiDetector: AiDetector? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var originalBitmap: Bitmap? = null

    companion object {
        private const val TAG = "MediaPageFragment"
        private const val AI_TIMEOUT_MS = 3000L

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
        val video = root.findViewById<VideoView>(R.id.vvPreview)

        // AI Views
        val btnAnalisisAi = root.findViewById<LinearLayout>(R.id.btnAnalisisAi)
        val btnHapusOverlay = root.findViewById<LinearLayout>(R.id.btnHapusOverlay)
        val pbAiLoading = root.findViewById<ProgressBar>(R.id.pbAiLoading)

        // --- Cek Validitas File ---
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "File media rusak/hilang", Toast.LENGTH_SHORT).show()
            return root
        }

        if (type.equals("IMAGE", ignoreCase = true)) {
            // --- MODE GAMBAR ---
            imageMode.visibility = View.VISIBLE
            videoMode.visibility = View.GONE

            photo.minimumScale = 1f
            photo.mediumScale = 2.5f
            photo.maximumScale = 5f

            // Decode bitmap with EXIF rotation
            runCatching {
                val bmp = decodeBitmapWithExifRotation(file)
                originalBitmap = bmp
                photo.setImageBitmap(bmp)
            }.onFailure {
                photo.setImageURI(Uri.fromFile(file))
            }

            // Initialize AI components
            initAiDetector()

            // --- "Analisis AI" button click ---
            btnAnalisisAi.setOnClickListener {
                performAiAnalysis(file, photo, btnAnalisisAi, btnHapusOverlay, pbAiLoading)
            }

            // --- "Hapus Overlay" button click ---
            btnHapusOverlay.setOnClickListener {
                // Restore original image
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

            // Hide AI buttons in video mode
            btnAnalisisAi.visibility = View.GONE
            btnHapusOverlay.visibility = View.GONE

            val uri = Uri.fromFile(file)
            val durationStr = getSafeDuration(file)

            if (durationStr == "00:00") {
                Toast.makeText(context, "Video corrupt", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    video.setVideoURI(uri)

                    val mc = MediaController(requireContext())
                    mc.setAnchorView(video)
                    video.setMediaController(mc)

                    val togglePlay = {
                        if (video.isPlaying) {
                            video.pause()
                            Toast.makeText(context, "Video paused", Toast.LENGTH_SHORT).show()
                        } else {
                            video.start()
                            Toast.makeText(context, "Video played", Toast.LENGTH_SHORT).show()
                        }
                    }

                    video.setOnClickListener { togglePlay() }
                    videoMode.setOnClickListener { togglePlay() }

                    video.setOnPreparedListener { mp ->
                        val w = mp.videoWidth
                        val h = mp.videoHeight
                        if (w > 0 && h > 0) {
                            val lp = video.layoutParams as ConstraintLayout.LayoutParams
                            val rot = getVideoRotationDeg(file)
                            if (rot == 90 || rot == 270) lp.dimensionRatio = "$h:$w" else lp.dimensionRatio = "$w:$h"
                            video.layoutParams = lp
                        }

                        val rot = getVideoRotationDeg(file)
                        if (rot != 0) video.rotation = rot.toFloat()
                        mp.isLooping = false

                        video.start()
                    }

                    video.setOnErrorListener { _, _, _ -> true }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return root
    }

    private fun initAiDetector() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("cervexa_ai_prefs", Context.MODE_PRIVATE)
        val viaModelHelper = ViaModelHelper(ctx)
        val acetowhiteDetector = AcetowhiteDetector()
        val analysisModeManager = AnalysisModeManager(prefs)
        aiDetector = AiDetector(ctx, viaModelHelper, acetowhiteDetector, analysisModeManager)
        overlayRenderer = OverlayRenderer()
    }

    private fun performAiAnalysis(
        file: File,
        photo: PhotoView,
        btnAnalisisAi: LinearLayout,
        btnHapusOverlay: LinearLayout,
        pbAiLoading: ProgressBar
    ) {
        val detector = aiDetector ?: return
        val renderer = overlayRenderer ?: return

        // Validate: decode check
        val bitmap = try {
            decodeBitmapWithExifRotation(file)
        } catch (e: Exception) {
            Toast.makeText(context, "Gambar rusak atau format tidak didukung", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate: minimum size 64x64
        val validationError = detector.validateImage(bitmap.width, bitmap.height)
        if (validationError != null) {
            Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading, hide button
        btnAnalisisAi.visibility = View.GONE
        pbAiLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withTimeoutOrNull(AI_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    detector.analyzeImage(bitmap)
                }
            }

            // Back on main thread
            pbAiLoading.visibility = View.GONE

            if (result == null) {
                // Timeout
                Toast.makeText(context, "Analisis AI timeout, coba lagi", Toast.LENGTH_SHORT).show()
                btnAnalisisAi.visibility = View.VISIBLE
                return@launch
            }

            when (result) {
                is AbnormalityResult.Detected -> {
                    // Render overlay on top of original bitmap
                    val overlayBitmap = renderer.renderOverlay(bitmap, result)
                    photo.setImageBitmap(overlayBitmap)
                    btnHapusOverlay.visibility = View.VISIBLE
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

    override fun onPause() {
        super.onPause()
        try {
            view?.findViewById<VideoView>(R.id.vvPreview)?.pause()
        } catch (_: Exception) {
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            view?.findViewById<VideoView>(R.id.vvPreview)?.stopPlayback()
        } catch (_: Exception) {
        }
        // Clean up AI resources
        aiDetector = null
        overlayRenderer = null
        originalBitmap = null
    }

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

    private fun getVideoRotationDeg(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            ((rot % 360) + 360) % 360
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }
}