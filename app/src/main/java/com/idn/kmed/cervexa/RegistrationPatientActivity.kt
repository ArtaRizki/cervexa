package com.idn.kmed.cervexa

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

    // Helper untuk menyembunyikan keyboard secara paksa
    private fun forceHideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun blockCenterKey(et: TextInputEditText) {
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
        toolbar.isFocusable = true
        toolbar.isFocusableInTouchMode = true

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
            // Hapus fokus agar keyboard tidak muncul lagi di field terakhir
            currentFocus?.clearFocus()
            handleRegistration()
        }
    }

    private fun setupTvDateInput() {
        etDob.apply {
            // 1. Matikan input type text
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false

            // 2. Cegah keyboard muncul (API 21+)
            showSoftInputOnFocus = false

            // 3. Logika Fokus: Matikan Keyboard & Jangan Auto-Open Dialog
            onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    // PENTING: Paksa keyboard mati saat fokus mendarat di sini
                    // (misal pindah dari field NIK ke Tanggal Lahir)
                    forceHideKeyboard(v)
                }
            }
        }

        // Fungsi pembuka date picker
        val openPicker = {
            // Pastikan keyboard benar-benar mati sebelum dialog muncul
            forceHideKeyboard(etDob)

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
                if (event.action == KeyEvent.ACTION_UP) {
                    openPicker()
                }
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun showTvDatePicker() {
        // ... (Kode sama seperti sebelumnya) ...
        val calendar = Calendar.getInstance()
        if (selectedDobUtcMs != null) {
            calendar.timeInMillis = selectedDobUtcMs!!
        }

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val datePicker = DatePickerDialog(
            this,
            R.style.BlueDatePickerDialog,
            { _, selectedYear, selectedMonth, selectedDay ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(selectedYear, selectedMonth, selectedDay)

                val utcMs = selectedCal.timeInMillis
                selectedDobUtcMs = utcMs

                etDob.setText(dateFormat.format(selectedCal.time))
                tilDob.error = null

                // Pindahkan fokus ke input berikutnya (RS)
                etRS.requestFocus()
            },
            year, month, day
        )

        val minCal = Calendar.getInstance()
        minCal.add(Calendar.YEAR, -130)
        datePicker.datePicker.minDate = minCal.timeInMillis

        // PENTING UNTUK TV: Cegah keyboard muncul saat dialog ditutup/dibuka
        datePicker.setOnDismissListener {
            // Pastikan keyboard tetap mati setelah dialog tutup
            etDob.postDelayed({ forceHideKeyboard(etDob) }, 100)
        }

        datePicker.show()

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
        // ... (Kode sama seperti sebelumnya) ...
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
        // ... (Kode sama seperti sebelumnya) ...
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