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
import androidx.core.content.FileProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.idn.kmed.cervexa.R
import java.io.File

open class MediaPagerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var toolbar: MaterialToolbar

    private var chipIndex: com.google.android.material.chip.Chip? = null
    private var bottomShare: View? = null

    private lateinit var paths: ArrayList<String>
    private lateinit var types: ArrayList<String> // "IMAGE" / "VIDEO"
    private var startIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force orientation if requested
        requestedOrientation = if (intent.getBooleanExtra("forceLandscape", false)) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        setContentView(R.layout.activity_media_pager)

        toolbar = findViewById(R.id.toolbar)
        pager = findViewById(R.id.pager)
        chipIndex = findViewById(R.id.chipIndex)
        bottomShare = findViewById(R.id.bottomShare)

        // Landscape controls (kalau layout punya)
        findViewById<View>(R.id.btnShare)?.setOnClickListener { onShareClick() }
        findViewById<View?>(R.id.btnBackLite)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View?>(R.id.btnExitLandscap)?.setOnClickListener {
            // hanya ganti orientasi; jika layout berbeda portrait/landscape,
            // sistem biasanya akan recreate activity sesuai config.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        bottomShare?.setOnClickListener { onShareClick() }

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

        // Rounded top sheet
        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)

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
                // FIX: ini sebelumnya pakai `this?.fillColor` (compile error)
                fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
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
            )
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemTg).setOnClickListener {
            shareToAppOrToast(
                packages = arrayOf("org.telegram.messenger"),
                appLabel = "Telegram",
                file = file,
                mime = mime,
                loosenMediaMime = true
            )
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemEmail).setOnClickListener {
            shareToAppOrToast(
                packages = arrayOf("com.google.android.gm"),
                appLabel = "Gmail",
                file = file,
                mime = mime,
                loosenMediaMime = false
            )
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

        dialog.show()
    }

    private fun fileUriForShare(f: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

    /** Cek paket yang terpasang pertama dari daftar */
    private fun resolveFirstInstalled(vararg pkgs: String): String? {
        val pm = packageManager
        return pkgs.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }
    }

    /**
     * Share ke app tertentu (by package). Kalau tidak terpasang → Toast "X belum terpasang".
     */
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
            Toast.makeText(this, "Tidak dapat membuka $appLabel", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    private fun exportToGallery(src: File, mime: String) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val isVideo = mime.startsWith("video")
            val rel = if (isVideo) android.os.Environment.DIRECTORY_MOVIES + "/Cervexa"
            else android.os.Environment.DIRECTORY_PICTURES + "/Cervexa"

            val coll =
                if (isVideo) android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
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
                cv.clear()
                cv.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
            Toast.makeText(this, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
        } else {
            val base = if (mime.startsWith("video"))
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            else
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)

            val dst = File(File(base, "Cervexa").apply { if (!exists()) mkdirs() }, src.name)

            java.io.FileInputStream(src).use { input ->
                java.io.FileOutputStream(dst).use { out -> input.copyTo(out) }
            }

            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(dst.absolutePath),
                arrayOf(mime),
                null
            )
            Toast.makeText(this, "Disimpan ke galeri", Toast.LENGTH_SHORT).show()
        }
    }
}