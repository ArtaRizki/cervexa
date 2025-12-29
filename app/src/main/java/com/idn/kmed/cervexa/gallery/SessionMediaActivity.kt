package com.idn.kmed.cervexa.gallery

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.scale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import com.idn.kmed.cervexa.utils.ThumbAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private data class VideoPreview(val frame: Bitmap?, val durationText: String)

class SessionMediaActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvDate: TextView
    private lateinit var adapter: ThumbAdapter

    private lateinit var sessionDir: File
    private lateinit var items: List<MediaItem>
    private var selectionMode = false

    private var patientNameExtra: String? = null
    private var patientNikExtra: String? = null
    private var patientRsExtra: String? = null
    private var patientDobUtcExtra: Long? = null
    private var patientNrmExtra: String? = null
    private var dateStrExtra: String? = null

    private lateinit var titleNormal: String

    private lateinit var bottomBar: View
    private lateinit var btnDeleteBottom: View
    private lateinit var tvBtnDelete: View
    private lateinit var btnShareBottom: View
    private lateinit var tvBtnShare: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_media)

        onBackPressedDispatcher.addCallback(this) {
            if (selectionMode) {
                // kalau sedang mode pilih → keluar mode pilih saja
                enterSelectionMode(false)
            } else {
                finish()
            }
        }

        toolbar = findViewById(R.id.toolbar)
        rv = findViewById(R.id.rvSessionMedia)
        tvDate = findViewById(R.id.tvDate)

        bottomBar = findViewById(R.id.bottomActionBar)
        btnDeleteBottom = findViewById(R.id.btnDeleteBottom)
        btnDeleteBottom.setOnClickListener { confirmAndDeleteSelected() }
        tvBtnDelete = findViewById(R.id.tvBtnDelete)
        btnShareBottom = findViewById(R.id.btnShareBottom)
        btnShareBottom.setOnClickListener { confirmAndShareSelected() }
        tvBtnShare = findViewById(R.id.tvBtnShare)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // extras dari list sesi
        val p = intent.getStringExtra("sessionDirPath") ?: run { finish(); return }
        val patientName = intent.getStringExtra("patientName").orEmpty()
        val dateStr = intent.getStringExtra("dateStr").orEmpty()
        patientNameExtra = intent.getStringExtra("patientName")
        patientNikExtra  = intent.getStringExtra("patientNik")
        patientRsExtra   = intent.getStringExtra("patientRs")
        patientNrmExtra  = intent.getStringExtra("patientNrm")
        patientDobUtcExtra = intent.getLongExtra("patientDobUtc", -1L).takeIf { it > 0 }
        dateStrExtra = intent.getStringExtra("dateStr")

        sessionDir = File(p)
        toolbar.title = patientName.ifBlank { sessionDir.name }
        titleNormal = toolbar.title?.toString().orEmpty()
        tvDate.text = dateStr.ifBlank { sessionDir.parentFile?.name ?: "" }

        // === Reuse persis seperti VideoFragment ===
        adapter = ThumbAdapter { item, index ->
            if (selectionMode) {
                // saat mode pilih: tap = toggle select
                adapter.toggleSelectPublic(item)
            } else {
                // normal: buka pager
                val paths = ArrayList(items.map { it.file.absolutePath })
                val types = ArrayList(items.map { it.type.name })
                startActivity(Intent(this, MediaPagerActivity::class.java).apply {
                    putStringArrayListExtra("paths", paths)
                    putStringArrayListExtra("types", types)
                    putExtra("index", index)
                })
            }
        }

        rv.layoutManager = GridLayoutManager(this, 4) // sama seperti VideoFragment
//        rv.setHasFixedSize(true)
        adapter.onStartSelectionRequested = {
            if (!selectionMode) enterSelectionMode(true)  // ini fungsi yang sudah kamu punya
        }
        rv.adapter = adapter
//        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
//            private val space = resources.displayMetrics.density * 8 // 8dp
//            override fun getItemOffsets(outRect: Rect, v: View, parent: RecyclerView, state: RecyclerView.State) {
//                outRect.set(space.toInt(), space.toInt(), space.toInt(), space.toInt())
//            }
//        })

        // dengarkan perubahan jumlah terpilih supaya update judul & menu
        adapter.selectionListener = object : ThumbAdapter.SelectionListener {
            override fun onSelectionChanged(count: Int) {
                if (selectionMode) toolbar.title = "$count dipilih"
                bottomBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
                btnDeleteBottom.isEnabled = count > 0
                btnDeleteBottom.alpha = if (count > 0) 1f else 0.4f
                tvBtnDelete.isEnabled = count > 0
                tvBtnDelete.alpha = if (count > 0) 1f else 0.4f

                btnShareBottom.isEnabled = count > 0
                btnShareBottom.alpha = if (count > 0) 1f else 0.4f
                tvBtnShare.isEnabled = count > 0
                tvBtnShare.alpha = if (count > 0) 1f else 0.4f
                invalidateOptionsMenu()
            }
        }

        items = loadSessionMedia(sessionDir)
        adapter.setSelectionMode(false) // sama seperti list thumb di VideoFragment (non-pilih)
        adapter.submitList(items)

    }

    // ==== MENU ====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_session_media, menu)
        return true
    }

    // ==== MENU ====
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select -> { enterSelectionMode(!selectionMode); true }
            R.id.action_delete -> { confirmAndDeleteSelected(); true }
            R.id.action_info   -> { showPatientInfoSheet(); true }
            R.id.action_delete_session -> { confirmAndDeleteSession(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

//    override fun onOptionsItemSelected(item: MenuItem): Boolean {
//        return when (item.itemId) {
//            R.id.action_select -> {
//                enterSelectionMode(!selectionMode)   // toggle
//                true
//            }
//            R.id.action_delete -> {
//                confirmAndDeleteSelected()
//                true
//            }
//            else -> super.onOptionsItemSelected(item)
//        }
//    }

    private fun showPatientInfoSheet() {
        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog)
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)

        // ---- Rounded top programatik (jalan di minSdk 25) ----
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val radius = resources.getDimension(R.dimen.bs_top_radius) // mis. 16dp (lihat dimens di bawah)
                val shape = MaterialShapeDrawable(
                    ShapeAppearanceModel.Builder()
                        .setTopLeftCorner(CornerFamily.ROUNDED, radius)
                        .setTopRightCorner(CornerFamily.ROUNDED, radius)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(Color.WHITE)
                    elevation = sheet.elevation
                }
                sheet.background = shape
            }
        }

        // tutup
        v.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }

        // view refs
        val tvTanggal = v.findViewById<TextView>(R.id.tvTanggal)
        val tvNama    = v.findViewById<TextView>(R.id.tvNama)
        val tvNik     = v.findViewById<TextView>(R.id.tvNik)
        val tvDob     = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm     = v.findViewById<TextView>(R.id.tvNrm)

        // --- ambil data dari extras / session.json / nama folder ---
        val meta = readSessionMeta()

        val nama = patientNameExtra ?: meta.name
        val nik  = patientNikExtra  ?: meta.nik
        val rs   = patientRsExtra   ?: meta.rs
        val nrm  = patientNrmExtra  ?: meta.nrm
        val patientDobUtc = patientDobUtcExtra ?: meta.dobUtc
        val tanggalUi = buildTanggalUi(meta.createdAt) //Karna menggunakan jam

        // isi UI
        tvTanggal.text = tanggalUi
        tvNama.text = nama.orEmpty().ifBlank { "—" }.plus(" (${rs})")
        tvNik.text  = nik.orEmpty().ifBlank { "—" }
        patientDobUtc?.let {
            tvDob.text  =  if (it > 0L) {
                val sdfDob = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("id","ID"))
                sdfDob.format(java.util.Date(patientDobUtc))
            } else "-"
        }
        tvNrm.text  = nrm.orEmpty().ifBlank { "Tidak ada nomor rekam medis" }

        dialog.show()
    }

    private fun showConfirmDeleteSheet(
        message: String,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(
            this,
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_confirm_delete, null)
        dialog.setContentView(v)

        // Rounded top (minSdk 25 OK)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.background = MaterialShapeDrawable(
                ShapeAppearanceModel.Builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, resources.getDimension(R.dimen.bs_top_radius))
                    .setTopRightCorner(CornerFamily.ROUNDED, resources.getDimension(R.dimen.bs_top_radius))
                    .build()
            ).apply {
                this?.fillColor = ColorStateList.valueOf(Color.WHITE)
                this?.elevation = sheet?.elevation ?: 0f
            }
        }

        v.findViewById<TextView>(R.id.tvMessage)?.text = message
        v.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        v.findViewById<View>(R.id.btnDelete)?.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.setCancelable(true)
        dialog.show()
    }


    private fun confirmAndDeleteSession() {
        showConfirmDeleteSheet(
            message = "Anda akan menghapus media, konfirmasi?",
            onConfirm = {
                if (deleteSessionDir(sessionDir)) finish()
                else Toast.makeText(this, "Gagal menghapus media", Toast.LENGTH_SHORT).show()
            }
            // tidak perlu onCancel khusus di sini
        )
    }

    private fun deleteSessionDir(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return false
        return runCatching {
            dir.walkBottomUp().forEach { it.delete() }
            // kalau mau, media scanner boleh di-skip karena kamu memang tidak ingin masuk galeri sistem
            true
        }.getOrElse { false }
    }

    /** Format DOB UTC millis → dd/MM/yyyy */
    private fun formatDob(utcMs: Long): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale("id","ID")).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(utcMs))
    }

    /** Format dari meta.createdAt → "yyyy-MM-dd, HH:mm". */
    private fun buildTanggalUi(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return ""

        // Jika format timestamp file: yyyyMMdd_HHmmss
        val tsPattern = Regex("^\\d{8}_\\d{6}$") // contoh: 20250826_181943
        if (tsPattern.matches(createdAt)) {
            return try {
                val inFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val d = inFmt.parse(createdAt)
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d!!)
            } catch (_: Exception) {
                createdAt
            }
        }

        // Kalau angka semua → epoch millis
        if (createdAt.all { it.isDigit() }) {
            return try {
                val d = Date(createdAt.toLong())
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d)
            } catch (_: Exception) { createdAt }
        }

        // Coba format umum lain
        val parsers = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd"
        )
        for (p in parsers) {
            try {
                val d = SimpleDateFormat(p, Locale.US).parse(createdAt)
                if (d != null) {
                    return SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d)
                }
            } catch (_: Exception) {}
        }

        return createdAt // fallback
    }

    /** Baca session.json (jika ada) lalu fallback dari nama folder "NIK_NAMA_USIA" */
    private fun readSessionMeta(): SessionMeta {
        // 1) JSON
        runCatching {
            val jsonFile = File(sessionDir, "session.json")
            if (jsonFile.exists()) {
                val o = JSONObject(jsonFile.readText())
                return SessionMeta(
                    name = o.optString("nama", null),
                    nik = o.optString("nik", null),
                    rs  = o.optString("rs", null),
                    nrm = o.optString("nrm", null),
                    dobUtc = o.optLong("dob_utc", -1L).takeIf { it > 0 },
                    createdAt = o.optString("created_at", sessionDir.parentFile?.name)
                )
            }
        }
        // 2) Parse nama folder pasien → "NIK_NAMA_USIA"
        val dateDir = sessionDir.parentFile
        val folder = sessionDir.name
        val parts = folder.split("_")
        val nik = parts.getOrNull(0)
        val name = parts.drop(1).dropLast(1).joinToString(" ")
            .replace('_',' ')
            .trim()
            .ifBlank { null }
        return SessionMeta(name = name, nik = nik, nrm = null, dobUtc = null, createdAt = dateDir?.name)
    }

    private data class SessionMeta(
        val name: String? = null,
        val nik: String? = null,
        val rs:  String? = null,
        val nrm: String? = null,
        val dobUtc: Long? = null,
        val createdAt: String? = null
    )

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val select = menu.findItem(R.id.action_select)
        val delete = menu.findItem(R.id.action_delete)
        val deleteSession = menu.findItem(R.id.action_delete_session)

        val selectedCount = adapter.getSelectedItems().size

        // Saat mode pilih: tombol hapus di toolbar disembunyikan (pakai bottom bar)
        delete.isVisible = false
        // Hapus sesi juga disembunyikan saat memilih
        deleteSession.isVisible = !selectionMode

        select.isVisible = true
        select.title = if (selectionMode) "Batal" else "Pilih"

        return super.onPrepareOptionsMenu(menu)
    }

    private fun enterSelectionMode(enable: Boolean) {
        selectionMode = enable
        adapter.setSelectionMode(enable)
        toolbar.title = if (enable) "0 dipilih" else titleNormal
        bottomBar.visibility = if (enable) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun showShareSheetBulk(files: List<File>) {
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

//        // actions
//        v.findViewById<LinearLayout>(R.id.itemWa).setOnClickListener {
//            shareToAppOrToast(
//                packages = arrayOf("com.whatsapp", "com.whatsapp.w4b"),
//                appLabel = "WhatsApp",
//                file = file,
//                mime = mime,
//                loosenMediaMime = true
//            );
//            dialog.dismiss()
//        }
//        v.findViewById<LinearLayout>(R.id.itemTg).setOnClickListener {
//            shareToAppOrToast(
//                packages = arrayOf("org.telegram.messenger"),
//                appLabel = "Telegram",
//                file = file,
//                mime = mime,
//                loosenMediaMime = true
//            );
//            dialog.dismiss()
//        }
//        v.findViewById<LinearLayout>(R.id.itemEmail).setOnClickListener {
//            shareToAppOrToast(
//                packages = arrayOf("com.google.android.gm"),
//                appLabel = "Gmail",
//                file = file,
//                mime = mime,
//                loosenMediaMime = false // biarkan mime asli; Gmail okay
//            );
//            dialog.dismiss()
//        }
        v.findViewById<LinearLayout>(R.id.itemCloud).setOnClickListener {
            Toast.makeText(this, "Dalam Pengembangan", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        v.findViewById<LinearLayout>(R.id.itemSave).setOnClickListener {
            lifecycleScope.launch {
                try {
                    val saved = exportManyToGallery(
                        files = files,
                        albumName = "Cervexa"
                    ) { cur, tot, _ ->
                        // kalau mau progress detail:
                        // progress.isIndeterminate = false
                        // progress.max = tot
                        // progress.progress = cur
                    }
                    Toast.makeText(this@SessionMediaActivity,
                        "Tersimpan: ${saved.size}/${files.size}", Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    enterSelectionMode(false)
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun fileUriForShare(f: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)

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

    private fun confirmAndShareSelected(){
        val filesToSave = adapter.getSelectedItems()
        showShareSheetBulk(filesToSave)
    }

    /** Cek paket yang terpasang pertama dari daftar */
    private fun resolveFirstInstalled(vararg pkgs: String): String? {
        val pm = packageManager
        return pkgs.firstOrNull { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }
    }

    private fun confirmAndDeleteSelected() {
        val selectedFiles = adapter.getSelectedItems()
        if (selectedFiles.isEmpty()) return

        showConfirmDeleteSheet(
            message = "Anda akan menghapus ${selectedFiles.size} media, konfirmasi?",
            onConfirm = {
                selectedFiles.forEach { runCatching { it.delete() } }
                items = loadSessionMedia(sessionDir)
                adapter.submitList(items)
                // keluar mode pilih setelah selesai
                enterSelectionMode(false)
                if (items.isEmpty()) finish()
            },
            onCancel = {
                // batal → juga keluar dari mode pilih (sesuai permintaanmu)
                enterSelectionMode(false)
            }
        )
    }

    private suspend fun exportManyToGallery(
        files: List<File>,
        albumName: String = "Cervexa",
        onProgress: (current: Int, total: Int, uri: Uri?) -> Unit = { _, _, _ -> }
    ): List<Uri> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Uri>()
        val resolver = contentResolver
        val total = files.size

        fun guessMime(f: File): String = when (f.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            else          -> "application/octet-stream"
        }

        suspend fun uniqueDisplayName(collection: Uri, relPath: String, baseName: String): String {
            if (Build.VERSION.SDK_INT < 29) return baseName
            val name = baseName.substringBeforeLast('.')
            val ext  = baseName.substringAfterLast('.', "")
            var candidate = baseName
            var i = 1
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            while (true) {
                resolver.query(collection, projection, selection, arrayOf(relPath, candidate), null)
                    ?.use { c -> if (!c.moveToFirst()) return candidate }
                candidate = if (ext.isNotEmpty()) "${name}_$i.$ext" else "${name}_$i"
                i++
            }
        }

        files.forEachIndexed { index, src ->
            val mime = guessMime(src)
            val isVideo = mime.startsWith("video")
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val relPath = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
                            "/$albumName/"
                    val collection = if (isVideo)
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                    val finalName = uniqueDisplayName(collection, relPath, src.name)
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }
                    val uri = resolver.insert(collection, cv) ?: throw IllegalStateException("insert null")
                    try {
                        resolver.openOutputStream(uri)?.use { outStream ->
                            FileInputStream(src).channel.use { inCh ->
                                (outStream as FileOutputStream).channel.use { outCh ->
                                    var pos = 0L
                                    val size = inCh.size()
                                    while (pos < size) {
                                        pos += inCh.transferTo(pos, size - pos, outCh)
                                    }
                                    outCh.force(true)
                                }
                            }
                        } ?: throw IllegalStateException("openOutputStream null")
                        // finalisasi
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        resolver.update(uri, done, null, null)
                        out += uri
                        onProgress(index + 1, total, uri)
                    } catch (e: Exception) {
                        // bersihkan entry yang gagal
                        runCatching { resolver.delete(uri, null, null) }
                        throw e
                    }
                } else {
                    // Pre-Q
                    val pubDir = if (isVideo)
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    else
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val dstDir = File(pubDir, albumName).apply { if (!exists()) mkdirs() }
                    val dst = File(dstDir, src.name)
                    FileInputStream(src).use { input ->
                        FileOutputStream(dst).use { output -> input.copyTo(output) }
                    }
                    MediaScannerConnection.scanFile(
                        this@SessionMediaActivity, arrayOf(dst.absolutePath), arrayOf(mime), null
                    )
                    // Build Uri untuk feedback (best-effort)
                    val uri = Uri.fromFile(dst)
                    out += uri
                    onProgress(index + 1, total, uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onProgress(index + 1, total, null)
            }
        }
        out
    }

    private fun loadSessionMedia(dir: File): List<MediaItem> {
        val imgs = File(dir, "Snapshots").listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.map { MediaItem(it, MediaType.IMAGE) }.orElseEmpty()
        val vids = File(dir, "Video").listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.map { MediaItem(it, MediaType.VIDEO) }.orElseEmpty()
        return (imgs + vids).sortedByDescending { it.file.lastModified() }
    }

    private fun <T> List<T>?.orElseEmpty(): List<T> = this ?: emptyList()
}