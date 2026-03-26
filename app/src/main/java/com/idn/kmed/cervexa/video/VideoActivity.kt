package com.idn.kmed.cervexa.video

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.idn.kmed.cervexa.R
import com.idn.kmed.cervexa.live.VideoFragmentMobile
import com.idn.kmed.cervexa.live.VideoFragmentTv
import com.idn.kmed.cervexa.utils.DeviceTypeDetector

class VideoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        DeviceTypeDetector.logDeviceInfo(this)
        val isTvDevice = DeviceTypeDetector.isTvDevice(this)

        Log.d(TAG, "Device Type: ${if (isTvDevice) "TV/STB" else "Smartphone/Tablet"}")

        // ── Ambil semua extra dari Intent ──────────────────────────────────
        val sessionDirPath = intent.getStringExtra("sessionDirPath")
        val patientNama = intent.getStringExtra("patient_nama")
        val patientNik = intent.getStringExtra("patient_nik")
        val patientRs = intent.getStringExtra("patient_rs")
        val patientNrm = intent.getStringExtra("patient_nrm")
        val patientDobUtc = intent.getLongExtra("patient_dob_utc", -1L)

        // FIX: teruskan patient_id dari server ke fragment agar sesi bisa dibuat via API
        val serverPatientId = intent.getIntExtra("patient_id", -1)

        // ── Bundle ke Fragment ─────────────────────────────────────────────
        val bundle = Bundle().apply {
            putString("sessionDirPath", sessionDirPath)
            putString("patient_nama", patientNama)
            putString("patient_nik", patientNik)
            putString("patient_rs", patientRs)
            putString("patient_nrm", patientNrm)
            putLong("patient_dob_utc", patientDobUtc)
            putInt("patient_id", serverPatientId)   // ← BARU: ID dari server
        }

        // ── Pilih fragment berdasarkan device ──────────────────────────────
        val fragment: Fragment = if (isTvDevice) {
            Log.i(TAG, "Loading VideoFragmentTv")
            VideoFragmentTv().apply { arguments = bundle }
        } else {
            Log.i(TAG, "Loading VideoFragmentMobile")
            VideoFragmentMobile().apply { arguments = bundle }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }
    }

    companion object {
        private const val TAG = "VideoActivity"
    }
}