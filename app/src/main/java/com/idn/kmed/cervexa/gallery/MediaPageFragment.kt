package com.idn.kmed.cervexa.gallery

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.github.chrisbanes.photoview.PhotoView
import com.idn.kmed.cervexa.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MediaPageFragment : Fragment() {

    companion object {
        fun newInstance(path: String, type: String): MediaPageFragment {
            val f = MediaPageFragment()
            f.arguments = Bundle().apply {
                putString("path", path)
                putString("type", type)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Pastikan nama layout sesuai dengan XML Anda (page_media)
        val root = inflater.inflate(R.layout.page_media, container, false)

        val path = requireArguments().getString("path") ?: return root
        val type = requireArguments().getString("type") ?: "IMAGE"
        val file = File(path)

        // --- Bind Views ---
        val imageMode = root.findViewById<View>(R.id.imageMode)
        val videoMode = root.findViewById<View>(R.id.videoMode)

        val photo = root.findViewById<PhotoView>(R.id.photoView)
        val video = root.findViewById<VideoView>(R.id.vvPreview)

        val overlayImg = root.findViewById<LinearLayout>(R.id.overlayImage)
        val overlayVid = root.findViewById<LinearLayout>(R.id.overlayVideo)

        val tvInfoRight = root.findViewById<TextView>(R.id.tvInfoRight) // Tanggal Image
        val tvVidRight = root.findViewById<TextView>(R.id.tvVidRight)   // Durasi Video

        // --- Cek Validitas File ---
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "File media rusak atau tidak ditemukan", Toast.LENGTH_SHORT).show()
            return root
        }

        if (type.equals("IMAGE", ignoreCase = true)) {
            // --- MODE GAMBAR ---
            imageMode.visibility = View.VISIBLE
            videoMode.visibility = View.GONE
            overlayImg.visibility = View.VISIBLE // Tampilkan overlay info
            overlayVid.visibility = View.GONE

            // Setup PhotoView
            photo.minimumScale = 1f
            photo.mediumScale  = 2.5f
            photo.maximumScale = 5f
            photo.setImageURI(Uri.fromFile(file))

            // Set Tanggal
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID"))
            tvInfoRight.text = dateFormat.format(Date(file.lastModified()))

        } else {
            // --- MODE VIDEO ---
            imageMode.visibility = View.GONE
            videoMode.visibility = View.VISIBLE
            overlayImg.visibility = View.GONE
            overlayVid.visibility = View.VISIBLE // Tampilkan overlay info

            // Setup VideoView
            val uri = Uri.fromFile(file)

            // 1. Ambil Durasi dengan Aman (Anti-Crash)
            val durationStr = getSafeDuration(file)
            tvVidRight.text = durationStr

            // Jika durasi 00:00 (file rusak header-nya), jangan paksa mainkan
            if (durationStr == "00:00") {
                Toast.makeText(context, "Video tidak dapat diputar", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    video.setVideoURI(uri)

                    val mc = MediaController(requireContext())
                    mc.setAnchorView(video)
                    video.setMediaController(mc)

                    video.setOnPreparedListener { mp ->
                        val w = mp.videoWidth
                        val h = mp.videoHeight
                        // Sesuaikan rasio aspek
                        if (w > 0 && h > 0) {
                            val lp = video.layoutParams as ConstraintLayout.LayoutParams
                            lp.dimensionRatio = "$w:$h"
                            video.layoutParams = lp
                        }
                        video.start()
                    }

                    video.setOnErrorListener { _, _, _ ->
                        // Cegah dialog error default Android muncul
                        true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return root
    }

    override fun onPause() {
        super.onPause()
        try { view?.findViewById<VideoView>(R.id.vvPreview)?.pause() } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { view?.findViewById<VideoView>(R.id.vvPreview)?.stopPlayback() } catch (_: Exception) {}
    }

    /**
     * Fungsi aman untuk mengambil durasi.
     * Menggunakan try-catch agar aplikasi TIDAK CRASH jika file video korup.
     */
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
            // Log error tapi jangan crash
            Log.e("MediaPage", "Gagal membaca file video: ${file.name}")
            "00:00"
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) { /* ignore */ }
        }
    }
}