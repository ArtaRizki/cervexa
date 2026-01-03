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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private var tvEmpty: TextView? = null
    private var tvEmptySubtitle: TextView? = null
    private var btnStart: Button? = null

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

        // [TV OPTIMIZATION] Jika empty state muncul, fokus ke tombol start
        if (show) {
            btnStart?.post { btnStart?.requestFocus() }
        }
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
        tvEmpty = v.findViewById(R.id.tvEmpty)
        tvEmptySubtitle = v.findViewById(R.id.tvEmptySubtitle)
        btnStart = v.findViewById(R.id.btnStart)
        searchView = v.findViewById(R.id.searchView)

        // [TV OPTIMIZATION] Config SearchView
        searchView.isFocusable = true
        searchView.isIconified = false
        searchView.clearFocus() // Supaya keyboard tidak langsung muncul

        if (currentQuery.isNotBlank()) {
            searchView.setQuery(currentQuery, false)
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applyFilter(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
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
                // Logic click handling...
                startActivity(Intent(requireContext(), SessionMediaActivity::class.java).apply {
                    putExtra("sessionDirPath", session.patientDir.absolutePath)
                    putExtra("patientName", session.nama ?: session.patientDir.name)
                    putExtra("dateStr", session.dateDir.name)
                })
            },
            onMoreClick = { session ->
                showSessionMoreSheet(session)
            }
        )

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.addItemDecoration(
            StickyMonthHeaderDecoration(
                provider = object : StickyHeaderProvider {
                    override fun isHeader(position: Int) = adapter.getItemViewType(position) == 1
                    override fun getHeaderText(position: Int) = ""
                }
            )
        )

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // [TV NOTE] onScrolled juga dipicu oleh navigasi D-Pad
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
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
                Toast.makeText(requireContext(), "Belum terhubung ke Wi-Fi kamera", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        loaded = 0
        adapter.reset()
        repo.invalidate()

        if (currentQuery.isBlank()) {
            loadNext()
        } else {
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

            if (currentQuery.isBlank()) {
                val isEmpty = adapter.itemCount == 0
                showEmptyState(isEmpty)

                // [TV OPTIMIZATION] Jika ini load pertama dan ada isi, fokus ke item pertama
                if (!isEmpty && loaded == batch.size) {
                    // Beri sedikit delay agar layout manager siap
                    rv.postDelayed({
                        val firstView = rv.layoutManager?.findViewByPosition(0)
                        firstView?.requestFocus()
                    }, 100)
                }
            } else {
                showEmptyState(false)
            }
        }
    }

    private fun applyFilter(query: String) {
        currentQuery = query

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            loaded = 0
            adapter.reset()
            repo.invalidate()
            progress.visibility = View.VISIBLE
            loadNext()
            return
        }

        loading = true
        progress.visibility = View.VISIBLE

        rv.post {
            val results = repo.searchSessions(trimmed)
            adapter.reset()
            adapter.append(results)
            loaded = results.size

            progress.visibility = View.GONE
            loading = false

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

        // [TV OPTIMIZATION] Paksa sheet full expanded agar D-Pad tidak bingung
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.background = com.google.android.material.shape.MaterialShapeDrawable(
                com.google.android.material.shape.ShapeAppearanceModel.Builder()
                    .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, resources.getDimension(R.dimen.bs_top_radius))
                    .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, resources.getDimension(R.dimen.bs_top_radius))
                    .build()
            ).apply {
                this?.fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                this?.elevation = sheet?.elevation ?: 0f
            }
        }

        val btnInfo = v.findViewById<View>(R.id.rowInfo)
        val btnDelete = v.findViewById<View>(R.id.rowDelete)
        val btnClose = v.findViewById<View>(R.id.btnClose)

        // Pastikan view focusable
        btnInfo?.isFocusable = true
        btnDelete?.isFocusable = true
        btnClose?.isFocusable = true

        btnClose?.setOnClickListener { dialog.dismiss() }
        btnInfo?.setOnClickListener {
            dialog.dismiss()
            showPatientInfoSheetFor(item)
        }
        btnDelete?.setOnClickListener {
            dialog.dismiss()
            confirmDeleteSession(item)
        }

        dialog.show()

        // [TV OPTIMIZATION] Fokus ke item pertama setelah muncul
        v.post { btnInfo?.requestFocus() }
    }

    private fun readSessionMeta(item: SessionItem): SessionMeta {
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
        val dateDir = item.patientDir.parentFile
        val folder = item.patientDir.name
        val parts = folder.split("_")
        val nik = parts.getOrNull(0)
        val name = parts.drop(1).dropLast(1).joinToString(" ").replace('_', ' ').trim().ifBlank { null }
        return SessionMeta(name, nik, null, null, null, dateDir?.name)
    }

    private fun showPatientInfoSheetFor(item: SessionItem) {
        val dialog = BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_patient_info, null)
        dialog.setContentView(v)

        // [TV OPTIMIZATION] Expanded
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val radius = resources.getDimension(R.dimen.bs_top_radius)
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

        val btnClose = v.findViewById<View>(R.id.btnClose)
        btnClose?.isFocusable = true
        btnClose?.setOnClickListener { dialog.dismiss() }

        val tvTanggal = v.findViewById<TextView>(R.id.tvTanggal)
        val tvNama = v.findViewById<TextView>(R.id.tvNama)
        val tvNik = v.findViewById<TextView>(R.id.tvNik)
        val tvDob = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm = v.findViewById<TextView>(R.id.tvNrm)

        val meta = readSessionMeta(item)
        val nama = meta.name
        val nik = meta.nik
        val nrm = meta.nrm
        val patientDobUtc = meta.dobUtc
        val tanggalUi = buildTanggalUi(meta.createdAt)

        tvTanggal.text = tanggalUi
        val rsText = meta.rs?.takeIf { it.isNotBlank() } ?: "-"
        tvNama.text = nama.orEmpty().ifBlank { "—" } + " ($rsText)"
        tvNik.text = nik.orEmpty().ifBlank { "—" }
        patientDobUtc?.let {
            tvDob.text = if (it > 0L) {
                SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).format(Date(patientDobUtc))
            } else "-"
        }
        tvNrm.text = nrm.orEmpty().ifBlank { "Tidak ada nomor rekam medis" }

        dialog.show()

        // [TV OPTIMIZATION] Fokus ke tombol tutup karena itu satu-satunya interaksi
        v.post { btnClose?.requestFocus() }
    }

    private fun confirmDeleteSession(item: SessionItem) {
        showConfirmDeleteSheet(
            "Anda akan menghapus media, konfirmasi?",
            onConfirm = {
                val dir = item.patientDir
                dir.walkBottomUp().forEach { it.delete() }
                onResume()
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

        // [TV OPTIMIZATION] Expanded
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

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
        val btnCancel = v.findViewById<View>(R.id.btnCancel)
        val btnDelete = v.findViewById<View>(R.id.btnDelete)

        // Make buttons focusable
        btnCancel.isFocusable = true
        btnDelete.isFocusable = true

        btnCancel?.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        btnDelete?.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.setCancelable(true)
        dialog.show()

        // [TV OPTIMIZATION] Default fokus ke Cancel (biar aman ga kepencet hapus)
        v.post { btnCancel?.requestFocus() }
    }

    private fun buildTanggalUi(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return ""
        val tsPattern = Regex("^\\d{8}_\\d{6}$")
        if (tsPattern.matches(createdAt)) {
            return try {
                val inFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val d = inFmt.parse(createdAt)
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d!!)
            } catch (_: Exception) { createdAt }
        }
        if (createdAt.all { it.isDigit() }) {
            return try {
                val d = Date(createdAt.toLong())
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d)
            } catch (_: Exception) { createdAt }
        }
        val parsers = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm", "yyyy-MM-dd"
        )
        for (p in parsers) {
            try {
                val d = SimpleDateFormat(p, Locale.US).parse(createdAt)
                if (d != null) return SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d)
            } catch (_: Exception) { }
        }
        return createdAt
    }

    private fun getSsidFromCaps(caps: NetworkCapabilities): String? =
        if (Build.VERSION.SDK_INT >= 31) (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"") else null

    private fun findCameraWifiNetwork(): Network? {
        val prefs = requireContext().getSharedPreferences(getString(R.string.pref_application), AppCompatActivity.MODE_PRIVATE)
        val exact = prefs.getString("camera_ssid_exact", null)
        val prefix = prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_")
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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