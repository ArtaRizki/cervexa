package com.idn.kmed.cervexa.patient

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.video.VideoActivity
import com.idn.kmed.cervexa.media.MediaRepository
import com.idn.kmed.cervexa.media.PatientListAdapter
import com.idn.kmed.cervexa.model.PatientItem
import com.idn.kmed.cervexa.network.ApiResult
import com.idn.kmed.cervexa.network.CervexaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Halaman "Pilih Pasien yang Sudah Ada".
 *
 * Strategi Hybrid:
 *  1. Tampil dulu dari lokal (MediaRepository) — instan, tidak butuh internet.
 *  2. Saat user mengetik, juga cari ke API Laravel (debounce 400ms).
 *  3. Gabungkan hasil, deduplikasi berdasarkan NIK.
 *  4. Saat pasien dipilih → lookup API untuk dapat server patient_id,
 *     lalu kirim ke VideoActivity.
 */
class SelectExistingPatientActivity : AppCompatActivity() {

    private lateinit var localRepo: MediaRepository
    private lateinit var apiRepo: CervexaRepository
    private lateinit var adapter: PatientListAdapter

    private var localList: List<PatientItem> = emptyList()
    private var displayList: List<PatientItem> = emptyList()

    // Map NIK → server patient_id (diisi saat lookup berhasil)
    private val serverIdCache = mutableMapOf<String, Int>()

    private lateinit var progressBar: ProgressBar
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_existing_patient)

        localRepo = MediaRepository(this)
        apiRepo = CervexaRepository.getInstance(this)

        val rv = findViewById<RecyclerView>(R.id.rvPatients)
        val etSearch = findViewById<EditText>(R.id.searchViewPatient)
        val btnSearch = findViewById<View>(R.id.btnSearch)
        progressBar =
            findViewById(R.id.progressBar)   // tambahkan ProgressBar di XML jika belum ada

        adapter = PatientListAdapter { patient -> openVideoForPatient(patient) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // ── Muat data lokal terlebih dahulu ────────────────────────────────
        loadLocalPatients()

        // ── Search listener (debounce) ─────────────────────────────────────
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                filterLocal(q)
                scheduleApiSearch(q)
            }
        })

        btnSearch.setOnClickListener {
            val q = etSearch.text.toString()
            filterLocal(q)
            scheduleApiSearch(q)
            hideKeyboard(etSearch)
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearch.text.toString()
                filterLocal(q)
                scheduleApiSearch(q)
                hideKeyboard(etSearch)
                true
            } else false
        }
    }

    // ─── Lokal ───────────────────────────────────────────────────────────────

    private fun loadLocalPatients() {
        val sessions = localRepo.collectAllSessions()
        val map = linkedMapOf<String, PatientItem>()
        for (s in sessions) {
            val nik = s.nik ?: continue
            if (!map.containsKey(nik)) {
                map[nik] = PatientItem(
                    nama = s.nama.orEmpty(),
                    nik = nik,
                    rs = s.rs,
                    nrm = s.nrm,
                    dobUtcMs = s.dobUtc ?: 0L
                )
            }
        }
        localList = map.values.toList()
        displayList = localList
        adapter.submitList(displayList)
    }

    private fun filterLocal(query: String) {
        val q = query.trim().lowercase()
        displayList = if (q.isEmpty()) localList
        else localList.filter {
            it.nama.lowercase().contains(q) ||
                    it.nik.lowercase().contains(q) ||
                    it.nrm?.lowercase()?.contains(q) == true ||
                    it.rs?.lowercase()?.contains(q) == true
        }
        adapter.submitList(displayList)
    }

    // ─── API search (debounce 400ms) ─────────────────────────────────────────

    private fun scheduleApiSearch(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) return   // jangan search jika kurang dari 2 karakter

        searchJob = lifecycleScope.launch {
            delay(400)
            searchFromApi(query.trim())
        }
    }

    private suspend fun searchFromApi(query: String) {
        setLoading(true)
        // Gunakan endpoint lookup jika input tepat 16 digit (NIK exact)
        if (query.length == 16 && query.all { it.isDigit() }) {
            when (val r = apiRepo.lookupPatient(query)) {
                is ApiResult.Success -> {
                    if (r.data.found && r.data.data != null) {
                        val p = r.data.data
                        serverIdCache[p.nik] = p.id   // simpan server ID
                        mergeApiResult(
                            listOf(
                                PatientItem(
                                    nama = p.nama,
                                    nik = p.nik,
                                    rs = p.hospitalName,
                                    nrm = p.nrm,
                                    dobUtcMs = 0L
                                )
                            )
                        )
                    }
                }

                is ApiResult.Error -> { /* diam — lokal masih tampil */
                }
            }
        }
        setLoading(false)
    }

    /** Gabungkan hasil API ke displayList, deduplikasi berdasarkan NIK. */
    private fun mergeApiResult(apiPatients: List<PatientItem>) {
        val currentNiks = displayList.map { it.nik }.toSet()
        val newItems = apiPatients.filter { it.nik !in currentNiks }
        if (newItems.isEmpty()) return
        displayList = displayList + newItems
        adapter.submitList(displayList)
    }

    // ─── Buka VideoActivity ───────────────────────────────────────────────────

    private fun openVideoForPatient(p: PatientItem) {
        // Jika server ID sudah di-cache, langsung buka
        val cachedId = serverIdCache[p.nik]
        if (cachedId != null) {
            launchVideo(p, patientId = cachedId)
            return
        }

        // Lookup ke API untuk dapat server ID, dengan fallback offline
        lifecycleScope.launch {
            setLoading(true)
            val patientId = when (val r = apiRepo.lookupPatient(p.nik)) {
                is ApiResult.Success -> {
                    r.data.data?.id?.also { serverIdCache[p.nik] = it } ?: -1
                }

                is ApiResult.Error -> -1
            }
            setLoading(false)
            launchVideo(p, patientId)
        }
    }

    private fun launchVideo(p: PatientItem, patientId: Int) {
        // Cari sessionDir lokal untuk melanjutkan sesi yang sudah ada
        val allSessions = localRepo.collectAllSessions()
        val matchingSession = allSessions
            .filter { it.nik == p.nik }
            .maxByOrNull { it.lastTs }

        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra("patient_id", patientId)   // server ID (−1 jika offline)
            putExtra("patient_nama", p.nama)
            putExtra("patient_nik", p.nik)
            putExtra("patient_rs", p.rs)
            putExtra("patient_nrm", p.nrm)
            putExtra("patient_dob_utc", p.dobUtcMs)
            putExtra("is_existing_patient", true)
            matchingSession?.patientDir?.let { putExtra("sessionDirPath", it.absolutePath) }
        })
        finish()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun setLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }
}