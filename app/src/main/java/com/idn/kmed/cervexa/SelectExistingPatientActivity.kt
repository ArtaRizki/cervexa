package com.idn.kmed.cervexa

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.media.MediaRepository
import com.idn.kmed.cervexa.media.PatientListAdapter
import com.idn.kmed.cervexa.model.PatientItem

class SelectExistingPatientActivity : AppCompatActivity() {

    private lateinit var repo: MediaRepository
    private lateinit var adapter: PatientListAdapter
    private var fullList: List<PatientItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_existing_patient)

        repo = MediaRepository(this)

        val rv = findViewById<RecyclerView>(R.id.rvPatients)
        val searchView = findViewById<SearchView>(R.id.searchViewPatient)

        // 🔹 Di TV: langsung fokus ke search, user bisa tekan OK untuk buka keyboard
        searchView.isFocusable = true
        searchView.isFocusableInTouchMode = true
        searchView.requestFocus()

        adapter = PatientListAdapter { patient ->
            openVideoForPatient(patient)
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // load data pasien dari session yang sudah ada
        loadPatients()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterPatients(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterPatients(newText.orEmpty())
                return true
            }
        })
    }

    private fun loadPatients() {
        // ambil semua session
        val sessions = repo.collectAllSessions()  // kalau belum public, bisa bikin helper di repo

        // group by NIK (atau kombinasi lain sesuai kebutuhanmu)
        val map = linkedMapOf<String, PatientItem>()

        for (s in sessions) {
            val nik = s.nik ?: continue
            // kalau sudah ada, bisa skip / override sesuai kebutuhan (misal ambil terakhir)
            if (!map.containsKey(nik)) {
                map[nik] = PatientItem(
                    nama = s.nama.orEmpty(),
                    nik = nik,
                    rs = s.rs,
                    nrm = s.nrm,
                    dobUtcMs = s.lastTs ?: 0L
                )
            }
        }

        fullList = map.values.toList()
        adapter.submitList(fullList)
    }

    private fun filterPatients(q: String) {
        val query = q.trim().lowercase()
        if (query.isEmpty()) {
            adapter.submitList(fullList)
            return
        }

        val filtered = fullList.filter { p ->
            p.nama.lowercase().contains(query) ||
                    p.nik.lowercase().contains(query) ||
                    p.nrm?.lowercase()?.contains(query) == true ||
                    p.rs?.lowercase()?.contains(query) == true
        }
        adapter.submitList(filtered)
    }

    private fun openVideoForPatient(p: PatientItem) {
        val intent = Intent(this, VideoActivity::class.java).apply {
            putExtra("patient_nama", p.nama)
            putExtra("patient_nik", p.nik)
            putExtra("patient_rs", p.rs)
            putExtra("patient_nrm", p.nrm)        // kalau mau pakai, bisa diisi dari SessionItem
            putExtra("patient_dob_utc", p.dobUtcMs)
            putExtra("is_existing_patient", true)
        }
        startActivity(intent)
        finish()
    }
}
