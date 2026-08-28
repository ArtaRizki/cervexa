package com.idn.kmed.cervexa.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.idn.kmed.cervexa.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.idn.kmed.cervexa.utils.PdfReportHelper
import com.idn.kmed.cervexa.utils.PrintHelper
import java.io.File
import androidx.core.content.FileProvider
import com.idn.kmed.cervexa.utils.MediaType
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.CornerFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

open class MediaPagerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var toolbar: MaterialToolbar
    private var chipIndex: android.widget.TextView? = null
    private lateinit var bottomShare: View
    private lateinit var btnBackLite: View

    private lateinit var paths: ArrayList<String>
    private lateinit var types: ArrayList<String>
    private var startIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Foto & video dari kamera MS2 selalu landscape (16:9) — paksa landscape
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Sembunyikan status bar — harus dipanggil SEBELUM setContentView
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_media_pager)

        // Extra: immersive untuk Android 11+ agar benar-benar hilang (panggil SETELAH setContentView)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        toolbar = findViewById(R.id.toolbar)
        pager = findViewById(R.id.pager)
        chipIndex = findViewById(R.id.chipIndex)
        bottomShare = findViewById(R.id.bottomShare)

        findViewById<View>(R.id.bottomShare)?.setOnClickListener { onShareClick() }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // btnBackLite selalu tampil — back button untuk kembali dari preview
        findViewById<View>(R.id.btnBackLite)?.let { btn ->
            btn.visibility = View.VISIBLE
            btn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        paths = intent.getStringArrayListExtra("paths") ?: arrayListOf()
        types = intent.getStringArrayListExtra("types") ?: arrayListOf()
        startIndex = intent.getIntExtra("index", 0).coerceIn(0, (paths.size - 1).coerceAtLeast(0))

        if (paths.isEmpty() || types.isEmpty() || paths.size != types.size) {
            Toast.makeText(this, "Tidak ada media untuk ditampilkan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = paths.size
            override fun createFragment(position: Int) =
                MediaPageFragment.newInstance(paths[position], types[position])
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateUiForPosition(position)
            }
        })

        pager.setCurrentItem(startIndex, false)
        updateUiForPosition(startIndex)
    }

    private fun onShareClick() {
        val idx = pager.currentItem
        if (idx !in paths.indices) return
        val f = File(paths[idx])
        val mime = if (types[idx] == "IMAGE") "image/jpeg" else "video/mp4"
        showShareSheet(f, mime)
    }

    private fun updateUiForPosition(position: Int) {
        chipIndex?.text = "${position + 1}/${paths.size}"
        toolbar.title = File(paths[position]).name
    }

    private fun showShareSheet(file: File, mime: String) {
        val dialog = BottomSheetDialog(
            this,
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_share_media, null)
        dialog.setContentView(v)
        dialog.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.background = MaterialShapeDrawable(
                ShapeAppearanceModel.Builder()
                    .setTopLeftCorner(
                        CornerFamily.ROUNDED,
                        resources.getDimension(R.dimen.bs_top_radius)
                    )
                    .setTopRightCorner(
                        CornerFamily.ROUNDED,
                        resources.getDimension(R.dimen.bs_top_radius)
                    )
                    .build()
            ).apply {
                fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
        }

        v.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        v.findViewById<LinearLayout>(R.id.itemWa).setOnClickListener {
            shareToAppOrToast(
                arrayOf("com.whatsapp", "com.whatsapp.w4b"),
                "WhatsApp",
                file,
                mime,
                true
            )
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemTg).setOnClickListener {
            shareToAppOrToast(arrayOf("org.telegram.messenger"), "Telegram", file, mime, true)
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemEmail).setOnClickListener {
            shareToAppOrToast(arrayOf("com.google.android.gm"), "Gmail", file, mime, false)
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemCloud).setOnClickListener {
            Toast.makeText(this, "Dalam Pengembangan", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemSave).setOnClickListener {
            exportToGallery(file, mime)
            dialog.dismiss()
        }

        // Cetak Data Pasien (PDF ringkasan)
        v.findViewById<LinearLayout>(R.id.itemPrintPatient)?.setOnClickListener {
            dialog.dismiss()
            generateAndActionPdf(file, ReportType.PATIENT_SUMMARY, download = false)
        }

        // Cetak Sesi / Foto
        v.findViewById<LinearLayout>(R.id.itemPrintSession)?.setOnClickListener {
            dialog.dismiss()
            if (mime.startsWith("image")) {
                generateAndActionPdf(file, ReportType.CURRENT_PHOTO, download = false)
            } else {
                generateAndActionPdf(file, ReportType.FULL_SESSION, download = false)
            }
        }

        // Unduh PDF
        v.findViewById<LinearLayout>(R.id.itemDownloadSession)?.setOnClickListener {
            dialog.dismiss()
            if (mime.startsWith("image")) {
                generateAndActionPdf(file, ReportType.CURRENT_PHOTO, download = true)
            } else {
                generateAndActionPdf(file, ReportType.FULL_SESSION, download = true)
            }
        }

        dialog.show()
    }

    private enum class ReportType {
        CURRENT_PHOTO,
        PATIENT_SUMMARY,
        FULL_SESSION
    }

    private data class SessionInfo(
        val nama: String,
        val nik: String,
        val hospitalName: String,
        val nrm: String?,
        val dobUtcMs: Long?,
        val sessionDir: File?
    )

    private fun getSessionOrPatientMetadata(currentFile: File): SessionInfo {
        // 1. Dari Intent Extras
        val nameExtra = intent.getStringExtra("patient_name")
        val nikExtra = intent.getStringExtra("patient_nik")
        val rsExtra = intent.getStringExtra("patient_rs")
        val nrmExtra = intent.getStringExtra("patient_nrm")
        val dobExtra = intent.getLongExtra("patient_dob_utc", -1L)

        if (!nameExtra.isNullOrBlank() || !nikExtra.isNullOrBlank() || !rsExtra.isNullOrBlank()) {
            return SessionInfo(
                nama = nameExtra.orEmpty().ifBlank { "—" },
                nik = nikExtra.orEmpty().ifBlank { "—" },
                hospitalName = rsExtra.orEmpty().ifBlank { "—" },
                nrm = nrmExtra?.ifBlank { null },
                dobUtcMs = dobExtra.takeIf { it > 0L },
                sessionDir = intent.getStringExtra("session_dir")?.let { File(it) } ?: currentFile.parentFile?.parentFile
            )
        }

        // 2. Parse dari folder sesi (session.json / nama folder)
        val parent = currentFile.parentFile
        val patientDir = if (parent != null && (parent.name.equals("Snapshots", true) || parent.name.equals("Video", true))) {
            parent.parentFile
        } else {
            parent
        }

        if (patientDir != null && patientDir.exists()) {
            val jsonFile = File(patientDir, "session.json")
            if (jsonFile.exists()) {
                val o = runCatching { JSONObject(jsonFile.readText()) }.getOrNull()
                if (o != null) {
                    return SessionInfo(
                        nama = o.optString("nama", "—").ifBlank { "—" },
                        nik = o.optString("nik", "—").ifBlank { "—" },
                        hospitalName = o.optString("rs", "—").ifBlank { "—" },
                        nrm = o.optString("nrm", null)?.ifBlank { null },
                        dobUtcMs = o.optLong("dob_utc").takeIf { it > 0L },
                        sessionDir = patientDir
                    )
                }
            }

            val parts = patientDir.name.split("_")
            if (parts.size >= 2) {
                val nik = parts[0]
                val nama = parts.drop(1).dropLast(1).joinToString(" ").replace('_', ' ').trim()
                return SessionInfo(
                    nama = nama.ifBlank { "—" },
                    nik = nik.ifBlank { "—" },
                    hospitalName = "Cervexa Clinic",
                    nrm = null,
                    dobUtcMs = null,
                    sessionDir = patientDir
                )
            }
        }

        // 3. Fallback SharedPreferences
        val sp = getSharedPreferences("cervexa_prefs", Context.MODE_PRIVATE)
        return SessionInfo(
            nama = sp.getString("pref_patient_name", "—") ?: "—",
            nik = sp.getString("pref_patient_nik", "—") ?: "—",
            hospitalName = sp.getString("pref_patient_rs", "—") ?: "—",
            nrm = sp.getString("pref_patient_nrm", null),
            dobUtcMs = null,
            sessionDir = patientDir
        )
    }

    private fun generateAndActionPdf(file: File, type: ReportType, download: Boolean) {
        val meta = getSessionOrPatientMetadata(file)
        val ts = System.currentTimeMillis()
        val fname = when (type) {
            ReportType.CURRENT_PHOTO -> "cervexa_foto_${ts}.pdf"
            ReportType.PATIENT_SUMMARY -> "cervexa_pasien_${ts}.pdf"
            ReportType.FULL_SESSION -> "cervexa_sesi_${ts}.pdf"
        }
        val outFile = File(cacheDir, fname)

        val sessionDir = meta.sessionDir
        val snaps = if (sessionDir != null && sessionDir.exists()) {
            File(sessionDir, "Snapshots")
                .listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                ?.sortedBy { it.lastModified() } ?: listOf(file).filter { it.extension.equals("jpg", true) }
        } else {
            listOf(file).filter { it.extension.equals("jpg", true) }
        }

        val videos = if (sessionDir != null && sessionDir.exists()) {
            File(sessionDir, "Video")
                .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                ?.sortedBy { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }

        Toast.makeText(this, "Menyiapkan laporan PDF...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val pdf = when (type) {
                ReportType.CURRENT_PHOTO -> {
                    PdfReportHelper.generateSingleMediaPdf(
                        outputFile = outFile,
                        nama = meta.nama,
                        nik = meta.nik,
                        hospitalName = meta.hospitalName,
                        nrm = meta.nrm,
                        dobUtcMs = meta.dobUtcMs,
                        mediaFile = file
                    )
                }
                ReportType.PATIENT_SUMMARY -> {
                    PdfReportHelper.generatePatientPdf(
                        outputFile = outFile,
                        nama = meta.nama,
                        nik = meta.nik,
                        hospitalName = meta.hospitalName,
                        nrm = meta.nrm,
                        dobUtcMs = meta.dobUtcMs,
                        sessionId = -1,
                        sessionCode = null,
                        startedAt = null,
                        completedAt = null,
                        snapshotCount = snaps.size,
                        videoCount = videos.size
                    )
                }
                ReportType.FULL_SESSION -> {
                    PdfReportHelper.generateSessionPdf(
                        outputFile = outFile,
                        nama = meta.nama,
                        nik = meta.nik,
                        hospitalName = meta.hospitalName,
                        nrm = meta.nrm,
                        dobUtcMs = meta.dobUtcMs,
                        sessionId = -1,
                        sessionCode = null,
                        startedAt = null,
                        completedAt = null,
                        snapshotFiles = snaps,
                        videoFiles = videos
                    )
                }
            }

            withContext(Dispatchers.Main) {
                if (pdf == null || !pdf.exists()) {
                    Toast.makeText(this@MediaPagerActivity, "Gagal membuat PDF", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                if (download) {
                    val ok = PrintHelper.downloadPdf(this@MediaPagerActivity, pdf, fname)
                    Toast.makeText(
                        this@MediaPagerActivity,
                        if (ok) "PDF tersimpan di folder Downloads" else "Gagal menyimpan PDF",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val label = when (type) {
                        ReportType.CURRENT_PHOTO -> "Hasil Foto"
                        ReportType.PATIENT_SUMMARY -> "Data Pasien"
                        ReportType.FULL_SESSION -> "Sesi Pemeriksaan"
                    }
                    PrintHelper.printPdf(this@MediaPagerActivity, pdf, "Cervexa — $label")
                }
            }
        }
    }

    private fun fileUriForShare(f: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun resolveFirstInstalled(vararg pkgs: String): String? {
        val pm = packageManager
        return pkgs.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }
    }

    private fun shareToAppOrToast(
        packages: Array<String>,
        appLabel: String,
        file: File,
        mime: String,
        loosenMediaMime: Boolean = true
    ) {
        val targetPkg = resolveFirstInstalled(*packages)
        if (targetPkg == null) {
            // App belum terpasang → tawarkan buka Play Store
            MaterialAlertDialogBuilder(this)
                .setTitle("$appLabel Belum Terpasang")
                .setMessage("Aplikasi $appLabel belum terpasang di perangkat ini. Buka Play Store untuk memasangnya?")
                .setPositiveButton("Buka Play Store") { _, _ ->
                    PrintHelper.openPlayStore(this, packages.first())
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }

        val uri = fileUriForShare(file)
        val finalMime =
            if (loosenMediaMime && (mime.startsWith("image") || mime.startsWith("video"))) {
                if (mime.startsWith("image")) "image/*" else "video/*"
            } else mime

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = finalMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "media", uri)
            `package` = targetPkg
        }

        runCatching { grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        val canHandle = packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        if (!canHandle) {
            // Package terinstall tapi tidak support intent ini → fallback chooser
            startActivity(
                Intent.createChooser(
                    intent.apply { `package` = null }, "Bagikan via"
                )
            )
            return
        }
        startActivity(intent)
    }

    private fun exportToGallery(src: File, mime: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val isVideo = mime.startsWith("video")
            val rel = if (isVideo) android.os.Environment.DIRECTORY_MOVIES + "/Cervexa"
            else android.os.Environment.DIRECTORY_PICTURES + "/Cervexa"
            val coll = if (isVideo)
                android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(coll, cv) ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    java.io.FileInputStream(src).use { it.copyTo(out) }
                }
            } finally {
                cv.clear()
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
        } else {
            val base = if (mime.startsWith("video"))
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            else
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            val dst = File(File(base, "Cervexa").apply { if (!exists()) mkdirs() }, src.name)
            java.io.FileInputStream(src).use { `in` ->
                java.io.FileOutputStream(dst).use { out -> `in`.copyTo(out) }
            }
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(dst.absolutePath),
                arrayOf(mime),
                null
            )
        }
        Toast.makeText(this, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
    }
}