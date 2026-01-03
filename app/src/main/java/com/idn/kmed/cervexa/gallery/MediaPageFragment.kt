package com.idn.kmed.cervexa.gallery

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.idn.kmed.cervexa.R
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
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
        val root = inflater.inflate(R.layout.page_media, container, false)
        val path = requireArguments().getString("path")!!
        val type = requireArguments().getString("type")!!
        val file = File(path)

        val imageMode = root.findViewById<View>(R.id.imageMode)
        val videoMode = root.findViewById<View>(R.id.videoMode)

        val photo = root.findViewById<PhotoView>(R.id.photoView)
        val video = root.findViewById<VideoView>(R.id.vvPreview)
        val overlayImg = root.findViewById<LinearLayout>(R.id.overlayImage)
        val overlayVid = root.findViewById<LinearLayout>(R.id.overlayVideo)
        val tvInfoRight = root.findViewById<TextView>(R.id.tvInfoRight)
        val tvVidRight = root.findViewById<TextView>(R.id.tvVidRight)

        if (type == "IMAGE") {
            imageMode.visibility = View.VISIBLE
            videoMode.visibility = View.GONE

            photo.minimumScale = 1f
            photo.mediumScale  = 2.5f
            photo.maximumScale = 5f
            photo.setImageURI(Uri.fromFile(file))

            overlayImg.visibility = View.GONE
            overlayVid.visibility = View.GONE

            tvInfoRight.text = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale("id","ID"))
                .format(java.util.Date(file.lastModified()))
        } else {
            imageMode.visibility = View.GONE
            videoMode.visibility = View.VISIBLE

            // --- REVISI LOGIKA URI ---
            // Kita tentukan URI yang valid dulu, baru dipakai untuk VideoView DAN Metadata
            var finalUri: Uri = Uri.fromFile(file)
            var useFileProvider = false

            // Cek sederhana apakah bisa dibaca langsung
            if (!file.exists() || !file.canRead()) {
                useFileProvider = true
            }

            // Jika perlu FileProvider (atau jika akses file langsung gagal nanti)
            // Disini kita siapkan try-catch untuk setVideoURI
            try {
                video.setVideoURI(finalUri)
            } catch (e: Exception) {
                useFileProvider = true
            }

            if (useFileProvider) {
                try {
                    finalUri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), "${requireContext().packageName}.fileprovider", file
                    )
                    // Beri izin baca sementara
                    requireContext().grantUriPermission(
                        requireContext().packageName, finalUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    video.setVideoURI(finalUri)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // MediaController
            val mc = android.widget.MediaController(requireContext()).apply { setAnchorView(video) }
            video.setMediaController(mc)

            video.setOnPreparedListener { mp ->
                val w = mp.videoWidth
                val h = mp.videoHeight
                if (w > 0 && h > 0) {
                    val lp = video.layoutParams as ConstraintLayout.LayoutParams
                    lp.dimensionRatio = "$w:$h"
                    video.layoutParams = lp
                }
                video.start()
            }

            overlayImg.visibility = View.GONE
            overlayVid.visibility = View.GONE

            // --- PANGGIL FUNGSI DENGAN URI ---
            tvVidRight.text = formatDuration(requireContext(), finalUri)
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

    // --- FUNGSI DIPERBAIKI ---
    // Menerima Context dan Uri agar aman dari masalah permission/scoped storage
    private fun formatDuration(context: Context, uri: Uri): String {
        val mmr = MediaMetadataRetriever()
        return try {
            // Gunakan setDataSource(Context, Uri)
            mmr.setDataSource(context, uri)

            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val ms = durationStr?.toLongOrNull() ?: 0L

            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60

            if (h > 0) {
                String.format("%02d:%02d:%02d", h, m, s)
            } else {
                String.format("%02d:%02d", m, s)
            }
        } catch (e: Exception) {
            // Log error tapi jangan crash
            e.printStackTrace()
            "00:00"
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}