package com.idn.kmed.cervexa.gallery

// ─── Perubahan vs versi lama ───────────────────────────────────────────────
//  1. Ganti ThumbAdapter  → SectionedThumbAdapter
//  2. items bertipe List<MediaItem>  → sectionedItems bertipe List<SectionedMediaItem>
//  3. GridLayoutManager pakai spanSizeLookup supaya header full-width
//  4. buildSectionedList() mengelompokkan media per tanggal & menyisipkan Header
//  5. Semua call ke adapter.submitList / getSelectedItems disesuaikan
// ──────────────────────────────────────────────────────────────────────────

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.utils.MediaItem
import com.idn.kmed.cervexa.utils.MediaType
import com.idn.kmed.cervexa.utils.SectionedMediaItem
import com.idn.kmed.cervexa.utils.SectionedThumbAdapter
import com.idn.kmed.cervexa.utils.PdfReportHelper
import com.idn.kmed.cervexa.utils.PrintHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.activity.addCallback

class SessionMediaActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var toolbar: MaterialToolbar

    // ← GANTI: pakai SectionedThumbAdapter
    private lateinit var adapter: SectionedThumbAdapter

    private lateinit var sessionDir: File

    // Data mentah (MediaItem), perlu disimpan untuk reload setelah hapus
    private var rawItems: List<MediaItem> = emptyList()

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
    private var allSessionPaths: ArrayList<String>? = null

    private companion object {
        const val SPAN = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_media)

        onBackPressedDispatcher.addCallback(this) {
            if (selectionMode) enterSelectionMode(false) else finish()
        }

        toolbar = findViewById(R.id.toolbar)
        rv = findViewById(R.id.rvSessionMedia)

        bottomBar = findViewById(R.id.bottomActionBar)
        btnDeleteBottom = findViewById(R.id.btnDeleteBottom)
        tvBtnDelete = findViewById(R.id.tvBtnDelete)
        btnShareBottom = findViewById(R.id.btnShareBottom)
        tvBtnShare = findViewById(R.id.tvBtnShare)

        btnDeleteBottom.setOnClickListener { confirmAndDeleteSelected() }
        btnShareBottom.setOnClickListener { confirmAndShareSelected() }

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // --- extras ---
        val p = intent.getStringExtra("sessionDirPath") ?: run { finish(); return }
        allSessionPaths = intent.getStringArrayListExtra("allSessionPaths")
        val targetPaths = allSessionPaths ?: arrayListOf(p)
        val patientName = intent.getStringExtra("patientName").orEmpty()
        val dateStr = intent.getStringExtra("dateStr").orEmpty()

        patientNameExtra = intent.getStringExtra("patientName")
        patientNikExtra = intent.getStringExtra("patientNik")
        patientRsExtra = intent.getStringExtra("patientRs")
        patientNrmExtra = intent.getStringExtra("patientNrm")
        patientDobUtcExtra = intent.getLongExtra("patientDobUtc", -1L).takeIf { it > 0 }
        dateStrExtra = intent.getStringExtra("dateStr")

        sessionDir = File(p)
        toolbar.title = patientName.ifBlank { sessionDir.name }
        titleNormal = toolbar.title?.toString().orEmpty()

        // ─── Adapter (SectionedThumbAdapter) ───────────────────────────
        adapter = SectionedThumbAdapter(spanCount = SPAN) { item, index ->
            if (selectionMode) {
                adapter.toggleSelectPublic(item)
            } else {
                val allMedia = adapter.getAllMediaItems()
                val paths = ArrayList(allMedia.map { it.file.absolutePath })
                val types = ArrayList(allMedia.map { it.type.name })
                startActivity(Intent(this, MediaPagerActivity::class.java).apply {
                    putStringArrayListExtra("paths", paths)
                    putStringArrayListExtra("types", types)
                    putExtra("index", index)
                })
            }
        }

        adapter.onStartSelectionRequested = {
            if (!selectionMode) enterSelectionMode(true)
        }

        adapter.selectionListener = object : SectionedThumbAdapter.SelectionListener {
            override fun onSelectionChanged(count: Int) {
                if (selectionMode) toolbar.title = "$count dipilih"
                bottomBar.visibility = if (selectionMode) View.VISIBLE else View.GONE

                fun setEnabled(btn: View, label: View, enabled: Boolean) {
                    btn.isEnabled = enabled
                    btn.alpha = if (enabled) 1f else 0.4f
                    label.isEnabled = enabled
                    (label as? TextView)?.alpha = if (enabled) 1f else 0.4f
                }
                setEnabled(btnDeleteBottom, tvBtnDelete, count > 0)
                setEnabled(btnShareBottom, tvBtnShare, count > 0)
                invalidateOptionsMenu()
            }
        }

        // ─── LayoutManager dengan SpanSizeLookup ───────────────────────
        val glm = GridLayoutManager(this, SPAN)
        glm.spanSizeLookup = adapter.buildSpanSizeLookup()
        rv.layoutManager = glm
        rv.adapter = adapter

        // ─── Muat data & tampilkan terseksi ────────────────────────────
        rawItems = loadMultiSessionMedia(targetPaths)
        adapter.submitList(buildSectionedList(rawItems))
    }

    // =====================================================================
    //  Pengelompokan per tanggal
    // =====================================================================

    /**
     * Ambil tanggal efektif dari MediaItem.
     *
     * Prioritas:
     *  1. Parse timestamp dari nama file  → pola yyyyMMdd_HHmmss di mana saja dalam nama
     *  2. Fallback: file.lastModified()   → bisa tidak akurat jika file dicopy
     */
    private fun effectiveDateMs(item: MediaItem): Long {
        // Nama file contoh: ss_20250625_181943.jpg  /  vid_20260225_110500.mp4
        val pattern = Regex("""(\d{8})_(\d{6})""")
        val match = pattern.find(item.file.nameWithoutExtension)
        if (match != null) {
            return runCatching {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .parse("${match.groupValues[1]}_${match.groupValues[2]}")!!.time
            }.getOrElse { item.file.lastModified() }
        }
        return item.file.lastModified()
    }

    /**
     * Kelompokkan [items] berdasarkan tanggal efektif dari nama file,
     * urutkan grup terbaru dulu, lalu sisipkan [SectionedMediaItem.Header]
     * sebelum setiap kelompok.
     */
    private fun buildSectionedList(items: List<MediaItem>): List<SectionedMediaItem> {
        if (items.isEmpty()) return emptyList()

        val sdfKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        val sdfLabel = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID"))

        // Sort ulang berdasarkan tanggal efektif (bukan lastModified)
        val sorted = items.sortedByDescending { effectiveDateMs(it) }

        // Kelompokkan ke LinkedHashMap supaya urutan terjaga
        val grouped = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in sorted) {
            val key = sdfKey.format(Date(effectiveDateMs(item)))
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<SectionedMediaItem>()
        for ((key, group) in grouped) {
            val date = sdfKey.parse(key) ?: Date()
            val label = sdfLabel.format(date)
                .replaceFirstChar { it.uppercase() }
            result.add(SectionedMediaItem.Header(label))
            group.forEach { result.add(SectionedMediaItem.Media(it)) }
        }
        return result
    }

    // =====================================================================
    //  Menu
    // =====================================================================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_session_media, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select -> {
            enterSelectionMode(!selectionMode); true
        }

        R.id.action_delete -> {
            confirmAndDeleteSelected(); true
        }

        R.id.action_info -> {
            showPatientInfoSheet(); true
        }

        R.id.action_delete_session -> {
            confirmAndDeleteSession(); true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_delete)?.isVisible = false
        menu.findItem(R.id.action_delete_session)?.isVisible = !selectionMode
        menu.findItem(R.id.action_select)?.title = if (selectionMode) "Batal" else "Pilih"
        return super.onPrepareOptionsMenu(menu)
    }

    // =====================================================================
    //  Selection mode
    // =====================================================================

    private fun enterSelectionMode(enable: Boolean) {
        selectionMode = enable
        adapter.setSelectionMode(enable)
        toolbar.title = if (enable) "0 dipilih" else titleNormal
        bottomBar.visibility = if (enable) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    // =====================================================================
    //  Delete
    // =====================================================================

    private fun confirmAndDeleteSelected() {
        val selectedFiles = adapter.getSelectedItems()
        if (selectedFiles.isEmpty()) return

        showConfirmDeleteSheet(
            message = "Anda akan menghapus ${selectedFiles.size} media, konfirmasi?",
            onConfirm = {
                selectedFiles.forEach { runCatching { it.delete() } }
                rawItems =
                    loadMultiSessionMedia(allSessionPaths ?: arrayListOf(sessionDir.absolutePath))
                adapter.submitList(buildSectionedList(rawItems))
                enterSelectionMode(false)
                if (rawItems.isEmpty()) finish()
            },
            onCancel = { enterSelectionMode(false) }
        )
    }

    private fun confirmAndDeleteSession() {
        val count = allSessionPaths?.size ?: 1
        val msg = if (count > 1)
            "Hapus semua histori medis ($count sesi) pasien ini?"
        else
            "Anda akan menghapus media sesi ini, konfirmasi?"

        showConfirmDeleteSheet(
            message = msg,
            onConfirm = {
                val targets = allSessionPaths ?: listOf(sessionDir.absolutePath)
                val ok = targets.count { deleteSessionDir(File(it)) }
                if (ok > 0) {
                    Toast.makeText(this, "Berhasil menghapus data", Toast.LENGTH_SHORT)
                        .show(); finish()
                } else Toast.makeText(this, "Gagal menghapus media", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun deleteSessionDir(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return false
        return runCatching { dir.walkBottomUp().forEach { it.delete() }; true }.getOrElse { false }
    }

    // =====================================================================
    //  Share
    // =====================================================================

    private fun confirmAndShareSelected() = showShareSheetBulk(adapter.getSelectedItems())

    private fun showShareSheetBulk(files: List<File>) {
        val dialog = BottomSheetDialog(
            this,
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_share_media, null)
        dialog.setContentView(v)
        dialog.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        applyRoundedTop(dialog)

        v.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        v.findViewById<LinearLayout>(R.id.itemCloud).setOnClickListener {
            Toast.makeText(this, "Dalam Pengembangan", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Simpan ke galeri
        v.findViewById<LinearLayout>(R.id.itemSave).setOnClickListener {
            lifecycleScope.launch {
                val saved = exportManyToGallery(files, "Cervexa")
                Toast.makeText(
                    this@SessionMediaActivity,
                    "Tersimpan: ${saved.size}/${files.size}",
                    Toast.LENGTH_SHORT
                ).show()
                enterSelectionMode(false)
            }
            dialog.dismiss()
        }

        // Cetak Data Pasien (PDF ringkasan)
        v.findViewById<LinearLayout>(R.id.itemPrintPatient)?.setOnClickListener {
            dialog.dismiss()
            generateAndActionPdf(sessionOnly = false, download = false)
        }

        // Unduh PDF Sesi Lengkap
        v.findViewById<LinearLayout>(R.id.itemPrintSession)?.setOnClickListener {
            dialog.dismiss()
            generateAndActionPdf(sessionOnly = true, download = true)
        }

        dialog.show()
    }

    /**
     * Generate PDF dan print atau download.
     * @param sessionOnly  true = sertakan media dalam PDF
     * @param download     true = simpan ke Downloads, false = dialog cetak printer
     */
    private fun generateAndActionPdf(sessionOnly: Boolean, download: Boolean) {
        val meta = readSessionMeta()
        val ts = System.currentTimeMillis()
        val fname = if (sessionOnly) "cervexa_sesi_${ts}.pdf" else "cervexa_pasien_${ts}.pdf"
        val outFile = File(cacheDir, fname)

        val snaps = File(sessionDir, "Snapshots")
            .listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        val videos = File(sessionDir, "Video")
            .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.sortedBy { it.lastModified() } ?: emptyList()

        val nama = (patientNameExtra ?: meta.name).orEmpty().ifBlank { "—" }
        val nik = (patientNikExtra ?: meta.nik).orEmpty().ifBlank { "—" }
        val rs = (patientRsExtra ?: meta.rs).orEmpty().ifBlank { "—" }
        val nrm = (patientNrmExtra ?: meta.nrm)?.ifBlank { null }
        val dobUtcMs = (patientDobUtcExtra ?: meta.dobUtc)?.takeIf { it > 0L }

        lifecycleScope.launch(Dispatchers.IO) {
            val pdf = if (sessionOnly) {
                PdfReportHelper.generateSessionPdf(
                    outputFile = outFile,
                    nama = nama, nik = nik, hospitalName = rs,
                    nrm = nrm, dobUtcMs = dobUtcMs,
                    sessionId = -1, sessionCode = null,
                    startedAt = null, completedAt = null,
                    snapshotFiles = snaps,
                    videoFiles = videos
                )
            } else {
                PdfReportHelper.generatePatientPdf(
                    outputFile = outFile,
                    nama = nama, nik = nik, hospitalName = rs,
                    nrm = nrm, dobUtcMs = dobUtcMs,
                    sessionId = -1, sessionCode = null,
                    startedAt = null, completedAt = null,
                    snapshotCount = snaps.size,
                    videoCount = videos.size
                )
            }

            withContext(Dispatchers.Main) {
                if (pdf == null) {
                    Toast.makeText(
                        this@SessionMediaActivity,
                        "Gagal membuat PDF",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                if (download) {
                    val ok = PrintHelper.downloadPdf(this@SessionMediaActivity, pdf, fname)
                    Toast.makeText(
                        this@SessionMediaActivity,
                        if (ok) "PDF tersimpan di folder Downloads" else "Gagal menyimpan PDF",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val label = if (sessionOnly) "Sesi Pemeriksaan" else "Data Pasien"
                    PrintHelper.printPdf(this@SessionMediaActivity, pdf, "Cervexa — $label")
                }
            }
        }
    }

    // =====================================================================
    //  Info pasien (BottomSheet)
    // =====================================================================

    private fun showPatientInfoSheet() {
        val dialog = BottomSheetDialog(
            this,
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)
        applyRoundedTop(dialog)

        v.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }

        val meta = readSessionMeta()
        val tanggalUi = buildTanggalUi(meta.createdAt)
        val patientDobUtc = patientDobUtcExtra ?: meta.dobUtc

        v.findViewById<TextView>(R.id.tvTanggal)?.text = tanggalUi
        v.findViewById<TextView>(R.id.tvNama)?.text = (patientNameExtra ?: meta.name).orEmpty()
            .ifBlank { "—" } + " (${patientRsExtra ?: meta.rs})"
        v.findViewById<TextView>(R.id.tvNik)?.text =
            (patientNikExtra ?: meta.nik).orEmpty().ifBlank { "—" }
        patientDobUtc?.let {
            v.findViewById<TextView>(R.id.tvDob)?.text = if (it > 0L)
                SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).format(Date(it))
            else "-"
        }
        v.findViewById<TextView>(R.id.tvNrm)?.text =
            (patientNrmExtra ?: meta.nrm).orEmpty().ifBlank { "Tidak ada nomor rekam medis" }

        dialog.show()
    }

    // =====================================================================
    //  Confirm delete BottomSheet helper
    // =====================================================================

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
        applyRoundedTop(dialog)

        v.findViewById<TextView>(R.id.tvMessage)?.text = message
        v.findViewById<View>(R.id.btnCancel)
            ?.setOnClickListener { dialog.dismiss(); onCancel?.invoke() }
        v.findViewById<View>(R.id.btnDelete)?.setOnClickListener { dialog.dismiss(); onConfirm() }
        dialog.setCancelable(true)
        dialog.show()
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private fun applyRoundedTop(dialog: BottomSheetDialog) {
        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                    ?: return@setOnShowListener
            val radius = resources.getDimension(R.dimen.bs_top_radius)
            sheet.background = MaterialShapeDrawable(
                ShapeAppearanceModel.Builder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, radius)
                    .setTopRightCorner(CornerFamily.ROUNDED, radius)
                    .build()
            ).apply {
                fillColor = ColorStateList.valueOf(Color.WHITE)
                elevation = sheet.elevation
            }
        }
    }

    private fun readSessionMeta(): SessionMeta {
        runCatching {
            val jsonFile = File(sessionDir, "session.json")
            if (jsonFile.exists()) {
                val o = JSONObject(jsonFile.readText())
                return SessionMeta(
                    name = o.optString("nama", null),
                    nik = o.optString("nik", null),
                    rs = o.optString("rs", null),
                    nrm = o.optString("nrm", null),
                    dobUtc = o.optLong("dob_utc", -1L).takeIf { it > 0 },
                    createdAt = o.optString("created_at", sessionDir.parentFile?.name)
                )
            }
        }
        val parts = sessionDir.name.split("_")
        return SessionMeta(
            name = parts.drop(1).dropLast(1).joinToString(" ").replace('_', ' ').trim()
                .ifBlank { null },
            nik = parts.getOrNull(0),
            createdAt = sessionDir.parentFile?.name
        )
    }

    private data class SessionMeta(
        val name: String? = null, val nik: String? = null,
        val rs: String? = null, val nrm: String? = null,
        val dobUtc: Long? = null, val createdAt: String? = null
    )

    private fun buildTanggalUi(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return ""
        if (Regex("^\\d{8}_\\d{6}$").matches(createdAt)) {
            return runCatching {
                val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(createdAt)
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d!!)
            }.getOrDefault(createdAt)
        }
        if (createdAt.all { it.isDigit() }) {
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(
                    Date(
                        createdAt.toLong()
                    )
                )
            }.getOrDefault(createdAt)
        }
        listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd"
        ).forEach { p ->
            runCatching {
                SimpleDateFormat(p, Locale.US).parse(createdAt)
                    ?.let { return SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(it) }
            }
        }
        return createdAt
    }

    // =====================================================================
    //  Load media
    // =====================================================================

    private fun loadSessionMedia(dir: File): List<MediaItem> {
        val imgs =
            File(dir, "Snapshots").listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                ?.map { MediaItem(it, MediaType.IMAGE) }.orEmpty()
        val vids = File(dir, "Video").listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
            ?.map { MediaItem(it, MediaType.VIDEO) }.orEmpty()
        // Gunakan effectiveDateMs (dari nama file) bukan lastModified
        return (imgs + vids).sortedByDescending { effectiveDateMs(it) }
    }

    private fun loadMultiSessionMedia(paths: List<String>): List<MediaItem> {
        val combined = mutableListOf<MediaItem>()
        for (path in paths) {
            val dir = File(path)
            if (!dir.exists()) continue
            combined += File(dir, "Snapshots")
                .listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
                ?.map { MediaItem(it, MediaType.IMAGE) }.orEmpty()
            combined += File(dir, "Video")
                .listFiles { f -> f.isFile && f.extension.equals("mp4", true) }
                ?.map { MediaItem(it, MediaType.VIDEO) }.orEmpty()
        }
        // Gunakan effectiveDateMs (dari nama file) bukan lastModified
        return combined.sortedByDescending { effectiveDateMs(it) }
    }

    // =====================================================================
    //  Export ke gallery
    // =====================================================================

    private suspend fun exportManyToGallery(
        files: List<File>,
        albumName: String = "Cervexa",
        onProgress: (Int, Int, Uri?) -> Unit = { _, _, _ -> }
    ): List<Uri> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Uri>()
        val resolver = contentResolver

        fun mime(f: File) = when (f.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

        files.forEachIndexed { idx, src ->
            val m = mime(src)
            val isVideo = m.startsWith("video")
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val relPath =
                        (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/$albumName/"
                    val collection = if (isVideo)
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, m)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, cv)!!
                    resolver.openOutputStream(uri)?.use { FileInputStream(src).copyTo(it) }
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                    out += uri
                } else {
                    val pub =
                        if (isVideo) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val dst = File(File(pub, albumName).apply { mkdirs() }, src.name)
                    FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
                    MediaScannerConnection.scanFile(
                        this@SessionMediaActivity,
                        arrayOf(dst.absolutePath),
                        arrayOf(m),
                        null
                    )
                    out += Uri.fromFile(dst)
                }
                onProgress(idx + 1, files.size, out.lastOrNull())
            }.onFailure { it.printStackTrace(); onProgress(idx + 1, files.size, null) }
        }
        out
    }
}