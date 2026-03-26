package com.idn.kmed.cervexa.patient

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.idn.kmed.cervexa.R

class ConfirmPatientActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_patient)

        // UBAH casting dari <MaterialButton> menjadi <AppCompatButton>
        val btnNew = findViewById<AppCompatButton>(R.id.btnNewPatient)
        val btnExist = findViewById<AppCompatButton>(R.id.btnExistingPatient)

        // Logic Klik
        btnNew.setOnClickListener {
            startActivity(Intent(this, RegistrationPatientActivity::class.java))
            finish()
        }

        btnExist.setOnClickListener {
            startActivity(Intent(this, SelectExistingPatientActivity::class.java))
            finish()
        }

        // [TV OPTIMIZATION] Paksa fokus ke tombol pertama
        btnNew.post {
            btnNew.requestFocus()
        }

        // 2. [TV OPTIMIZATION] Paksa fokus ke tombol pertama saat layar muncul
        // Gunakan post agar layout selesai dirender dulu baru minta fokus
        btnNew.post {
            btnNew.requestFocus()
        }
    }
}