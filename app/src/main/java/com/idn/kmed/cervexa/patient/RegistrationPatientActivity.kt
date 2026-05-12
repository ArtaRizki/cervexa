package com.idn.kmed.cervexa.patient

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
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
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.video.VideoActivity
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
    private lateinit var loadingOverlay: View   // sudah ada di layout XML

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

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener { finish() }

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
        loadingOverlay = findViewById(R.id.loadingOverlay)

        setupTvDateInput()

        btnNext.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && event.action == KeyEvent.ACTION_DOWN
            ) {
                btnNext.performClick(); true
            } else false
        }

        btnNext.setOnClickListener {
            currentFocus?.clearFocus()
            blockCenterKey(etNama); blockCenterKey(etNik)
            blockCenterKey(etRS); blockCenterKey(etNrm)
            handleRegistration()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Registration — lookup NIK → store patient → buka VideoActivity
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleRegistration() {
        if (!validate()) return

        val nama = etNama.text?.toString()?.trim().orEmpty()
        val nik = etNik.text?.toString()?.trim().orEmpty()
        val rs = etRS.text?.toString()?.trim().orEmpty()
        val nrm = etNrm.text?.toString()?.trim().orEmpty()
        val dob = selectedDobUtcMs ?: -1L

        // Langsung buka video activity (Mode Offline - Seperti Commit 1665902)
        openVideoActivity(
            patientId = -1,
            nama = nama, nik = nik, rs = rs, nrm = nrm, dobUtcMs = dob
        )
    }

    private fun openVideoActivity(
        patientId: Int,
        nama: String, nik: String, rs: String, nrm: String, dobUtcMs: Long
    ) {
        startActivity(Intent(this, VideoActivity::class.java).apply {
            putExtra("patient_id", patientId)   // ← ID dari server (BARU)
            putExtra("patient_nama", nama)
            putExtra("patient_nik", nik)
            putExtra("patient_rs", rs)
            putExtra("patient_nrm", nrm)
            putExtra("patient_dob_utc", dobUtcMs)
        })
    }

    private fun setLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        btnNext.isEnabled = !show
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Date Picker — tidak berubah dari versi asli
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTvDateInput() {
        etDob.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isCursorVisible = false
            isFocusableInTouchMode = false
            showSoftInputOnFocus = false
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) post { showTvDatePicker() } }
        }
        val openPicker = { etDob.post { showTvDatePicker() } }
        etDob.setOnClickListener { openPicker() }
        tilDob.setEndIconOnClickListener { openPicker() }
        etDob.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) openPicker()
                true
            } else false
        }
        etDob.setOnFocusChangeListener { _, _ -> }
    }

    private fun showTvDatePicker() {
        val cal = Calendar.getInstance().apply { selectedDobUtcMs?.let { timeInMillis = it } }
        DatePickerDialog(
            this, R.style.BlueDatePickerDialog,
            { _, y, m, d ->
                val c = Calendar.getInstance().apply { set(y, m, d) }
                selectedDobUtcMs = c.timeInMillis
                etDob.setText(dateFormat.format(c.time))
                tilDob.error = null
                etRS.requestFocus()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate =
                Calendar.getInstance().apply { add(Calendar.YEAR, -130) }.timeInMillis
            show()
            getButton(DatePickerDialog.BUTTON_POSITIVE)
                ?.setBackgroundColor(Color.parseColor("#1E63E4"))
            getButton(DatePickerDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.parseColor("#FFFFFF"))
            getButton(DatePickerDialog.BUTTON_NEGATIVE)
                ?.setBackgroundColor(Color.parseColor("#FFFFFF"))
            getButton(DatePickerDialog.BUTTON_NEGATIVE)
                ?.setTextColor(Color.parseColor("#1E63E4"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Validation — tidak berubah dari versi asli
    // ─────────────────────────────────────────────────────────────────────────

    private fun validate(): Boolean {
        var ok = true

        val nama = etNama.text?.toString()?.trim().orEmpty()
        if (nama.isEmpty()) {
            tilNama.error = "Nama wajib diisi"; ok = false
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
            tilDob.error = "Tanggal lahir wajib diisi"; ok = false
        } else tilDob.error = null

        val rs = etRS.text?.toString()?.trim().orEmpty()
        if (rs.isEmpty()) {
            tilRS.error = "Nama Rumah Sakit wajib diisi"; ok = false
        } else tilRS.error = null

        if (!ok) Toast.makeText(this, "Periksa kembali data", Toast.LENGTH_SHORT).show()
        return ok
    }
}