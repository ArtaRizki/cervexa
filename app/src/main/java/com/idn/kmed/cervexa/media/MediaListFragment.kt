package com.idn.kmed.cervexa.media

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.RegistrationPatientActivity
import com.idn.kmed.cervexa.gallery.SessionMediaActivity
//import com.idn.kmed.cervexa.gallery.SessionMediaActivity.SessionMeta
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.idn.kmed.cervexa.SelectExistingPatientActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaListFragment : Fragment() {

    private lateinit var repo: MediaRepository
    private lateinit var adapter: SessionListAdapter
    private var loading = false
    private var loaded = 0
    private val pageSize = 40

    private lateinit var rv: RecyclerView
    private lateinit var progress: View
    private lateinit var imgMedia: View
    private var tvEmpty: TextView? = null   // optional, kalau kamu punya empty view
    private var tvEmptySubtitle: TextView? = null   // optional, kalau kamu punya empty view
    private var btnStart: Button? = null   // optional, kalau kamu punya empty view

    // 🔍 state search
    private lateinit var searchView: SearchView
    private var emptyStateContainer: View? = null
    private var currentQuery: String = ""

    private data class SessionMeta(
        val name: String? = null,
        val nik: String? = null,
        val rs: String? = null,
        val nrm: String? = null,
        val dobUtc: Long? = null,
        val createdAt: String? = null
    )

    private fun showEmptyState(show: Boolean) {
        emptyStateContainer?.visibility = if (show) View.VISIBLE else View.GONE
        rv.visibility = if (show) View.GONE else View.VISIBLE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_media_list, container, false)
        rv = v.findViewById(R.id.rv)
        progress = v.findViewById(R.id.progress)
        emptyStateContainer = v.findViewById(R.id.emptyStateContainer)
        imgMedia = v.findViewById(R.id.imageView2)
        tvEmpty = v.findViewById(R.id.tvEmpty) // boleh null kalau layout-mu belum ada
        tvEmptySubtitle = v.findViewById(R.id.tvEmptySubtitle)
        btnStart = v.findViewById(R.id.btnStart)
        searchView = v.findViewById(R.id.searchView)

        // Keep text kalau fragment direcreate
        if (currentQuery.isNotBlank()) {
            searchView.setQuery(currentQuery, false)
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applyFilter(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // live filter saat user ngetik
                applyFilter(newText.orEmpty())
                return true
            }
        })


        requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)?.title =
            "Media"

        repo = MediaRepository(requireContext())
        adapter = SessionListAdapter(
            onSessionClick = { session ->
                val all = repo.listMediaInSession(session)
                val paths = ArrayList(all.map { it.file.absolutePath })
                val types = ArrayList(all.map { it.type.name })
                val idx = all.indexOfFirst { it.file == session.thumb.file }.coerceAtLeast(0)

                /*startActivity(Intent(requireContext(), com.idn.kmed.cervexa.gallery.MediaPagerActivity::class.java).apply {
                    putStringArrayListExtra("paths", paths)
                    putStringArrayListExtra("types", types)
                    putExtra("index", idx)
                })*/
                startActivity(Intent(requireContext(), SessionMediaActivity::class.java).apply {
                    putExtra("sessionDirPath", session.patientDir.absolutePath)
                    putExtra("patientName", session.nama ?: session.patientDir.name)
                    putExtra("dateStr", session.dateDir.name) // "yyyy-MM-dd"
                })
            },
            onMoreClick = { session ->
                // klik titik tiga → buka bottom sheet
                showSessionMoreSheet(session)
            }
        )

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.addItemDecoration(
            StickyMonthHeaderDecoration(
                // adapter kamu implement StickyHeaderProvider? kalau iya, cukup: provider = adapter
                provider = object : StickyHeaderProvider {
                    override fun isHeader(position: Int) = adapter.getItemViewType(position) == 1
                    override fun getHeaderText(position: Int) =
                        "" // adapter bisa diupgrade utk expose teks; aman diisi kosong
                }
            )
        )

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
                // hanya lazy-load kalau TIDAK sedang search
                if (!loading && currentQuery.isBlank() && last >= adapter.itemCount - 10) {
                    loadNext()
                }
            }
        })

        btnStart?.setOnClickListener {
            val camNet = findCameraWifiNetwork()
            if (camNet != null) {
                val cm =
                    requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                runCatching { cm.bindProcessToNetwork(camNet) }
                startActivity(Intent(requireContext(), RegistrationPatientActivity::class.java))
            } else {
                Toast.makeText(
                    requireContext(),
                    "Belum terhubung ke Wi-Fi kamera",
                    Toast.LENGTH_SHORT
                ).show()
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        // refresh setiap masuk tab Media
        loaded = 0
        adapter.reset()
        repo.invalidate()

        if (currentQuery.isBlank()) {
            // mode normal: lazy load
            loadNext()
        } else {
            // kalau sebelumnya ada query, ulangi search
            applyFilter(currentQuery)
        }
    }

    private fun loadNext() {
        loading = true
        progress.visibility = View.VISIBLE
        rv.post {
            val batch = repo.loadPage(loaded, pageSize)
            adapter.append(batch)
            loaded += batch.size
            progress.visibility = View.GONE
            loading = false

            // kalau bukan search, atur empty state
            if (currentQuery.isBlank()) {
                val isEmpty = adapter.itemCount == 0
                showEmptyState(isEmpty)
            } else {
                // kalau lagi search, jangan pakai empty state besar
                showEmptyState(false)
            }
//
//            // toggle empty (opsional)
//            rv.visibility = if (loaded == 0 && batch.isEmpty()) View.GONE else View.VISIBLE
//            imgMedia?.visibility = if (loaded == 0 && batch.isEmpty()) View.VISIBLE else View.GONE
//            tvEmpty?.visibility = if (loaded == 0 && batch.isEmpty()) View.VISIBLE else View.GONE
//            tvEmptySubtitle?.visibility = if (loaded == 0 && batch.isEmpty()) View.VISIBLE else View.GONE
//            btnStart?.visibility = if (loaded == 0 && batch.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun applyFilter(query: String) {
        currentQuery = query

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // balik ke mode normal (paging)
            loaded = 0
            adapter.reset()
            repo.invalidate()
            progress.visibility = View.VISIBLE
//            showEmptyState(false)   // tampilkan list (nanti loadNext yang atur empty real)
            loadNext()
            return
        }

        // mode search: ambil semua dari repo, filter di memory
        loading = true
        progress.visibility = View.VISIBLE

        rv.post {
            val results = repo.searchSessions(trimmed)
            adapter.reset()
            adapter.append(results)
            loaded = results.size

            progress.visibility = View.GONE
            loading = false

            // Mode search:
            // - list tetap kelihatan (walaupun kosong)
            // - empty state "Belum ada media" TIDAK dipakai
            rv.visibility = View.VISIBLE
            emptyStateContainer?.visibility = View.GONE

        }
    }


    private fun showSessionMoreSheet(item: SessionItem) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_session_more, null)
        dialog.setContentView(v)

        // Rounded top
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.background = com.google.android.material.shape.MaterialShapeDrawable(
                com.google.android.material.shape.ShapeAppearanceModel.Builder()
                    .setTopLeftCorner(
                        com.google.android.material.shape.CornerFamily.ROUNDED,
                        resources.getDimension(R.dimen.bs_top_radius)
                    )
                    .setTopRightCorner(
                        com.google.android.material.shape.CornerFamily.ROUNDED,
                        resources.getDimension(R.dimen.bs_top_radius)
                    )
                    .build()
            ).apply {
                this?.fillColor =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                this?.elevation = sheet?.elevation ?: 0f
            }
        }

        v.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.rowInfo)?.setOnClickListener {
            dialog.dismiss()
            // panggil sheet informasi pasien yang sudah kamu punya
            showPatientInfoSheetFor(item)   // implement ke fungsi kamu
        }
        v.findViewById<View>(R.id.rowDelete)?.setOnClickListener {
            dialog.dismiss()
            // konfirmasi hapus sesi ini (atau media di dalamnya)
            confirmDeleteSession(item)
        }

        dialog.show()
    }

    /** Baca session.json (jika ada) lalu fallback dari nama folder "NIK_NAMA_USIA" */
    private fun readSessionMeta(item: SessionItem): SessionMeta {
        // 1) JSON
        runCatching {
            val jsonFile = File(item.patientDir, "session.json")
            if (jsonFile.exists()) {
                val o = JSONObject(jsonFile.readText())
                return SessionMeta(
                    name = o.optString("nama", null),
                    nik = o.optString("nik", null),
                    rs = o.optString("rs", null),
                    nrm = o.optString("nrm", null),
                    dobUtc = o.optLong("dob_utc", -1L).takeIf { it > 0 },
                    createdAt = o.optString("created_at", item.patientDir.parentFile?.name)
                )
            }
        }
        // 2) Parse nama folder pasien → "NIK_NAMA_USIA"
        val dateDir = item.patientDir.parentFile
        val folder = item.patientDir.name
        val parts = folder.split("_")
        val nik = parts.getOrNull(0)
        val name = parts.drop(1).dropLast(1).joinToString(" ")
            .replace('_', ' ')
            .trim()
            .ifBlank { null }
        return SessionMeta(
            name = name,
            nik = nik,
            nrm = null,
            dobUtc = null,
            createdAt = dateDir?.name
        )
    }

    private fun showPatientInfoSheetFor(item: SessionItem) {
        val dialog = BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)

        // ---- Rounded top programatik (jalan di minSdk 25) ----
        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val radius =
                    resources.getDimension(R.dimen.bs_top_radius) // mis. 16dp (lihat dimens di bawah)
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
        val tvNama = v.findViewById<TextView>(R.id.tvNama)
        val tvNik = v.findViewById<TextView>(R.id.tvNik)
        val tvDob = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm = v.findViewById<TextView>(R.id.tvNrm)

        // --- ambil data dari extras / session.json / nama folder ---
        val meta = readSessionMeta(item)

        val nama = meta.name
        val nik = meta.nik
        val rs = meta.rs
        val nrm = meta.nrm
        val patientDobUtc = meta.dobUtc
        val tanggalUi = buildTanggalUi(meta.createdAt) //Karna menggunakan jam

        // isi UI
        tvTanggal.text = tanggalUi
        val rsText = meta.rs?.takeIf { it.isNotBlank() } ?: "-"
        tvNama.text = nama.orEmpty().ifBlank { "—" } + " ($rsText)"
        tvNik.text = nik.orEmpty().ifBlank { "—" }
        patientDobUtc?.let {
            tvDob.text = if (it > 0L) {
                val sdfDob = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("id", "ID"))
                sdfDob.format(java.util.Date(patientDobUtc))
            } else "-"
        }
        tvNrm.text = nrm.orEmpty().ifBlank { "Tidak ada nomor rekam medis" }

        dialog.show()
    }

    private fun confirmDeleteSession(item: SessionItem) {

        showConfirmDeleteSheet(
            "Anda akan menghapus media, konfirmasi?",
            onConfirm = {
                val dir = item.patientDir
                dir.walkBottomUp().forEach { it.delete() }
                onResume() // refresh list
            }
        )
    }

    private fun showConfirmDeleteSheet(
        message: String,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_confirm_delete, null)
        dialog.setContentView(v)

        // Rounded top (minSdk 25 OK)
        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
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
            } catch (_: Exception) {
                createdAt
            }
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
            } catch (_: Exception) {
            }
        }

        return createdAt // fallback
    }

    // ====== NET HELPERS (port dari Activity) ======
    private fun getSsidFromCaps(caps: NetworkCapabilities): String? =
        if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding(
            "\""
        ) else null

    private fun findCameraWifiNetwork(): Network? {
        val prefs = requireContext().getSharedPreferences(
            getString(R.string.pref_application),
            AppCompatActivity.MODE_PRIVATE
        )
        val exact = prefs.getString("camera_ssid_exact", null)
        val prefix = prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_")

        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val all = cm.allNetworks ?: return null

        if (Build.VERSION.SDK_INT >= 31 && !exact.isNullOrBlank()) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (getSsidFromCaps(caps) == exact) return n
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !prefix.isNullOrBlank()) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val ssid = getSsidFromCaps(caps) ?: continue
                if (ssid.startsWith(prefix, ignoreCase = false)) return n
            }
        }

        var fallbackWifi: Network? = null
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!validated || !hasInternet) return n
            if (fallbackWifi == null) fallbackWifi = n
        }
        return fallbackWifi
    }
}
