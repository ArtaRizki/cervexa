package com.idn.kmed.cervexa.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.patient.RegistrationPatientActivity
import com.idn.kmed.cervexa.gallery.SessionMediaActivity
import com.idn.kmed.cervexa.utils.WifiMonitor
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
    private lateinit var imgMedia: ImageView
    private var tvEmpty: TextView? = null
    private var tvEmptySubtitle: TextView? = null
    private var btnStart: Button? = null

    // 🔍 state search
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: View
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
        tvEmpty = v.findViewById(R.id.tvEmpty)
        tvEmptySubtitle = v.findViewById(R.id.tvEmptySubtitle)
        btnStart = v.findViewById(R.id.btnStart)
        etSearch = v.findViewById(R.id.searchView)
        btnSearch = v.findViewById(R.id.btnSearch)

        // Restore search query if exists
        if (currentQuery.isNotBlank()) {
            etSearch.setText(currentQuery)
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s.toString())
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applyFilter(etSearch.text.toString())
                true
            } else false
        }

        btnSearch.setOnClickListener {
            applyFilter(etSearch.text.toString())
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }

        requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)?.title =
            "Media"

        repo = MediaRepository(requireContext())
        adapter = SessionListAdapter(
            onSessionClick = { session ->
                val relatedSessions = repo.getRelatedSessions(session)
                val allPaths = ArrayList(relatedSessions.map { it.patientDir.absolutePath })

                startActivity(Intent(requireContext(), SessionMediaActivity::class.java).apply {
                    putExtra("sessionDirPath", session.patientDir.absolutePath)
                    putExtra("patientName", session.nama ?: session.patientDir.name)
                    putExtra("dateStr", session.dateDir.name)
                    putStringArrayListExtra("allSessionPaths", allPaths)
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
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
                if (!loading && currentQuery.isBlank() && last >= adapter.itemCount - 10) {
                    loadNext()
                }
            }
        })

        // [PERUBAHAN UTAMA] Menggunakan handler baru untuk diagnosa & koneksi
        btnStart?.setOnClickListener {
            handleStartClickMedia()
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        // Refresh List Data
        loaded = 0
        adapter.reset()
        repo.invalidate()

        if (currentQuery.isBlank()) {
            loadNext()
        } else {
            applyFilter(currentQuery)
        }

        // [PENTING] Refresh status WifiMonitor saat user kembali dari Settings
        WifiMonitor.init(requireContext()) { /* no-op */ }
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

    // =========================================================================
    // NETWORK & CONNECTION LOGIC (UPDATED)
    // =========================================================================

    private fun handleStartClickMedia() {
        val ctx = requireContext()

        // 1. DIAGNOSA PERMISSION
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    ctx,
                    "Mohon izinkan 'Nearby Devices' untuk deteksi kamera.",
                    Toast.LENGTH_LONG
                ).show()
                WifiMonitor.handlePermissionResult(2201, intArrayOf(), ctx)
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    ctx,
                    "Mohon izinkan Lokasi (Fine Location) untuk deteksi kamera.",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2201)
                return
            }
        }

        // 2. CEK STATUS WIFI via Monitor
        val status = WifiMonitor.statusFlow.value
        if (!status.isCamera) {
            val ssid = status.ssid ?: "Null"

            // Diagnosa Error SSID
            if (ssid == "Null" || ssid.contains("unknown", ignoreCase = true)) {
                val lm =
                    ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val isGpsOn = try {
                    lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                } catch (e: Exception) {
                    true
                }

                if (!isGpsOn) {
                    Toast.makeText(
                        ctx,
                        "GPS Mati. Harap nyalakan GPS agar SSID terbaca.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    Toast.makeText(
                        ctx,
                        "SSID tak terbaca. Pastikan izin lokasi 'PRECISE' (Akurat).",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", ctx.packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else {
                Toast.makeText(
                    ctx,
                    "Terhubung ke: $ssid.\nSilakan pindah ke Wi-Fi Kamera.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            return
        }

        // 3. STRICT NETWORK FINDING & BINDING
        val camNet = findCameraWifiNetworkStrict() ?: run {
            Toast.makeText(ctx, "Sedang menyiapkan koneksi... Coba lagi.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // [CRITICAL] Bind process ke network kamera (No Internet bypass)
        runCatching { cm.bindProcessToNetwork(camNet) }

        // Start Next Activity
        startActivity(Intent(ctx, RegistrationPatientActivity::class.java))
    }

    private fun findCameraWifiNetworkStrict(): Network? {
        val prefs = requireContext().getSharedPreferences(
            getString(R.string.pref_application),
            AppCompatActivity.MODE_PRIVATE
        )
        val exact = prefs.getString("camera_ssid_exact", null)
        val prefix = prefs.getString("camera_ssid_prefix", "wifi_camera_MS2_")

        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val all = cm.allNetworks

        // Loop scan all networks (including those with no internet)
        for (n in all) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            var ssid: String? = null
            if (Build.VERSION.SDK_INT >= 31) {
                ssid = (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
            }

            // Fallback: If SSID is match
            if (!exact.isNullOrBlank() && ssid == exact) return n
            if (!prefix.isNullOrBlank() && ssid?.startsWith(prefix) == true) return n
        }

        // Fallback: If WifiMonitor says we are connected, but capabilities didn't give SSID (API limit),
        // trust WifiMonitor and grab the first WIFI network found.
        if (WifiMonitor.statusFlow.value.isCamera) {
            for (n in all) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return n
                }
            }
        }
        return null
    }

    // =========================================================================
    // UI HELPERS (BottomSheet, Meta, Dates)
    // =========================================================================

    private fun showSessionMoreSheet(item: SessionItem) {
        val dialog = BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.bs_session_more, null)
        dialog.setContentView(v)

        dialog.setOnShowListener {
            val bottomSheetInternal =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheetInternal != null) {
                val behavior = BottomSheetBehavior.from(bottomSheetInternal)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                bottomSheetInternal.background = MaterialShapeDrawable(
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
                    fillColor = ColorStateList.valueOf(Color.WHITE)
                    elevation = bottomSheetInternal.elevation
                }
            }
        }

        v.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.rowInfo)?.setOnClickListener {
            dialog.dismiss()
            showPatientInfoSheetFor(item)
        }
        v.findViewById<View>(R.id.rowDelete)?.setOnClickListener {
            dialog.dismiss()
            confirmDeleteSession(item)
        }

        dialog.show()
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
        val name =
            parts.drop(1).dropLast(1).joinToString(" ").replace('_', ' ').trim().ifBlank { null }
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

        dialog.setOnShowListener {
            val sheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
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

        v.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }

        val tvTanggal = v.findViewById<TextView>(R.id.tvTanggal)
        val tvNama = v.findViewById<TextView>(R.id.tvNama)
        val tvNik = v.findViewById<TextView>(R.id.tvNik)
        val tvDob = v.findViewById<TextView>(R.id.tvDob)
        val tvNrm = v.findViewById<TextView>(R.id.tvNrm)

        val meta = readSessionMeta(item)
        val tanggalUi = buildTanggalUi(meta.createdAt)

        tvTanggal.text = tanggalUi
        val rsText = meta.rs?.takeIf { it.isNotBlank() } ?: "-"
        tvNama.text = meta.name.orEmpty().ifBlank { "—" } + " ($rsText)"
        tvNik.text = meta.nik.orEmpty().ifBlank { "—" }
        meta.dobUtc?.let {
            tvDob.text = if (it > 0L) {
                SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).format(Date(it))
            } else "-"
        }
        tvNrm.text = meta.nrm.orEmpty().ifBlank { "Tidak ada nomor rekam medis" }

        dialog.show()
    }

    private fun confirmDeleteSession(item: SessionItem) {
        showConfirmDeleteSheet(
            "Anda akan menghapus media, konfirmasi?",
            onConfirm = {
                item.patientDir.walkBottomUp().forEach { it.delete() }
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

    private fun buildTanggalUi(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return ""
        val tsPattern = Regex("^\\d{8}_\\d{6}$")
        if (tsPattern.matches(createdAt)) {
            return try {
                val inFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val d = inFmt.parse(createdAt)
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d!!)
            } catch (_: Exception) {
                createdAt
            }
        }
        // Fallback for epoch or other formats
        if (createdAt.all { it.isDigit() }) {
            return try {
                val d = Date(createdAt.toLong())
                SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US).format(d)
            } catch (_: Exception) {
                createdAt
            }
        }
        return createdAt
    }
}