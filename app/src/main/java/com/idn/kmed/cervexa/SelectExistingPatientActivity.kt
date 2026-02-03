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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
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

//        etSearch.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
//            v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
//                .setDuration(80).start()
//        }
//
//        btnSearch.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
//            v.animate()
//                .scaleX(if (hasFocus) 1.08f else 1f)
//                .scaleY(if (hasFocus) 1.08f else 1f)
//                .setDuration(80)
//                .start()
//        }


        adapter = PatientListAdapter { patient ->
            openVideoForPatient(patient)
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // load data pasien dari session yang sudah ada
        loadPatients()
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
//                    dobUtcMs = s.lastTs ?: 0L
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
