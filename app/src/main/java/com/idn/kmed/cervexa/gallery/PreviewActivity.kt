package com.idn.kmed.cervexa.gallery

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.widget.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.MediaType
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class PreviewActivity : AppCompatActivity() {

    private lateinit var imageMode: View
    private lateinit var videoMode: View
    private lateinit var photoView: PhotoView
    private lateinit var vv: VideoView
    private lateinit var toolbar: MaterialToolbar
    private var chipIndex: Chip? = null

    // overlay image
    private lateinit var tvInfoLeft: TextView
    private lateinit var tvInfoRight: TextView

    // overlay video
    private lateinit var tvVidLeft: TextView
    private lateinit var tvVidRight: TextView

    private lateinit var bottomShare: View
    private lateinit var btnBackLite: View

    private var file: File? = null
    private var type: MediaType = MediaType.IMAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        // --- views
        toolbar = findViewById(R.id.toolbar)
        chipIndex = findViewById(R.id.chipIndex)

        imageMode = findViewById(R.id.imageMode)
        videoMode = findViewById(R.id.videoMode)
        photoView = findViewById(R.id.photoView)
        vv = findViewById(R.id.vvPreview)

        tvInfoLeft = findViewById(R.id.tvInfoLeft)
        tvInfoRight = findViewById(R.id.tvInfoRight)
        tvVidLeft = findViewById(R.id.tvVidLeft)
        tvVidRight = findViewById(R.id.tvVidRight)

        bottomShare = findViewById(R.id.bottomShare)

        //Landscap
        btnBackLite = findViewById(R.id.btnBackLite)

        btnBackLite.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // --- extras
        val path = intent.getStringExtra("filePath")
        val typeStr = intent.getStringExtra("type")
        val index = intent.getIntExtra("index", 0)
        val total = intent.getIntExtra("total", 1)

        file = path?.let { File(it) }
        type = runCatching { MediaType.valueOf(typeStr ?: "IMAGE") }.getOrDefault(MediaType.IMAGE)

        val f = file
        if (f == null || !f.exists()) {
            Toast.makeText(this, "File tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        toolbar.title = f.name
        chipIndex?.text = "${index + 1}/$total"

        when (type) {
            MediaType.IMAGE -> {
                imageMode.visibility = View.VISIBLE
                videoMode.visibility = View.GONE

                photoView.minimumScale = 1.0f
                photoView.mediumScale = 2.5f
                photoView.maximumScale = 5.0f
                photoView.setImageURI(Uri.fromFile(f))

                tvInfoLeft.text = "" // isi sesuai kebutuhan (NIK/Nama/Usia)
                tvInfoRight.text = formatFileTime(f) // contoh: waktu dari lastModified
            }
            MediaType.VIDEO -> {
                imageMode.visibility = View.GONE
                videoMode.visibility = View.VISIBLE

                setupVideoCentered(f)

                tvVidLeft.text = "" // isi sesuai kebutuhan (NIK/Nama/Usia)
                tvVidRight.text = formatVideoDuration(f)
            }
        }

        bottomShare.setOnClickListener { shareFile(f, type) }
    }

    private fun setupVideoCentered(f: File) {
        vv.setVideoURI(Uri.fromFile(f))

        // MediaController dari android.widget
        val mc = android.widget.MediaController(this).apply { setAnchorView(vv) }
        vv.setMediaController(mc)

        // saat sudah siap, terapkan aspect ratio ke ConstraintLayout
        vv.setOnPreparedListener { mp ->
            val w = mp.videoWidth
            val h = mp.videoHeight
            if (w > 0 && h > 0) {
                val lp = vv.layoutParams as ConstraintLayout.LayoutParams
                lp.dimensionRatio = "$w:$h" // contoh: "1920:1080"
                vv.layoutParams = lp
            }
            vv.start()
        }
    }

    private fun shareFile(f: File, type: MediaType) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
        val mime = if (type == MediaType.IMAGE) "image/jpeg" else "video/mp4"
        val share = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            this.type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Bagikan"))

        // opsional: scan biar muncul di galeri umum
        MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf(mime), null)
    }

    private fun formatVideoDuration(file: File): String {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            String.format("%02d:%02d:%02d", h, m, s)
        } catch (_: Exception) {
            "00:00:00"
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun formatFileTime(f: File): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID"))
        return sdf.format(java.util.Date(f.lastModified()))
    }


}