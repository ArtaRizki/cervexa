package com.idn.kmed.cervexa.gallery

import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.idn.kmed.cervexa.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.idn.kmed.cervexa.utils.PrintHelper
import java.io.File
import androidx.core.content.FileProvider
import com.idn.kmed.cervexa.utils.MediaType
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.CornerFamily

open class MediaPagerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var toolbar: MaterialToolbar
    private var chipIndex: Chip? = null
    private lateinit var bottomShare: View
    private lateinit var btnBackLite: View

    private lateinit var paths: ArrayList<String>
    private lateinit var types: ArrayList<String>
    private var startIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forceLandscape = intent.getBooleanExtra("forceLandscape", false)

        if (forceLandscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        setContentView(R.layout.activity_media_pager)

        toolbar = findViewById(R.id.toolbar)
        pager = findViewById(R.id.pager)
        chipIndex = findViewById(R.id.chipIndex)
        bottomShare = findViewById(R.id.bottomShare)

        findViewById<View>(R.id.bottomShare)?.setOnClickListener { onShareClick() }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // ============================================================
        // btnBackLite dan btnExitLandscap:
        // - Di TV (forceLandscape = true): GONE — tidak ada back stack
        //   yang benar di TV, dan tombol ini tidak relevan
        // - Di phone/tablet (forceLandscape = false): VISIBLE — normal
        // ============================================================
        val navVisibility = if (forceLandscape) View.GONE else View.VISIBLE

        findViewById<View>(R.id.btnBackLite)?.let { btn ->
            btn.visibility = navVisibility
            btn.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

//        findViewById<View>(R.id.btnExitLandscap)?.let { btn ->
//            btn.visibility = navVisibility
//            btn.setOnClickListener {
//                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
//            }
//        }

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

        // Cetak Data Pasien (PDF ringkasan, tanpa media)
        v.findViewById<LinearLayout>(R.id.itemPrintPatient)?.setOnClickListener {
            dialog.dismiss()
            // Di MediaPagerActivity tidak ada data pasien lengkap — buka chooser share PDF
            val outFile = File(cacheDir, "cervexa_media_${System.currentTimeMillis()}.pdf")
            PrintHelper.sharePdf(this, outFile.takeIf { it.exists() } ?: file, appLabel = "")
        }

        // Unduh / cetak — share PDF ke aplikasi lain
        v.findViewById<LinearLayout>(R.id.itemPrintSession)?.setOnClickListener {
            dialog.dismiss()
            // Langsung share file aslinya via chooser (PDF share dari context galeri)
            val pdfFile = File(cacheDir, "cervexa_media_${System.currentTimeMillis()}.pdf")
            PrintHelper.printPdf(this, file, "Cervexa Media")
        }

        dialog.show()
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