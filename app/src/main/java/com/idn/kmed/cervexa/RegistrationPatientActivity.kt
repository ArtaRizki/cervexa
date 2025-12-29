package com.idn.kmed.cervexa

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration_patient)

        // Toolbar
        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        // Views
        tilNama = findViewById(R.id.tilNama)
        tilNik  = findViewById(R.id.tilNik)
        tilDob  = findViewById(R.id.tilDob)
        tilRS   = findViewById(R.id.tilRS)
        etNama  = findViewById(R.id.etNama)
        etNik   = findViewById(R.id.etNik)
        etDob   = findViewById(R.id.etDob)
        etRS    = findViewById(R.id.etRS)
        etNrm   = findViewById(R.id.etNrm)
        btnNext = findViewById(R.id.btnNext)

        etDob.setOnClickListener { showDobPicker() }
        tilDob.setEndIconOnClickListener { showDobPicker() }

        btnNext.setOnClickListener {
            if (!validate()) return@setOnClickListener

            val nama = etNama.text?.toString()?.trim().orEmpty()
            val nik  = etNik.text?.toString()?.trim().orEmpty()
            val rs   = etRS.text?.toString()?.trim().orEmpty()
            val nrm  = etNrm.text?.toString()?.trim().orEmpty()
            val dob  = selectedDobUtcMs ?: -1L

            // Kirim ke VideoActivity
            startActivity(Intent(this, VideoActivity::class.java).apply {
                putExtra("patient_nama", nama)
                putExtra("patient_nik",  nik)
                putExtra("patient_rs",   rs)
                putExtra("patient_nrm",  nrm)           // opsional
                putExtra("patient_dob_utc", dob)
            })
        }
    }

    private fun showDobPicker() {
        val nowUtc = MaterialDatePicker.todayInUtcMilliseconds()

        // Minimal 130 tahun lalu (UTC)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowUtc
        cal.add(Calendar.YEAR, -130)
        val minUtc = cal.timeInMillis

        // ✅ Validator tanpa lambda
        val validators = listOf<CalendarConstraints.DateValidator>(
            DateValidatorPointForward.from(minUtc),     // tidak boleh sebelum minUtc
            DateValidatorPointBackward.before(nowUtc)   // tidak boleh setelah hari ini
        )
        val constraints = CalendarConstraints.Builder()
            .setStart(minUtc)            // batas navigasi kalender (opsional tapi bagus)
            .setEnd(nowUtc)
            .setValidator(CompositeDateValidator.allOf(validators))
            .build()

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTheme(R.style.MyCustomDatePicker)
            .setTitleText("Pilih Tanggal Lahir")
            .setSelection(selectedDobUtcMs ?: nowUtc)
            .setCalendarConstraints(constraints)
            .build()

        picker.addOnPositiveButtonClickListener { utcMs ->
            selectedDobUtcMs = utcMs
            etDob.setText(dateFormat.format(java.util.Date(utcMs)))
            tilDob.error = null
        }
        picker.show(supportFragmentManager, "dob_picker")
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
            nik.isEmpty() -> { tilNik.error = "NIK wajib diisi"; ok = false }
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
