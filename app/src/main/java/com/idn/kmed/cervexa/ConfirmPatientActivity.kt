package com.idn.kmed.cervexa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ConfirmPatientActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_patient)

        // Tombol Pasien Baru
        findViewById<MaterialButton>(R.id.btnNewPatient).setOnClickListener {
            startActivity(Intent(this, RegistrationPatientActivity::class.java))
            finish() // tutup layer konfirmasi biar nggak numpuk di back stack
        }

        // Tombol Pasien Lama
        findViewById<MaterialButton>(R.id.btnExistingPatient).setOnClickListener {
            startActivity(Intent(this, SelectExistingPatientActivity::class.java))
            finish()
        }

        // OPTIONAL: jika mau klik di luar / tombol back langsung balik ke Home
        // tidak perlu diapa-apakan, default back sudah ke HomeActivity
    }
}
