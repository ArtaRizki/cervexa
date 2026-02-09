package com.idn.kmed.cervexa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idn.kmed.cervexa.live.VideoActivity
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
        val etSearch = findViewById<EditText>(R.id.searchViewPatient)
        val btnSearch = findViewById<View>(R.id.btnSearch)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterPatients(s.toString())
            }
        })

        btnSearch.setOnClickListener {
            filterPatients(etSearch.text.toString())
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filterPatients(etSearch.text.toString())
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
                true
            } else false
        }

        adapter = PatientListAdapter { patient ->
            openVideoForPatient(patient)
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        loadPatients()
    }

    private fun loadPatients() {
        // ✅ Ambil semua session dari MediaRepository
        val sessions = repo.collectAllSessions()

        // ✅ Group by NIK untuk menampilkan unique patients
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
        // ✅ Cari session terakhir pasien ini
        val allSessions = repo.collectAllSessions()
        val matchingSession = allSessions
            .filter { it.nik == p.nik }
            .maxByOrNull { it.lastTs }

        val intent = Intent(this, VideoActivity::class.java).apply {
            putExtra("patient_nama", p.nama)
            putExtra("patient_nik", p.nik)
            putExtra("patient_rs", p.rs)
            putExtra("patient_nrm", p.nrm)
            putExtra("patient_dob_utc", p.dobUtcMs)
            putExtra("is_existing_patient", true)

            // ✅ KIRIM PATH SESSION YANG SUDAH ADA
            matchingSession?.patientDir?.let { patientDir ->
                putExtra("sessionDirPath", patientDir.absolutePath)
            }
        }

        startActivity(intent)
        finish()
    }
}