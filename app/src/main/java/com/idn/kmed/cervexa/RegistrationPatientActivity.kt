package com.idn.kmed.cervexa

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.idn.kmed.cervexa.live.VideoActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class RegistrationPatientActivity : AppCompatActivity() {

    private lateinit var tilNama: TextInputLayout
    private lateinit var tilNik: TextInputLayout
    private lateinit var tilRS: TextInputLayout
    private lateinit var tilDob: TextInputLayout
    private lateinit var etNama: TextInputEditText
    private lateinit var etNik: TextInputEditText
    private lateinit var etDob: TextInputEditText
    private lateinit var etRS: TextInputEditText
    private lateinit var etNrm: TextInputEditText
    private lateinit var btnNext: Button

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("id", "ID")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }
    private var selectedDobUtcMs: Long? = null

    fun blockCenterKey(et: TextInputEditText) {
        et.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && event.action == KeyEvent.ACTION_DOWN
            ) true else false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration_patient)

        // Toolbar Navigasi
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener { finish() }

        // Agar toolbar bisa difokus remote (opsional, untuk tombol back)
//        toolbar.isFocusable = true
//        toolbar.isFocusableInTouchMode = true

        // Views
        tilNama = findViewById(R.id.tilNama)
        tilNik = findViewById(R.id.tilNik)
        tilDob = findViewById(R.id.tilDob)
        tilRS = findViewById(R.id.tilRS)
        etNama = findViewById(R.id.etNama)
        etNik = findViewById(R.id.etNik)
        etDob = findViewById(R.id.etDob)
        etRS = findViewById(R.id.etRS)
        etNrm = findViewById(R.id.etNrm)
        btnNext = findViewById(R.id.btnNext)

        // [TV OPTIMIZATION] Setup Input Tanggal
        setupTvDateInput()

        btnNext.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && event.action == KeyEvent.ACTION_DOWN
            ) {
                btnNext.performClick()
                return@setOnKeyListener true
            }
            false
        }

        btnNext.setOnClickListener {
            currentFocus?.clearFocus()
            blockCenterKey(etNama)
            blockCenterKey(etNik)
            blockCenterKey(etRS)
            blockCenterKey(etNrm)
            handleRegistration()
        }
    }

    private fun setupTvDateInput() {
        etDob.apply {
            // benar-benar cegah keyboard & input manual
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            isFocusableInTouchMode = false

            // cegah soft keyboard muncul saat fokus (API 21+)
            showSoftInputOnFocus = false

            // opsional: kalau masih ada IME/keyboard TV yang “maksa”
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) post { showTvDatePicker() }
            }
        }
        // Fungsi pembuka date picker
        val openPicker = {
            // [FIX PENTING] Gunakan .post {}
            // Ini menjamin dialog baru muncul SETELAH event tombol Enter selesai sepenuhnya.
            // Tanpa ini, fokus sering nyangkut di EditText belakang dialog.
            etDob.post {
                showTvDatePicker()
            }
        }

        // 1. Klik via Touch / Mouse
        etDob.setOnClickListener { openPicker() }
        tilDob.setEndIconOnClickListener { openPicker() }

        // 2. Klik via Remote (Enter / D-Pad Center)
        etDob.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                // Hanya eksekusi saat tombol DILEPAS (ACTION_UP)
                if (event.action == KeyEvent.ACTION_UP) {
                    openPicker()
                }
                // Wajib return true pada DOWN dan UP agar event tidak bocor
                return@setOnKeyListener true
            }
            false
        }

        // Pastikan keyboard tidak muncul saat fokus (sudah aman karena inputType="none")
        etDob.setOnFocusChangeListener { _, _ -> }
    }

    private fun showTvDatePicker() {
        val calendar = Calendar.getInstance()
        if (selectedDobUtcMs != null) {
            calendar.timeInMillis = selectedDobUtcMs!!
        }

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePicker = DatePickerDialog(
            this,
            R.style.BlueDatePickerDialog, // Pastikan style ini sesuai XML tadi
            { _, selectedYear, selectedMonth, selectedDay ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(selectedYear, selectedMonth, selectedDay)

                val utcMs = selectedCal.timeInMillis
                selectedDobUtcMs = utcMs

                etDob.setText(dateFormat.format(selectedCal.time))
                tilDob.error = null

                etRS.requestFocus()
            },
            year, month, day
        )

        // --- BAGIAN YANG DIHAPUS/DIKOMENTARI ---
        // datePicker.datePicker.maxDate = System.currentTimeMillis() // <--- HAPUS INI agar masa depan bisa dipilih
        // ---------------------------------------

        // Tetap batasi masa lalu (misal 130 tahun) agar user tidak scroll terlalu jauh ke tahun 1900
        val minCal = Calendar.getInstance()
        minCal.add(Calendar.YEAR, -130)
        datePicker.datePicker.minDate = minCal.timeInMillis

        datePicker.show()

        // [OPSIONAL] Memaksa warna tombol secara manual jika XML tidak tembus di beberapa TV
        datePicker.getButton(DatePickerDialog.BUTTON_POSITIVE)
            ?.setBackgroundColor(android.graphics.Color.parseColor("#1E63E4"))
        datePicker.getButton(DatePickerDialog.BUTTON_POSITIVE)
            ?.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
        datePicker.getButton(DatePickerDialog.BUTTON_NEGATIVE)
            ?.setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
        datePicker.getButton(DatePickerDialog.BUTTON_NEGATIVE)
            ?.setTextColor(android.graphics.Color.parseColor("#1E63E4"))
    }

    private fun handleRegistration() {
        if (!validate()) return

        val nama = etNama.text?.toString()?.trim().orEmpty()
        val nik = etNik.text?.toString()?.trim().orEmpty()
        val rs = etRS.text?.toString()?.trim().orEmpty()
        val nrm = etNrm.text?.toString()?.trim().orEmpty()
        val dob = selectedDobUtcMs ?: -1L

        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra("patient_nama", nama)
            putExtra("patient_nik", nik)
            putExtra("patient_rs", rs)
            putExtra("patient_nrm", nrm)
            putExtra("patient_dob_utc", dob)
        })
    }

    private fun validate(): Boolean {
        var ok = true

        val nama = etNama.text?.toString()?.trim().orEmpty()
        if (nama.isEmpty()) {
            tilNama.error = "Nama wajib diisi"
            ok = false
        } else tilNama.error = null

        val nik = etNik.text?.toString()?.trim().orEmpty()
        when {
            nik.isEmpty() -> {
                tilNik.error = "NIK wajib diisi"; ok = false
            }

            nik.length != 16 || !nik.all { it.isDigit() } -> {
                tilNik.error = "NIK harus 16 digit angka"; ok = false
            }

            else -> tilNik.error = null
        }

        if (selectedDobUtcMs == null) {
            tilDob.error = "Tanggal lahir wajib diisi"
            ok = false
        } else tilDob.error = null

        val rs = etRS.text?.toString()?.trim().orEmpty()
        if (rs.isEmpty()) {
            tilRS.error = "Nama Rumah Sakit wajib diisi"
            ok = false
        } else tilRS.error = null

        if (!ok) Toast.makeText(this, "Periksa kembali data", Toast.LENGTH_SHORT).show()
        return ok
    }
}