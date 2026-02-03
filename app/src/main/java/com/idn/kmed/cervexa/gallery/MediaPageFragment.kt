package com.idn.kmed.cervexa.gallery

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
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.github.chrisbanes.photoview.PhotoView
import com.idn.kmed.cervexa.R
import androidx.exifinterface.media.ExifInterface
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
        val videoMode = root.findViewById<View>(R.id.videoMode) // Ini container video

        val photo = root.findViewById<PhotoView>(R.id.photoView)
        val video = root.findViewById<VideoView>(R.id.vvPreview)

//        val overlayImg = root.findViewById<LinearLayout>(R.id.overlayImage)
//        val overlayVid = root.findViewById<LinearLayout>(R.id.overlayVideo)

//        val tvInfoRight = root.findViewById<TextView>(R.id.tvInfoRight)
//        val tvVidRight = root.findViewById<TextView>(R.id.tvVidRight)

        // --- Cek Validitas File ---
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "File media rusak/hilang", Toast.LENGTH_SHORT).show()
            return root
        }

        if (type.equals("IMAGE", ignoreCase = true)) {
            // --- MODE GAMBAR ---
            imageMode.visibility = View.VISIBLE
            videoMode.visibility = View.GONE
//            overlayImg.visibility = View.VISIBLE
//            overlayVid.visibility = View.GONE

            photo.minimumScale = 1f
            photo.mediumScale = 2.5f
            photo.maximumScale = 5f
            // Gunakan bitmap + EXIF rotation agar hasil landscape tidak "miring" / ter-rotate
            runCatching {
                val bmp = decodeBitmapWithExifRotation(file)
                photo.setImageBitmap(bmp)
            }.onFailure {
                // fallback
                photo.setImageURI(Uri.fromFile(file))
            }

            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID"))
//            tvInfoRight.text = dateFormat.format(Date(file.lastModified()))

        } else {
            // --- MODE VIDEO ---
            imageMode.visibility = View.GONE
            videoMode.visibility = View.VISIBLE

            // Default awal: Overlay disembunyikan dulu (nanti muncul kalau dipause)
//            overlayImg.visibility = View.GONE
//            overlayVid.visibility = View.GONE

            val uri = Uri.fromFile(file)
            val durationStr = getSafeDuration(file)
//            tvVidRight.text = durationStr

            if (durationStr == "00:00") {
                Toast.makeText(context, "Video corrupt", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    video.setVideoURI(uri)

                    // 1. Pastikan kontroler bawaan mati
//                    video.setMediaController(null)
                    val mc = MediaController(requireContext())
                    mc.setAnchorView(video)
                    video.setMediaController(mc)

                    // 2. Fungsi Toggle: Play = Bersih, Pause = Muncul Info
                    val togglePlay = {
                        if (video.isPlaying) {
                            video.pause()
                            // Saat PAUSE: Munculkan overlay info (bar abu-abu)
//                            overlayVid.visibility = View.GONE
                            Toast.makeText(context, "Video paused", Toast.LENGTH_SHORT).show()
                        } else {
                            video.start()
                            // Saat PLAY: Sembunyikan overlay info (Layar bersih/Full)
//                            overlayVid.visibility = View.GONE
                            Toast.makeText(context, "Video played", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // 3. Pasang listener
                    video.setOnClickListener { togglePlay() }
                    videoMode.setOnClickListener { togglePlay() }

                    video.setOnPreparedListener { mp ->
                        val w = mp.videoWidth
                        val h = mp.videoHeight
                        if (w > 0 && h > 0) {
                            val lp = video.layoutParams as ConstraintLayout.LayoutParams
                            // Jika video punya metadata rotasi 90/270, ratio harus dibalik.
                            val rot = getVideoRotationDeg(file)
                            if (rot == 90 || rot == 270) lp.dimensionRatio = "$h:$w" else lp.dimensionRatio = "$w:$h"
                            video.layoutParams = lp
                        }

                        // Terapkan rotasi ke view (VideoView adalah View, jadi rotation property bisa dipakai)
                        val rot = getVideoRotationDeg(file)
                        if (rot != 0) video.rotation = rot.toFloat()
                        mp.isLooping = false

                        // Mulai video dan sembunyikan overlay agar bersih
                        video.start()
//                        overlayVid.visibility = View.GONE
                    }

                    video.setOnErrorListener { _, _, _ -> true }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return root
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