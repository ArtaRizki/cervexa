package com.idn.kmed.cervexa.gallery

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
            videoMode.visibility = View.VISIBLE   // <-- perbaikan utamanya

            // Coba pakai file://
            var setOk = false
            try {
                video.setVideoURI(Uri.fromFile(file))
                setOk = true
            } catch (_: Exception) { }

            // Fallback ke content:// via FileProvider bila perlu
            if (!setOk) {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), "${requireContext().packageName}.fileprovider", file
                    )
                    video.setVideoURI(uri)
                    requireContext().grantUriPermission(
                        requireContext().packageName, uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    setOk = true
                } catch (_: Exception) { }
            }

            // MediaController
            val mc = android.widget.MediaController(requireContext()).apply { setAnchorView(video) }
            video.setMediaController(mc)

            video.setOnPreparedListener { mp ->
                val w = mp.videoWidth; val h = mp.videoHeight
                if (w > 0 && h > 0) {
                    val lp = video.layoutParams as ConstraintLayout.LayoutParams
                    lp.dimensionRatio = "$w:$h"   // menjaga rasio asli
                    video.layoutParams = lp
                }
                video.start()
            }

            overlayImg.visibility = View.GONE
            overlayVid.visibility = View.GONE
            tvVidRight.text = formatDuration(file)
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

    private fun formatDuration(f: File): String {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            String.format("%02d:%02d:%02d", h, m, s)
        } finally { try { mmr.release() } catch (_: Exception) {} }
    }
}
