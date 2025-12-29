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
    private lateinit var types: ArrayList<String> // "IMAGE" / "VIDEO"
    private var startIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("forceLandscape", false)) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            // biarkan default (boleh portrait)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        setContentView(R.layout.activity_media_pager)

        toolbar = findViewById(R.id.toolbar)
        pager = findViewById(R.id.pager)
        chipIndex = findViewById(R.id.chipIndex)
        bottomShare = findViewById(R.id.bottomShare)
        //Landscap
        findViewById<View>(R.id.bottomShare)?.setOnClickListener { onShareClick() }
        findViewById<View>(R.id.btnBackLite)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.btnExitLandscap)?.setOnClickListener { this.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

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
        showShareSheet(f, mime)   // fungsi di bawah
    }

    private fun updateUiForPosition(position: Int) {
        chipIndex?.text = "${position + 1}/${paths.size}"
        toolbar.title = File(paths[position]).name
    }

    private fun shareCurrent() {
        val pos = pager.currentItem
        if (pos !in paths.indices) return

        val file = File(paths[pos])
        if (!file.exists()) {
            Toast.makeText(this, "File tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }
        val type = types.getOrNull(pos) ?: "IMAGE"
        val mime = if (type == "VIDEO") "video/mp4" else "image/jpeg"

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("shared", uri)
        }

        val chooser = Intent.createChooser(send, "Bagikan").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // (opsional) bawa clipData juga
            clipData = android.content.ClipData.newRawUri("shared", uri)
        }

        startActivity(chooser)
    }

    private fun showShareSheet(file: File, mime: String) {
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
        val v = layoutInflater.inflate(R.layout.bs_share_media, null)
        dialog.setContentView(v)

        // rounded top
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.background = MaterialShapeDrawable(
                ShapeAppearanceModel.Builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, resources.getDimension(R.dimen.bs_top_radius))
                    .setTopRightCorner(CornerFamily.ROUNDED, resources.getDimension(R.dimen.bs_top_radius))
                    .build()
            ).apply { this?.fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE) }
        }

        v.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        // actions
        v.findViewById<LinearLayout>(R.id.itemWa).setOnClickListener {
            shareToAppOrToast(
                packages = arrayOf("com.whatsapp", "com.whatsapp.w4b"),
                appLabel = "WhatsApp",
                file = file,
                mime = mime,
                loosenMediaMime = true
            );
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemTg).setOnClickListener {
            shareToAppOrToast(
                packages = arrayOf("org.telegram.messenger"),
                appLabel = "Telegram",
                file = file,
                mime = mime,
                loosenMediaMime = true
            );
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemEmail).setOnClickListener {
            shareToAppOrToast(
                packages = arrayOf("com.google.android.gm"),
                appLabel = "Gmail",
                file = file,
                mime = mime,
                loosenMediaMime = false // biarkan mime asli; Gmail okay
            );
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemCloud).setOnClickListener {
            Toast.makeText(this, "Dalam Pengembangan", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemSave).setOnClickListener {
            exportToGallery(file, mime) // kalau kamu ingin simpan ke Galeri
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun fileUriForShare(f: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    private fun shareToPackage(file: File, mime: String, vararg packages: String, fallbackChooser: Boolean = true) {
        val uri = fileUriForShare(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // pilih paket pertama yang terpasang
        val pm = packageManager
        val pkg = packages.firstOrNull { p ->
            try { pm.getPackageInfo(p, 0); true } catch (_: Exception) { false }
        }
        if (pkg != null) {
            send.`package` = pkg
            startActivity(send)
        } else if (fallbackChooser) {
            startActivity(Intent.createChooser(send, "Bagikan via"))
        } else {
            Toast.makeText(this, "Aplikasi tidak terpasang", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareToEmail(file: File, mime: String) {
        val uri = fileUriForShare(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Kirim email"))
    }

    /** Cek paket yang terpasang pertama dari daftar */
    private fun resolveFirstInstalled(vararg pkgs: String): String? {
        val pm = packageManager
        return pkgs.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }
    }

// **
// * Share ke app tertentu (by package). Kalau tidak terpasang → Toast "X belum terpasang".
// *
// * @param packages daftar kandidat package (urutkan dari prioritas tertinggi)
// * @param appLabel label untuk toast (mis. "WhatsApp", "Telegram", "Gmail")
// * @param file file yang akan dibagikan
// * @param mime mime asli (mis. image/jpeg atau video/mp4)
// * @param loosenMediaMime jika true dan mime image/video → pakai wildcard image/* / video/* (lebih kompatibel)
// **

    private fun shareToAppOrToast(
        packages: Array<String>,
        appLabel: String,
        file: File,
        mime: String,
        loosenMediaMime: Boolean = true
    ) {
        val targetPkg = resolveFirstInstalled(*packages)
        if (targetPkg == null) {
            Toast.makeText(this, "$appLabel belum terpasang", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = fileUriForShare(file)

        val finalMime = if (loosenMediaMime && (mime.startsWith("image") || mime.startsWith("video"))) {
            if (mime.startsWith("image")) "image/*" else "video/*"
        } else mime

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = finalMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // beberapa OEM butuh ClipData supaya izin ikut
            clipData = ClipData.newUri(contentResolver, "media", uri)
            `package` = targetPkg
        }

        // Grant eksplisit (beberapa device wajib)
        runCatching { grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        // Pastikan memang ada activity yang handle
        val canHandle = packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        if (!canHandle) {
            Toast.makeText(this, "Tidak dapat membuka $appLabel", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

//    /** Intent share standar ke package tertentu */
//    private fun shareToSpecificApp(file: File, mime: String, targetPackage: String): Boolean {
//        val uri = fileUriForShare(file)
//        val intent = Intent(Intent.ACTION_SEND).apply {
//            type = mime
//            putExtra(Intent.EXTRA_STREAM, uri)
//            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//            // tambahkan ClipData agar permission ikut untuk beberapa OEM
//            clipData = android.content.ClipData.newRawUri(file.name, uri)
//            `package` = targetPackage
//        }
//        return runCatching { startActivity(intent) }.isSuccess
//    }
//
//    /** WhatsApp (consumer / business) */
//    private fun shareToWhatsApp(file: File, mime: String) {
//        val uri = fileUriForShare(file)
//
//        // Prioritaskan WA personal lalu WA Business
//        val targetPkg = resolveFirstInstalled("com.whatsapp", "com.whatsapp.w4b")
//            ?: return Toast.makeText(this, "WhatsApp tidak terpasang", Toast.LENGTH_SHORT).show()
//
//        // WA lebih toleran kalau type = image/* atau video/* (bukan fixed jpeg/mp4)
//        val waMime = when {
//            mime.startsWith("image") -> "image/*"
//            mime.startsWith("video") -> "video/*"
//            else -> mime
//        }
//
//        val send = Intent(Intent.ACTION_SEND).apply {
//            type = waMime
//            putExtra(Intent.EXTRA_STREAM, uri)
//            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//            // beberapa OEM butuh ClipData agar permission benar-benar ikut
//            clipData = android.content.ClipData.newUri(contentResolver, "media", uri)
//            `package` = targetPkg
//        }
//
//        // Pastikan ada activity yang bisa handle intent ini
//        val canHandle = packageManager.queryIntentActivities(send, 0).isNotEmpty()
//        if (!canHandle) {
//            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        // Grant permission eksplisit ke paket tujuan (beberapa device wajib)
//        try {
//            grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
//        } catch (_: Exception) { /* aman diabaikan */ }
//
//        try {
//            startActivity(send)
//        } catch (_: Exception) {
//            // fallback chooser kalau ada constraint aneh di device
//            startActivity(Intent.createChooser(send, "Bagikan via"))
//        }
//    }
//
//    /** Telegram */
//    private fun shareToTelegram(file: File, mime: String) {
//        val pkg = resolveFirstInstalled("org.telegram.messenger")
//        if (pkg != null && shareToSpecificApp(file, mime, pkg)) return
//        startActivity(Intent.createChooser(
//            Intent(Intent.ACTION_SEND).apply {
//                type = mime; putExtra(Intent.EXTRA_STREAM, fileUriForShare(file))
//                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//            }, "Bagikan via"))
//    }
//
//    /** Email → prioritaskan Gmail; kalau tidak ada, batasi ke app email saja */
//    private fun shareToGmail(file: File, mime: String) {
//        val uri = fileUriForShare(file)
//        val gmail = resolveFirstInstalled("com.google.android.gm")
//        if (gmail != null) {
//            val i = Intent(Intent.ACTION_SEND).apply {
//                type = mime
//                putExtra(Intent.EXTRA_STREAM, uri)
//                putExtra(Intent.EXTRA_SUBJECT, File(uri.path ?: "").name)
//                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                clipData = android.content.ClipData.newRawUri("attachment", uri)
//                `package` = gmail
//            }
//            runCatching { startActivity(i) }.onFailure {
//                // fallback ke selector email-only
//                shareEmailWithSelector(uri, mime)
//            }
//        } else {
//            shareEmailWithSelector(uri, mime)
//        }
//    }
//
//    /** Selector email-only (membatasi agar hanya aplikasi email yang muncul) */
//    private fun shareEmailWithSelector(uri: Uri, mime: String) {
//        val selector = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:") }
//        val i = Intent(Intent.ACTION_SEND).apply {
//            type = mime
//            putExtra(Intent.EXTRA_STREAM, uri)
//            putExtra(Intent.EXTRA_SUBJECT, File(uri.path ?: "").name)
//            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//            clipData = android.content.ClipData.newRawUri("attachment", uri)
//            setSelector(selector)
//        }
//        startActivity(i)
//    }


    private fun exportToGallery(src: File, mime: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val isVideo = mime.startsWith("video")
            val rel = if (isVideo) android.os.Environment.DIRECTORY_MOVIES + "/Cervexa"
            else android.os.Environment.DIRECTORY_PICTURES + "/Cervexa"
            val coll = if (isVideo) android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
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
                cv.clear(); cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
            Toast.makeText(this, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
        } else {
            val base = if (mime.startsWith("video"))
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            else
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            val dst = File(File(base, "Cervexa").apply { if (!exists()) mkdirs() }, src.name)
            java.io.FileInputStream(src).use { `in` ->
                java.io.FileOutputStream(dst).use { out -> `in`.copyTo(out) }
            }
            android.media.MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), arrayOf(mime), null)
            Toast.makeText(this, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
        }
    }
}